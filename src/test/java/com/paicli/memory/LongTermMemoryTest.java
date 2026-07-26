package com.paicli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldSearchByKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchByMultipleKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("Java 偏好", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnSpaces() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("偏好设置", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("s1", "摘要1", MemoryEntry.MemoryType.SUMMARY, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.SUMMARY, null, 8));

        // 创建新实例，从磁盘加载
        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("f1").isPresent());
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.FACT, timestamp, null, 10));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
    }

    @Test
    void shouldFilterProjectScopedMemories() {
        memory.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        memory.store(new MemoryEntry("project-a", "项目A使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"), 10));
        memory.store(new MemoryEntry("project-b", "项目B使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"), 10));

        var visible = memory.getAll("/repo/a");

        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("global")));
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("project-a")));
        assertTrue(visible.stream().noneMatch(entry -> entry.getId().equals("project-b")));
    }

    @Test
    void legacyMemoriesWithoutScopeRemainGlobal() {
        MemoryEntry legacy = new MemoryEntry("legacy", "历史偏好", MemoryEntry.MemoryType.FACT, null, 10);

        assertEquals("global", LongTermMemory.scopeOf(legacy));
        assertTrue(LongTermMemory.isVisibleInProject(legacy, "/repo/current"));
    }

    @Test
    void shouldWriteMemoryIndexAndTopicFiles() throws Exception {
        memory.store(new MemoryEntry("fact-topic", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));

        Path index = tempDir.resolve("MEMORY.md");
        Path topic = tempDir.resolve("topics").resolve("fact-topic.md");

        assertTrue(java.nio.file.Files.isRegularFile(index));
        assertTrue(java.nio.file.Files.isRegularFile(topic));
        String indexText = java.nio.file.Files.readString(index);
        String topicText = java.nio.file.Files.readString(topic);
        assertTrue(indexText.contains("[默认用中文回答](topics/fact-topic.md)"));
        assertTrue(topicText.contains("id: fact-topic"));
        assertTrue(topicText.contains("scope: global"));
        assertTrue(topicText.contains("默认用中文回答"));
    }

    @Test
    void shouldLoadTopicFilesWhenJsonIsMissing() throws Exception {
        Path topics = tempDir.resolve("topics");
        java.nio.file.Files.createDirectories(topics);
        java.nio.file.Files.writeString(topics.resolve("fact-topic.md"), """
                ---
                id: fact-topic
                name: 测试策略
                description: 使用真实数据库跑集成测试
                type: FACT
                scope: project
                project: /repo/current
                modified: 2026-08-12T00:00:00Z
                ---

                集成测试优先使用真实数据库，不使用 mock。
                """);

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());

        assertEquals(1, reloaded.size());
        MemoryEntry entry = reloaded.retrieve("fact-topic").orElseThrow();
        assertEquals("集成测试优先使用真实数据库，不使用 mock。", entry.getContent());
        assertEquals("project", entry.getMetadata().get("scope"));
        assertEquals("/repo/current", entry.getMetadata().get("project"));
    }

    @Test
    void shouldDeleteTopicFileWithEntry() throws Exception {
        memory.store(new MemoryEntry("fact-delete", "要删除的记忆", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));

        assertTrue(memory.delete("fact-delete"));

        assertFalse(java.nio.file.Files.exists(tempDir.resolve("topics").resolve("fact-delete.md")));
        assertFalse(java.nio.file.Files.readString(tempDir.resolve("MEMORY.md")).contains("fact-delete"));
    }
}
