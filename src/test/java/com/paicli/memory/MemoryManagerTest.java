package com.paicli.memory;

import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCompressBeforeShortTermMemoryEvictsOldEntries() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new GLMClient.ChatResponse("assistant", "压缩摘要", null, 100, 20)
        ));
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                40,
                128000,
                new LongTermMemory(tempDir.toFile())
        );
        String longMessage = "a".repeat(36);

        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);
        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);

        assertTrue(memoryManager.getShortTermMemory().getAll().stream()
                .anyMatch(entry -> entry.getType() == MemoryEntry.MemoryType.SUMMARY));
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
