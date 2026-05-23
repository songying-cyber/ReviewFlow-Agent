package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPolicyTest {

    @Test
    void writeFileRequiresApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));
    }

    @Test
    void executeCommandRequiresApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("execute_command"));
    }

    @Test
    void createProjectRequiresApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("create_project"));
    }

    @Test
    void readFileDoesNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval("read_file"));
    }

    @Test
    void listDirDoesNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval("list_dir"));
    }

    @Test
    void searchCodeDoesNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval("search_code"));
    }

    @Test
    void unknownToolDoesNotRequireApproval() {
        assertFalse(ApprovalPolicy.requiresApproval("unknown_tool"));
    }

    @Test
    void mcpToolRequiresApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("mcp__filesystem__read_file"));
        assertEquals("filesystem", ApprovalPolicy.mcpServerName("mcp__filesystem__read_file"));
    }

    @Test
    void executeCommandIsHighDanger() {
        assertEquals("🔴 高危", ApprovalPolicy.getDangerLevel("execute_command"));
    }

    @Test
    void writeFileIsMediumDanger() {
        assertEquals("🟡 中危", ApprovalPolicy.getDangerLevel("write_file"));
    }

    @Test
    void createProjectIsMediumDanger() {
        assertEquals("🟡 中危", ApprovalPolicy.getDangerLevel("create_project"));
    }

    @Test
    void unknownToolIsSafe() {
        assertEquals("🟢 安全", ApprovalPolicy.getDangerLevel("read_file"));
    }

    @Test
    void mcpToolHasMcpDangerLevel() {
        assertEquals("🟡 MCP", ApprovalPolicy.getDangerLevel("mcp__demo__tool"));
        assertTrue(ApprovalPolicy.getRiskDescription("mcp__demo__tool").contains("MCP"));
    }

    @Test
    void getDangerousToolsContainsAllThree() {
        Set<String> tools = ApprovalPolicy.getDangerousTools();
        assertTrue(tools.contains("write_file"));
        assertTrue(tools.contains("execute_command"));
        assertTrue(tools.contains("create_project"));
        assertTrue(tools.contains("revert_turn"));
        assertEquals(4, tools.size());
    }

    @Test
    void riskDescriptionNotBlankForDangerousTools() {
        assertFalse(ApprovalPolicy.getRiskDescription("write_file").isBlank());
        assertFalse(ApprovalPolicy.getRiskDescription("execute_command").isBlank());
        assertFalse(ApprovalPolicy.getRiskDescription("create_project").isBlank());
        assertFalse(ApprovalPolicy.getRiskDescription("revert_turn").isBlank());
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
        // 工具名内可以再含 __，server 名只取第一段
        assertEquals("server", ApprovalPolicy.mcpServerName("mcp__server__tool__with__underscores"));
    }

    @Test
    void mcpToolStaysOutsideOfBuiltinDangerousTools() {
        // mcp__ 前缀不应污染 DANGEROUS_TOOLS 集合本身（保证 set 含义清晰）
        assertEquals(4, ApprovalPolicy.getDangerousTools().size());
        assertFalse(ApprovalPolicy.getDangerousTools().contains("mcp__demo__tool"));
    }
}
