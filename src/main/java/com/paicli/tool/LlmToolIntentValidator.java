package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Uses a lightweight LLM call to verify whether a proposed tool call matches the user's business intent.
 */
public final class LlmToolIntentValidator implements ToolIntentValidator {
    private static final Logger log = LoggerFactory.getLogger(LlmToolIntentValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final boolean validateReadOnly;

    public LlmToolIntentValidator(LlmClient llmClient, boolean validateReadOnly) {
        this.llmClient = llmClient;
        this.validateReadOnly = validateReadOnly;
    }

    public static boolean enabledByEnvironment() {
        return parseBoolean(envOrProperty("PAICLI_TOOL_INTENT_VALIDATION", "false"));
    }

    public static boolean validateReadOnlyByEnvironment() {
        return parseBoolean(envOrProperty("PAICLI_TOOL_INTENT_VALIDATE_READ_ONLY", "false"));
    }

    @Override
    public Optional<ToolError> validate(ToolIntentContext context, ToolRegistry.ToolInvocation invocation,
                                        ToolMetadata metadata) {
        if (llmClient == null || invocation == null || context == null || context.isBlank()) {
            return Optional.empty();
        }
        ToolRiskLevel riskLevel = metadata == null ? ToolRiskLevel.READ_ONLY : metadata.riskLevel();
        if (!validateReadOnly && riskLevel == ToolRiskLevel.READ_ONLY) {
            return Optional.empty();
        }
        try {
            LlmClient.ChatResponse response = llmClient.chat(
                    List.of(
                            LlmClient.Message.system(systemPrompt()),
                            LlmClient.Message.user(requestJson(context, invocation, metadata))
                    ),
                    null
            );
            return parseDecision(response == null ? null : response.content(), invocation);
        } catch (Exception e) {
            log.warn("tool intent validation failed open for tool {}: {}",
                    invocation.name(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ToolError> parseDecision(String content, ToolRegistry.ToolInvocation invocation) {
        try {
            String json = extractJson(content);
            if (json.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(json);
            if (root.path("allowed").asBoolean(true)) {
                return Optional.empty();
            }
            String message = root.path("message").asText("");
            String suggestion = root.path("suggestion").asText("");
            boolean recoverable = root.path("recoverable").asBoolean(true);
            if (message.isBlank()) {
                message = "工具调用与用户业务意图不匹配: " + invocation.name();
            }
            return Optional.of(new ToolError(
                    ToolErrorType.INTENT_TOOL_MISMATCH,
                    recoverable,
                    message,
                    suggestion.isBlank() ? "请根据用户原始意图选择更合适的工具或修改工具参数。" : suggestion
            ));
        } catch (Exception e) {
            log.warn("tool intent validator returned unparsable response, fail open: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String systemPrompt() {
        return """
                You are PaiCLI's tool intent validator.
                Decide whether a proposed tool call directly serves the user's business intent.
                Check intent-tool mismatch, especially read-only requests that propose write/command/external side effects.
                Do not judge JSON syntax. Do not execute the tool. Do not add extra text.
                Return strict JSON only:
                {"allowed":true|false,"recoverable":true|false,"message":"...","suggestion":"..."}
                Use allowed=false only when the mismatch is clear from the request and proposed tool call.
                """;
    }

    private static String requestJson(ToolIntentContext context, ToolRegistry.ToolInvocation invocation,
                                      ToolMetadata metadata) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("user_request", context.userRequest());
        root.put("execution_context", context.executionContext());
        ObjectNode toolCall = root.putObject("tool_call");
        toolCall.put("name", invocation.name());
        toolCall.put("arguments_json", invocation.argumentsJson());
        ObjectNode meta = root.putObject("tool_metadata");
        ToolRiskLevel riskLevel = metadata == null ? ToolRiskLevel.READ_ONLY : metadata.riskLevel();
        meta.put("risk_level", riskLevel.name());
        meta.put("side_effect", metadata == null ? "" : metadata.sideEffectDescription());
        return MAPPER.writeValueAsString(root);
    }

    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) {
            return "";
        }
        return trimmed.substring(start, end + 1);
    }

    private static boolean parseBoolean(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true")
                || normalized.equals("yes") || normalized.equals("on");
    }

    private static String envOrProperty(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
