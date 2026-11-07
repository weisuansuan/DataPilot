package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datapilot.ai.ChatModelClient.Completion;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatClientTest {
    @Test
    void sendsOpenAiCompatibleRequestAndParsesOrdinaryResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (ServerFixture server = new ServerFixture(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json", """
                    {"choices":[{"message":{"content":"{\\"sql\\":\\"SELECT COUNT(*) FROM sales_order\\"}"}}],"usage":{"prompt_tokens":31,"completion_tokens":12,"total_tokens":43}}
                    """);
        })) {
            Completion result = client(server, false, "secret-test-key", 5).complete("system", "question");
            assertEquals("{\"sql\":\"SELECT COUNT(*) FROM sales_order\"}", result.content());
            assertEquals(43, result.tokenUsage().totalTokens());
            assertEquals("Bearer secret-test-key", authorization.get());
            var request = new tools.jackson.databind.ObjectMapper().readTree(requestBody.get());
            assertEquals("qiniu/conatus-free", request.get("model").asString());
            org.junit.jupiter.api.Assertions.assertFalse(request.get("stream").asBoolean());
            assertEquals(900, request.get("max_tokens").intValue());
        }
    }

    @Test
    void assemblesSseDeltasUntilDoneAndReadsUsage() throws Exception {
        try (ServerFixture server = new ServerFixture(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String events = "data: {\"choices\":[{\"delta\":{\"content\":\"SELECT \"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"COUNT(*) FROM sales_order\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":8,\"total_tokens\":18}}\n\n"
                    + "data: [DONE]\n\n";
            exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        })) {
            Completion result = client(server, true, "secret-test-key", 5).complete("system", "question");
            assertEquals("SELECT COUNT(*) FROM sales_order", result.content());
            assertEquals(18, result.tokenUsage().totalTokens());
        }
    }

    @Test
    void mapsAuthenticationFailureWithoutReturningProviderBody() throws Exception {
        try (ServerFixture server = new ServerFixture(exchange -> respond(exchange, 401, "application/json", "private error detail"))) {
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> client(server, false, "secret-test-key", 5).complete("system", "question"));
            assertEquals("AI_AUTHENTICATION_FAILED", error.errorCode());
            org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("private"));
        }
    }

    @Test
    void mapsRateLimitAndMalformedJsonToControlledCodes() throws Exception {
        try (ServerFixture server = new ServerFixture(exchange -> respond(exchange, 429, "application/json", "provider quota detail"))) {
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> client(server, false, "secret-test-key", 5).complete("system", "question"));
            assertEquals("AI_RATE_LIMITED", error.errorCode());
        }
        try (ServerFixture server = new ServerFixture(exchange -> respond(exchange, 200, "application/json", "not json"))) {
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> client(server, false, "secret-test-key", 5).complete("system", "question"));
            assertEquals("AI_INVALID_RESPONSE", error.errorCode());
        }
    }

    @Test
    void retriesRecognizedRequestRateLimitOnceAndParsesRetryAfter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerFixture server = new ServerFixture(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respond(exchange, 429, "application/json", """
                        {"error":{"type":"rate_limit_exceeded_error","code":"rate_limit_exceeded","message":"too many requests"}}
                        """);
            } else {
                respond(exchange, 200, "application/json", """
                        {"choices":[{"message":{"content":"SELECT 1"}}]}
                        """);
            }
        })) {
            Completion result = new OpenAiCompatibleChatClient(server.baseUrl(), "model", "test", false, 5, 900, 1, 5)
                    .complete("system", "question");
            assertEquals("SELECT 1", result.content());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void doesNotRetryProviderGlobalLimitAndReturnsOnlySafeMetadata() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerFixture server = new ServerFixture(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.getResponseHeaders().set("X-RateLimit-Remaining-Requests", "0");
            respond(exchange, 429, "application/json", """
                    {"error":{"type":"rate_limit_exceeded_error","code":"free_campaign_global_limit","message":"private account details"}}
                    """);
        })) {
            OpenAiCompatibleChatClient client = client(server, false, "secret-test-key", 5);
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> client.complete("system", "question"));
            assertEquals(1, calls.get());
            assertEquals("PROVIDER_GLOBAL_LIMIT", error.providerFailure().category());
            assertEquals(0, error.providerFailure().remainingRequests());
            org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("private"));
            org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("secret-test-key"));
            AiServiceException blocked = assertThrows(AiServiceException.class,
                    () -> client.complete("system", "question again"));
            assertEquals(1, calls.get());
            assertEquals("PROVIDER_GLOBAL_LIMIT", blocked.providerFailure().category());
            assertEquals(300L, blocked.providerFailure().retryAfterSeconds());
        }
    }

    @Test
    void skipsRetryWhenRetryAfterExceedsConfiguredBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ServerFixture server = new ServerFixture(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "20");
            respond(exchange, 429, "application/json", """
                    {"error":{"type":"rate_limit_exceeded_error","code":"rate_limit_exceeded"}}
                    """);
        })) {
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> new OpenAiCompatibleChatClient(server.baseUrl(), "model", "test", false, 5, 900, 1, 2)
                            .complete("system", "question"));
            assertEquals(1, calls.get());
            assertEquals(20L, error.providerFailure().retryAfterSeconds());
        }
    }

    @Test
    void rejectsMissingDoneMarkerAndMissingApiKey() throws Exception {
        try (ServerFixture server = new ServerFixture(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respond(exchange, 200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"incomplete\"}}]}\n\n");
        })) {
            AiServiceException invalid = assertThrows(AiServiceException.class,
                    () -> client(server, true, "secret-test-key", 5).complete("system", "question"));
            assertEquals("AI_INVALID_RESPONSE", invalid.errorCode());
        }
        AiServiceException missing = assertThrows(AiServiceException.class,
                    () -> new OpenAiCompatibleChatClient("http://127.0.0.1:1/v1", "model", "", false, 1, 900, 1, 5)
                        .complete("system", "question"));
        assertEquals("AI_NOT_CONFIGURED", missing.errorCode());
    }

    @Test
    void enforcesStreamingDeadline() throws Exception {
        try (ServerFixture server = new ServerFixture(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            AiServiceException error = assertThrows(AiServiceException.class,
                    () -> client(server, true, "secret-test-key", 1).complete("system", "question"));
            assertEquals("AI_TIMEOUT", error.errorCode());
        }
    }

    private OpenAiCompatibleChatClient client(ServerFixture server, boolean stream, String key, int timeout) {
        return new OpenAiCompatibleChatClient(server.baseUrl(), "qiniu/conatus-free", key, stream, timeout, 900, 1, 5);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface private interface Handler { void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException; }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;
        ServerFixture(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/chat/completions", handler::handle);
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        @Override public void close() { server.stop(0); }
    }
}
