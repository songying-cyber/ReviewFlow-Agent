package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压缩 ReAct 主循环里的 {@code conversationHistory}（即 {@code List<LlmClient.Message>}）。
 *
 * 与 {@link ContextCompressor} 的区别：
 * - {@code ContextCompressor} 压的是 {@link ConversationMemory}（PaiCLI 的短期记忆条目）
 * - 本类压的是 Agent 实际发给 LLM 的消息列表
 *
 * 第 3 期 Memory 设计假设"LLM 调用从 shortTermMemory 重建消息"，但实际 Agent 直接维护
 * conversationHistory，与 shortTermMemory 并行。两个度量错位导致旧版压缩从未真正缩短
 * 即将发给 LLM 的 token——本类是在 Agent.run 主循环里"调 LLM 前评估并压缩"的补丁。
 *
 * 算法：
 * 1. 估算 conversationHistory 当前 token，未达 trigger 直接返回 false
 * 2. 找出所有 user message 的索引；保留最近 retainRecentRounds 个 user 起算的尾部
 * 3. 把 system 之后、splitIdx 之前的全部消息喂给 LLM 摘要
 * 4. 重建：[system] + [user("[已压缩的历史对话摘要]\n" + summary)] +
 *         [assistant("好的，已了解上下文。请继续。")] + [尾部保留消息]
 *
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call / tool_result 的成对协议。
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @param triggerTokens 触发压缩的 token 阈值（通常是 ContextProfile.compressionTriggerTokens()）
     * @return 是否真的压缩了
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }
        if (userIndices.size() <= retainRecentRounds) {
            log.info("compactIfNeeded skip: only {} user turns, < retain {}",
                    userIndices.size(), retainRecentRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIdx <= systemEnd) return false;

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, summary chars %d",
                currentTokens, afterTokens, userIndices.size() + systemEnd /* 估值 */, rebuilt.size(),
                summary.length()));
        return true;
    }

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }
}
