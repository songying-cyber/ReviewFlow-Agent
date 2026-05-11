package com.paicli.skill;

import java.util.List;

/**
 * 把启用 skill 渲染成 system prompt 索引段。
 *
 * 预算约束（命中即截断 + stderr 警告）：
 * - 单条 description ≤ 500 codepoint
 * - 启用 skill 数 ≤ 20（按 name 字典序保留前 20）
 * - 总段大小 ≤ 4096 字符
 *
 * 注入位置：每个 Agent / SubAgent 的 system prompt 末尾，独立段。
 */
public final class SkillIndexFormatter {

    public static final int MAX_DESCRIPTION_CODEPOINTS = 500;
    public static final int MAX_ENABLED_SKILLS = 20;
    public static final int MAX_INDEX_BYTES = 4096;

    private SkillIndexFormatter() {
    }

    public static String format(List<Skill> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return "";
        }

        List<Skill> effective = enabled;
        if (enabled.size() > MAX_ENABLED_SKILLS) {
            effective = enabled.stream()
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .limit(MAX_ENABLED_SKILLS)
                    .toList();
            System.err.println("⚠️ 已检测到 " + enabled.size()
                    + " 个 skill，仅前 " + MAX_ENABLED_SKILLS + " 个进入 system prompt 索引");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用 Skills（按需调用 load_skill 加载完整指引）\n\n");

        for (Skill skill : effective) {
            String desc = truncateByCodepoint(skill.description().trim(), MAX_DESCRIPTION_CODEPOINTS);
            sb.append("- **").append(skill.name()).append("**：").append(desc).append('\n');
        }

        sb.append('\n');
        sb.append("判断准则：当任务描述匹配某个 skill 的触发场景时，调用 load_skill(name) 加载完整指引；")
                .append("已加载的 skill 会在下一轮以 \"## 已加载 Skill\" 段落出现在你的 user message 中。")
                .append("不要重复加载同一 skill；同一会话内一次足够。\n");

        if (sb.length() > MAX_INDEX_BYTES) {
            String truncated = sb.substring(0, MAX_INDEX_BYTES) + "\n...(skill 索引段被截断)\n";
            System.err.println("⚠️ skill 索引段超过 " + MAX_INDEX_BYTES + " 字符，已截断");
            return truncated;
        }
        return sb.toString();
    }

    static String truncateByCodepoint(String s, int limit) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= limit) return s;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < s.length() && count < limit) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            count++;
        }
        return sb.toString() + "...";
    }
}
