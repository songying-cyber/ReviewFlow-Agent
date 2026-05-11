package com.paicli.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 加载与运行时维护。
 *
 * 三层目录扫描顺序（后者整体覆盖前者同名 skill）：
 *   1. builtin（jar 内置，由 SkillBuiltinExtractor 解压到 cacheRoot）
 *   2. user：~/.paicli/skills/&lt;name&gt;/SKILL.md
 *   3. project：&lt;projectDir&gt;/.paicli/skills/&lt;name&gt;/SKILL.md
 *
 * 启用状态由 SkillStateStore 提供 disabled 列表过滤。
 */
public final class SkillRegistry {

    private final Path builtinCacheRoot;
    private final Path userSkillsDir;
    private final Path projectSkillsDir;
    private final SkillStateStore stateStore;

    private final Map<String, Skill> skillsByName = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillRegistry(Path builtinCacheRoot, Path userSkillsDir, Path projectSkillsDir, SkillStateStore stateStore) {
        this.builtinCacheRoot = builtinCacheRoot;
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        this.stateStore = stateStore;
    }

    public synchronized void reload() {
        skillsByName.clear();
        warnings.clear();

        loadDirectory(builtinCacheRoot, Skill.Source.BUILTIN);
        loadDirectory(userSkillsDir, Skill.Source.USER);
        loadDirectory(projectSkillsDir, Skill.Source.PROJECT);
    }

    public synchronized List<Skill> allSkills() {
        return skillsByName.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    public synchronized List<Skill> enabledSkills() {
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        return allSkills().stream()
                .filter(s -> !disabled.contains(s.name()))
                .toList();
    }

    public synchronized Skill findSkill(String name) {
        if (name == null) return null;
        Skill skill = skillsByName.get(name);
        if (skill == null) return null;
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        if (disabled.contains(name)) return null;
        return skill;
    }

    public synchronized Skill findAnySkill(String name) {
        if (name == null) return null;
        return skillsByName.get(name);
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public SkillStateStore stateStore() {
        return stateStore;
    }

    private void loadDirectory(Path dir, Skill.Source source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path entry : entries) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }
                Skill skill = parseSkill(entry, skillMd, source);
                if (skill != null) {
                    skillsByName.put(skill.name(), skill);
                }
            }
        } catch (IOException e) {
            warnings.add("扫描 skill 目录失败 " + dir + ": " + e.getMessage());
            System.err.println("⚠️ 扫描 skill 目录失败 " + dir + ": " + e.getMessage());
        }
    }

    private Skill parseSkill(Path skillDir, Path skillMd, Skill.Source source) {
        String content;
        try {
            content = Files.readString(skillMd);
        } catch (IOException e) {
            warnings.add("读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            System.err.println("⚠️ 读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            return null;
        }

        SkillFrontmatterParser.ParseResult parsed = SkillFrontmatterParser.parse(content);
        for (String w : parsed.warnings()) {
            warnings.add(skillMd + ": " + w);
            System.err.println("⚠️ Skill " + skillMd + " frontmatter: " + w);
        }

        Map<String, Object> fm = parsed.frontmatter();
        String name = stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = skillDir.getFileName().toString();
        }
        String description = stringField(fm, "description");
        if (description == null) description = "";
        String version = stringField(fm, "version");
        String author = stringField(fm, "author");
        List<String> tags = listField(fm, "tags");

        Path referencesDir = skillDir.resolve("references");
        if (!Files.isDirectory(referencesDir)) {
            referencesDir = null;
        }

        return new Skill(
                name,
                description,
                version,
                author,
                tags,
                source,
                parsed.body(),
                skillMd,
                referencesDir
        );
    }

    private static String stringField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List<?> list) {
            return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
        }
        return Collections.emptyList();
    }
}
