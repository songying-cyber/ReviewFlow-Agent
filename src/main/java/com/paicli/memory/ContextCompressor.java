package com.paicli.memory;

import com.paicli.llm.GLMClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 上下文压缩器 - 当对话过长时，自动压缩旧消息
 *
 * 压缩策略：
 * 1. Map-Reduce：先将旧消息分片摘要（Map），再合并摘要（Reduce）
 * 2. 保留最近 N 轮完整消息（不压缩）
 * 3. 压缩后的摘要回注到 ConversationMemory
 */
public class ContextCompressor {
    private final GLMClient llmClient;
    private final int retainRecentRounds;

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论
            - 重要的技术细节

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private static final String REDUCE_PROMPT = """
            请将以下多个摘要合并成一个整体摘要，保留所有关键信息。

            各片段摘要：
            %s

            请用中文输出合并摘要，控制在300字以内。
            """;

    private static final String EXTRACT_FACTS_PROMPT = """
            请从以下对话中提取关键事实，格式为每行一条：
            - 用户偏好和习惯
            - 项目信息（名称、路径、技术栈）
            - 重要决策和约定

            对话内容：
            %s

            请每行一条事实，不要多余解释。
            """;

    public ContextCompressor(GLMClient llmClient) {
        this(llmClient, 3);
    }

    /**
     * @param llmClient          LLM 客户端
     * @param retainRecentRounds 保留最近 N 轮完整消息不压缩
     */
    public ContextCompressor(GLMClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    /**
     * 压缩对话记忆
     *
     * @param memory 短期记忆
     * @return 压缩后的摘要，如果不需要压缩则返回 null
     */
    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() <= retainRecentRounds) {
            return null; // 条目太少，不需要压缩
        }

        // 分割：旧消息 vs 近期消息（必须拷贝，因为后面会 clear 底层集合）
        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        // Map 阶段：分片摘要
        List<String> chunkSummaries = mapPhase(oldEntries);
        if (chunkSummaries.isEmpty()) {
            return null;
        }

        // Reduce 阶段：合并摘要
        String finalSummary;
        if (chunkSummaries.size() == 1) {
            finalSummary = chunkSummaries.get(0);
        } else {
            finalSummary = reducePhase(chunkSummaries);
        }

        // 清空旧记忆，注入摘要，保留近期记忆
        memory.clear();
        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                "[历史对话摘要] " + finalSummary,
                MemoryEntry.MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(finalSummary)
        );
        memory.store(summaryEntry);

        // 回注近期记忆
        for (MemoryEntry entry : recentEntries) {
            memory.store(entry);
        }

        return finalSummary;
    }

    /**
     * 从对话中提取关键事实，存入长期记忆
     */
    public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
        if (entries.isEmpty()) return List.of();

        StringBuilder conversation = new StringBuilder();
        for (MemoryEntry entry : entries) {
            conversation.append(entry.getType()).append(": ")
                    .append(entry.getContent()).append("\n\n");
        }

        try {
            String prompt = String.format(EXTRACT_FACTS_PROMPT, conversation);
            List<GLMClient.Message> messages = List.of(
                    GLMClient.Message.system("你是一个信息提取助手，只输出关键事实，不输出其他内容。"),
                    GLMClient.Message.user(prompt)
            );

            GLMClient.ChatResponse response = llmClient.chat(messages, null);
            String factsText = response.content();

            List<String> facts = new ArrayList<>();
            for (String line : factsText.split("\n")) {
                String fact = line.trim();
                // 去掉前缀 "- " 或 "• "
                if (fact.startsWith("- ")) fact = fact.substring(2);
                else if (fact.startsWith("• ")) fact = fact.substring(2);

                if (!fact.isEmpty() && fact.length() > 5) {
                    facts.add(fact);

                    // 存入长期记忆
                    MemoryEntry factEntry = new MemoryEntry(
                            "fact-" + UUID.randomUUID().toString().substring(0, 8),
                            fact,
                            MemoryEntry.MemoryType.FACT,
                            null,
                            MemoryEntry.estimateTokens(fact)
                    );
                    longTermMemory.store(factEntry);
                }
            }
            return facts;
        } catch (IOException e) {
            System.err.println("⚠️ 事实提取失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Map 阶段：将旧消息分片，每片独立摘要
     */
    private List<String> mapPhase(List<MemoryEntry> oldEntries) {
        List<String> summaries = new ArrayList<>();
        int chunkSize = 5; // 每片 5 条消息
        List<List<MemoryEntry>> chunks = partition(oldEntries, chunkSize);

        for (List<MemoryEntry> chunk : chunks) {
            StringBuilder chunkText = new StringBuilder();
            for (MemoryEntry entry : chunk) {
                chunkText.append(entry.getType()).append(": ")
                        .append(entry.getContent()).append("\n\n");
            }

            try {
                String prompt = String.format(MAP_PROMPT, chunkText);
                List<GLMClient.Message> messages = List.of(
                        GLMClient.Message.system("你是一个对话摘要助手。"),
                        GLMClient.Message.user(prompt)
                );

                GLMClient.ChatResponse response = llmClient.chat(messages, null);
                summaries.add(response.content());
            } catch (IOException e) {
                System.err.println("⚠️ 摘要生成失败: " + e.getMessage());
                // 降级：直接截取前 200 字
                String fallback = chunkText.substring(0, Math.min(200, chunkText.length()));
                summaries.add("[压缩] " + fallback);
            }
        }

        return summaries;
    }

    /**
     * Reduce 阶段：合并多个摘要
     */
    private String reducePhase(List<String> summaries) {
        String joined = String.join("\n\n---\n\n", summaries);

        try {
            String prompt = String.format(REDUCE_PROMPT, joined);
            List<GLMClient.Message> messages = List.of(
                    GLMClient.Message.system("你是一个摘要合并助手。"),
                    GLMClient.Message.user(prompt)
            );

            GLMClient.ChatResponse response = llmClient.chat(messages, null);
            return response.content();
        } catch (IOException e) {
            System.err.println("⚠️ 摘要合并失败: " + e.getMessage());
            // 降级：直接拼接
            return String.join("；", summaries);
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
