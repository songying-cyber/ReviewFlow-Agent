package com.paicli.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class PromptRepository {
    private static final String RESOURCE_PREFIX = "prompts/";

    private final Path userPromptsDir;
    private final Path projectPromptsDir;
    private final ClassLoader classLoader;

    public PromptRepository(Path userPromptsDir, Path projectPromptsDir) {
        this(userPromptsDir, projectPromptsDir, PromptRepository.class.getClassLoader());
    }

    PromptRepository(Path userPromptsDir, Path projectPromptsDir, ClassLoader classLoader) {
        this.userPromptsDir = userPromptsDir;
        this.projectPromptsDir = projectPromptsDir;
        this.classLoader = Objects.requireNonNull(classLoader);
    }

    public static PromptRepository createDefault() {
        Path home = Path.of(System.getProperty("user.home"), ".paicli", "prompts");
        Path project = Path.of(".paicli", "prompts").toAbsolutePath().normalize();
        return new PromptRepository(home, project);
    }

    public String loadRequired(String relativePath) {
        String normalized = normalize(relativePath);
        String content = loadBuiltin(normalized);
        content = overrideIfPresent(userPromptsDir, normalized, content);
        content = overrideIfPresent(projectPromptsDir, normalized, content);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Prompt resource missing: " + normalized);
        }
        return content.trim();
    }

    private String loadBuiltin(String relativePath) {
        try (InputStream in = classLoader.getResourceAsStream(RESOURCE_PREFIX + relativePath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + relativePath, e);
        }
    }

    private String overrideIfPresent(Path root, String relativePath, String fallback) {
        if (root == null) {
            return fallback;
        }
        Path override = root.resolve(relativePath).normalize();
        if (!override.startsWith(root.normalize()) || !Files.isRegularFile(override)) {
            return fallback;
        }
        try {
            return Files.readString(override, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt override: " + override, e);
        }
    }

    private static String normalize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is blank");
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid prompt path: " + relativePath);
        }
        return normalized;
    }
}
