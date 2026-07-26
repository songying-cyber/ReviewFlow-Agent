package com.paicli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolRiskLevel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ApprovalFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ApprovalFingerprint() {
    }

    public static String create(ApprovalActionType actionType, String subject, String argumentsJson,
                                ToolRiskLevel riskLevel, String context) {
        String payload = String.join("\n",
                actionType == null ? "" : actionType.name(),
                subject == null ? "" : subject,
                canonicalArguments(argumentsJson),
                riskLevel == null ? "" : riskLevel.name(),
                context == null ? "" : context);
        return shortSha256(payload);
    }

    public static String canonicalArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            return MAPPER.writeValueAsString(sortNode(node));
        } catch (Exception ignored) {
            return argumentsJson.trim();
        }
    }

    private static JsonNode sortNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                sorted.add(sortNode(child));
            }
            return sorted;
        }
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                names.add(fields.next().getKey());
            }
            names.stream().sorted().forEach(name -> sorted.set(name, sortNode(node.get(name))));
            return sorted;
        }
        return node;
    }

    private static String shortSha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(payload.hashCode());
        }
    }
}
