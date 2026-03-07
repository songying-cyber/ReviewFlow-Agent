package com.paicli.memory;

import com.paicli.llm.GLMClient;
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
        List<GLMClient.Message> messages = List.of(
                GLMClient.Message.system("系统提示"),
                GLMClient.Message.user("用户消息")
        );

        int tokens = TokenBudget.estimateMessagesTokens(messages);
        assertTrue(tokens > 0);
        // 至少包含两条消息的开销
        assertTrue(tokens >= 8); // 2 messages * 4 overhead
    }

    @Test
    void shouldDetectCompressionNeed() {
        TokenBudget budget = new TokenBudget(128000);
        // needsCompression 检查 memory.getTokenCount() > getAvailableForConversation() * 0.8
        // getAvailableForConversation = 128000 - 500 - 800 - 2000 = 124700
        // 80% = 99760
        ConversationMemory memory = new ConversationMemory(200000);
        memory.store(new MemoryEntry("e1", "test content that is long enough", MemoryEntry.MemoryType.CONVERSATION, null, 100000));

        assertTrue(budget.needsCompression(memory));
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
