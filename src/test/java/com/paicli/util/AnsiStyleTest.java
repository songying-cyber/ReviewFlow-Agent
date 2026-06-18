package com.paicli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiStyleTest {

    @Test
    void userMessageBlockDoesNotForceWrapWhenContentExactlyFits() {
        String line = AnsiStyle.userMessageBlock("abc", 8);

        assertFalse(line.contains("\n"), line);
        assertTrue(stripAnsi(line).startsWith("  >  abc"), line);
    }

    @Test
    void userMessageBlockKeepsExplicitMultilineInputAsRows() {
        String block = AnsiStyle.userMessageBlock("第一行\n第二行", 40);

        assertEquals(1, block.chars().filter(ch -> ch == '\n').count(), block);
        assertTrue(block.contains("第一行"), block);
        assertTrue(block.contains("第二行"), block);
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
