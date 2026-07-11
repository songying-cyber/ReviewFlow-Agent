package com.paicli.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads PaiCLI project memory files that are intended to be versioned and
 * injected into the system prompt at session start.
 */
public class ProjectMemoryLoader {
    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryLoader.class);
    private static final int MAX_TOTAL_CHARS = 24_000;
    private static final int MAX_IMPORT_DEPTH = 3;

    private final Path userConfigDir;
    private final Path projectRoot;

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot) {
        this.userConfigDir = userConfigDir == null ? null : userConfigDir.toAbsolutePath().normalize();
        this.projectRoot = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
    }

    public static ProjectMemoryLoader createDefault(Path projectRoot) {
        return new ProjectMemoryLoader(Path.of(System.getProperty("user.home"), ".paicli"), projectRoot);
    }

    public String loadForPrompt() {
        List<MemorySource> sources = sources();
        StringBuilder body = new StringBuilder();
        Set<Path> importStack = new HashSet<>();

        for (MemorySource source : sources) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            String content = readWithImports(source.path(), source.importRoot(), importStack, 0).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append("### ").append(label(source.path())).append("\n\n").append(content);
            if (body.length() >= MAX_TOTAL_CHARS) {
                return truncateSection(body);
            }
        }

        if (body.isEmpty()) {
            return "";
        }
        return "## PAI.md 项目记忆\n\n" + body;
    }

    private List<MemorySource> sources() {
        List<MemorySource> sources = new ArrayList<>();
        if (userConfigDir != null) {
            sources.add(new MemorySource(userConfigDir.resolve("PAI.md"), userConfigDir));
        }
        sources.add(new MemorySource(projectRoot.resolve("PAI.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".paicli").resolve("PAI.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve("PAI.local.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".paicli").resolve("PAI.local.md"), projectRoot));
        return sources;
    }

    private String readWithImports(Path file, Path importRoot, Set<Path> importStack, int depth) {
        Path normalized = file.toAbsolutePath().normalize();
        if (depth > MAX_IMPORT_DEPTH) {
            log.warn("Skipping PAI.md import beyond depth {}: {}", MAX_IMPORT_DEPTH, normalized);
            return "";
        }
        if (!normalized.startsWith(importRoot) || !Files.isRegularFile(normalized)) {
            log.warn("Skipping PAI.md import outside allowed root or missing file: {}", normalized);
            return "";
        }
        if (!importStack.add(normalized)) {
            log.warn("Skipping cyclic PAI.md import: {}", normalized);
            return "";
        }

        try {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(normalized, StandardCharsets.UTF_8)) {
                String importPath = parseImport(line);
                if (importPath == null) {
                    out.append(line).append("\n");
                    continue;
                }
                Path imported = normalized.getParent().resolve(importPath).normalize();
                String importedContent = readWithImports(imported, importRoot, importStack, depth + 1).trim();
                if (!importedContent.isBlank()) {
                    out.append(importedContent).append("\n");
                }
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("Failed to read PAI.md memory file: {}", normalized, e);
            return "";
        } finally {
            importStack.remove(normalized);
        }
    }

    private static String parseImport(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("@") || trimmed.length() < 2 || trimmed.contains(" ")) {
            return null;
        }
        String path = trimmed.substring(1).trim();
        if (path.startsWith("/") || path.contains("..")) {
            return null;
        }
        return path;
    }

    private static String truncateSection(StringBuilder body) {
        int keep = Math.max(0, MAX_TOTAL_CHARS - 80);
        String truncated = body.substring(0, Math.min(body.length(), keep)).stripTrailing();
        return "## PAI.md 项目记忆\n\n" + truncated + "\n\n[PAI.md 内容已按 " + MAX_TOTAL_CHARS + " 字符预算截断]";
    }

    private static String label(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private record MemorySource(Path path, Path importRoot) {
        private MemorySource {
            path = path.toAbsolutePath().normalize();
            importRoot = importRoot.toAbsolutePath().normalize();
        }
    }
}
