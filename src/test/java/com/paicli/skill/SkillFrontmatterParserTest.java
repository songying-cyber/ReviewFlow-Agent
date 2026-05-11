package com.paicli.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillFrontmatterParserTest {

    @Test
    void parsesSingleLineFields() {
        String input = """
                ---
                name: web-access
                version: "1.0.0"
                ---
                body content
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        Map<String, Object> fm = r.frontmatter();
        assertEquals("web-access", fm.get("name"));
        assertEquals("1.0.0", fm.get("version"));
        assertEquals("body content\n", r.body());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void parsesMultilineDescription() {
        String input = """
                ---
                name: web-access
                description: |
                  第一行
                  第二行
                ---
                hello
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        Object desc = r.frontmatter().get("description");
        assertEquals("第一行 第二行", desc);
    }

    @Test
    void parsesInlineArray() {
        String input = """
                ---
                name: foo
                tags: [web, browser, fetch]
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) r.frontmatter().get("tags");
        assertEquals(List.of("web", "browser", "fetch"), tags);
    }

    @Test
    void warnsOnMissingClosingMarker() {
        String input = """
                ---
                name: foo
                body without closing
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertFalse(r.warnings().isEmpty());
        assertTrue(r.warnings().get(0).contains("结束标记"));
    }

    @Test
    void warnsOnMissingOpeningMarker() {
        String input = "name: foo\nbody\n";
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("起始标记")));
    }

    @Test
    void warnsOnNestedObjectField() {
        String input = """
                ---
                name: foo
                metadata: { nested: object }
                ---
                body
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("嵌套对象")));
        assertEquals("foo", r.frontmatter().get("name"));
    }

    @Test
    void stripsQuotesFromStringValues() {
        String input = """
                ---
                name: "quoted-name"
                version: '0.0.1'
                ---
                """;
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertEquals("quoted-name", r.frontmatter().get("name"));
        assertEquals("0.0.1", r.frontmatter().get("version"));
    }

    @Test
    void handlesEmptyBody() {
        String input = "---\nname: foo\n---\n";
        SkillFrontmatterParser.ParseResult r = SkillFrontmatterParser.parse(input);
        assertEquals("foo", r.frontmatter().get("name"));
        assertEquals("", r.body());
    }
}
