package com.paicli.cli;

import com.paicli.skill.SkillRegistry;
import com.paicli.skill.SkillStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillCommandHandlerTest {

    @Test
    void listShowsAllSkillsWithStatus(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "联网决策", "1.0.0"),
                new SkillSpec("debug-helper", "调试辅助", "0.1.0"));
        String out = SkillCommandHandler.list(registry);

        assertTrue(out.contains("web-access"));
        assertTrue(out.contains("debug-helper"));
        assertTrue(out.contains("/skill show <name>"));
    }

    @Test
    void listMarksDisabledSkill(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("skills.json");
        SkillStateStore state = new SkillStateStore(stateFile);
        state.disable("debug-helper");

        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "联网决策", "1.0.0"),
                new SkillSpec("debug-helper", "调试辅助", "0.1.0"));
        String out = SkillCommandHandler.list(registry);

        // 启用的用 ●，禁用的用 ○
        assertTrue(out.contains("● web-access") || out.contains("●  web-access"));
        assertTrue(out.contains("○ debug-helper") || out.contains("○  debug-helper"));
    }

    @Test
    void showReturnsErrorForMissingSkill(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir);
        String out = SkillCommandHandler.show(registry, "nonexistent");
        assertTrue(out.contains("Skill 未找到"));
    }

    @Test
    void showRendersFrontmatterAndBody(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "联网决策手册", "1.0.0"));
        String out = SkillCommandHandler.show(registry, "web-access");
        assertTrue(out.contains("📖 Skill: web-access"));
        assertTrue(out.contains("description: 联网决策手册"));
        assertTrue(out.contains("body for web-access"));
    }

    @Test
    void enableAndDisableUpdateStateStore(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("skills.json");
        SkillStateStore state = new SkillStateStore(stateFile);
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "联网决策", "1.0.0"));

        String r1 = SkillCommandHandler.disable(registry, state, "web-access");
        assertTrue(r1.contains("已禁用"));
        assertTrue(state.disabled().contains("web-access"));

        String r2 = SkillCommandHandler.enable(registry, state, "web-access");
        assertTrue(r2.contains("已启用"));
        assertFalse(state.disabled().contains("web-access"));
    }

    @Test
    void rejectsEmptyOrUnknownNames(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir);
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));

        assertTrue(SkillCommandHandler.show(registry, "").contains("请提供 skill 名称"));
        assertTrue(SkillCommandHandler.enable(registry, state, "").contains("请提供 skill 名称"));
        assertTrue(SkillCommandHandler.disable(registry, state, "").contains("请提供 skill 名称"));
        assertTrue(SkillCommandHandler.enable(registry, state, "ghost").contains("Skill 未找到"));
    }

    private record SkillSpec(String name, String desc, String version) {
    }

    private static SkillRegistry registryWith(Path tempDir, SkillSpec... specs) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Files.createDirectories(userRoot);
        for (SkillSpec spec : specs) {
            Path dir = userRoot.resolve(spec.name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: " + spec.name()
                            + "\ndescription: " + spec.desc()
                            + "\nversion: \"" + spec.version() + "\"\n---\nbody for " + spec.name() + "\n");
        }
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }
}
