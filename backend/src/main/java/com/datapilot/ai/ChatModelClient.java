package com.datapilot.ai;

public interface ChatModelClient {
    Completion complete(String systemPrompt, String userPrompt);
    default String model() { return "mock"; }

    record Completion(String content, TokenUsage tokenUsage) { }
    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) { }
}
