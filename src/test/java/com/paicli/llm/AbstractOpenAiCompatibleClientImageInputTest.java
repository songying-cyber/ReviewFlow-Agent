package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.paicli.image.ImageReferenceParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractOpenAiCompatibleClientImageInputTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesMultimodalMessageAsOpenAiCompatibleContentArray() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("看图"),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
            ))), null);

            RecordedRequest request = server.takeRequest();
            JsonNode root = MAPPER.readTree(request.getBody().readUtf8());
            JsonNode content = root.path("messages").get(0).path("content");

            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("看图", content.get(0).path("text").asText());
            assertEquals("image_url", content.get(1).path("type").asText());
            assertEquals("data:image/png;base64,aGVsbG8=",
                    content.get(1).path("image_url").path("url").asText());
        }
    }

    @Test
    void serializesLocalImageMessageWithImageBlockLast(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        BufferedImage buffered = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(buffered, "png", image.toFile());

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            client.chat(List.of(ImageReferenceParser.userMessage("看图 @image:" + image, tempDir)), null);

            RecordedRequest request = server.takeRequest();
            JsonNode content = MAPPER.readTree(request.getBody().readUtf8())
                    .path("messages").get(0).path("content");

            assertEquals("text", content.get(0).path("type").asText());
            assertEquals(true, content.get(0).path("text").asText().contains("Image source"));
            assertEquals("image_url", content.get(content.size() - 1).path("type").asText());
        }
    }

    @Test
    void doesNotSendReasoningContentBackInRequestHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.assistant("hidden reasoning", "visible answer")), null);

            RecordedRequest request = server.takeRequest();
            JsonNode message = MAPPER.readTree(request.getBody().readUtf8())
                    .path("messages").get(0);

            assertEquals("visible answer", message.path("content").asText());
            assertFalse(message.has("reasoning_content"),
                    "reasoning_content 是日志/展示信息，不应回传进下一轮请求历史");
        }
    }

    @Test
    void kimiClientKeepsReasoningContentForThinkingToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            KimiClient client = new KimiClient("test-key", "kimi-k2.6", server.url("/v1").toString());

            client.chat(List.of(LlmClient.Message.assistant(
                    "hidden reasoning",
                    "",
                    List.of(new LlmClient.ToolCall(
                            "call_1",
                            new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}")
                    ))
            )), null);

            RecordedRequest request = server.takeRequest();
            JsonNode message = MAPPER.readTree(request.getBody().readUtf8())
                    .path("messages").get(0);

            assertEquals("hidden reasoning", message.path("reasoning_content").asText());
            assertEquals("call_1", message.path("tool_calls").get(0).path("id").asText());
        }
    }

    @Test
    void deepseekClientKeepsReasoningContentForThinkingToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-v4-pro",
                    server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.assistant(
                    "hidden reasoning",
                    "",
                    List.of(new LlmClient.ToolCall(
                            "call_1",
                            new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}")
                    ))
            )), null);

            JsonNode message = MAPPER.readTree(server.takeRequest().getBody().readUtf8())
                    .path("messages").get(0);

            assertEquals("hidden reasoning", message.path("reasoning_content").asText());
            assertEquals("call_1", message.path("tool_calls").get(0).path("id").asText());
        }
    }

    @Test
    void deepseekClientUsesHttp11ForStreamingCalls() {
        DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-v4-pro",
                "https://api.deepseek.com/chat/completions");

        assertEquals(List.of(Protocol.HTTP_1_1), client.httpClient().protocols());
    }

    @Test
    void parsesReasoningFieldAliasesFromStreamingDeltas() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","reasoning":"先判断意图。"}}]}

                            data: {"choices":[{"delta":{"reasoning_details":[{"text":"再组织回复。"}]}}]}

                            data: {"choices":[{"delta":{"content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());
            AtomicReference<StringBuilder> streamedReasoning = new AtomicReference<>(new StringBuilder());

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("你好")),
                    null,
                    new LlmClient.StreamListener() {
                        @Override
                        public void onReasoningDelta(String delta) {
                            streamedReasoning.get().append(delta);
                        }
                    });

            assertEquals("先判断意图。再组织回复。", response.reasoningContent());
            assertEquals("先判断意图。再组织回复。", streamedReasoning.get().toString());
            assertEquals("ok", response.content());
        }
    }

    @Test
    void stepClientRequestsDeepseekStyleReasoningContent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            StepClient client = new StepClient("test-key", "step-3.5-flash-2603", server.url("/v1").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
            assertEquals("deepseek-style", root.path("reasoning_format").asText());
            assertEquals("high", root.path("reasoning_effort").asText());
        }
    }

    @Test
    void stepClientDoesNotSendUnsupportedReasoningEffortForOlderStepModel() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            StepClient client = new StepClient("test-key", "step-3.5-flash", server.url("/v1").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
            assertEquals("deepseek-style", root.path("reasoning_format").asText());
            assertFalse(root.has("reasoning_effort"));
        }
    }

    @Test
    void xfyunMaaSClientSendsHttpDocHeadersAndStreamOptions() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            XfyunMaaSClient client = new XfyunMaaSClient(
                    "test-key",
                    "model-id-from-card",
                    server.url("/v2").toString(),
                    "resource-id-from-card");

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            RecordedRequest request = server.takeRequest();
            assertEquals("Bearer test-key", request.getHeader("Authorization"));
            assertEquals("resource-id-from-card", request.getHeader("lora_id"));

            JsonNode root = MAPPER.readTree(request.getBody().readUtf8());
            assertEquals("model-id-from-card", root.path("model").asText());
            assertEquals(true, root.path("stream").asBoolean());
            assertEquals(true, root.path("stream_options").path("include_usage").asBoolean());
            assertFalse(root.has("tools"));
        }
    }

    @Test
    void glm5vTurboSerializesBase64ImageAsRawBase64() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            GLMClient client = new GLMClient("test-key", "glm-5v-turbo", server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("看图"),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
            ))), null);

            RecordedRequest request = server.takeRequest();
            JsonNode imageUrl = MAPPER.readTree(request.getBody().readUtf8())
                    .path("messages").get(0).path("content").get(1).path("image_url");

            assertEquals("aGVsbG8=", imageUrl.path("url").asText());
        }
    }

    @Test
    void serializesImageMessagesWithoutModelCapabilityGate() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            TextOnlyClient client = new TextOnlyClient(server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("看图"),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
            ))), null);

            RecordedRequest request = server.takeRequest();
            JsonNode content = MAPPER.readTree(request.getBody().readUtf8())
                    .path("messages").get(0).path("content");

            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("image_url", content.get(1).path("type").asText());
            assertEquals("data:image/png;base64,aGVsbG8=",
                    content.get(1).path("image_url").path("url").asText());
        }
    }

    @Test
    void throwsVisibleErrorWhenStreamingResponseHasNoContent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: [DONE]

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            IOException error = assertThrows(IOException.class,
                    () -> client.chat(List.of(LlmClient.Message.user("你好")), null));

            assertEquals("API返回空内容，请检查 provider/model 配置或该模型是否支持当前请求参数",
                    error.getMessage());
        }
    }

    @Test
    void throwsVisibleErrorForStreamingErrorChunk() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"error":{"code":10040,"message":"image rejected by provider"}}

                            """));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            IOException error = assertThrows(IOException.class,
                    () -> client.chat(List.of(LlmClient.Message.user("你好")), null));

            assertEquals("API请求失败: 10040 - image rejected by provider", error.getMessage());
        }
    }


    // 验证 @image: 用户输入 和 MCP screenshot 工具回灌（appendImageToolMessages 同款 user(text, image_part)）
    // 经过同一个 appendMessageContent 路径序列化后，image_url block 字段（type / mime / base64 / url
    // 前缀）完全一致，仅文本 part 内容不同。这条用来回答"为什么 Chrome DevTools 路径模型能描述、@image:
    // 路径模型瞎编"的问题：协议层无差，差异在文本指引和模型行为。
    @Test
    void atImageAndMcpScreenshotProduceIdenticalImageBlock(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        BufferedImage buffered = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(buffered, "png", image.toFile());

        // 1) @image: 路径
        LlmClient.Message atImageMsg = ImageReferenceParser.userMessage("看图 @image:" + image, tempDir);

        // 2) MCP screenshot 路径：appendImageToolMessages 构造的 user(text, image_part)，
        //    image_part 来源是 ImageProcessor.toContentPart(processed)，processed 与 @image: 同源。
        com.paicli.image.ImageProcessor.ProcessedImage processed =
                com.paicli.image.ImageProcessor.fromPath(image, "image/png");
        LlmClient.Message mcpMsg = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("工具 mcp__chrome-devtools__take_screenshot 返回了图片内容，请结合上面的工具文本结果分析。"),
                com.paicli.image.ImageProcessor.toContentPart(processed)
        ));

        try (MockWebServer server = new MockWebServer()) {
            String responseBody = """
                    data: {"choices":[{"delta":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                    data: [DONE]

                    """;
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(responseBody));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(responseBody));
            TestClient client = new TestClient(server.url("/chat/completions").toString());

            client.chat(List.of(atImageMsg), null);
            client.chat(List.of(mcpMsg), null);

            JsonNode atImageContent = MAPPER.readTree(server.takeRequest().getBody().readUtf8())
                    .path("messages").get(0).path("content");
            JsonNode mcpContent = MAPPER.readTree(server.takeRequest().getBody().readUtf8())
                    .path("messages").get(0).path("content");

            JsonNode atImageBlock = atImageContent.get(atImageContent.size() - 1);
            JsonNode mcpBlock = mcpContent.get(mcpContent.size() - 1);

            assertEquals("image_url", atImageBlock.path("type").asText());
            assertEquals("image_url", mcpBlock.path("type").asText());
            assertEquals(atImageBlock.path("image_url").path("url").asText(),
                    mcpBlock.path("image_url").path("url").asText(),
                    "两条路径序列化出来的 image_url 应该字节相同；如果这里不等，就是协议差异");
        }
    }

    @Test
    void canStripHistoricalImagePayloadWhileKeepingTextMetadata() {
        LlmClient.Message message = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("Image source: /tmp/shot.png"),
                LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
        ));

        LlmClient.Message stripped = message.withoutImageContent();

        assertFalse(stripped.hasImageContent());
        assertEquals("user", stripped.role());
        assertEquals(2, stripped.contentParts().size());
        assertEquals("Image source: /tmp/shot.png\n\n[历史图片附件已省略 1 张；如需重新查看，请使用上文 Image source 或相关工具结果。]",
                stripped.content());
    }

    private static final class TestClient extends AbstractOpenAiCompatibleClient {
        private final String apiUrl;

        private TestClient(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        @Override
        protected String getApiUrl() {
            return apiUrl;
        }

        @Override
        protected String getModel() {
            return "image-input-test";
        }

        @Override
        public String getModelName() {
            return getModel();
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }

    }

    private static final class TextOnlyClient extends AbstractOpenAiCompatibleClient {
        private final String apiUrl;

        private TextOnlyClient(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        @Override
        protected String getApiUrl() {
            return apiUrl;
        }

        @Override
        protected String getModel() {
            return "text-only-test";
        }

        @Override
        public String getModelName() {
            return getModel();
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }
    }
}
