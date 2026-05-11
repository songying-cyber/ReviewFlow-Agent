package com.paicli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillStateStoreTest {

    @Test
    void returnsEmptyDisabledWhenFileMissing(@TempDir Path tempDir) {
        SkillStateStore store = new SkillStateStore(tempDir.resolve("skills.json"));
        assertTrue(store.disabled().isEmpty());
    }

    @Test
    void persistsDisabledList(@TempDir Path tempDir) {
        Path file = tempDir.resolve("skills.json");
        SkillStateStore store = new SkillStateStore(file);

        store.disable("web-access");
        store.disable("verbose-debug");

        SkillStateStore reload = new SkillStateStore(file);
        Set<String> disabled = reload.disabled();
        assertEquals(2, disabled.size());
        assertTrue(disabled.contains("web-access"));
        assertTrue(disabled.contains("verbose-debug"));
    }

    @Test
    void enableRemovesFromDisabled(@TempDir Path tempDir) {
        Path file = tempDir.resolve("skills.json");
        SkillStateStore store = new SkillStateStore(file);
        store.disable("a");
        store.disable("b");
        store.enable("a");

        Set<String> disabled = new SkillStateStore(file).disabled();
        assertEquals(Set.of("b"), disabled);
    }

    @Test
    void treatsCorruptFileAsEmpty(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("skills.json");
        Files.writeString(file, "not json {{");
        SkillStateStore store = new SkillStateStore(file);
        assertTrue(store.disabled().isEmpty());
    }
}
