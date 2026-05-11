package com.paicli.cli;

import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;
import com.paicli.skill.SkillStateStore;

import java.util.List;

/**
 * /skill 命令组的展示与状态切换逻辑。
 * 抽出独立类便于单测；Main.java 只负责 dispatch + 打印。
 */
final class SkillCommandHandler {

    private SkillCommandHandler() {
    }

    static String startupSummary(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill";
        }
        List<Skill> enabled = registry.enabledSkills();
        StringBuilder sb = new StringBuilder("📚 Skills 加载（" + all.size() + " 个）...\n");
        for (Skill skill : all) {
            boolean isEnabled = enabled.contains(skill);
            sb.append(String.format("   %s %-16s %-8s %s%n",
                    isEnabled ? "✓" : "○",
                    skill.name(),
                    skill.displaySource(),
                    abbreviate(skill.description(), 60)));
        }
        sb.append("   ").append(enabled.size()).append("/").append(all.size()).append(" 启用");
        return sb.toString();
    }

    static String list(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill\n   /skill reload 重新扫描";
        }
        List<Skill> enabled = registry.enabledSkills();
        StringBuilder sb = new StringBuilder("📚 Skills（" + all.size() + " 个）\n");
        for (Skill skill : all) {
            boolean isEnabled = enabled.contains(skill);
            sb.append(String.format("  %s %-16s %-8s %-8s %s%n",
                    isEnabled ? "●" : "○",
                    skill.name(),
                    skill.displaySource(),
                    skill.version() == null ? "" : "v" + skill.version(),
                    abbreviate(skill.description(), 80)));
        }
        sb.append('\n')
                .append("提示：\n")
                .append("  /skill show <name> 看完整 SKILL.md\n")
                .append("  /skill on/off <name> 切换启用状态\n")
                .append("  /skill reload 重新扫描");
        return sb.toString();
    }

    static String show(SkillRegistry registry, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill show web-access";
        }
        Skill skill = registry.findAnySkill(name);
        if (skill == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📖 Skill: ").append(skill.name())
                .append(" (").append(skill.displaySource())
                .append(skill.version() == null ? "" : ", v" + skill.version())
                .append(")\n");
        sb.append("  路径: ").append(skill.skillMdPath()).append('\n');
        if (skill.referencesDir() != null) {
            sb.append("  references/: ").append(skill.referencesDir()).append('\n');
        }
        sb.append('\n');
        sb.append("---\n");
        sb.append("name: ").append(skill.name()).append('\n');
        sb.append("description: ").append(skill.description()).append('\n');
        if (skill.version() != null) sb.append("version: \"").append(skill.version()).append("\"\n");
        if (skill.author() != null) sb.append("author: ").append(skill.author()).append('\n');
        if (!skill.tags().isEmpty()) sb.append("tags: ").append(skill.tags()).append('\n');
        sb.append("---\n\n");
        sb.append(skill.body());
        return sb.toString();
    }

    static String enable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill on web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        stateStore.enable(name);
        return "▶️ 已启用 skill: " + name + "（下一轮 LLM 调用生效）";
    }

    static String disable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill off web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name;
        }
        stateStore.disable(name);
        return "⏸️ 已禁用 skill: " + name + "（已写入 ~/.paicli/skills.json，下一轮 LLM 调用生效）";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
