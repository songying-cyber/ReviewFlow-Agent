package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoldableBlockTest {

    @Test
    void renderInitialPrintsCollapsedHeader() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "⏵ 读取 3 个文件 (ctrl+o to expand)",
                List.of("  📖 line 1", "  └ a.txt", "  └ b.txt", "  └ c.txt"));
        block.renderInitial();
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("⏵ 读取 3 个文件"), text);
        assertEquals(1, block.renderedLineCount());
        assertFalse(block.isExpanded());
    }

    @Test
    void toggleExpandsToFullLines() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "⏵ Read 2 files (ctrl+o to expand)",
                List.of("  📖 line A", "  📖 line B"));
        block.renderInitial();
        sink.reset();
        boolean ok = block.toggle();
        assertTrue(ok);
        assertTrue(block.isExpanded());
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[1A"), "should move up to overwrite: " + text);
        assertTrue(text.contains("line A"));
        assertTrue(text.contains("line B"));
        assertTrue(text.contains("⏷ collapse"));
    }

    @Test
    void toggleCollapsesBackToHeader() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "⏵ header",
                List.of("expanded line"));
        block.renderInitial();
        block.toggle();  // expanded
        sink.reset();
        block.toggle();  // collapse
        assertFalse(block.isExpanded());
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("header"), text);
    }

    @Test
    void frozenBlocksRefuseToggle() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "header",
                List.of("expanded"));
        block.renderInitial();
        block.freeze();
        boolean ok = block.toggle();
        assertFalse(ok);
        assertTrue(block.isFrozen());
    }

    @Test
    void renderedLineCountReflectsExpandedSize() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "header",
                List.of("a", "b", "c"));
        block.renderInitial();
        block.toggle();
        // 3 expanded + 1 footer = 4
        assertEquals(4, block.renderedLineCount());
    }

    @Test
    void noFooterWhenNullCollapseFooter() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "header",
                List.of("a", "b"),
                null);
        block.renderInitial();
        sink.reset();
        block.toggle();
        String text = sink.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("⏷"));
        assertEquals(2, block.renderedLineCount());
    }
}
