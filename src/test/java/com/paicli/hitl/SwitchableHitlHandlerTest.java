package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwitchableHitlHandlerTest {

    @Test
    void delegatesToCurrentHandlerAndForwardsSessionStateOperations() {
        StubHitlHandler first = new StubHitlHandler(ApprovalResult.reject("first"));
        StubHitlHandler second = new StubHitlHandler(ApprovalResult.approve());
        SwitchableHitlHandler handler = new SwitchableHitlHandler(first);
        ApprovalRequest request = ApprovalRequest.of("write_file", "{}", null, null, null);

        assertTrue(handler.requestApproval(request).isRejected());

        handler.setDelegate(second);

        assertTrue(handler.requestApproval(request).isApproved());
        assertEquals(1, first.requestCount);
        assertEquals(1, second.requestCount);

        handler.setEnabled(true);
        handler.clearApprovedAll();
        handler.clearApprovedAllForServer("chrome-devtools");

        assertTrue(second.enabled);
        assertEquals(1, second.clearAllCount);
        assertEquals("chrome-devtools", second.lastClearedServer);
    }

    private static final class StubHitlHandler implements HitlHandler {
        private final ApprovalResult result;
        private boolean enabled;
        private int requestCount;
        private int clearAllCount;
        private String lastClearedServer;

        private StubHitlHandler(ApprovalResult result) {
            this.result = result;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            return result;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void clearApprovedAll() {
            clearAllCount++;
        }

        @Override
        public void clearApprovedAllForServer(String serverName) {
            lastClearedServer = serverName;
        }
    }
}
