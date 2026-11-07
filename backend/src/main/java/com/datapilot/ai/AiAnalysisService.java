package com.datapilot.ai;

import com.datapilot.ai.ChatModelClient.Completion;
import com.datapilot.ai.ChatModelClient.TokenUsage;
import com.datapilot.ai.Text2SqlOutputParser.Text2SqlOutput;
import com.datapilot.query.QueryService;
import com.datapilot.query.SqlPolicy;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private final ChatModelClient modelClient;
    private final Text2SqlPromptBuilder promptBuilder;
    private final Text2SqlOutputParser outputParser = new Text2SqlOutputParser();
    private final SqlPolicy policy;
    private final QueryService queryService;
    private final String provider;
    private final int maxRepairAttempts;
    private final Semaphore concurrency;
    private final ConcurrentHashMap<String, CompletableFuture<AiResponse>> inFlight = new ConcurrentHashMap<>();

    public AiAnalysisService(ChatModelClient modelClient, Text2SqlPromptBuilder promptBuilder,
            SqlPolicy policy, QueryService queryService,
            @Value("${datapilot.ai.provider:qiniu}") String provider,
            @Value("${datapilot.ai.max-repair-attempts:2}") int maxRepairAttempts,
            @Value("${datapilot.ai.max-concurrency:2}") int maxConcurrency) {
        this.modelClient = modelClient;
        this.promptBuilder = promptBuilder;
        this.policy = policy;
        this.queryService = queryService;
        this.provider = provider;
        this.maxRepairAttempts = Math.max(0, Math.min(maxRepairAttempts, 2));
        this.concurrency = new Semaphore(Math.max(1, Math.min(maxConcurrency, 4)));
    }

    public AiResponse generate(String question) { return singleFlight(question, false); }
    public AiResponse query(String question) { return singleFlight(question, true); }

    private AiResponse singleFlight(String question, boolean execute) {
        String key = (execute ? "query:" : "generate:") + question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        CompletableFuture<AiResponse> current = new CompletableFuture<>();
        CompletableFuture<AiResponse> active = inFlight.putIfAbsent(key, current);
        if (active != null) {
            try { return active.get(300, TimeUnit.SECONDS); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failed("AI_CANCELLED", "AI 分析已取消", 0, 0, null, null, null);
            } catch (ExecutionException | TimeoutException exception) {
                return failed("AI_BUSY", "相同问题正在处理中，请稍后查看结果", 0, 0, null, null, null);
            }
        }
        try {
            AiResponse response = analyze(question, execute);
            current.complete(response);
            return response;
        } catch (RuntimeException exception) {
            current.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, current);
        }
    }

    private AiResponse analyze(String question, boolean execute) {
        boolean acquired = false;
        long modelElapsed = 0;
        try {
            acquired = concurrency.tryAcquire(100, TimeUnit.MILLISECONDS);
            if (!acquired) return failed("AI_BUSY", "AI 分析繁忙，请稍后重试", 0, 0, null, null, null);
            String basePrompt = promptBuilder.systemPrompt();
            String repairInstruction = "";
            UsageAccumulator usage = new UsageAccumulator();
            for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
                long callStarted = System.nanoTime();
                Completion completion;
                try { completion = modelClient.complete(basePrompt + repairInstruction, question); }
                finally { modelElapsed += elapsed(callStarted); }
                usage.add(completion.tokenUsage());
                Text2SqlOutput output;
                try {
                    output = outputParser.parse(completion.content());
                } catch (RuntimeException exception) {
                    return failed("AI_INVALID_RESPONSE", "模型返回内容无法解析，请调整问题后重试", modelElapsed, attempt,
                            usage.value(), null, null);
                }
                if (output.clarificationRequired())
                    return new AiResponse(true, true, output.intent(), "", output.explanation(), output.clarificationQuestion(),
                            null, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            modelElapsed, usage.value(), attempt, null, null, null);
                if (output.sql().isBlank())
                    return new AiResponse(true, false, output.intent(), "", output.explanation(), "",
                            null, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            modelElapsed, usage.value(), attempt, null, output.reason(), null);

                SqlPolicy.Validation validation = policy.validate(output.sql());
                if (!validation.allowed()) {
                    if ("SQL_PARSE_ERROR".equals(validation.errorCode()) && attempt < maxRepairAttempts) {
                        repairInstruction = "\nThe previous candidate did not parse as supported MySQL syntax. Generate a corrected candidate using only the explicitly allowed syntax. This is a syntax correction; do not weaken or evade any safety rule.";
                        continue;
                    }
                    return new AiResponse(false, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            modelElapsed, usage.value(), attempt, "SQL_REJECTED", validation.message(), null);
                }
                if (!execute)
                    return new AiResponse(true, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            modelElapsed, usage.value(), attempt, null, null, null);

                QueryService.QueryResponse query = queryService.execute(output.sql());
                if (query.success())
                    return new AiResponse(true, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, query, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            modelElapsed, usage.value(), attempt, null, null, null);
                if (isRepairableExecutionError(query.errorCode()) && attempt < maxRepairAttempts) {
                    repairInstruction = "\nThe previous candidate passed the SQL policy but the database reported a schema or MySQL syntax mismatch. Correct the query using only the supplied allowlisted schema and metrics. Do not alter, bypass, or weaken any safety rule.";
                    continue;
                }
                return new AiResponse(false, false, output.intent(), output.sql(), output.explanation(), "",
                        validation, query, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                        modelElapsed, usage.value(), attempt, query.errorCode(), query.message(), null);
            }
            return failed("AI_REPAIR_EXHAUSTED", "SQL 自动修复次数已用尽，请调整问题后重试", modelElapsed,
                    maxRepairAttempts, usage.value(), null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed("AI_CANCELLED", "AI 分析已取消", modelElapsed, 0, null, null, null);
        } catch (AiServiceException exception) {
            return failed(exception.errorCode(), safeMessage(exception), modelElapsed, 0, null, null, null,
                    exception.providerFailure());
        } catch (RuntimeException exception) {
            return failed("AI_FAILED", "AI 分析未能完成，请稍后重试", modelElapsed, 0, null, null, null);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private boolean isRepairableExecutionError(String errorCode) {
        return "QUERY_SYNTAX_ERROR".equals(errorCode) || "QUERY_SCHEMA_ERROR".equals(errorCode);
    }

    private AiResponse failed(String code, String message, long modelElapsed, int attempts, TokenUsage usage,
            SqlPolicy.Validation validation, QueryService.QueryResponse query) {
        return failed(code, message, modelElapsed, attempts, usage, validation, query, null);
    }

    private AiResponse failed(String code, String message, long modelElapsed, int attempts, TokenUsage usage,
            SqlPolicy.Validation validation, QueryService.QueryResponse query,
            AiServiceException.ProviderFailure providerFailure) {
        return new AiResponse(false, false, "", "", "", "", validation, query, provider,
                modelClient.model(), Text2SqlPromptBuilder.VERSION, modelElapsed, usage, attempts,
                code, message, providerFailure);
    }

    private static String safeMessage(AiServiceException exception) {
        return switch (exception.errorCode()) {
            case "AI_NOT_CONFIGURED" -> "未配置模型 API Key；请在后端环境变量中设置 QINIU_API_KEY。";
            case "AI_AUTHENTICATION_FAILED" -> "模型服务认证失败，请检查后端 API Key 配置。";
            case "AI_RATE_LIMITED" -> switch (exception.providerFailure() == null ? "UNKNOWN_429" : exception.providerFailure().category()) {
                case "REQUEST_RATE" -> "模型服务请求频率受限，请按提示等待后再试。";
                case "QUOTA_OR_BILLING" -> "模型服务额度或账单限制，暂时无法调用。";
                case "PROVIDER_GLOBAL_LIMIT" -> "模型服务报告了提供方全局限制，具体原因尚未细分。";
                case "MODEL_CAPACITY" -> "模型当前容量不足，请稍后再试。";
                default -> "模型服务暂时限制了请求，请稍后再试。";
            };
            case "AI_TIMEOUT" -> "模型请求超时，请稍后重试。";
            case "AI_CANCELLED" -> "模型请求已取消。";
            case "AI_INVALID_RESPONSE", "AI_EMPTY_RESPONSE" -> "模型返回内容无效或为空，请重试。";
            case "AI_PROMPT_TOO_LARGE" -> "业务上下文超过配置的提示长度限制。";
            default -> "模型服务暂时不可用，请稍后重试。";
        };
    }

    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    public record AiResponse(boolean success, boolean clarificationRequired, String intent, String sql,
            String explanation, String clarificationQuestion, SqlPolicy.Validation validation,
            QueryService.QueryResponse queryResult, String provider, String model, String promptVersion,
            long modelResponseTimeMs, TokenUsage tokenUsage, int repairAttempts, String errorCode,
            String message, AiServiceException.ProviderFailure providerFailure) { }

    private static final class UsageAccumulator {
        private Integer prompt;
        private Integer completion;
        private Integer total;
        void add(TokenUsage next) {
            if (next == null) return;
            prompt = add(prompt, next.promptTokens());
            completion = add(completion, next.completionTokens());
            total = add(total, next.totalTokens());
        }
        private Integer add(Integer left, Integer right) {
            return right == null ? left : left == null ? right : Math.addExact(left, right);
        }
        TokenUsage value() { return prompt == null && completion == null && total == null ? null : new TokenUsage(prompt, completion, total); }
    }
}
