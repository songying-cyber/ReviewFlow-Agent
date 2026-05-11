package com.paicli.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单 Agent 实例的 skill 注入缓冲区。
 *
 * 生命周期：LLM 调 load_skill → push 到 buffer → 下一轮构造 user message 时 drain → 拼到原内容前。
 *
 * 关键约束：
 * - drain 是一次性消费（防止跨轮重复注入）
 * - 同一会话内最多保留 3 个 skill body（上限 3 个，超出 LRU 淘汰最旧）
 * - 同一 skill 重复 push 会替换旧 body 并刷新到末尾，避免重复
 * - /clear 命令调 clear() 复位
 *
 * 三个 SubAgent 角色（Planner / Worker / Reviewer）+ 主 Agent 各持一个独立实例，
 * 不共享 buffer，避免角色间提示词污染。
 */
public final class SkillContextBuffer {

    private static final int MAX_SKILLS = 3;

    private final Map<String, String> entries = new LinkedHashMap<>();

    public synchronized void push(String skillName, String body) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return;
        }
        entries.remove(skillName);
        entries.put(skillName, body);
        while (entries.size() > MAX_SKILLS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    /**
     * 取出全部已积累 skill body 并清空。返回拼接好的 markdown 段，可直接前置到 user message。
     */
    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : snapshot) {
            sb.append("## 已加载 Skill：").append(e.getKey()).append('\n')
                    .append(e.getValue().trim()).append('\n')
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
