package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("拒绝执行命令"));
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }
}
