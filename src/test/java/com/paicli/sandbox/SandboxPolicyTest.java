package com.paicli.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandboxPolicyTest {
    @Test
    void disabledConfigDoesNotUseSandbox() {
        SandboxDecision decision = new SandboxPolicy(new SandboxConfig()).decide("echo hi", false);

        assertFalse(decision.sandboxEnabled());
        assertFalse(decision.useSandbox());
        assertEquals("sandbox_disabled", decision.unsandboxedReason());
    }

    @Test
    void requiredSandboxDeniesUnsupportedPlatform() {
        String previous = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        try {
            SandboxConfig config = new SandboxConfig();
            config.setEnabled(true);
            config.setRequired(true);

            SandboxDecision decision = new SandboxPolicy(config).decide("echo hi", false);

            assertTrue(decision.denied());
            assertTrue(decision.denyReason().contains("仅支持 macOS"));
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }

    @Test
    void excludedCommandFallsBackToUnsandboxed() {
        String previous = System.getProperty("os.name");
        System.setProperty("os.name", "Mac OS X");
        try {
            SandboxConfig config = new SandboxConfig();
            config.setEnabled(true);

            SandboxDecision decision = new SandboxPolicy(config).decide("docker ps", false);

            assertFalse(decision.useSandbox());
            assertEquals("excluded_command", decision.unsandboxedReason());
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }
}
