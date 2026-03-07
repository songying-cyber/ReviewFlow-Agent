package com.paicli.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {

    @Test
    void shouldStoreAndRetrieveEntry() {
        ConversationMemory memory = new ConversationMemory(4096);
        MemoryEntry entry = new MemoryEntry("test-1", "你好世界", MemoryEntry.MemoryType.CONVERSATION, null, 10);

        memory.store(entry);

        assertEquals(1, memory.size());
        assertTrue(memory.retrieve("test-1").isPresent());
        assertEquals("你好世界", memory.retrieve("test-1").get().getContent());
    }

    @Test
    void shouldEvictOldestWhenOverBudget() {
        ConversationMemory memory = new ConversationMemory(50); // 很小的预算

        MemoryEntry entry1 = new MemoryEntry("e1", "第一条消息内容", MemoryEntry.MemoryType.CONVERSATION, null, 20);
        MemoryEntry entry2 = new MemoryEntry("e2", "第二条消息内容", MemoryEntry.MemoryType.CONVERSATION, null, 20);
        MemoryEntry entry3 = new MemoryEntry("e3", "第三条消息内容", MemoryEntry.MemoryType.CONVERSATION, null, 20);

        memory.store(entry1);
        memory.store(entry2);
        memory.store(entry3);

        // 第一条应该被淘汰
        assertTrue(memory.retrieve("e1").isEmpty());
        assertFalse(memory.retrieve("e3").isEmpty());
    }

    @Test
    void shouldSearchByKeyword() {
        ConversationMemory memory = new ConversationMemory(4096);
        memory.store(new MemoryEntry("e1", "用户偏好：喜欢用Java", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("e2", "执行命令: mvn clean", MemoryEntry.MemoryType.TOOL_RESULT, null, 10));

        var results = memory.search("Java", 10);
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).getId());
    }

    @Test
    void shouldDeleteEntry() {
        ConversationMemory memory = new ConversationMemory(4096);
        memory.store(new MemoryEntry("e1", "内容", MemoryEntry.MemoryType.CONVERSATION, null, 5));

        assertTrue(memory.delete("e1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldClearAll() {
        ConversationMemory memory = new ConversationMemory(4096);
        memory.store(new MemoryEntry("e1", "内容1", MemoryEntry.MemoryType.CONVERSATION, null, 5));
        memory.store(new MemoryEntry("e2", "内容2", MemoryEntry.MemoryType.CONVERSATION, null, 5));

        memory.clear();

        assertEquals(0, memory.size());
        assertEquals(0, memory.getTokenCount());
    }

    @Test
    void shouldTrackTokenCount() {
        ConversationMemory memory = new ConversationMemory(4096);
        memory.store(new MemoryEntry("e1", "test", MemoryEntry.MemoryType.CONVERSATION, null, 50));
        memory.store(new MemoryEntry("e2", "test2", MemoryEntry.MemoryType.CONVERSATION, null, 30));

        assertEquals(80, memory.getTokenCount());
    }

    @Test
    void shouldReturnUsageRatio() {
        ConversationMemory memory = new ConversationMemory(100);
        memory.store(new MemoryEntry("e1", "test", MemoryEntry.MemoryType.CONVERSATION, null, 50));

        assertEquals(0.5, memory.getUsageRatio(), 0.01);
    }
}
