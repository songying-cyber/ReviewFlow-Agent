package com.paicli.context;

import com.paicli.llm.LlmClient;

/**
 * 上下文策略配置。
 *
 * **设计原则**：没有"长 / 短 / 平衡"模式分档。所有参数都是 maxContextWindow 的简单函数，
 * 全模型走同一套行为，只是 window 大小不同导致触发时机和容量不同。
 *
 * 全局常量：
 * - 压缩触发阈值：预留摘要输出空间后，再保留 13k token 自动压缩缓冲
 *
 * 按 window 派生：
 * - 短期记忆预算 = window × 0.45
 * - 注入到 system prompt 的相关记忆 token 上限 = window × 0.005，封顶 5000
 * - MCP resource 索引注入：window ≥ 32k 才有意义（再小就挤）
 */
public record ContextProfile(
        int maxContextWindow,
        int agentTokenBudget,
        double compressionTriggerRatio,
        int shortTermMemoryBudget,
        int memoryContextTokens,
        boolean mcpResourceIndexEnabled,
        boolean promptCachingSupported,
        String promptCacheMode
) {
    public static final int MAX_SUMMARY_OUTPUT_RESERVE_TOKENS = 20_000;
    public static final int AUTOCOMPACT_BUFFER_TOKENS = 13_000;
    public static final double MIN_COMPRESSION_TRIGGER_RATIO = 0.50;
    private static final int MIN_WINDOW = 8_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;

    public static ContextProfile from(LlmClient llmClient) {
        int window = Math.max(MIN_WINDOW, llmClient == null ? 128_000 : llmClient.maxContextWindow());
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionTriggerRatio(window),
                shortTermBudget(window),
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode()
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionTriggerRatio(window),
                shortTerm,
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    /** 触发压缩的绝对 token 阈值（占用 ≥ 此值即压缩） */
    public int compressionTriggerTokens() {
        return autoCompactTriggerTokens(maxContextWindow);
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 压缩阈值: " + (int) (compressionTriggerRatio * 100) + "% (" + compressionTriggerTokens() + " tokens)"
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token 上限（input + output 累计），保 20% 余量给响应突发
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }

    private static double compressionTriggerRatio(int window) {
        return Math.max(MIN_COMPRESSION_TRIGGER_RATIO,
                Math.min(0.99, autoCompactTriggerTokens(window) / (double) window));
    }

    private static int autoCompactTriggerTokens(int window) {
        int safeWindow = Math.max(MIN_WINDOW, window);
        int summaryReserve = Math.min(MAX_SUMMARY_OUTPUT_RESERVE_TOKENS, Math.max(1_000, safeWindow / 4));
        int buffer = Math.min(AUTOCOMPACT_BUFFER_TOKENS, Math.max(1_000, safeWindow / 8));
        int trigger = safeWindow - summaryReserve - buffer;
        return Math.max(1_000, Math.min(safeWindow - 1, trigger));
    }
}
