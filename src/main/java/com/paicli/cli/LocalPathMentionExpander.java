package com.paicli.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalPathMentionExpander {
    private static final int MAX_FILE_BYTES = 120_000;
    private static final int MAX_DIR_ENTRIES = 80;
    private static final Pattern LOCAL_PATH_MENTION = Pattern.compile("(^|\\s)@(<[^>]+>|[^\\s<>:]+)");

    private final Path projectRoot;
    private final Path homeDir;

    LocalPathMentionExpander(Path projectRoot) {
        this.projectRoot = realPathOrNormalize(projectRoot == null ? Path.of(".") : projectRoot);
        this.homeDir = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    String expand(String input) {
        if (input == null || input.isBlank() || input.indexOf('@') < 0) {
            return input;
        }
        Matcher matcher = LOCAL_PATH_MENTION.matcher(input);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String leading = matcher.group(1);
            String raw = matcher.group(2);
            String replacement = expandToken(raw);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(leading + replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String expandToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "@" + raw;
        }
        String value = stripAngles(raw);
        if (value.startsWith("image:") || value.equals("clipboard") || value.contains(":")) {
            return "@" + raw;
        }
        Path candidate = resolve(value);
        if (candidate == null || !Files.exists(candidate)) {
            return "@" + raw;
        }
        Path realCandidate = realPathOrNormalize(candidate);
        if (!realCandidate.startsWith(projectRoot)) {
            return "@" + raw;
        }
        try {
            if (Files.isDirectory(realCandidate)) {
                return renderDirectory(realCandidate);
            }
            if (Files.isRegularFile(realCandidate)) {
                return renderFile(realCandidate);
            }
        } catch (IOException ignored) {
            return "@" + raw;
        }
        return "@" + raw;
    }

    private Path resolve(String value) {
        if (value.isBlank()) {
            return null;
        }
        if (value.startsWith("~/")) {
            return homeDir.resolve(value.substring(2)).normalize();
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(path).normalize();
    }

    private String renderFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        boolean truncated = bytes.length > MAX_FILE_BYTES;
        int length = Math.min(bytes.length, MAX_FILE_BYTES);
        if (looksBinary(bytes, length)) {
            return "@<" + displayPath(path) + ">\n<file path=\"" + escapeXml(displayPath(path)) +
                    "\" binary=\"true\">binary content omitted</file>";
        }
        String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
        String suffix = truncated ? "\n[file truncated by PaiCLI at " + MAX_FILE_BYTES + " bytes]" : "";
        return "@<" + displayPath(path) + ">\n<file path=\"" + escapeXml(displayPath(path)) + "\">\n" +
                content + suffix + "\n</file>";
    }

    private String renderDirectory(Path path) throws IOException {
        List<Path> children;
        try (var stream = Files.list(path)) {
            children = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_DIR_ENTRIES + 1L)
                    .toList();
        }
        StringBuilder out = new StringBuilder("@<").append(displayPath(path)).append(">\n")
                .append("<directory path=\"").append(escapeXml(displayPath(path))).append("\">\n");
        int count = Math.min(children.size(), MAX_DIR_ENTRIES);
        for (int i = 0; i < count; i++) {
            Path child = children.get(i);
            out.append("- ").append(child.getFileName());
            if (Files.isDirectory(child)) {
                out.append('/');
            }
            out.append('\n');
        }
        if (children.size() > MAX_DIR_ENTRIES) {
            out.append("[directory truncated by PaiCLI at ").append(MAX_DIR_ENTRIES).append(" entries]\n");
        }
        return out.append("</directory>").toString();
    }

    private String displayPath(Path path) {
        Path relative = projectRoot.relativize(path);
        String value = relative.toString();
        return value.isBlank() ? "." : value;
    }

    private static Path realPathOrNormalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String stripAngles(String raw) {
        if (raw.startsWith("<") && raw.endsWith(">") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static boolean looksBinary(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String escapeXml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
