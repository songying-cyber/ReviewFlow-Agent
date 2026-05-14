package com.paicli.hitl;

import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererHitlHandlerTest {

    @Test
    void delegatesPromptToRenderer() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approve();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        assertEquals(1, stub.promptCount);
    }

    @Test
    void approveAllByToolFastPathSkipsRenderer() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAll();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        // First call asks renderer and records approve-all
        handler.requestApproval(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(1, stub.promptCount);
        assertTrue(handler.isApprovedAllByTool("write_file"));

        // Second call should NOT prompt — fast path
        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(1, stub.promptCount);  // unchanged
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
    }

    @Test
    void approveAllByServerCoversFutureMcpCalls() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAllByServer();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        handler.requestApproval(ApprovalRequest.of("mcp__chrome-devtools__click", "{}", "test"));
        assertTrue(handler.isApprovedAllByServer("chrome-devtools"));

        // Different tool from same server — fast path
        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("mcp__chrome-devtools__navigate_page", "{}", "test"));
        assertEquals(1, stub.promptCount);
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void clearApprovedAllResetsState() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAll();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);
        handler.requestApproval(ApprovalRequest.of("write_file", "{}", "test"));
        assertTrue(handler.isApprovedAllByTool("write_file"));

        handler.clearApprovedAll();
        assertFalse(handler.isApprovedAllByTool("write_file"));
    }

    @Test
    void enabledFlagIsTracked() {
        RendererHitlHandler handler = new RendererHitlHandler(new StubRenderer(), false);
        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        assertTrue(handler.isEnabled());
    }

    @Test
    void rejectedResultDoesNotPersistApproval() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.reject("nope");
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertFalse(handler.isApprovedAllByTool("write_file"));
    }

    @Test
    void nullResultFromRendererBecomesReject() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = null;
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertNotNull(result);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
    }

    private static final class StubRenderer implements Renderer {
        private final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        ApprovalResult nextResult;
        int promptCount;

        @Override public void start() {}
        @Override public void close() {}
        @Override public PrintStream stream() { return out; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {}
        @Override public void appendDiff(String filePath, String before, String after) {}
        @Override public void updateStatus(StatusInfo status) {}
        @Override public ApprovalResult promptApproval(ApprovalRequest request) {
            promptCount++;
            return nextResult;
        }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }
}
