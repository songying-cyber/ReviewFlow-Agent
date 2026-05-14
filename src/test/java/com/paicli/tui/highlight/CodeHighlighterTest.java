package com.paicli.tui.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeHighlighterTest {

    @Test
    void highlightsRepresentativeJavaSyntax() {
        String code = "public class Foo { public static void main(String[] args) { } }";
        String result = CodeHighlighter.highlight(code, "java");

        assertNotNull(result);
        assertTrue(result.contains("public"));
    }

    @Test
    void supportsNonJavaKeywordSets() {
        String code = "def hello():\n    print('world')\n    return True";
        String result = CodeHighlighter.highlight(code, "python");

        assertNotNull(result);
        assertTrue(result.contains("def"));
        assertTrue(result.contains("return"));
    }

    @Test
    void handlesEmptyInputAndPreservesStringContent() {
        assertNull(CodeHighlighter.highlight(null, "java"));
        assertEquals("", CodeHighlighter.highlight("", "java"));

        String code = "String s = \"class\";";
        String result = CodeHighlighter.highlight(code, "java");

        assertNotNull(result);
        assertTrue(result.contains("\"class\""));
    }
}
