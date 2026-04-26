package com.paicli.mcp.mention;

import com.paicli.mcp.McpServerManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtMentionExpanderTest {
    @Test
    void replacesMentionWithResourceBlock(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "hello", "text/plain"));

        String expanded = expander.expand("看 @fs:file://README.md");

        assertTrue(expanded.contains("<resource server=\"fs\" uri=\"file://README.md\" mimeType=\"text/plain\">"));
        assertTrue(expanded.contains("hello"));
        assertFalse(expanded.contains("@fs:file://README.md"));
    }

    @Test
    void expandsMultipleMentionsFromRightToLeft(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "body", "text/plain"));

        String expanded = expander.expand("@fs:file://a 和 @fs:file://b");

        assertEquals(2, count(expanded, "<resource server=\"fs\""));
    }

    @Test
    void leavesInputUnchangedWithoutMentions(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "body", "text/plain"));

        assertEquals("普通输入", expander.expand("普通输入"));
    }

    @Test
    void insertsErrorBlockWhenReadFails(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FailingManager(tempDir));

        String expanded = expander.expand("@fs:file://missing");

        assertTrue(expanded.contains("<resource_error"));
        assertTrue(expanded.contains("boom"));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static class FakeManager extends McpServerManager {
        private final String content;
        private final String mimeType;

        FakeManager(Path projectDir, String content, String mimeType) {
            super(new ToolRegistry(), projectDir);
            this.content = content;
            this.mimeType = mimeType;
        }

        @Override
        public ResourceReadResult readResourceForMention(String serverName, String uri) {
            return new ResourceReadResult(content, mimeType);
        }
    }

    private static class FailingManager extends McpServerManager {
        FailingManager(Path projectDir) {
            super(new ToolRegistry(), projectDir);
        }

        @Override
        public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
            throw new IOException("boom");
        }
    }
}
