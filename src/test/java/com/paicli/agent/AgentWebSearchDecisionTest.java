package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentWebSearchDecisionTest {

    @Test
    void doesNotRunWebSearchBeforeModelToolCallForCurrentReadme(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "已读取 README。", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("读一下当前的readme");

        assertEquals(0, registry.webSearchCalls);
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private int webSearchCalls;

        @Override
        public String executeTool(String name, String argumentsJson) {
            if ("web_search".equals(name)) {
                webSearchCalls++;
            }
            return super.executeTool(name, argumentsJson);
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
