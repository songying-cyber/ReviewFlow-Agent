package com.paicli.tool;

import com.paicli.policy.AuditLog;
import com.paicli.sandbox.SandboxConfig;
import com.paicli.sandbox.SandboxPolicy;
import com.paicli.sandbox.mac.MacSeatbeltSandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistrySandboxTest {
    @Test
    void strictSandboxRejectsCommandOnUnsupportedPlatform(@TempDir Path tempDir) {
        String previousOs = System.getProperty("os.name");
        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("os.name", "Linux");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            SandboxConfig sandbox = new SandboxConfig();
            sandbox.setEnabled(true);
            sandbox.setRequired(true);
            registry.setSandboxConfig(sandbox);

            ToolOutput output = registry.executeToolOutput(
                    "execute_command",
                    "{\"command\":\"echo hi\"}");

            assertFalse(output.ok());
            assertTrue(output.text().contains("策略拒绝"));
            AuditLog.AuditEntry entry = registry.getAuditLog().readRecent(1).get(0);
            assertNotNull(entry.sandbox());
            assertTrue(entry.sandbox().enabled());
            assertFalse(entry.sandbox().used());
            assertEquals("macos-seatbelt", entry.sandbox().runtime());
        } finally {
            if (previousOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previousOs);
            }
            if (previousAuditDir == null) {
                System.clearProperty("paicli.audit.dir");
            } else {
                System.setProperty("paicli.audit.dir", previousAuditDir);
            }
        }
    }

    @Test
    void macSeatbeltBlocksEnvWriteWhenAvailable(@TempDir Path tempDir) {
        SandboxConfig sandbox = new SandboxConfig();
        sandbox.setEnabled(true);
        Assumptions.assumeTrue(SandboxPolicy.isMacOs(), "macOS only");
        Assumptions.assumeTrue(new MacSeatbeltSandbox(sandbox, tempDir).available(), "sandbox-exec unavailable");

        String previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.audit.dir", tempDir.resolve("audit").toString());
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.setSandboxConfig(sandbox);

            ToolOutput output = registry.executeToolOutput(
                    "execute_command",
                    "{\"command\":\"printf secret > .env\"}");

            assertFalse(Files.exists(tempDir.resolve(".env")));
            AuditLog.AuditEntry entry = registry.getAuditLog().readRecent(1).get(0);
            assertNotNull(entry.sandbox());
            assertTrue(entry.sandbox().used());
            assertFalse(output.ok(), "沙箱拒绝重定向写入时命令应返回非零退出码");
        } finally {
            if (previousAuditDir == null) {
                System.clearProperty("paicli.audit.dir");
            } else {
                System.setProperty("paicli.audit.dir", previousAuditDir);
            }
        }
    }
}
