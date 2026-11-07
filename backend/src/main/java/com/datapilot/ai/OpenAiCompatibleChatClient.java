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
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiCompatibleChatClient implements ChatModelClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);
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
    private final int maxProviderRetries;
    private final int maxRetryDelaySeconds;
    private volatile long blockedUntilEpochMs;
    private volatile String blockedCategory = "UNKNOWN_429";

    public OpenAiCompatibleChatClient(
            @Value("${datapilot.ai.base-url:https://api.qnaigc.com/v1}") String baseUrl,
            @Value("${datapilot.ai.model:qiniu/conatus-free}") String model,
            @Value("${datapilot.ai.api-key:}") String apiKey,
            @Value("${datapilot.ai.stream:true}") boolean stream,
            @Value("${datapilot.ai.timeout-seconds:45}") int timeoutSeconds,
            @Value("${datapilot.ai.max-output-tokens:1200}") int maxOutputTokens,
            @Value("${datapilot.ai.max-provider-retries:1}") int maxProviderRetries,
            @Value("${datapilot.ai.max-retry-delay-seconds:5}") int maxRetryDelaySeconds) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.stream = stream;
        this.timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 90));
        this.maxOutputTokens = Math.max(128, Math.min(maxOutputTokens, 4096));
        this.maxProviderRetries = Math.max(0, Math.min(maxProviderRetries, 2));
        this.maxRetryDelaySeconds = Math.max(0, Math.min(maxRetryDelaySeconds, 30));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) throw new AiServiceException("AI_NOT_CONFIGURED");
        long now = System.currentTimeMillis();
        if (now < blockedUntilEpochMs) {
            long millis = blockedUntilEpochMs - now;
            long remaining = Math.max(1, millis / 1000 + (millis % 1000 == 0 ? 0 : 1));
            throw new AiServiceException("AI_RATE_LIMITED", new AiServiceException.ProviderFailure(
                    blockedCategory, 429, remaining, 0, null, null));
        }
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
            for (int attempt = 1; ; attempt++) {
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
                    if (status == 429) {
                        AiServiceException.ProviderFailure failure = providerFailure(response, input, attempt);
                        long delayMs = retryDelayMillis(failure, attempt);
                        if (failure.category().equals("REQUEST_RATE") && attempt <= maxProviderRetries && delayMs >= 0) {
                            Thread.sleep(delayMs);
                            continue;
                        }
                        long cooldownSeconds = blockAfter429(failure);
                        failure = new AiServiceException.ProviderFailure(failure.category(), failure.status(), cooldownSeconds,
                                failure.attempts(), failure.remainingRequests(), failure.remainingTokens());
                        log.warn("AI provider throttled: status={} category={} retryAfterSeconds={} attempts={} remainingRequests={} remainingTokens={}",
                                failure.status(), failure.category(), failure.retryAfterSeconds(), failure.attempts(),
                                failure.remainingRequests(), failure.remainingTokens());
                        throw new AiServiceException("AI_RATE_LIMITED", failure);
                    }
                    if (status < 200 || status >= 300) throw new AiServiceException("AI_PROVIDER_ERROR");
                    blockedUntilEpochMs = 0;
                    return stream ? readSseWithDeadline(input) : readJsonWithDeadline(input);
                }
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

    private long blockAfter429(AiServiceException.ProviderFailure failure) {
        blockedCategory = failure.category();
        long defaultSeconds = switch (failure.category()) {
            case "QUOTA_OR_BILLING", "PROVIDER_GLOBAL_LIMIT" -> 300;
            case "MODEL_CAPACITY" -> 30;
            case "REQUEST_RATE" -> 5;
            default -> 60;
        };
        long seconds = failure.retryAfterSeconds() == null || failure.retryAfterSeconds() == 0
                ? defaultSeconds : failure.retryAfterSeconds();
        long now = System.currentTimeMillis();
        blockedUntilEpochMs = seconds > (Long.MAX_VALUE - now) / 1000L ? Long.MAX_VALUE : now + seconds * 1000L;
        return seconds;
    }

    private AiServiceException.ProviderFailure providerFailure(HttpResponse<?> response, InputStream body, int attempts) {
        String code = "";
        String type = "";
        try {
            byte[] bytes = body.readNBytes(8193);
            if (bytes.length <= 8192) {
                JsonNode error = mapper.readTree(bytes).get("error");
                if (error != null) {
                    code = text(error.get("code"));
                    type = text(error.get("type"));
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        String category = classify(code, type);
        Long retryAfter = retryAfterSeconds(response);
        Integer remainingRequests = headerInteger(response, "x-ratelimit-remaining-requests", "ratelimit-remaining");
        Integer remainingTokens = headerInteger(response, "x-ratelimit-remaining-tokens");
        return new AiServiceException.ProviderFailure(category, response.statusCode(), retryAfter, attempts,
                remainingRequests, remainingTokens);
    }

    private static String classify(String code, String type) {
        String value = (code + " " + type).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("free_campaign_global_limit") || value.contains("global_limit")) return "PROVIDER_GLOBAL_LIMIT";
        if (value.contains("insufficient_quota") || value.contains("quota") || value.contains("billing") || value.contains("credit")) return "QUOTA_OR_BILLING";
        if (value.contains("capacity") || value.contains("overload") || value.contains("busy")) return "MODEL_CAPACITY";
        if (value.contains("rate_limit") || value.contains("too_many_requests")) return "REQUEST_RATE";
        return "UNKNOWN_429";
    }

    private long retryDelayMillis(AiServiceException.ProviderFailure failure, int attempt) {
        if (failure.category().equals("PROVIDER_GLOBAL_LIMIT") || failure.category().equals("QUOTA_OR_BILLING")
                || failure.category().equals("MODEL_CAPACITY") || failure.category().equals("UNKNOWN_429")) return -1;
        long backoff = Math.min(500L << Math.min(attempt - 1, 5), 30_000L);
        if (failure.retryAfterSeconds() != null) {
            if (failure.retryAfterSeconds() > maxRetryDelaySeconds) return -1;
            backoff = Math.max(backoff, failure.retryAfterSeconds() * 1000L);
        }
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, backoff / 4 + 1));
        long delay = backoff + jitter;
        return delay <= maxRetryDelaySeconds * 1000L ? delay : -1;
    }

    private static Long retryAfterSeconds(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) return null;
        try { return Math.max(0, Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) {
            try { return Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()); }
            catch (RuntimeException invalid) { return null; }
        }
    }

    private static Integer headerInteger(HttpResponse<?> response, String... names) {
        for (String name : names) {
            try {
                String value = response.headers().firstValue(name).orElse(null);
                if (value != null) return Integer.valueOf(value.trim());
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static String text(JsonNode node) { return node == null || !node.isString() ? "" : node.asString(); }

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
