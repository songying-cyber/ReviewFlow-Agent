package com.paicli.cli;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PaiCliHighlighterTest {
    private final PaiCliHighlighter highlighter = new PaiCliHighlighter();

    @Test
    void highlightsSlashCommandPrefix() {
        AttributedString result = highlighter.highlight(null, "/model step");

        assertStyled(result, 0);
        assertStyled(result, 5);
        assertDefault(result, 6);
    }

    @Test
    void highlightsImageAndClipboardReferences() {
        AttributedString image = highlighter.highlight(null, "看 @image:<shot.png> 和 @clipboard");

        assertStyled(image, 2);
        assertStyled(image, image.toString().indexOf("@clipboard"));
    }

    @Test
    void highlightsAtReferences() {
        AttributedString result = highlighter.highlight(null, "读 @chrome-devtools:file:///tmp/a");

        assertStyled(result, 2);
    }

    @Test
    void highlightsDangerousShellText() {
        AttributedString result = highlighter.highlight(null, "执行 rm -rf /");

        assertStyled(result, result.toString().indexOf("rm"));
        assertStyled(result, result.toString().indexOf("/"));
    }

    @Test
    void highlightsSensitiveWords() {
        AttributedString result = highlighter.highlight(null, "Authorization: Bearer abc");

        assertStyled(result, 0);
        assertStyled(result, result.toString().indexOf("Bearer"));
    }

    @Test
    void marksUnclosedImageAngle() {
        AttributedString result = highlighter.highlight(null, "看 @image:<shot.png");

        assertStyled(result, result.toString().indexOf('<'));
    }

    @Test
    void emptyInputReturnsEmptyAttributedString() {
        AttributedString result = highlighter.highlight(null, "");

        assertEquals(0, result.length());
    }

    private static void assertStyled(AttributedString result, int index) {
        assertNotEquals(AttributedStyle.DEFAULT, result.styleAt(index));
    }

    private static void assertDefault(AttributedString result, int index) {
        assertEquals(AttributedStyle.DEFAULT, result.styleAt(index));
    }
}
