package com.datapilot.ai;

import com.datapilot.ai.ChatModelClient.Completion;
import com.datapilot.ai.ChatModelClient.TokenUsage;
import com.datapilot.ai.Text2SqlOutputParser.Text2SqlOutput;
import com.datapilot.query.QueryService;
import com.datapilot.query.SqlPolicy;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    public AiResponse generate(String question) { return analyze(question, false); }
    public AiResponse query(String question) { return analyze(question, true); }

    private AiResponse analyze(String question, boolean execute) {
        long started = System.nanoTime();
        boolean acquired = false;
        try {
            acquired = concurrency.tryAcquire(100, TimeUnit.MILLISECONDS);
            if (!acquired) return failed("AI_BUSY", "AI 分析繁忙，请稍后重试", started, 0, null, null, null);
            String basePrompt = promptBuilder.systemPrompt();
            String repairInstruction = "";
            UsageAccumulator usage = new UsageAccumulator();
            for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
                Completion completion = modelClient.complete(basePrompt + repairInstruction, question);
                usage.add(completion.tokenUsage());
                Text2SqlOutput output;
                try {
                    output = outputParser.parse(completion.content());
                } catch (RuntimeException exception) {
                    return failed("AI_INVALID_RESPONSE", "模型返回内容无法解析，请调整问题后重试", started, attempt,
                            usage.value(), null, null);
                }
                if (output.clarificationRequired())
                    return new AiResponse(true, true, output.intent(), "", output.explanation(), output.clarificationQuestion(),
                            null, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            elapsed(started), usage.value(), attempt, null, null);
                if (output.sql().isBlank())
                    return new AiResponse(true, false, output.intent(), "", output.explanation(), "",
                            null, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            elapsed(started), usage.value(), attempt, null, output.reason());

                SqlPolicy.Validation validation = policy.validate(output.sql());
                if (!validation.allowed()) {
                    if ("SQL_PARSE_ERROR".equals(validation.errorCode()) && attempt < maxRepairAttempts) {
                        repairInstruction = "\nThe previous candidate did not parse as supported MySQL syntax. Generate a corrected candidate using only the explicitly allowed syntax. This is a syntax correction; do not weaken or evade any safety rule.";
                        continue;
                    }
                    return new AiResponse(false, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            elapsed(started), usage.value(), attempt, "SQL_REJECTED", validation.message());
                }
                if (!execute)
                    return new AiResponse(true, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, null, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            elapsed(started), usage.value(), attempt, null, null);

                QueryService.QueryResponse query = queryService.execute(output.sql());
                if (query.success())
                    return new AiResponse(true, false, output.intent(), output.sql(), output.explanation(), "",
                            validation, query, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                            elapsed(started), usage.value(), attempt, null, null);
                if (isRepairableExecutionError(query.errorCode()) && attempt < maxRepairAttempts) {
                    repairInstruction = "\nThe previous candidate passed the SQL policy but the database reported a schema or MySQL syntax mismatch. Correct the query using only the supplied allowlisted schema and metrics. Do not alter, bypass, or weaken any safety rule.";
                    continue;
                }
                return new AiResponse(false, false, output.intent(), output.sql(), output.explanation(), "",
                        validation, query, provider, modelClient.model(), Text2SqlPromptBuilder.VERSION,
                        elapsed(started), usage.value(), attempt, query.errorCode(), query.message());
            }
            return failed("AI_REPAIR_EXHAUSTED", "SQL 自动修复次数已用尽，请调整问题后重试", started,
                    maxRepairAttempts, usage.value(), null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed("AI_CANCELLED", "AI 分析已取消", started, 0, null, null, null);
        } catch (AiServiceException exception) {
            return failed(exception.errorCode(), safeMessage(exception.errorCode()), started, 0, null, null, null);
        } catch (RuntimeException exception) {
            return failed("AI_FAILED", "AI 分析未能完成，请稍后重试", started, 0, null, null, null);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private boolean isRepairableExecutionError(String errorCode) {
        return "QUERY_SYNTAX_ERROR".equals(errorCode) || "QUERY_SCHEMA_ERROR".equals(errorCode);
    }

    private AiResponse failed(String code, String message, long started, int attempts, TokenUsage usage,
            SqlPolicy.Validation validation, QueryService.QueryResponse query) {
        return new AiResponse(false, false, "", "", "", "", validation, query, provider,
                modelClient.model(), Text2SqlPromptBuilder.VERSION, elapsed(started), usage, attempts,
                code, message);
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "AI_NOT_CONFIGURED" -> "未配置模型 API Key；请在后端环境变量中设置 QINIU_API_KEY。";
            case "AI_AUTHENTICATION_FAILED" -> "模型服务认证失败，请检查后端 API Key 配置。";
            case "AI_RATE_LIMITED" -> "模型服务请求过于频繁或额度暂不可用，请稍后重试。";
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
            String message) { }

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
