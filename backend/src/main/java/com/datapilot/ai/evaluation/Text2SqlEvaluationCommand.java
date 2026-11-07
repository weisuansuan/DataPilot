package com.datapilot.ai.evaluation;

import com.datapilot.ai.AiAnalysisService;
import com.datapilot.ai.AiServiceException;
import com.datapilot.ai.ChatModelClient;
import com.datapilot.ai.ChatModelClient.Completion;
import com.datapilot.ai.Text2SqlPromptBuilder;
import com.datapilot.query.QueryService;
import com.datapilot.query.SqlPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "datapilot.evaluation", name = "mode")
public class Text2SqlEvaluationCommand implements ApplicationRunner {
    private final ChatModelClient liveClient;
    private final Text2SqlPromptBuilder promptBuilder;
    private final SqlPolicy policy;
    private final QueryService queryService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String mode;
    private final int maxCalls;
    private final int intervalSeconds;
    private final Path outputDirectory;
    private final int maxRepairs;
    private final int configuredProviderRetries;
    private final boolean streaming;
    private final AtomicInteger actualLiveCalls = new AtomicInteger();
    private final AtomicInteger actualLiveResponses = new AtomicInteger();

    public Text2SqlEvaluationCommand(ChatModelClient liveClient, Text2SqlPromptBuilder promptBuilder,
            SqlPolicy policy, QueryService queryService,
            @Value("${datapilot.evaluation.mode:}") String mode,
            @Value("${datapilot.evaluation.max-calls:5}") int maxCalls,
            @Value("${datapilot.evaluation.interval-seconds:10}") int intervalSeconds,
            @Value("${datapilot.evaluation.output-directory:.runtime/evaluations}") String outputDirectory,
            @Value("${datapilot.ai.max-repair-attempts:2}") int maxRepairs,
            @Value("${datapilot.ai.max-provider-retries:1}") int configuredProviderRetries,
            @Value("${datapilot.ai.stream:true}") boolean streaming) {
        this.liveClient = liveClient;
        this.promptBuilder = promptBuilder;
        this.policy = policy;
        this.queryService = queryService;
        this.mode = mode;
        this.maxCalls = Math.max(1, Math.min(maxCalls, 5));
        this.intervalSeconds = Math.max(5, Math.min(intervalSeconds, 3600));
        this.outputDirectory = Path.of(outputDirectory);
        this.maxRepairs = maxRepairs;
        this.configuredProviderRetries = configuredProviderRetries;
        this.streaming = streaming;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!mode.equalsIgnoreCase("mock") && !mode.equalsIgnoreCase("live"))
            throw new IllegalArgumentException("datapilot.evaluation.mode must be mock or live");
        List<Case> corpus;
        try (var input = new ClassPathResource("text2sql/evaluation-v1.json").getInputStream()) {
            corpus = mapper.readValue(input, new TypeReference<List<Case>>() { });
        }
        boolean mock = mode.equalsIgnoreCase("mock");
        if (!mock && configuredProviderRetries != 0)
            throw new IllegalArgumentException("Live evaluation requires datapilot.ai.max-provider-retries=0 to keep the API call ceiling exact.");
        List<Case> selected = mock ? corpus : corpus.subList(0, Math.min(maxCalls, corpus.size()));
        AtomicInteger fixtureCount = new AtomicInteger();
        ChatModelClient client = mock ? new FixtureClient(corpus, fixtureCount) : new CountingClient(liveClient, actualLiveCalls, actualLiveResponses, maxCalls);
        int repairs = mock ? 0 : Math.max(0, Math.min(maxRepairs, 2));
        AiAnalysisService analysis = new AiAnalysisService(client, promptBuilder, policy, queryService,
                mock ? "offline-mock" : "qiniu", repairs, 1);

