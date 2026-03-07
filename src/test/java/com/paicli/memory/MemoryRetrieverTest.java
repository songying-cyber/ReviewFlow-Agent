package com.paicli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    @TempDir
    Path tempDir;

    private ConversationMemory shortTerm;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        shortTerm = new ConversationMemory(4096);
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(shortTerm, longTerm);
    }

    @Test
    void shouldRetrieveFromShortTerm() {
        shortTerm.store(new MemoryEntry("e1", "项目使用Maven构建", MemoryEntry.MemoryType.CONVERSATION, null, 10));
        shortTerm.store(new MemoryEntry("e2", "今天天气不错", MemoryEntry.MemoryType.CONVERSATION, null, 10));

        var results = retriever.retrieve("Maven", 5);
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).getId());
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldRetrieveFromBothMemories() {
        shortTerm.store(new MemoryEntry("e1", "正在开发Spring Boot应用", MemoryEntry.MemoryType.CONVERSATION, null, 10));
        longTerm.store(new MemoryEntry("f1", "项目技术栈：Spring Boot + MyBatis", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertEquals(2, results.size());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));

        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        shortTerm.store(new MemoryEntry("e1", "无关内容", MemoryEntry.MemoryType.CONVERSATION, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }
}
