package com.paicli.memory;

import com.paicli.llm.GLMClient;

import java.util.List;
import java.util.UUID;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理短期记忆、长期记忆、上下文压缩和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private final TokenBudget tokenBudget;

    public MemoryManager(GLMClient llmClient) {
        this(llmClient, 8192, 200000, null);
    }

    /**
     * @param llmClient      LLM 客户端（用于压缩时的摘要生成）
     * @param shortTermBudget 短期记忆 token 预算
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(GLMClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(GLMClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this.shortTermMemory = new ConversationMemory(shortTermBudget);
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextWindow);
    }

    /**
     * 添加用户消息到短期记忆
     */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                null,
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 添加助手回复到短期记忆
     */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                null,
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 添加工具执行结果到短期记忆
     */
    public void addToolResult(String toolName, String result) {
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                "[" + toolName + "] " + result,
                MemoryEntry.MemoryType.TOOL_RESULT,
                null,
                MemoryEntry.estimateTokens(result)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                null,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
    }

    /**
     * 检索与查询最相关的记忆
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    /**
     * 构建用于 LLM 的记忆上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    /**
     * 检查并触发压缩（由 Agent 在 LLM 调用前主动调用）
     *
     * @return 是否执行了压缩
     */
    public boolean compressIfNeeded() {
        if (!tokenBudget.needsCompression(shortTermMemory)) {
            return false;
        }
        System.out.println("📦 短期记忆接近预算上限，触发压缩...");
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            System.out.println("   压缩完成，摘要: " +
                    summary.substring(0, Math.min(100, summary.length())) + "...");
        }
        return summary != null;
    }

    /**
     * 在对话结束时，提取关键事实存入长期记忆
     */
    public void extractAndSaveFacts() {
        List<MemoryEntry> conversations = shortTermMemory.getAll();
        if (conversations.isEmpty()) return;

        System.out.println("🧠 提取关键事实到长期记忆...");
        List<String> facts = compressor.extractFacts(conversations, longTermMemory);
        if (!facts.isEmpty()) {
            System.out.println("   提取了 " + facts.size() + " 条事实");
        }
    }

    /**
     * 清空短期记忆（保留长期记忆）
     */
    public void clearShortTerm() {
        // 先提取事实
        extractAndSaveFacts();
        shortTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // Getter
    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
}
