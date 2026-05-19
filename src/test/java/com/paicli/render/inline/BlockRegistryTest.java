package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockRegistryTest {

    @Test
    void toggleLastReturnsFalseOnEmpty() {
        BlockRegistry registry = new BlockRegistry();
        assertFalse(registry.toggleLast());
    }

    @Test
    void registerKeepsTailAsActiveBlock() {
        BlockRegistry registry = new BlockRegistry();
        FoldableBlock first = newBlock();
        FoldableBlock second = newBlock();
        registry.register(first);
        registry.register(second);
        assertEquals(second, registry.peekLast());
        assertEquals(2, registry.size());
    }

    @Test
    void registerFreezesAllPriorBlocks() {
        BlockRegistry registry = new BlockRegistry();
        FoldableBlock first = newBlock();
        FoldableBlock second = newBlock();
        registry.register(first);
        assertFalse(first.isFrozen());
        registry.register(second);
        assertTrue(first.isFrozen(), "first block should freeze when second registers");
        assertFalse(second.isFrozen());
    }

    @Test
    void toggleLastTogglesOnlyTailBlock() {
        BlockRegistry registry = new BlockRegistry();
        FoldableBlock first = newBlock();
        FoldableBlock second = newBlock();
        registry.register(first);
        registry.register(second);
        boolean ok = registry.toggleLast();
        assertTrue(ok);
        assertTrue(second.isExpanded());
        assertFalse(first.isExpanded());
    }

    @Test
    void toggleLastFailsWhenTailIsFrozen() {
        BlockRegistry registry = new BlockRegistry();
        FoldableBlock block = newBlock();
        registry.register(block);
        block.freeze();
        assertFalse(registry.toggleLast());
    }

    @Test
    void freezeAllPreventsLaterRelativeRepaint() {
        BlockRegistry registry = new BlockRegistry();
        FoldableBlock block = newBlock();
        registry.register(block);

        registry.freezeAll();

        assertTrue(block.isFrozen());
        assertFalse(registry.toggleLast());
    }

    @Test
    void clearRemovesAllBlocks() {
        BlockRegistry registry = new BlockRegistry();
        registry.register(newBlock());
        registry.register(newBlock());
        registry.clear();
        assertEquals(0, registry.size());
    }

    private FoldableBlock newBlock() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FoldableBlock block = new FoldableBlock(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                "header",
                List.of("expanded"));
        block.renderInitial();
        return block;
    }
}
