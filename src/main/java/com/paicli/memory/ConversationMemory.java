package com.paicli.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 短期记忆 - 管理当前对话的上下文
 *
 * 职责：
 * 1. 维护对话历史（用户消息、助手回复、工具调用与结果）
 * 2. 当 token 超出预算时，自动压缩旧消息（滑动窗口 + 摘要）
 * 3. 提供关键词检索能力
 */
public class ConversationMemory implements Memory {
    private final LinkedHashMap<String, MemoryEntry> entries;
    private final int maxTokens;
    private int currentTokens;
    private final List<MemoryEntry> compressedSummaries;

    /**
     * @param maxTokens 最大 token 预算，超出时触发压缩
     */
    public ConversationMemory(int maxTokens) {
        this.entries = new LinkedHashMap<>();
        this.maxTokens = maxTokens;
        this.currentTokens = 0;
        this.compressedSummaries = new ArrayList<>();
    }

    @Override
    public void store(MemoryEntry entry) {
        entries.put(entry.getId(), entry);
        currentTokens += entry.getTokenCount();

        // 超出预算时自动淘汰最旧的条目
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(entry -> MemoryQueryTokenizer.matches(entry.getContent(), queryTokens))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            currentTokens -= removed.getTokenCount();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        currentTokens = 0;
        compressedSummaries.clear();
    }

    @Override
    public int getTokenCount() {
        return currentTokens;
    }

    @Override
    public int size() {
        return entries.size();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * 淘汰最旧的一条记忆，并加入压缩摘要
     */
    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, MemoryEntry> oldest = it.next();
            it.remove();
            currentTokens -= oldest.getValue().getTokenCount();
            compressedSummaries.add(oldest.getValue());
        }
    }

    /**
     * 获取已压缩淘汰的记忆摘要
     */
    public List<MemoryEntry> getCompressedSummaries() {
        return Collections.unmodifiableList(compressedSummaries);
    }

    /**
     * 将压缩摘要回注到记忆中（上下文压缩后调用）
     */
    public void injectSummary(MemoryEntry summary) {
        // 清空旧的压缩摘要
        compressedSummaries.clear();
        // 将摘要作为新条目插入
        entries.put(summary.getId(), summary);
        currentTokens += summary.getTokenCount();
    }

    /**
     * 获取记忆使用率
     */
    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        return String.format("短期记忆: %d条 / %d tokens (预算: %d, 使用率: %.0f%%, 已压缩: %d条)",
                entries.size(), currentTokens, maxTokens, getUsageRatio() * 100, compressedSummaries.size());
    }
}
