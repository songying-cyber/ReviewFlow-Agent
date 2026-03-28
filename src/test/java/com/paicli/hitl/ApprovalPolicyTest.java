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
    void getDangerousToolsContainsAllThree() {
        Set<String> tools = ApprovalPolicy.getDangerousTools();
        assertTrue(tools.contains("write_file"));
        assertTrue(tools.contains("execute_command"));
        assertTrue(tools.contains("create_project"));
        assertEquals(3, tools.size());
    }

    @Test
    void riskDescriptionNotBlankForDangerousTools() {
        assertFalse(ApprovalPolicy.getRiskDescription("write_file").isBlank());
        assertFalse(ApprovalPolicy.getRiskDescription("execute_command").isBlank());
        assertFalse(ApprovalPolicy.getRiskDescription("create_project").isBlank());
    }
}
