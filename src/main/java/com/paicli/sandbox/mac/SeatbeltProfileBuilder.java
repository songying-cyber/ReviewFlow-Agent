package com.paicli.sandbox.mac;

import com.paicli.sandbox.SandboxConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SeatbeltProfileBuilder {
    private final Path projectRoot;
    private final Path tempDir;
    private final SandboxConfig config;

    public SeatbeltProfileBuilder(Path projectRoot, Path tempDir, SandboxConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.tempDir = tempDir.toAbsolutePath().normalize();
        this.config = config == null ? new SandboxConfig() : config;
    }

    public String build() {
        List<Path> writePaths = new ArrayList<>();
        writePaths.add(projectRoot);
        writePaths.add(tempDir);
        for (String pattern : config.getFilesystem().getAllowWrite()) {
            Path resolved = resolvePath(pattern);
            if (resolved != null) {
                writePaths.add(resolved);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(allow default)\n");
        if (!config.getNetwork().isEnabled()) {
            sb.append("(deny network*)\n");
        }
        sb.append("(deny file-write*)\n");
        sb.append("(allow file-write*\n");
        for (Path path : writePaths.stream().distinct().toList()) {
            sb.append("  (subpath \"").append(escape(path.toString())).append("\")\n");
        }
        sb.append(")\n");
        appendDenyRead(sb);
        appendDenyWrite(sb);
        return sb.toString();
    }

    private void appendDenyRead(StringBuilder sb) {
        appendDeny(sb, "file-read*", config.getFilesystem().getDenyRead());
    }

    private void appendDenyWrite(StringBuilder sb) {
        List<String> deny = new ArrayList<>(config.getFilesystem().getDenyWrite());
        deny.add("~/.paicli/**");
        deny.add(".paicli/**");
        appendDeny(sb, "file-write*", deny);
    }

    private void appendDeny(StringBuilder sb, String operation, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        sb.append("(deny ").append(operation).append("\n");
        for (String pattern : patterns) {
            Path resolved = resolvePath(pattern);
            if (resolved == null) {
                continue;
            }
            String value = stripGlobSuffix(resolved.toString());
            if (isRecursivePattern(pattern)) {
                sb.append("  (subpath \"").append(escape(value)).append("\")\n");
            } else {
                sb.append("  (literal \"").append(escape(value)).append("\")\n");
            }
        }
        sb.append(")\n");
    }

    private Path resolvePath(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String trimmed = pattern.trim();
        if (trimmed.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(trimmed.substring(2)).toAbsolutePath().normalize();
        }
        if (trimmed.contains("*") && !isRecursivePattern(trimmed)) {
            return null;
        }
        String withoutGlob = stripGlobSuffix(trimmed);
        Path path = Path.of(withoutGlob);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(withoutGlob).normalize();
    }

    private static boolean isRecursivePattern(String pattern) {
        return pattern != null && (pattern.endsWith("/**") || pattern.endsWith("*"));
    }

    private static String stripGlobSuffix(String value) {
        String stripped = value;
        if (stripped.endsWith("/**")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        while (stripped.endsWith("*")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
