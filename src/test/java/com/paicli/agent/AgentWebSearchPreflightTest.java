package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWebSearchPreflightTest {

    @Test
    void injectsFreshnessSearchBeforeFirstLlmCall(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "基于联网结果回答。", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("@image:</tmp/demo.png> 除了图里提到的，AI Agent 还有哪些2026年最新技术栈？");

        assertEquals(1, registry.webSearchCalls);
        assertTrue(registry.lastArguments.contains("2026 AI Agent 技术栈 趋势"));
        assertEquals(1, llm.messagesByCall.size());
        assertTrue(llm.messagesByCall.get(0).stream()
                        .filter(message -> "user".equals(message.role()))
                        .anyMatch(message -> message.content().contains("## 联网预检结果")
                                && message.content().contains("联网结果：MCP A2A")),
                "第一轮 LLM 请求前应注入自动联网搜索结果");
    }

    @Test
    void skipsFreshnessSearchWhenUserAsksOffline(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "离线回答。", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("不要联网，基于已有知识说说 2026 AI Agent 技术栈");

        assertEquals(0, registry.webSearchCalls);
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private int webSearchCalls;
        private String lastArguments = "";

        @Override
        public String executeTool(String name, String argumentsJson) {
            if ("web_search".equals(name)) {
                webSearchCalls++;
                lastArguments = argumentsJson;
                return "联网结果：MCP A2A Agents SDK LangGraph";
            }
            return super.executeTool(name, argumentsJson);
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
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
