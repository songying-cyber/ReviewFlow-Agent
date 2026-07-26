package com.paicli.hitl;

import com.paicli.tool.ToolMetadata;
import com.paicli.tool.ToolRiskLevel;

/**
 * 审批策略：工具声明自身风险等级，策略层按等级决定是否需要人工确认。
 */
public class ApprovalPolicy {

    private ApprovalPolicy() {
    }

    public static boolean requiresApproval(ToolMetadata metadata) {
        return metadata != null && requiresApproval(metadata.riskLevel());
    }

    public static boolean requiresApproval(ToolRiskLevel riskLevel) {
        return riskLevel != null && riskLevel.requiresApprovalByDefault();
    }

    /**
     * 兼容旧调用：主执行路径应优先使用 {@link #requiresApproval(ToolMetadata)}。
     */
    public static boolean requiresApproval(String toolName) {
        if (isMcpTool(toolName)) {
            return true;
        }
        return legacyMetadata(toolName).riskLevel().requiresApprovalByDefault();
    }

    public static String getDangerLevel(ToolMetadata metadata) {
        ToolRiskLevel level = metadata == null ? ToolRiskLevel.READ_ONLY : metadata.riskLevel();
        return switch (level) {
            case READ_ONLY -> "🟢 只读";
            case LOW_WRITE -> "🔵 低风险写";
            case MEDIUM_WRITE -> "🟡 中风险写";
            case HIGH_RISK -> "🔴 高风险";
        };
    }

    /**
     * 兼容旧调用：主执行路径应优先使用 {@link #getDangerLevel(ToolMetadata)}。
     */
    public static String getDangerLevel(String toolName) {
        if (isMcpTool(toolName)) {
            return "🔴 高风险";
        }
        return getDangerLevel(legacyMetadata(toolName));
    }

    public static String getRiskDescription(ToolMetadata metadata) {
        if (metadata != null && !metadata.sideEffectDescription().isBlank()) {
            return metadata.sideEffectDescription();
        }
        ToolRiskLevel level = metadata == null ? ToolRiskLevel.READ_ONLY : metadata.riskLevel();
        return switch (level) {
            case READ_ONLY -> "只读取信息，不应修改本地文件、外部服务或会话状态";
            case LOW_WRITE -> "会修改 PaiCLI 的低风险本地状态，默认允许但会审计";
            case MEDIUM_WRITE -> "会修改项目文件、目录或会话状态，需要人工确认";
            case HIGH_RISK -> "可能执行命令、批量恢复或调用外部系统，需要人工确认";
        };
    }

    /**
     * 兼容旧调用：主执行路径应优先使用 {@link #getRiskDescription(ToolMetadata)}。
     */
    public static String getRiskDescription(String toolName) {
        if (isMcpTool(toolName)) {
            return "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务";
        }
        return getRiskDescription(legacyMetadata(toolName));
    }

    public static String policySummary() {
        return "READ_ONLY 默认放行；LOW_WRITE 默认放行并审计；MEDIUM_WRITE/HIGH_RISK 需要 HITL 审批；MCP 工具默认 HIGH_RISK";
    }

    public static ToolMetadata metadataForToolName(String toolName) {
        if (isMcpTool(toolName)) {
            return ToolMetadata.highRisk("将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务");
        }
        return legacyMetadata(toolName);
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private static ToolMetadata legacyMetadata(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "write_file" -> ToolMetadata.mediumWrite("将写入或覆盖文件内容，原有内容将丢失");
            case "create_project" -> ToolMetadata.mediumWrite("将在磁盘上创建新目录和文件");
            case "browser_connect", "browser_disconnect" -> ToolMetadata.mediumWrite("将切换浏览器会话模式");
            case "execute_command" -> ToolMetadata.highRisk("将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态");
            case "revert_turn" -> ToolMetadata.highRisk("将按 Side-Git 快照批量恢复工作区文件，可能覆盖当前未保存修改");
            case "load_skill" -> ToolMetadata.lowWrite("会把 Skill 指引注入下一轮上下文");
            case "save_memory" -> ToolMetadata.lowWrite("会写入长期记忆");
            default -> ToolMetadata.readOnly("只读取信息，不应修改本地文件、外部服务或会话状态");
        };
    }
}
