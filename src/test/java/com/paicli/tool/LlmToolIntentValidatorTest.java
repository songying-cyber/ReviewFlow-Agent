package com.paicli.tool;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmToolIntentValidatorTest {

    @Test
    void parsesDeniedIntentMismatchDecision() {
        LlmToolIntentValidator validator = new LlmToolIntentValidator(new FixedClient("""
                {"allowed":false,"recoverable":true,"message":"用户只要求查看文件，但工具会覆盖文件。","suggestion":"请改用 read_file。"}
                """), true);

        Optional<ToolError> error = validator.validate(
                new ToolIntentContext("看看 README", "react"),
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"README.md\",\"content\":\"bad\"}"),
                ToolMetadata.mediumWrite("覆盖文件"));

        assertTrue(error.isPresent());
        assertEquals(ToolErrorType.INTENT_TOOL_MISMATCH, error.get().errorType());
        assertTrue(error.get().recoverable());
        assertTrue(error.get().message().contains("查看文件"));
    }

    @Test
    void skipsReadOnlyWhenDisabled() {
        FixedClient client = new FixedClient("""
                {"allowed":false,"recoverable":true,"message":"no","suggestion":"no"}
                """);
        LlmToolIntentValidator validator = new LlmToolIntentValidator(client, false);

        Optional<ToolError> error = validator.validate(
                new ToolIntentContext("看看 README", "react"),
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"README.md\"}"),
                ToolMetadata.readOnly("读取文件"));

        assertTrue(error.isEmpty());
        assertEquals(0, client.calls);
    }

    private static final class FixedClient implements LlmClient {
        private final String response;
        private int calls;

        private FixedClient(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            calls++;
            return new ChatResponse("assistant", response, null, 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
