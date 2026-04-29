package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalResultTest {

    @Test
    void approveIsApproved() {
        ApprovalResult result = ApprovalResult.approve();
        assertTrue(result.isApproved());
        assertFalse(result.isRejected());
        assertFalse(result.isSkipped());
        assertFalse(result.isApprovedAll());
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void approveAllIsApprovedAndApprovedAll() {
        ApprovalResult result = ApprovalResult.approveAll();
        assertTrue(result.isApproved());
        assertTrue(result.isApprovedAll());
        assertTrue(result.isApprovedAllForTool());
        assertFalse(result.isApprovedAllForServer());
        assertFalse(result.isRejected());
        assertFalse(result.isSkipped());
    }

    @Test
    void approveAllByServerIsApprovedForServerOnly() {
        ApprovalResult result = ApprovalResult.approveAllByServer();
        assertTrue(result.isApproved());
        assertFalse(result.isApprovedAll());
        assertFalse(result.isApprovedAllForTool());
        assertTrue(result.isApprovedAllForServer());
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void rejectIsRejected() {
        ApprovalResult result = ApprovalResult.reject("太危险");
        assertTrue(result.isRejected());
        assertFalse(result.isApproved());
        assertFalse(result.isSkipped());
        assertEquals("太危险", result.reason());
    }

    @Test
    void skipIsSkipped() {
        ApprovalResult result = ApprovalResult.skip();
        assertTrue(result.isSkipped());
        assertFalse(result.isApproved());
        assertFalse(result.isRejected());
    }

    @Test
    void modifyIsApprovedWithModifiedArgs() {
        ApprovalResult result = ApprovalResult.modify("{\"command\": \"ls\"}");
        assertTrue(result.isApproved());
        assertFalse(result.isRejected());
        assertEquals("{\"command\": \"ls\"}", result.modifiedArguments());
    }

    @Test
    void effectiveArgumentsReturnsModifiedWhenModified() {
        ApprovalResult result = ApprovalResult.modify("{\"path\": \"/tmp/safe.txt\"}");
        assertEquals("{\"path\": \"/tmp/safe.txt\"}",
                result.effectiveArguments("{\"path\": \"/etc/passwd\"}"));
    }

    @Test
    void effectiveArgumentsReturnsOriginalWhenApproved() {
        ApprovalResult result = ApprovalResult.approve();
        String original = "{\"command\": \"ls\"}";
        assertEquals(original, result.effectiveArguments(original));
    }

    @Test
    void effectiveArgumentsReturnsOriginalWhenApprovedAll() {
        ApprovalResult result = ApprovalResult.approveAll();
        String original = "{\"path\": \"pom.xml\", \"content\": \"...\"}";
        assertEquals(original, result.effectiveArguments(original));
    }

    @Test
    void effectiveArgumentsReturnsOriginalWhenApprovedAllByServer() {
        ApprovalResult result = ApprovalResult.approveAllByServer();
        String original = "{\"url\": \"https://example.com\"}";
        assertEquals(original, result.effectiveArguments(original));
    }

    @Test
    void rejectWithNullReasonIsAllowed() {
        ApprovalResult result = ApprovalResult.reject(null);
        assertTrue(result.isRejected());
        assertNull(result.reason());
    }
}
