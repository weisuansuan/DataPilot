package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiModelRegistryTest {
    @Test
    void exposesModelScopeOnlyWhenConfiguredAndUsesItAsTheDefaultWhenRequested() {
        AiModelRegistry registry = registry("modelscope", "test-modelscope-token");
        var option = registry.models().stream().filter(model -> model.id().equals("modelscope:kimi-k3.1-pro")).findFirst().orElseThrow();

        assertTrue(option.configured());
        assertTrue(option.available());
        assertTrue(option.defaultModel());
        assertEquals("modelscope:kimi-k3.1-pro", registry.defaultModelId());
        var selection = registry.resolve(option.id());
        assertNotNull(selection);
        assertEquals("ModelScope", selection.provider());
        assertEquals("moonshotai/Kimi-K3.1-Pro", selection.model());
    }

    @Test
    void refusesModelScopeWhenItsTokenIsMissing() {
        AiModelRegistry registry = registry("modelscope", "");
        var option = registry.models().stream().filter(model -> model.id().equals("modelscope:kimi-k3.1-pro")).findFirst().orElseThrow();

        assertFalse(option.configured());
        assertFalse(option.available());
        assertNull(registry.resolve(option.id()));
    }

    private static AiModelRegistry registry(String provider, String apiKey) {
        var qiniuClient = new OpenAiCompatibleChatClient("http://127.0.0.1:1/v1", "qiniu-model", "", false, 1, 128, 0, 1);
        return new AiModelRegistry(qiniuClient, provider, "qiniu/conatus-free", "", 45, 1200, 5,
                "http://127.0.0.1:1/v1", "auto", "", false,
                "https://api-inference.modelscope.cn/v1", "moonshotai/Kimi-K3.1-Pro", apiKey, true);
    }
}
