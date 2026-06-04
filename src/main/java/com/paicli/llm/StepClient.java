package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class StepClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.stepfun.com/v1";
    private static final String DEFAULT_MODEL = "step-3.5-flash";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public StepClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public StepClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "step";
    }

    @Override
    public int maxContextWindow() {
        return 256_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "step-prefix-cache";
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        // StepFun 默认 reasoning_format=general，会返回 `reasoning` 字段；
        // deepseek-style 让它返回 PaiCLI/DeepSeek 兼容的 `reasoning_content`。
        requestBody.put("reasoning_format", "deepseek-style");
        if (model != null && model.contains("2603")) {
            requestBody.put("reasoning_effort", "high");
        }
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
