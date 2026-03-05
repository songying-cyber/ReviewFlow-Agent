package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GLM-5.1 API 客户端
 */
public class GLMClient {
    private static final String API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String MODEL = "glm-5.1";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String apiKey;

    public GLMClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 发送聊天请求（支持工具调用）
     */
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", MODEL);

        // 添加消息
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());

            // 添加工具调用信息
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 添加工具调用结果
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            String responseBody = responseBodyObj.string();
            JsonNode root = mapper.readTree(responseBody);

            // 解析响应
            JsonNode choice = root.path("choices").get(0);
            JsonNode message = choice.path("message");

            String role = message.path("role").asText();
            String content = message.path("content").asText();

            // 解析工具调用
            List<ToolCall> toolCalls = null;
            if (message.has("tool_calls") && message.path("tool_calls").isArray()) {
                toolCalls = new ArrayList<>();
                for (JsonNode tc : message.path("tool_calls")) {
                    toolCalls.add(new ToolCall(
                            tc.path("id").asText(),
                            new ToolCall.Function(
                                    tc.path("function").path("name").asText(),
                                    tc.path("function").path("arguments").asText()
                            )
                    ));
                }
            }

            // 解析token使用
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt();
            int outputTokens = usage.path("completion_tokens").asInt();

            return new ChatResponse(role, content, toolCalls, inputTokens, outputTokens);
        }
    }

    // 记录定义
    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public Message(String role, String content) {
            this(role, content, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, toolCallId);
        }
    }

    public record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }

    public record Tool(String name, String description, JsonNode parameters) {}

    public record ChatResponse(String role, String content, List<ToolCall> toolCalls,
                               int inputTokens, int outputTokens) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
