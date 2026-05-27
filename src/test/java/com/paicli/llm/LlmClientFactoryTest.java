package com.paicli.llm;

import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmClientFactoryTest {

    @Test
    void createsGlm5vTurboClientWithMultimodalEndpoint() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("glm",
                new PaiCliConfig.ProviderConfig("test-glm-key", null, "glm-5v-turbo"));

        LlmClient client = LlmClientFactory.create("glm", config);

        GLMClient glmClient = assertInstanceOf(GLMClient.class, client);
        assertEquals("glm", glmClient.getProviderName());
        assertEquals("glm-5v-turbo", glmClient.getModelName());
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glmClient.getApiUrl());
    }

    @Test
    void createsStepClientFromConfiguredProvider() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("step",
                new PaiCliConfig.ProviderConfig("test-step-key", null, "step-3.5-flash-2603"));

        LlmClient client = LlmClientFactory.create("step", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step", stepClient.getProviderName());
        assertEquals("step-3.5-flash-2603", stepClient.getModelName());
        assertEquals(256_000, stepClient.maxContextWindow());
        assertEquals(expectedStepChatUrl(config.getBaseUrl("step")), stepClient.getApiUrl());
    }

    @Test
    void createsStepClientFromStepfunAliasAndCustomBaseUrl() {
        PaiCliConfig config = new PaiCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("step",
                new PaiCliConfig.ProviderConfig(
                        "test-step-key",
                        "https://api.stepfun.com/step_plan/v1",
                        "step-router-v1"));

        LlmClient client = LlmClientFactory.create("stepfun", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step-router-v1", stepClient.getModelName());
        assertEquals("https://api.stepfun.com/step_plan/v1/chat/completions", stepClient.getApiUrl());
    }

    @Test
    void createsKimiClientFromMoonshotAliasAndCustomBaseUrl() {
        PaiCliConfig config = new PaiCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("kimi",
                new PaiCliConfig.ProviderConfig(
                        "test-kimi-key",
                        "https://api.moonshot.ai/v1",
                        "kimi-k2.6"));

        LlmClient client = LlmClientFactory.create("moonshot", config);

        KimiClient kimiClient = assertInstanceOf(KimiClient.class, client);
        assertEquals("kimi", kimiClient.getProviderName());
        assertEquals("kimi-k2.6", kimiClient.getModelName());
        assertEquals(256_000, kimiClient.maxContextWindow());
    }

    @Test
    void returnsNullForUnknownProvider() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("unknown", new PaiCliConfig.ProviderConfig("test-key", null, "unknown-model"));

        assertNull(LlmClientFactory.create("unknown", config));
    }

    private static String expectedStepChatUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.trim()
                : "https://api.stepfun.com/v1";
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
