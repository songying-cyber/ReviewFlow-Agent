package com.paicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

public class WechatPolicyDecider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WechatPolicyConfig config;

    public WechatPolicyDecider(WechatPolicyConfig config) {
        this.config = config;
    }

    public WechatPolicyDecision decide(String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isBlank()) {
            return WechatPolicyDecision.deny("工具名为空");
        }
        if (isReadOnlyBuiltin(name)) {
            return WechatPolicyDecision.allow();
        }
        if ("execute_command".equals(name)) {
            return commandAllowed(argumentsJson);
        }
        if ("write_file".equals(name) || "create_project".equals(name)) {
            return WechatPolicyDecision.allow();
        }
        if ("revert_turn".equals(name)) {
            return WechatPolicyDecision.deny("微信通道 v1 不允许远程回滚快照");
        }
        if ("browser_connect".equals(name) || "browser_disconnect".equals(name)) {
            return WechatPolicyDecision.deny("微信通道 v1 不允许远程切换浏览器会话");
        }
        if (name.startsWith("mcp__")) {
            return mcpAllowed(name);
        }
        return WechatPolicyDecision.deny("微信通道未将该工具列入允许清单: " + name);
    }

    private WechatPolicyDecision commandAllowed(String argumentsJson) {
        String command = extract(argumentsJson, "command");
        if (command.isBlank()) {
            return WechatPolicyDecision.deny("微信通道拒绝空命令");
        }
        for (String allowed : config.commandAllowlist()) {
            String normalized = allowed == null ? "" : allowed.trim();
            if (!normalized.isBlank() && command.trim().equals(normalized)) {
                return WechatPolicyDecision.allow();
            }
        }
        return WechatPolicyDecision.deny("微信通道默认拒绝 execute_command；请在 setup 策略中配置命令白名单后重试");
    }

    private WechatPolicyDecision mcpAllowed(String toolName) {
        for (String allowed : config.mcpAllowlist()) {
            String normalized = allowed == null ? "" : allowed.trim();
            if (!normalized.isBlank() && toolName.equals(normalized)) {
                return WechatPolicyDecision.allow();
            }
            if (!normalized.isBlank() && toolName.startsWith("mcp__" + normalized + "__")) {
                return WechatPolicyDecision.allow();
            }
        }
        return WechatPolicyDecision.deny("微信通道默认拒绝 MCP 工具: " + toolName);
    }

    private static boolean isReadOnlyBuiltin(String name) {
        return switch (name) {
            case "read_file", "list_dir", "glob_files", "grep_code", "search_code",
                    "web_search", "web_fetch", "browser_status" -> true;
            default -> false;
        };
    }

    private static String extract(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
