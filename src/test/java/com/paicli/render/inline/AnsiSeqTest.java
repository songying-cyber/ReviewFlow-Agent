package com.paicli.render.inline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiSeqTest {

    private static final String ESC = "";

    @Test
    void cursorSaveAndRestoreUseDecCommands() {
        assertEquals(ESC + "7", AnsiSeq.SAVE_CURSOR);
        assertEquals(ESC + "8", AnsiSeq.RESTORE_CURSOR);
    }

    @Test
    void clearLineSequenceMatchesAnsiStandard() {
        assertEquals(ESC + "[2K", AnsiSeq.CLEAR_LINE);
    }

    @Test
    void scrollRegionEmitsTopAndBottomBoundaries() {
        assertEquals(ESC + "[1;23r", AnsiSeq.setScrollRegion(1, 23));
    }

    @Test
    void resetScrollRegionIsBareR() {
        assertEquals(ESC + "[r", AnsiSeq.RESET_SCROLL_REGION);
    }

    @Test
    void moveCursorEmits1BasedRowCol() {
        assertEquals(ESC + "[24;1H", AnsiSeq.moveCursor(24, 1));
    }

    @Test
    void moveUpAndDownAreSeparateSequences() {
        assertEquals(ESC + "[5A", AnsiSeq.moveUp(5));
        assertEquals(ESC + "[3B", AnsiSeq.moveDown(3));
    }

    @Test
    void reverseTogglesAreOnAnd27() {
        assertEquals(ESC + "[7m", AnsiSeq.REVERSE_ON);
        assertEquals(ESC + "[27m", AnsiSeq.REVERSE_OFF);
    }

    @Test
    void hideAndShowCursorUseQuestionMark25() {
        assertTrue(AnsiSeq.HIDE_CURSOR.endsWith("[?25l"));
        assertTrue(AnsiSeq.SHOW_CURSOR.endsWith("[?25h"));
    }
}
