package com.paicli.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspManagerTest {

    @Test
    void collectsJavaSyntaxDiagnosticsAfterEdit(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Broken.java");
        Files.writeString(file, "class Broken {");
        LspManager manager = new LspManager(tempDir.toString());

        manager.runPostEditLspHook("Broken.java", file);

        LspDiagnosticReport report = manager.flushPendingDiagnostics();
        assertFalse(report.isEmpty());
        assertTrue(report.promptText().contains("[LSP 诊断注入]"));
        assertTrue(report.promptText().contains("Broken.java"));
        assertTrue(report.promptText().contains("[error]"));
    }

    @Test
    void clearsDiagnosticsWhenFileBecomesValid(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Fixed.java");
        LspManager manager = new LspManager(tempDir.toString());

        Files.writeString(file, "class Fixed {");
        manager.runPostEditLspHook("Fixed.java", file);
        assertFalse(manager.pendingDiagnosticsSnapshot().isEmpty());

        Files.writeString(file, "class Fixed {}");
        manager.runPostEditLspHook("Fixed.java", file);

        assertTrue(manager.flushPendingDiagnostics().isEmpty());
    }
}
