package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {

    @Test
    void shouldCalculateAvailableTokens() {
        TokenBudget budget = new TokenBudget(128000);
        int available = budget.getAvailableForConversation();
        // 128000 - 500(system) - 800(tools) - 2000(response)
        assertEquals(124700, available);
    }

    @Test
    void shouldTrackUsageStats() {
        TokenBudget budget = new TokenBudget(128000);
        budget.recordUsage(1000, 500);
        budget.recordUsage(1200, 600);

        assertEquals(2200, budget.getTotalInputTokens());
        assertEquals(1100, budget.getTotalOutputTokens());
        assertEquals(2, budget.getLlmCallCount());
    }

    @Test
    void shouldEstimateMessageTokens() {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("系统提示"),
                LlmClient.Message.user("用户消息")
        );

        int tokens = TokenBudget.estimateMessagesTokens(messages);
        assertTrue(tokens > 0);
        // 至少包含两条消息的开销
        assertTrue(tokens >= 8); // 2 messages * 4 overhead
    }

    @Test
    void shouldDetectCompressionNeed() {
        TokenBudget budget = new TokenBudget(128000);
        // needsCompression 默认 0.9 触发率：
        // getAvailableForConversation = 128000 - 500 - 800 - 2000 = 124700
        // 触发阈值 90% = 112230
        ConversationMemory memory = new ConversationMemory(200000);
        memory.store(new MemoryEntry("e1", "test content that is long enough", MemoryEntry.MemoryType.CONVERSATION, null, 113000));

        assertTrue(budget.needsCompression(memory));
    }

    @Test
    void shouldRespectCustomTriggerRatio() {
        TokenBudget budget = new TokenBudget(128000);
        ConversationMemory memory = new ConversationMemory(200000);
        // 占用 70k：超过 0.5 阈值（62k），但低于 0.9 阈值（112k）
        memory.store(new MemoryEntry("e1", "x", MemoryEntry.MemoryType.CONVERSATION, null, 70000));

        assertTrue(budget.needsCompression(memory, 0.5), "占用 70k 应触发 50% 阈值");
        assertFalse(budget.needsCompression(memory, 0.9), "占用 70k 不应触发 90% 阈值");
    }

    @Test
    void shouldGenerateUsageReport() {
        TokenBudget budget = new TokenBudget(128000);
        budget.recordUsage(1000, 500);

        String report = budget.getUsageReport();
        assertTrue(report.contains("1 次"));
        assertTrue(report.contains("1000"));
    }
}
