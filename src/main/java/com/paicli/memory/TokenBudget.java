package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.util.List;

/**
 * Token 预算管理器 - 确保对话不会超出模型的上下文窗口
 *
 * 策略：
 * 1. 设定总 token 预算（系统提示 + 工具定义 + 对话历史 + 回复预留）
 * 2. 每次调用 LLM 前检查预算
 * 3. 超出预算时触发压缩或裁剪
 */
public class TokenBudget {
    private final int contextWindow;    // 模型上下文窗口大小
    private final int reservedForSystem; // 系统提示预留
    private final int reservedForTools;  // 工具定义预留
    private final int reservedForResponse; // 回复预留

    // 累计 token 消耗统计
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    /**
     * @param contextWindow       模型上下文窗口（如 128K = 131072）
     * @param reservedForSystem   系统提示预留 token 数
     * @param reservedForTools    工具定义预留 token 数
     * @param reservedForResponse 回复预留 token 数
     */
    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.totalCachedInputTokens = 0;
        this.llmCallCount = 0;
    }

    /**
     * 获取对话历史可用的 token 预算
     */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /**
     * 检查给定的消息列表是否在预算内
     */
    public boolean isWithinBudget(List<LlmClient.Message> messages) {
        int estimatedTokens = estimateMessagesTokens(messages);
        return estimatedTokens <= getAvailableForConversation();
    }

    /**
     * 检查是否需要压缩。
     *
     * @param triggerRatio 触发压缩的占用率（0.0–1.0）；通常由 {@code ContextProfile.compressionTriggerRatio()} 提供
     */
    public boolean needsCompression(ConversationMemory memory, double triggerRatio) {
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * triggerRatio;
    }

    /** 兼容旧调用方：默认 0.9 触发率。 */
    public boolean needsCompression(ConversationMemory memory) {
        return needsCompression(memory, 0.9);
    }

    /**
     * 记录一次 LLM 调用的 token 消耗
     */
    public void recordUsage(int inputTokens, int outputTokens) {
        recordUsage(inputTokens, outputTokens, 0);
    }

    public void recordUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        totalCachedInputTokens += Math.max(0, cachedInputTokens);
        llmCallCount++;
    }

    /**
     * 获取 token 使用统计
     */
    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | cached: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, totalCachedInputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getTotalCachedInputTokens() { return totalCachedInputTokens; }
    public int getLlmCallCount() { return llmCallCount; }

    /**
     * 估算消息列表的 token 总数
     */
    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (LlmClient.Message msg : messages) {
            if (msg.contentParts() != null) {
                for (LlmClient.ContentPart part : msg.contentParts()) {
                    if (part == null) {
                        continue;
                    }
                    if (part.isText()) {
                        total += MemoryEntry.estimateTokens(part.text());
                    } else if (part.isImage()) {
                        total += estimateImageTokens(part);
                    }
                }
            } else {
                total += MemoryEntry.estimateTokens(msg.content());
            }
            // 工具调用的 arguments 也计算
            if (msg.toolCalls() != null) {
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    total += MemoryEntry.estimateTokens(tc.function().arguments());
                }
            }
        }
        // 每条消息额外开销约 4 tokens（role、separator 等）
        total += messages.size() * 4;
        return total;
    }

    private static int estimateImageTokens(LlmClient.ContentPart part) {
        if (part.imageBase64() != null && !part.imageBase64().isBlank()) {
            int bytes = (int) (part.imageBase64().length() * 3L / 4L);
            return Math.max(256, Math.min(4096, bytes / 768));
        }
        return 1024;
    }
}
