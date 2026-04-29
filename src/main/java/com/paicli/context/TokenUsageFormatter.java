package com.paicli.context;

import com.paicli.llm.LlmClient;
import com.paicli.util.AnsiStyle;

import java.util.Locale;

public final class TokenUsageFormatter {
    private TokenUsageFormatter() {
    }

    public static String format(LlmClient llmClient, int inputTokens, int outputTokens,
                                int cachedInputTokens, long startNanos) {
        ContextProfile profile = ContextProfile.from(llmClient);
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        int total = Math.max(0, inputTokens) + Math.max(0, outputTokens);
        String cost = estimatedCostCny(llmClient, inputTokens, outputTokens, cachedInputTokens);
        return AnsiStyle.subtle(String.format(Locale.ROOT,
                "📊 Token: 已用 %d / %d (window %d, cached: %d, 估算 %s) | 输入 %d / 输出 %d | ⏱ %.1fs",
                total,
                profile.agentTokenBudget(),
                profile.maxContextWindow(),
                Math.max(0, cachedInputTokens),
                cost,
                Math.max(0, inputTokens),
                Math.max(0, outputTokens),
                elapsedSeconds));
    }

    private static String estimatedCostCny(LlmClient llmClient, int inputTokens, int outputTokens, int cachedInputTokens) {
        String provider = llmClient == null ? "" : llmClient.getProviderName();
        double inputPerMillion;
        double cachedPerMillion;
        double outputPerMillion;
        if ("deepseek".equalsIgnoreCase(provider)) {
            inputPerMillion = 2.0;
            cachedPerMillion = 0.5;
            outputPerMillion = 8.0;
        } else if ("glm".equalsIgnoreCase(provider)) {
            inputPerMillion = 5.0;
            cachedPerMillion = 1.0;
            outputPerMillion = 15.0;
        } else {
            inputPerMillion = 5.0;
            cachedPerMillion = 1.0;
            outputPerMillion = 15.0;
        }
        int cached = Math.max(0, Math.min(inputTokens, cachedInputTokens));
        int uncachedInput = Math.max(0, inputTokens - cached);
        double cny = (uncachedInput / 1_000_000.0) * inputPerMillion
                + (cached / 1_000_000.0) * cachedPerMillion
                + (Math.max(0, outputTokens) / 1_000_000.0) * outputPerMillion;
        return String.format(Locale.ROOT, "¥%.4f", cny);
    }
}