        List<Sample> samples = new ArrayList<>();
        boolean stopAfterProviderLimit = false;
        for (int index = 0; index < selected.size(); index++) {
            Case testCase = selected.get(index);
            if (stopAfterProviderLimit) {
                samples.add(Sample.notAttempted(testCase));
                continue;
            }
            QueryService.QueryResponse reference = queryService.execute(testCase.expectedSql());
            boolean referenceValid = referenceMatchesCase(reference, testCase);
            long started = System.nanoTime();
            int callsBefore = actualLiveCalls.get();
            int responsesBefore = actualLiveResponses.get();
            AiAnalysisService.AiResponse result = analysis.query(testCase.question());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            boolean executed = result.queryResult() != null && result.queryResult().success();
            Boolean accurate = executed && referenceValid
                    ? QueryResultComparator.equivalent(reference, result.queryResult()) : null;
            boolean sqlGenerated = !result.sql().isBlank();
            boolean astAllowed = sqlGenerated && result.validation() != null && result.validation().allowed();
            boolean unsafeExecuted = result.queryResult() != null && (result.validation() == null || !result.validation().allowed());
            AiServiceException.ProviderFailure providerFailure = result.providerFailure();
            int modelAttempts = actualLiveCalls.get() - callsBefore;
            samples.add(new Sample(testCase.id(), testCase.question(), testCase.metricId(), result.errorCode(),
                    result.sql().length() > 16_384 ? result.sql().substring(0, 16_384) : result.sql(),
                    true, mock || actualLiveResponses.get() > responsesBefore, sqlGenerated, astAllowed, executed, accurate, accurate,
                    mock ? null : result.modelResponseTimeMs(),
                    result.tokenUsage() == null ? null : result.tokenUsage().promptTokens(),
                    result.tokenUsage() == null ? null : result.tokenUsage().completionTokens(),
                    result.tokenUsage() == null ? null : result.tokenUsage().totalTokens(),
                    result.repairAttempts(), result.repairAttempts() > 0 && executed,
                    unsafeExecuted, referenceValid, elapsedMs, modelAttempts,
                    providerFailure == null ? null : providerFailure.category(),
                    providerFailure == null ? null : providerFailure.status(),
                    providerFailure == null ? null : providerFailure.retryAfterSeconds(),
                    referenceValid ? QueryResultComparator.digest(reference) : null,
                    executed ? QueryResultComparator.digest(result.queryResult()) : null));
            if (!mock && "AI_RATE_LIMITED".equals(result.errorCode())) stopAfterProviderLimit = true;
            if (!mock && !stopAfterProviderLimit && index + 1 < selected.size()) Thread.sleep(intervalSeconds * 1000L);
        }

