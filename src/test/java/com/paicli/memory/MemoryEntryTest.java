package com.paicli.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTest {

    @Test
    void shouldEstimateTokensForChinese() {
        int tokens = MemoryEntry.estimateTokens("你好世界这是测试");
        assertTrue(tokens > 0);
        // 纯中文约 1.5 字/token，8个字约 5-6 tokens
        assertTrue(tokens >= 4 && tokens <= 8);
    }

    @Test
    void shouldEstimateTokensForEnglish() {
        int tokens = MemoryEntry.estimateTokens("hello world this is a test");
        assertTrue(tokens > 0);
    }

    @Test
    void shouldEstimateTokensForMixedContent() {
        int tokens = MemoryEntry.estimateTokens("使用Java 17开发Agent");
        assertTrue(tokens > 0);
    }

    @Test
    void shouldHandleEmptyOrNullText() {
        assertEquals(0, MemoryEntry.estimateTokens(""));
        assertEquals(0, MemoryEntry.estimateTokens(null));
    }

    @Test
    void shouldCreateEntryWithMetadata() {
        MemoryEntry entry = new MemoryEntry("test-1", "内容", MemoryEntry.MemoryType.FACT,
                Map.of("source", "user"), 5);

        assertEquals("test-1", entry.getId());
        assertEquals(MemoryEntry.MemoryType.FACT, entry.getType());
        assertEquals("user", entry.getMetadata().get("source"));
    }

    @Test
    void shouldProvideStringRepresentation() {
        MemoryEntry entry = new MemoryEntry("test-1", "这是一段很长的内容用来测试toString方法是否正确截断显示", MemoryEntry.MemoryType.CONVERSATION, null, 10);
        String str = entry.toString();
        assertTrue(str.contains("CONVERSATION"));
        assertTrue(str.contains("test-1"));
    }
}
