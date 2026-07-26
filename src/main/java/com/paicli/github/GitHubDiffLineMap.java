package com.paicli.github;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubDiffLineMap {
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private final Map<String, FileLineMap> files;
    private final Map<String, String> aliases;

    private GitHubDiffLineMap(Map<String, FileLineMap> files, Map<String, String> aliases) {
        this.files = Map.copyOf(files);
        this.aliases = Map.copyOf(aliases);
    }

    public static GitHubDiffLineMap fromChangedFiles(List<GitHubChangedFile> changedFiles) {
        Map<String, FileLineMap> files = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (changedFiles == null) {
            return new GitHubDiffLineMap(files, aliases);
        }
        for (GitHubChangedFile file : changedFiles) {
            if (file == null || isBlank(file.filename())) {
                continue;
            }
            String path = normalizePath(file.filename());
            FileLineMap lineMap = parsePatch(path, file.patch());
            files.put(path, lineMap);
            if (!isBlank(file.previousFilename())) {
                aliases.put(normalizePath(file.previousFilename()), path);
            }
        }
        return new GitHubDiffLineMap(files, aliases);
    }

    public Optional<GitHubDiffPosition> resolve(GitHubReviewFinding finding) {
        if (finding == null || isBlank(finding.path()) || finding.line() == null) {
            return Optional.empty();
        }
        return resolve(finding.path(), finding.line(), finding.side());
    }

    public Optional<GitHubDiffPosition> resolve(String path, Integer line, String side) {
        if (isBlank(path) || line == null || line <= 0) {
            return Optional.empty();
        }
        FileLineMap file = files.get(resolvePath(path));
        if (file == null) {
            return Optional.empty();
        }
        String normalizedSide = normalizeSide(side);
        GitHubDiffPosition position = "LEFT".equals(normalizedSide)
                ? file.leftLines.get(line)
                : file.rightLines.get(line);
        return Optional.ofNullable(position);
    }

    public boolean contains(String path, Integer line, String side) {
        return resolve(path, line, side).isPresent();
    }

    public boolean hasFile(String path) {
        return !isBlank(path) && files.containsKey(resolvePath(path));
    }

    private String resolvePath(String path) {
        String normalized = normalizePath(path);
        return aliases.getOrDefault(normalized, normalized);
    }

    private static FileLineMap parsePatch(String path, String patch) {
        FileLineMap lineMap = new FileLineMap();
        if (isBlank(patch)) {
            return lineMap;
        }
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        String[] lines = patch.split("\\R", -1);
        for (String rawLine : lines) {
            Matcher header = HUNK_HEADER.matcher(rawLine);
            if (header.matches()) {
                oldLine = Integer.parseInt(header.group(1));
                newLine = Integer.parseInt(header.group(3));
                inHunk = true;
                continue;
            }
            if (!inHunk || rawLine.isEmpty()) {
                continue;
            }
            char marker = rawLine.charAt(0);
            if (marker == '+') {
                lineMap.rightLines.put(newLine,
                        new GitHubDiffPosition(path, newLine, "RIGHT", 0, newLine, "added"));
                newLine++;
            } else if (marker == '-') {
                lineMap.leftLines.put(oldLine,
                        new GitHubDiffPosition(path, oldLine, "LEFT", oldLine, 0, "deleted"));
                oldLine++;
            } else if (marker == ' ') {
                lineMap.leftLines.put(oldLine,
                        new GitHubDiffPosition(path, oldLine, "LEFT", oldLine, newLine, "context"));
                lineMap.rightLines.put(newLine,
                        new GitHubDiffPosition(path, newLine, "RIGHT", oldLine, newLine, "context"));
                oldLine++;
                newLine++;
            } else if (marker == '\\') {
                // "\ No newline at end of file" is metadata and not commentable.
            }
        }
        return lineMap;
    }

    private static String normalizeSide(String side) {
        if (isBlank(side)) {
            return "RIGHT";
        }
        String normalized = side.trim().toUpperCase(Locale.ROOT);
        return "LEFT".equals(normalized) ? "LEFT" : "RIGHT";
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static class FileLineMap {
        private final Map<Integer, GitHubDiffPosition> leftLines = new LinkedHashMap<>();
        private final Map<Integer, GitHubDiffPosition> rightLines = new LinkedHashMap<>();
    }
}
