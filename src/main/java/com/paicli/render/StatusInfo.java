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
        boolean hitlEnabled,
        long elapsedMillis
) {
    public static StatusInfo idle(String model, long contextWindow, boolean hitlEnabled) {
        return new StatusInfo(model, 0L, contextWindow, hitlEnabled, 0L);
    }
}
