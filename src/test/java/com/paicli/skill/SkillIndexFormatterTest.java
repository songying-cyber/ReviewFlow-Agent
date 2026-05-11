package com.paicli.skill;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillIndexFormatterTest {

    @Test
    void emptyListReturnsEmptyString() {
        assertEquals("", SkillIndexFormatter.format(List.of()));
        assertEquals("", SkillIndexFormatter.format(null));
    }

    @Test
    void formatsSingleSkillWithDescription() {
        Skill skill = mockSkill("web-access", "联网工具决策手册", Skill.Source.BUILTIN);
        String out = SkillIndexFormatter.format(List.of(skill));
        assertTrue(out.contains("web-access"));
        assertTrue(out.contains("联网工具决策手册"));
        assertTrue(out.contains("load_skill"));
    }

    @Test
    void truncatesLongDescriptionByCodepoint() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("中");
        Skill skill = mockSkill("foo", sb.toString(), Skill.Source.USER);
        String out = SkillIndexFormatter.format(List.of(skill));

        // 切出 description 行（"- **foo**：<desc>\n"）后再统计，避免 footer 含"中"字干扰
        String prefix = "**foo**：";
        int start = out.indexOf(prefix) + prefix.length();
        int end = out.indexOf('\n', start);
        String descLine = out.substring(start, end);
        long count = descLine.codePoints().filter(c -> c == '中').count();
        assertEquals(500, count, "description 应按 codepoint 截断到 500");
        assertTrue(descLine.endsWith("..."));
    }

    @Test
    void capsAtTwentySkills() {
        List<Skill> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(mockSkill(String.format("skill-%02d", i), "desc " + i, Skill.Source.USER));
        }
        String out = SkillIndexFormatter.format(many);
        assertTrue(out.contains("skill-00"));
        assertTrue(out.contains("skill-19"));
        assertFalse(out.contains("skill-20"), "超过 20 个时按字典序前 20 应被保留");
    }

    @Test
    void truncatesByCodepointHelperHandlesAsciiAndCjk() {
        assertEquals("abc", SkillIndexFormatter.truncateByCodepoint("abc", 10));
        String s = "中文测试";
        assertEquals(s, SkillIndexFormatter.truncateByCodepoint(s, 4));
        String truncated = SkillIndexFormatter.truncateByCodepoint(s, 2);
        assertTrue(truncated.startsWith("中文"));
        assertTrue(truncated.endsWith("..."));
    }

    private static Skill mockSkill(String name, String desc, Skill.Source source) {
        return new Skill(name, desc, "1.0.0", null, List.of(), source, "body", null, null);
    }
}
