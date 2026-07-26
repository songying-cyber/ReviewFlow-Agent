package com.paicli.hitl;

import com.paicli.tool.ToolMetadata;
import com.paicli.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPolicyTest {

    @Test
    void readOnlyDoesNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval(ToolMetadata.readOnly("read")));
        assertFalse(ApprovalPolicy.requiresApproval(ToolRiskLevel.READ_ONLY));
    }

    @Test
    void lowWriteDoesNotRequireApprovalByDefault() {
        assertFalse(ApprovalPolicy.requiresApproval(ToolMetadata.lowWrite("state change")));
        assertFalse(ApprovalPolicy.requiresApproval(ToolRiskLevel.LOW_WRITE));
    }

    @Test
    void mediumAndHighRiskRequireApproval() {
        assertTrue(ApprovalPolicy.requiresApproval(ToolMetadata.mediumWrite("write files")));
        assertTrue(ApprovalPolicy.requiresApproval(ToolMetadata.highRisk("run command")));
        assertTrue(ApprovalPolicy.requiresApproval(ToolRiskLevel.MEDIUM_WRITE));
        assertTrue(ApprovalPolicy.requiresApproval(ToolRiskLevel.HIGH_RISK));
    }

    @Test
    void dangerLevelComesFromRiskLevel() {
        assertEquals("🟢 只读", ApprovalPolicy.getDangerLevel(ToolMetadata.readOnly("read")));
        assertEquals("🔵 低风险写", ApprovalPolicy.getDangerLevel(ToolMetadata.lowWrite("state")));
        assertEquals("🟡 中风险写", ApprovalPolicy.getDangerLevel(ToolMetadata.mediumWrite("files")));
        assertEquals("🔴 高风险", ApprovalPolicy.getDangerLevel(ToolMetadata.highRisk("command")));
    }

    @Test
    void riskDescriptionPrefersToolMetadataSideEffect() {
        assertEquals("custom side effect",
                ApprovalPolicy.getRiskDescription(ToolMetadata.mediumWrite("custom side effect")));
    }

    @Test
    void legacyToolNameApiRemainsCompatible() {
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));
        assertTrue(ApprovalPolicy.requiresApproval("execute_command"));
        assertTrue(ApprovalPolicy.requiresApproval("create_project"));
        assertFalse(ApprovalPolicy.requiresApproval("read_file"));
        assertFalse(ApprovalPolicy.requiresApproval("unknown_tool"));
    }

    @Test
    void mcpToolRequiresApprovalByLegacyName() {
        assertTrue(ApprovalPolicy.requiresApproval("mcp__filesystem__read_file"));
        assertEquals("filesystem", ApprovalPolicy.mcpServerName("mcp__filesystem__read_file"));
        assertEquals("🔴 高风险", ApprovalPolicy.getDangerLevel("mcp__demo__tool"));
        assertTrue(ApprovalPolicy.getRiskDescription("mcp__demo__tool").contains("MCP"));
    }

    @Test
    void isMcpToolRecognizesPrefix() {
        assertTrue(ApprovalPolicy.isMcpTool("mcp__filesystem__read_file"));
        assertTrue(ApprovalPolicy.isMcpTool("mcp__a__b"));
    }

    @Test
    void isMcpToolRejectsNonMcpNames() {
        assertFalse(ApprovalPolicy.isMcpTool(null));
        assertFalse(ApprovalPolicy.isMcpTool(""));
        assertFalse(ApprovalPolicy.isMcpTool("read_file"));
        assertFalse(ApprovalPolicy.isMcpTool("MCP__server__tool"), "前缀大小写敏感，仅小写 mcp__ 才识别");
        assertFalse(ApprovalPolicy.isMcpTool("mcp_singleunderscore"));
    }

    @Test
    void mcpServerNameReturnsNullForNonMcpTool() {
        assertNull(ApprovalPolicy.mcpServerName(null));
        assertNull(ApprovalPolicy.mcpServerName("read_file"));
        assertNull(ApprovalPolicy.mcpServerName("write_file"));
    }

    @Test
    void mcpServerNameExtractsServerSegment() {
        assertEquals("filesystem", ApprovalPolicy.mcpServerName("mcp__filesystem__read_file"));
        assertEquals("git", ApprovalPolicy.mcpServerName("mcp__git__status"));
        assertEquals("server", ApprovalPolicy.mcpServerName("mcp__server__tool__with__underscores"));
    }
}
