package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    String getModelName();

    String getProviderName();

    default int maxContextWindow() {
        return 128_000;
    }

    default boolean supportsPromptCaching() {
        return false;
    }

    default String promptCacheMode() {
        return "none";
    }

    record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null, null);
        }

        public static ContentPart imageBase64(String imageBase64, String mimeType) {
            return new ContentPart("image_base64", null, imageBase64, null,
                    mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        }

        public static ContentPart imageUrl(String imageUrl) {
            return new ContentPart("image_url", null, null, imageUrl, null);
        }

        public boolean isText() {
            return "text".equals(type);
        }

        public boolean isImage() {
            return "image_base64".equals(type) || "image_url".equals(type);
        }
    }

    record Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                   String toolCallId, List<ContentPart> contentParts) {
        public Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                       String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, null);
        }

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message user(List<ContentPart> contentParts) {
            return new Message("user", plainText(contentParts), null, null, null,
                    contentParts == null ? null : List.copyOf(contentParts));
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }

        public boolean hasContentParts() {
            return contentParts != null && !contentParts.isEmpty();
        }

        public boolean hasImageContent() {
            return hasContentParts() && contentParts.stream().anyMatch(ContentPart::isImage);
        }

        public int imagePartCount() {
            if (!hasContentParts()) {
                return 0;
            }
            int count = 0;
            for (ContentPart part : contentParts) {
                if (part != null && part.isImage()) {
                    count++;
                }
            }
            return count;
        }

        public Message withoutImageContent() {
            if (!hasImageContent()) {
                return this;
            }
            List<ContentPart> stripped = new ArrayList<>();
            int omitted = 0;
            for (ContentPart part : contentParts) {
                if (part == null) {
                    continue;
                }
                if (part.isImage()) {
                    omitted++;
                } else {
                    stripped.add(part);
                }
            }
            stripped.add(ContentPart.text("[历史图片附件已省略 " + omitted
                    + " 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"));
            return new Message(role, plainText(stripped), reasoningContent, toolCalls, toolCallId, List.copyOf(stripped));
        }

        public Message withoutReasoningContent() {
            if (reasoningContent == null || reasoningContent.isBlank()) {
                return this;
            }
            return new Message(role, content, null, toolCalls, toolCallId, contentParts);
        }

        private static String plainText(List<ContentPart> parts) {
            if (parts == null || parts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int imageCount = 0;
            for (ContentPart part : parts) {
                if (part == null) {
                    continue;
                }
                if (part.isText() && part.text() != null && !part.text().isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(part.text());
                } else if (part.isImage()) {
                    imageCount++;
                }
            }
            if (imageCount > 0) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("[已附加 ").append(imageCount).append(" 张图片]");
            }
            return sb.toString();
        }
    }

    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }

    record Tool(String name, String description, JsonNode parameters) {}

    interface StreamListener {
        StreamListener NO_OP = new StreamListener() {};

        default void onReasoningDelta(String delta) {}

        default void onContentDelta(String delta) {}
    }

    record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {
        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens, 0);
        }

        public ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
