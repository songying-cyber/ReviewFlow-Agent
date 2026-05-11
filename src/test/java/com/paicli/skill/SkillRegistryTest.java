package com.paicli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void loadsSkillsFromAllThreeLayers(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        Path user = tempDir.resolve("user");
        Path project = tempDir.resolve("project");
        writeSkill(builtin, "web-access", "builtin desc", "v0");
        writeSkill(user, "user-only", "u desc", "v1");
        writeSkill(project, "project-only", "p desc", "v2");

        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(builtin, user, project, state);
        registry.reload();

        List<Skill> all = registry.allSkills();
        assertEquals(3, all.size());
        // sorted by name asc
        assertEquals("project-only", all.get(0).name());
        assertEquals("user-only", all.get(1).name());
        assertEquals("web-access", all.get(2).name());
    }

    @Test
    void projectOverridesUserOverridesBuiltin(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        Path user = tempDir.resolve("user");
        Path project = tempDir.resolve("project");
        writeSkill(builtin, "web-access", "builtin desc", "v-builtin");
        writeSkill(user, "web-access", "user desc", "v-user");
        writeSkill(project, "web-access", "project desc", "v-project");

        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(builtin, user, project, state);
        registry.reload();

        List<Skill> all = registry.allSkills();
        assertEquals(1, all.size());
        Skill skill = all.get(0);
        assertEquals("v-project", skill.version());
        assertEquals(Skill.Source.PROJECT, skill.source());
    }

    @Test
    void disabledFiltersOutSkill(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        writeSkill(builtin, "web-access", "desc", "v0");
        writeSkill(builtin, "other", "desc2", "v0");

        Path stateFile = tempDir.resolve("skills.json");
        SkillStateStore state = new SkillStateStore(stateFile);
        state.disable("other");

        SkillRegistry registry = new SkillRegistry(builtin, null, null, state);
        registry.reload();

        assertEquals(2, registry.allSkills().size());
        assertEquals(1, registry.enabledSkills().size());
        assertEquals("web-access", registry.enabledSkills().get(0).name());
        assertNull(registry.findSkill("other"), "disabled skill 应不可通过 findSkill 取到");
    }

    @Test
    void reloadPicksUpNewSkills(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Files.createDirectories(user);
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, user, null, state);
        registry.reload();
        assertTrue(registry.allSkills().isEmpty());

        writeSkill(user, "web-access", "desc", "v0");
        registry.reload();
        assertEquals(1, registry.allSkills().size());
    }

    @Test
    void skipsFileWithMalformedFrontmatterButContinues(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Files.createDirectories(user.resolve("good"));
        Files.writeString(user.resolve("good/SKILL.md"),
                "---\nname: good\ndescription: ok\n---\nbody\n");

        Files.createDirectories(user.resolve("bad"));
        Files.writeString(user.resolve("bad/SKILL.md"),
                "no frontmatter at all\n");

        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, user, null, state);
        registry.reload();

        // bad skill 没有 frontmatter，name 退化为目录名 "bad"，仍能加载（Spec 中无 frontmatter 仅 warning）
        assertEquals(2, registry.allSkills().size());
    }

    private static void writeSkill(Path root, String name, String desc, String version) throws IOException {
        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);
        String content = "---\nname: " + name
                + "\ndescription: " + desc
                + "\nversion: \"" + version + "\"\n---\nbody for " + name + "\n";
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }
}
