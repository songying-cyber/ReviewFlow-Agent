package com.paicli.sandbox;

import com.paicli.sandbox.mac.SeatbeltProfileBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SeatbeltProfileBuilderTest {
    @Test
    void buildsProfileWithProjectWriteAndSensitiveDenies(@TempDir Path tempDir) {
        SandboxConfig config = new SandboxConfig();

        String profile = new SeatbeltProfileBuilder(
                tempDir.resolve("project"),
                tempDir.resolve("tmp"),
                config).build();

        assertTrue(profile.contains("(version 1)"));
        assertTrue(profile.contains("(deny network*)"));
        assertTrue(profile.contains("(deny file-write*)"));
        assertTrue(profile.contains("(subpath \"" + tempDir.resolve("project").toAbsolutePath().normalize()));
        assertTrue(profile.contains("(literal \"" + tempDir.resolve("project").resolve(".env").toAbsolutePath().normalize()));
        assertTrue(profile.contains("(literal \"" + tempDir.resolve("project").resolve("PAI.md").toAbsolutePath().normalize()));
    }
}