        int countedProviderCalls = samples.stream().mapToInt(Sample::modelAttempts).sum();
        Report report = report(mock ? "offline-mock" : "live", mock ? "mock" : streaming ? "sse" : "json", corpus.size(), selected.size(),
                mock ? 0 : countedProviderCalls, mock ? fixtureCount.get() : 0, samples);
        Files.createDirectories(outputDirectory);
        Path json = outputDirectory.resolve("text2sql-" + report.mode() + "-latest.json");
        Path markdown = outputDirectory.resolve("text2sql-" + report.mode() + "-latest.md");
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
        Files.writeString(markdown, markdown(report));
        System.out.println("TEXT2SQL_EVALUATION_MODE=" + report.mode());
        System.out.println("TRANSPORT=" + report.transport());
        System.out.println("CASES=" + report.sampleCount() + "/" + report.corpusSize());
        System.out.println("ATTEMPTED_SAMPLES=" + report.attemptedSamples() + "/" + report.sampleCount());
        System.out.println("PROVIDER_API_CALLS=" + report.providerApiCalls());
        System.out.println("MODEL_RESPONSES=" + report.modelResponseSamples());
        System.out.println("GENERATION=" + report.sqlGenerationSuccess() + "/" + report.generationDenominator());
        System.out.println("AST_APPROVED=" + report.astApproved() + "/" + report.sqlGenerationSuccess());
        System.out.println("EXECUTED=" + report.executionSuccess() + "/" + report.astApproved());
        System.out.println("RESULT_ACCURACY=" + report.accurateCount() + "/" + report.accuracySamples());
        System.out.println("SAFETY_VIOLATIONS=" + report.unsafeExecutionCount());
        System.out.println("JSON_REPORT=" + json.toAbsolutePath());
        System.out.println("MARKDOWN_REPORT=" + markdown.toAbsolutePath());
    }

    private boolean referenceMatchesCase(QueryService.QueryResponse reference, Case testCase) {
        if (reference == null || !reference.success()) return false;
        if (!testCase.expectedColumns().equals(reference.columns().stream().map(QueryService.QueryColumn::name).toList())) return false;
        if (testCase.expectedScalar() == null) return true;
        if (reference.rows().isEmpty()) return false;
        Object value = reference.rows().getFirst().get(testCase.expectedColumns().getFirst());
        if (value == null) return false;
        try { return new java.math.BigDecimal(value.toString()).compareTo(new java.math.BigDecimal(testCase.expectedScalar())) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private Report report(String mode, String transport, int corpusSize, int sampleCount, int providerCalls, int mockCalls, List<Sample> samples) {
        List<Long> latencies = samples.stream().map(Sample::modelLatencyMs).filter(v -> v != null).sorted().toList();
        long tokenCount = samples.stream().filter(s -> s.totalTokens() != null).count();
        long repairCandidates = samples.stream().filter(s -> s.repairAttempts() > 0).count();
        Map<String, MetricResult> metrics = new java.util.TreeMap<>();
        samples.stream().collect(java.util.stream.Collectors.groupingBy(Sample::metricId))
                .forEach((id, group) -> metrics.put(id, new MetricResult(
                        (int) group.stream().filter(s -> s.metricAccurate() != null).count(),
                        (int) group.stream().filter(s -> Boolean.TRUE.equals(s.metricAccurate())).count())));
        return new Report(mode, transport, Instant.now().toString(), corpusSize, sampleCount,
                (int) samples.stream().filter(Sample::sqlGenerated).count(),
                (int) samples.stream().filter(Sample::astApproved).count(),
                (int) samples.stream().filter(Sample::executionSuccess).count(),
                (int) samples.stream().filter(s -> s.accurate() != null).count(),
                (int) samples.stream().filter(s -> Boolean.TRUE.equals(s.accurate())).count(),
                (int) samples.stream().filter(s -> s.metricAccurate() != null).count(),
                (int) samples.stream().filter(s -> Boolean.TRUE.equals(s.metricAccurate())).count(),
                (int) samples.stream().filter(Sample::modelResponseReceived).count(),
                (int) samples.stream().filter(s -> s.attempted() && s.modelAttempts() > 0 && s.modelResponseReceived()).count(),
                average(latencies), percentile95(latencies), tokenCount,
                samples.stream().filter(s -> s.attempted() && s.errorCode() != null).collect(java.util.stream.Collectors.groupingBy(Sample::errorCode, java.util.TreeMap::new, java.util.stream.Collectors.counting())),
                (int) samples.stream().filter(s -> s.attempted() && "AI_RATE_LIMITED".equals(s.errorCode())).count(),
                (int) samples.stream().filter(Sample::repairSucceeded).count(), (int) repairCandidates,
                (int) samples.stream().filter(s -> s.attempted() && s.unsafeExecution()).count(),
                (int) samples.stream().filter(s -> s.attempted() && !s.referenceExecutionSuccess()).count(),
                providerCalls, mockCalls, metrics, samples);
    }

    private String markdown(Report r) {
        StringBuilder out = new StringBuilder("# Text2SQL 评测报告\n\n")
                .append("- 模式：").append(r.mode()).append(r.mode().equals("offline-mock") ? "（确定性 SQL fixture；不代表在线模型准确率）" : "（真实模型；只评测受调用上限约束的样本）").append("\n")
                .append("- 通信方式：").append(r.transport()).append("\n")
                .append("- 时间：").append(r.generatedAt()).append("\n")
                .append("- 样本：").append(r.sampleCount()).append(" / 语料 ").append(r.corpusSize()).append("\n")
                .append("- 实际尝试：").append(r.attemptedSamples()).append(" / 所选样本 ").append(r.sampleCount()).append("\n")
                .append("- Provider API 调用：").append(r.providerApiCalls()).append("；有效模型响应：").append(r.modelResponseSamples()).append("；Mock fixture：").append(r.mockResponses()).append("\n\n")
                .append("| 指标 | 成功 | 有效样本 | 比率 |\n|---|---:|---:|---:|\n")
                .append(row("Provider 完整响应", r.providerResponseCount(), r.providerApiCalls()))
                .append(row("SQL 生成", r.sqlGenerationSuccess(), r.generationDenominator()))
                .append(row("AST 通过", r.astApproved(), r.sqlGenerationSuccess()))
                .append(row("SQL 执行", r.executionSuccess(), r.astApproved()))
                .append(row("结果准确", r.accurateCount(), r.accuracySamples()))
                .append(row("业务指标准确", r.metricAccurateCount(), r.metricAccuracySamples()))
                .append(row("安全违规执行", r.unsafeExecutionCount(), r.attemptedSamples()))
                .append(row("参考 SQL 环境/结果失败", r.referenceFailures(), r.attemptedSamples()))
                .append("\n- 模型延迟：有效样本 ").append(r.latencySamples()).append("，平均 ").append(r.averageModelLatencyMs() == null ? "N/A" : r.averageModelLatencyMs() + " ms").append("，P95 ").append(r.p95ModelLatencyMs() == null ? "N/A" : r.p95ModelLatencyMs() + " ms").append("\n")
                .append("- Token：有效返回 ").append(r.tokenUsageSamples()).append(" 个样本；累计 prompt/completion/total = ").append(sum(r.samples(), 0)).append(" / ").append(sum(r.samples(), 1)).append(" / ").append(sum(r.samples(), 2)).append("\n")
                .append("- 429 限流：").append(r.rateLimitFailures()).append("；错误分类：").append(r.failures()).append("\n")
                .append("- 自动修复成功/尝试：").append(r.repairSuccesses()).append("/").append(r.repairSamples()).append("\n\n")
                .append("| ID | 指标 | SQL生成 | AST | 执行 | 结果准确 | 模型延迟 | 错误 |\n|---|---|---|---|---|---|---:|---|\n");
        for (Sample s : r.samples()) out.append("| ").append(s.id()).append(" | ").append(s.metricId()).append(" | ").append(!s.attempted() ? "未请求" : mark(s.sqlGenerated())).append(" | ").append(!s.attempted() ? "未测" : mark(s.astApproved())).append(" | ").append(!s.attempted() ? "未测" : mark(s.executionSuccess())).append(" | ").append(s.accurate() == null ? "未评测" : mark(s.accurate())).append(" | ").append(s.modelLatencyMs() == null ? "—" : s.modelLatencyMs() + " ms").append(" | ").append(s.errorCode() == null ? "—" : s.errorCode() + (s.providerFailureCategory() == null ? "" : " / " + s.providerFailureCategory() + " / HTTP " + s.providerStatus() + " / 建议等待 " + s.retryAfterSeconds() + "s")).append(" |\n");
        return out.toString();
    }

    private static String row(String label, int success, int total) { return "| " + label + " | " + success + " | " + total + " | " + (total == 0 ? "N/A" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * success / total)) + " |\n"; }
    private static String mark(boolean value) { return value ? "通过" : "失败"; }
    private static Integer sum(List<Sample> samples, int field) { return samples.stream().map(s -> field == 0 ? s.promptTokens() : field == 1 ? s.completionTokens() : s.totalTokens()).filter(v -> v != null).reduce(0, Integer::sum); }
    private static Long average(List<Long> values) { return values.isEmpty() ? null : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0)); }
    private static Long percentile95(List<Long> values) { return values.isEmpty() ? null : values.get(Math.max(0, (int) Math.ceil(values.size() * .95) - 1)); }

    private final class FixtureClient implements ChatModelClient {
        private final Map<String, Case> cases;
        private final AtomicInteger count;
        FixtureClient(List<Case> corpus, AtomicInteger count) {
            this.cases = new HashMap<>(); corpus.forEach(c -> cases.put(c.question(), c)); this.count = count;
        }
        @Override public Completion complete(String systemPrompt, String userPrompt) {
            Case c = cases.get(userPrompt);
            if (c == null) throw new IllegalArgumentException("Question is not in evaluation corpus");
            count.incrementAndGet();
            return new Completion(mapper.writeValueAsString(Map.of("intent", c.metricId(), "sql", c.expectedSql(), "explanation", "Offline reference fixture")), null);
        }
        @Override public String model() { return "reference-fixture-v1"; }
    }

    private static final class CountingClient implements ChatModelClient {
        private final ChatModelClient delegate;
        private final AtomicInteger count;
        private final AtomicInteger responses;
        private final int maximum;
        CountingClient(ChatModelClient delegate, AtomicInteger count, AtomicInteger responses, int maximum) { this.delegate = delegate; this.count = count; this.responses = responses; this.maximum = maximum; }
        @Override public Completion complete(String systemPrompt, String userPrompt) {
            if (count.incrementAndGet() > maximum) throw new IllegalStateException("Evaluation API call ceiling reached");
            Completion completion = delegate.complete(systemPrompt, userPrompt);
            responses.incrementAndGet();
            return completion;
        }
        @Override public String model() { return delegate.model(); }
    }

    public record Case(String id, String question, String metricId, String expectedSql, List<String> expectedColumns, String expectedScalar) { }
    public record Sample(String id, String question, String metricId, String errorCode, String generatedSql, boolean attempted, boolean modelResponseReceived, boolean sqlGenerated, boolean astApproved,
            boolean executionSuccess, Boolean accurate, Boolean metricAccurate, Long modelLatencyMs,
            Integer promptTokens, Integer completionTokens, Integer totalTokens, int repairAttempts,
            boolean repairSucceeded, boolean unsafeExecution, boolean referenceExecutionSuccess, long workflowElapsedMs,
            int modelAttempts, String providerFailureCategory, Integer providerStatus, Long retryAfterSeconds,
            String referenceResultDigest, String actualResultDigest) {
        static Sample notAttempted(Case testCase) { return new Sample(testCase.id(), testCase.question(), testCase.metricId(),
                "NOT_ATTEMPTED_AFTER_PROVIDER_LIMIT", "", false, false, false, false, false, null, null,
                null, null, null, null, 0, false, false, false, 0, 0, null, null, null, null, null); }
    }
    public record MetricResult(int evaluated, int accurate) { }
    public record Report(String mode, String transport, String generatedAt, int corpusSize, int sampleCount, int sqlGenerationSuccess,
            int astApproved, int executionSuccess, int accuracySamples, int accurateCount,
            int metricAccuracySamples, int metricAccurateCount, int modelResponseSamples, int providerResponseCount,
            Long averageModelLatencyMs, Long p95ModelLatencyMs,
            long tokenUsageSamples, Map<String, Long> failures, int rateLimitFailures, int repairSuccesses,
            int repairSamples, int unsafeExecutionCount, int referenceFailures, int providerApiCalls, int mockResponses,
            Map<String, MetricResult> metrics, List<Sample> samples) {
        int latencySamples() { return (int) samples.stream().filter(s -> s.modelLatencyMs() != null).count(); }
        int attemptedSamples() { return (int) samples.stream().filter(Sample::attempted).count(); }
        int generationDenominator() { return (int) samples.stream().filter(Sample::modelResponseReceived).count(); }
    }
}
