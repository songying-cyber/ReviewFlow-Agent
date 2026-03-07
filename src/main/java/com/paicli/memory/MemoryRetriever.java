package com.paicli.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从短期记忆和长期记忆中检索最相关的信息
 *
 * 检索策略：
 * 1. 关键词匹配：直接匹配内容中的关键词
 * 2. 类型优先：不同场景优先检索不同类型的记忆
 * 3. 时间衰减：越近的记忆权重越高
 */
public class MemoryRetriever {
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
    }

    /**
     * 检索与查询最相关的记忆
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度排序的记忆列表
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        // 从短期记忆中检索
        for (MemoryEntry entry : shortTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score, true));
            }
        }

        // 从长期记忆中检索
        for (MemoryEntry entry : longTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            // 长期记忆加一个小权重，因为它更精炼
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score * 1.2, false));
            }
        }

        // 按分数降序排序
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文：将相关记忆组装成文本，用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        List<MemoryEntry> relevant = retrieve(query, 10);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    /**
     * 计算记忆条目与查询的相关度分数
     */
    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 1. 精确匹配加分
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }

        // 2. 关键词匹配
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;

        double keywordScore = (double) matchedWords / queryWords.size();

        // 3. 时间衰减（越近分数越高，简单实现）
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0); // 24小时内从1.0衰减到0.5

        return keywordScore * timeDecay;
    }

    private record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {}
}
