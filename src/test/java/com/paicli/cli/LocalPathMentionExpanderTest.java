package com.paicli.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPathMentionExpanderTest {
    @TempDir
    Path tempDir;

    @Test
    void expandsLocalFileMentionIntoContextBlock() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "hello");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("读一下 @README.md");

        assertTrue(expanded.contains("@<README.md>"));
        assertTrue(expanded.contains("<file path=\"README.md\">"));
        assertTrue(expanded.contains("hello"));
    }

    @Test
    void expandsDirectoryMentionIntoDirectoryBlock() throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve("Main.java"), "class Main {}");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("列一下 @src");

        assertTrue(expanded.contains("<directory path=\"src\">"));
        assertTrue(expanded.contains("- Main.java"));
    }

    @Test
    void leavesMcpAndImageMentionsUntouched() {
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        assertEquals("@fs:file://README.md", expander.expand("@fs:file://README.md"));
        assertEquals("@image:<shot.png>", expander.expand("@image:<shot.png>"));
        assertEquals("@clipboard", expander.expand("@clipboard"));
    }

    @Test
    void refusesPathOutsideProjectRoot() throws Exception {
        Path outside = Files.writeString(tempDir.getParent().resolve("outside.txt"), "secret");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("读 @" + outside);

        assertFalse(expanded.contains("<file"));
        assertTrue(expanded.contains("@" + outside));
    }
}
