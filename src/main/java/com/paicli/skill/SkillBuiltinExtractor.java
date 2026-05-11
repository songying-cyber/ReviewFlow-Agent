package com.paicli.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把 jar 内 resources/skills/&lt;name&gt;/ 解压到 ~/.paicli/skills-cache/&lt;name&gt;/。
 *
 * 解压策略：通过 .version 文件标记当前 jar 内置版本。版本一致跳过；不一致或缺失则覆盖整个目录。
 *
 * 内置 skill 文件清单为硬编码（避免 jar 内 resource walk 的跨平台问题），
 * 当前覆盖：web-access skill 的 SKILL.md / cdp-cheatsheet.md / 6 个 site-patterns。
 */
public final class SkillBuiltinExtractor {

    /** 当 web-access 内容有破坏性更新时上调，触发缓存重建。 */
    public static final String CURRENT_VERSION = "1.0.0";

    private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
            new BuiltinSkillSpec("web-access", List.of(
                    "SKILL.md",
                    "references/cdp-cheatsheet.md",
                    "references/site-patterns/github.com.md",
                    "references/site-patterns/juejin.cn.md",
                    "references/site-patterns/mp.weixin.qq.com.md",
                    "references/site-patterns/x.com.md",
                    "references/site-patterns/xiaohongshu.com.md",
                    "references/site-patterns/zhuanlan.zhihu.com.md"
            ))
    );

    private final Path cacheRoot;

    public SkillBuiltinExtractor(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<String> builtinSkillNames() {
        return BUILTIN_SKILLS.stream().map(BuiltinSkillSpec::name).toList();
    }

    public Path skillCacheDir(String skillName) {
        return cacheRoot.resolve(skillName);
    }

    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (BuiltinSkillSpec spec : BUILTIN_SKILLS) {
            extract(spec);
        }
    }

    private void extract(BuiltinSkillSpec spec) throws IOException {
        Path skillDir = cacheRoot.resolve(spec.name());
        Path versionFile = skillDir.resolve(".version");
        if (Files.exists(versionFile)) {
            String existing = Files.readString(versionFile).trim();
            if (CURRENT_VERSION.equals(existing)) {
                return;
            }
        }
        if (Files.exists(skillDir)) {
            deleteRecursive(skillDir);
        }
        Files.createDirectories(skillDir);
        for (String relative : spec.files()) {
            String resourcePath = "skills/" + spec.name() + "/" + relative;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    System.err.println("⚠️ 内置 skill 资源缺失: " + resourcePath);
                    continue;
                }
                Path target = skillDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(versionFile, CURRENT_VERSION);
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private record BuiltinSkillSpec(String name, List<String> files) {
    }
}
