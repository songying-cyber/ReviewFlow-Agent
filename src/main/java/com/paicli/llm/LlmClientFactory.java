package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, PaiCliConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String apiKey = config.getApiKey(normalized);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = config.getModel(normalized);
        String baseUrl = config.getBaseUrl(normalized);

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(PaiCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"glm", "deepseek", "step"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            default -> normalized;
        };
    }
}
