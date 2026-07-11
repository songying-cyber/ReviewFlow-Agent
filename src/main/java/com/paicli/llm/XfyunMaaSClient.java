package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Request;

public class XfyunMaaSClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://maas-api.cn-huabei-1.xf-yun.com/v2";
    private static final String DEFAULT_MODEL = "Qwen3.6-35B-A3B";

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final String loraId;

    public XfyunMaaSClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, null);
    }

    public XfyunMaaSClient(String apiKey, String model, String baseUrl, String loraId) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
        this.loraId = loraId == null || loraId.isBlank() ? null : loraId.trim();
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
        return "xfyun";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }

    @Override
    public boolean supportsTools() {
        return false;
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        ObjectNode streamOptions = requestBody.putObject("stream_options");
        streamOptions.put("include_usage", true);
    }

    @Override
    protected void customizeRequest(Request.Builder request) {
        if (loraId != null) {
            request.header("lora_id", loraId);
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
