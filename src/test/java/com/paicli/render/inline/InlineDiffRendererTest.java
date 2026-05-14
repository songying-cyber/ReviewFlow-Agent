package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDiffRendererTest {

    @Test
    void newFilePrintsAllLinesAsAdditions() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new InlineDiffRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8))
                .render("hello.md", null, "line A\nline B\n");
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("+line A"), text);
        assertTrue(text.contains("+line B"), text);
        assertTrue(text.contains("@@ -0,0 +1,3 @@"), text);  // includes trailing empty line
    }

    @Test
    void deleteFileShowsAllLinesAsRemovals() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new InlineDiffRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8))
                .render("gone.md", "alpha\nbeta\n", null);
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("-alpha"), text);
        assertTrue(text.contains("-beta"), text);
    }

    @Test
    void unchangedContentSkipsDiffOutput() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new InlineDiffRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8))
                .render("same.md", "x\n", "x\n");
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("内容未变"));
    }

    @Test
    void modifiedFileShowsAddAndDeletePairs() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new InlineDiffRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8))
                .render("a.md", "before\nshared\n", "after\nshared\n");
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("-before"), text);
        assertTrue(text.contains("+after"), text);
    }

    @Test
    void computeDiffProducesEqualOpsForIdenticalLines() {
        var ops = InlineDiffRenderer.computeDiff(new String[]{"a", "b"}, new String[]{"a", "b"});
        assertEquals(2, ops.size());
        assertTrue(ops.stream().allMatch(o -> o.type() == InlineDiffRenderer.OpType.EQUAL));
    }

    @Test
    void computeDiffMarksInsertions() {
        var ops = InlineDiffRenderer.computeDiff(new String[]{"a"}, new String[]{"a", "b"});
        assertEquals(2, ops.size());
        assertEquals(InlineDiffRenderer.OpType.EQUAL, ops.get(0).type());
        assertEquals(InlineDiffRenderer.OpType.ADD, ops.get(1).type());
    }

    @Test
    void computeDiffMarksDeletions() {
        var ops = InlineDiffRenderer.computeDiff(new String[]{"a", "b"}, new String[]{"a"});
        assertTrue(ops.stream().anyMatch(o -> o.type() == InlineDiffRenderer.OpType.DELETE && "b".equals(o.text())));
    }

    @Test
    void groupIntoHunksProducesAtLeastOneHunkOnChange() {
        var ops = InlineDiffRenderer.computeDiff(
                new String[]{"a", "b", "c"},
                new String[]{"a", "X", "c"});
        List<InlineDiffRenderer.Hunk> hunks = InlineDiffRenderer.groupIntoHunks(
                ops, new String[]{"a", "b", "c"}, new String[]{"a", "X", "c"});
        assertFalse(hunks.isEmpty());
    }
}
