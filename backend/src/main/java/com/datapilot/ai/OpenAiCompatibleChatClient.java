package com.datapilot.ai;

import com.datapilot.ai.ChatModelClient.Completion;
import com.datapilot.ai.ChatModelClient.TokenUsage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiCompatibleChatClient implements ChatModelClient {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final int MAX_CONTENT_CHARS = 48_000;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final boolean stream;
    private final int timeoutSeconds;
    private final int maxOutputTokens;

    public OpenAiCompatibleChatClient(
            @Value("${datapilot.ai.base-url:https://api.qnaigc.com/v1}") String baseUrl,
            @Value("${datapilot.ai.model:qiniu/conatus-free}") String model,
            @Value("${datapilot.ai.api-key:}") String apiKey,
            @Value("${datapilot.ai.stream:true}") boolean stream,
            @Value("${datapilot.ai.timeout-seconds:45}") int timeoutSeconds,
            @Value("${datapilot.ai.max-output-tokens:1200}") int maxOutputTokens) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.stream = stream;
        this.timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 90));
        this.maxOutputTokens = Math.max(128, Math.min(maxOutputTokens, 4096));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) throw new AiServiceException("AI_NOT_CONFIGURED");
        try {
            URI uri = URI.create(baseUrl + "/chat/completions");
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)),
                    "max_tokens", maxOutputTokens,
                    "stream", stream);
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", stream ? "text/event-stream" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                int status = response.statusCode();
                if (status == 401 || status == 403) throw new AiServiceException("AI_AUTHENTICATION_FAILED");
                if (status == 429) throw new AiServiceException("AI_RATE_LIMITED");
                if (status < 200 || status >= 300) throw new AiServiceException("AI_PROVIDER_ERROR");
                return stream ? readSseWithDeadline(input) : readJsonWithDeadline(input);
            }
        } catch (AiServiceException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException | TimeoutException exception) {
            throw new AiServiceException("AI_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("AI_CANCELLED");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiServiceException serviceException) throw serviceException;
            if (exception.getCause() instanceof IOException) throw new AiServiceException("AI_INVALID_RESPONSE");
            if (exception.getCause() instanceof RuntimeException) throw new AiServiceException("AI_INVALID_RESPONSE");
            throw new AiServiceException("AI_PROVIDER_UNAVAILABLE", exception.getCause());
        } catch (IOException exception) {
            throw new AiServiceException("AI_PROVIDER_UNAVAILABLE");
        } catch (RuntimeException exception) {
            throw new AiServiceException("AI_INVALID_RESPONSE");
        }
    }

    private Completion readSseWithDeadline(InputStream input) throws ExecutionException, InterruptedException, TimeoutException {
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Completion> result = reader.submit(() -> readSse(input));
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            try { input.close(); } catch (IOException ignored) { }
            throw exception;
        } finally {
            reader.shutdownNow();
        }
    }

    private Completion readJsonWithDeadline(InputStream input) throws ExecutionException, InterruptedException, TimeoutException {
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Completion> result = reader.submit(() -> parseCompletion(readBounded(input)));
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            try { input.close(); } catch (IOException ignored) { }
            throw exception;
        } finally {
            reader.shutdownNow();
        }
    }

    private Completion readSse(InputStream input) throws IOException {
        StringBuilder content = new StringBuilder();
        List<String> eventData = new ArrayList<>();
        TokenUsage usage = null;
        int totalBytes = 0;
        boolean done = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (totalBytes > MAX_RESPONSE_BYTES) throw new AiServiceException("AI_INVALID_RESPONSE");
                if (line.isEmpty()) {
                    if (!eventData.isEmpty()) {
                        Event parsed = parseEvent(String.join("\n", eventData), content);
                        if (parsed.usage() != null) usage = parsed.usage();
                        if (parsed.done()) { done = true; break; }
                        eventData.clear();
                    }
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5);
                    eventData.add(data.startsWith(" ") ? data.substring(1) : data);
                }
            }
        }
        if (!eventData.isEmpty()) {
            Event parsed = parseEvent(String.join("\n", eventData), content);
            if (parsed.usage() != null) usage = parsed.usage();
            done = parsed.done();
        }
        if (!done) throw new AiServiceException("AI_INVALID_RESPONSE");
        return finish(content.toString(), usage);
    }

    private Event parseEvent(String data, StringBuilder content) throws IOException {
        if ("[DONE]".equals(data.trim())) return new Event(null, true);
        JsonNode root = mapper.readTree(data);
        if (root == null) throw new AiServiceException("AI_INVALID_RESPONSE");
        TokenUsage usage = parseUsage(root.get("usage"));
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode delta = choices.get(0).get("delta");
            JsonNode piece = delta == null ? null : delta.get("content");
            appendContent(content, piece);
        }
        return new Event(usage, false);
    }

    private Completion parseCompletion(String response) throws IOException {
        JsonNode root = mapper.readTree(response);
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty())
            throw new AiServiceException("AI_EMPTY_RESPONSE");
        JsonNode message = choices.get(0).get("message");
        String content = message == null || message.get("content") == null ? "" : message.get("content").asString("");
        return finish(content, parseUsage(root.get("usage")));
    }

    private Completion finish(String content, TokenUsage usage) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) throw new AiServiceException("AI_EMPTY_RESPONSE");
        if (trimmed.length() > MAX_CONTENT_CHARS) throw new AiServiceException("AI_INVALID_RESPONSE");
        return new Completion(trimmed, usage);
    }

    private void appendContent(StringBuilder target, JsonNode node) {
        if (node == null || node.isNull()) return;
        if (!node.isString()) throw new AiServiceException("AI_INVALID_RESPONSE");
        target.append(node.asString());
        if (target.length() > MAX_CONTENT_CHARS) throw new AiServiceException("AI_INVALID_RESPONSE");
    }

    private TokenUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) return null;
        return new TokenUsage(integer(usage.get("prompt_tokens")), integer(usage.get("completion_tokens")), integer(usage.get("total_tokens")));
    }

    private Integer integer(JsonNode node) { return node == null || !node.isNumber() ? null : node.intValue(); }

    private String readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new AiServiceException("AI_INVALID_RESPONSE");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String model() { return model; }

    private record Event(TokenUsage usage, boolean done) { }
}
