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

class AgentLspDiagnosticsTest {

    @Test
    void injectsPostEditDiagnosticsBeforeNextLlmCall(@TempDir Path tempDir) {
        LlmClient.ToolCall writeBrokenJava = new LlmClient.ToolCall(
                "call_1",
                new LlmClient.ToolCall.Function(
                        "write_file",
                        "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}"
                )
        );
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(writeBrokenJava), 10, 2),
                new LlmClient.ChatResponse("assistant", "已看到诊断。", null, 20, 5)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("写一个有语法问题的 Java 文件");

        assertEquals(2, llm.messagesByCall.size());
        List<LlmClient.Message> secondCallMessages = llm.messagesByCall.get(1);
        assertTrue(secondCallMessages.stream()
                        .filter(message -> "user".equals(message.role()))
                        .anyMatch(message -> message.content().contains("[LSP 诊断注入]")
                                && message.content().contains("Broken.java")
                                && message.content().contains("[error]")),
                "第二轮 LLM 请求前应注入 LSP 诊断");
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
