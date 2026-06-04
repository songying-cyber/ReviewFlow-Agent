package com.paicli.render;

/**
 * 渲染器状态栏数据载体。
 *
 * <p>InlineRenderer 把这些字段格式化到底部常驻状态栏；
 * LanternaRenderer 写入 StatusPane；PlainRenderer 直接忽略。
 */
public record StatusInfo(
        String model,
        long totalTokens,
        long contextWindow,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        String estimatedCost,
        boolean hitlEnabled,
        long elapsedMillis,
        String phase,
        String mcpSummary,
        String skillSummary
) {
    public StatusInfo(String model, long totalTokens, long contextWindow, boolean hitlEnabled, long elapsedMillis) {
        this(model, totalTokens, contextWindow, 0L, 0L, 0L, null, hitlEnabled, elapsedMillis,
                (totalTokens > 0 || elapsedMillis > 0) ? "running" : "idle", null, null);
    }

    public StatusInfo(String model,
                      long totalTokens,
                      long contextWindow,
                      long inputTokens,
                      long outputTokens,
                      long cachedInputTokens,
                      String estimatedCost,
                      boolean hitlEnabled,
                      long elapsedMillis,
                      String phase) {
        this(model, totalTokens, contextWindow, inputTokens, outputTokens, cachedInputTokens,
                estimatedCost, hitlEnabled, elapsedMillis, phase, null, null);
    }

    public static StatusInfo idle(String model, long contextWindow, boolean hitlEnabled) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, hitlEnabled, 0L, "idle");
    }

    public static StatusInfo active(String model, long contextWindow, boolean hitlEnabled, String phase) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, hitlEnabled, 0L, phase);
    }

    public static StatusInfo tokens(String model,
                                    long contextWindow,
                                    long inputTokens,
                                    long outputTokens,
                                    long cachedInputTokens,
                                    String estimatedCost,
                                    boolean hitlEnabled,
                                    long elapsedMillis,
                                    String phase) {
        long total = Math.max(0L, inputTokens) + Math.max(0L, outputTokens);
        return new StatusInfo(
                model,
                total,
                contextWindow,
                Math.max(0L, inputTokens),
                Math.max(0L, outputTokens),
                Math.max(0L, cachedInputTokens),
                estimatedCost,
                hitlEnabled,
                elapsedMillis,
                phase == null || phase.isBlank() ? "running" : phase
        );
    }

    public StatusInfo withEnvironment(String mcpSummary, String skillSummary) {
        return new StatusInfo(
                model,
                totalTokens,
                contextWindow,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                estimatedCost,
                hitlEnabled,
                elapsedMillis,
                phase,
                normalizeSummary(mcpSummary),
                normalizeSummary(skillSummary)
        );
    }

    private static String normalizeSummary(String summary) {
        return summary == null || summary.isBlank() ? null : summary.trim();
    }
}
