package com.paicli.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class JavaCodeSearchEngine implements CodeSearchEngine {
    private static final long MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024;

    private final Set<String> excludedDirs;

    JavaCodeSearchEngine(Set<String> excludedDirs) {
        this.excludedDirs = excludedDirs;
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        Pattern contentPattern;
        try {
            int flags = request.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            contentPattern = Pattern.compile(request.regex() ? request.query() : Pattern.quote(request.query()), flags);
        } catch (PatternSyntaxException e) {
            return new CodeSearchResult("java", List.of(), false, "正则表达式无效: " + e.getMessage());
        }

        PathMatcher globMatcher = null;
        PathMatcher fileNameGlobMatcher = null;
        if (request.glob() != null && !request.glob().isBlank()) {
            globMatcher = request.projectRoot().getFileSystem().getPathMatcher("glob:" + normalizeGlob(request.glob()));
            fileNameGlobMatcher = request.projectRoot().getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(request.glob()));
        }

        List<GrepMatch> matches = new ArrayList<>();
        Map<String, Integer> perFileMatches = new HashMap<>();
        boolean[] headLimited = new boolean[]{false};
        PathMatcher finalGlobMatcher = globMatcher;
        PathMatcher finalFileNameGlobMatcher = fileNameGlobMatcher;
        try {
            Files.walkFileTree(request.root(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(request.projectRoot()) && excludedDirs.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= request.maxResults() || !Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = request.projectRoot().relativize(file);
                    if (finalGlobMatcher != null
                            && !finalGlobMatcher.matches(relative)
                            && !finalFileNameGlobMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    collectMatches(file, relative, contentPattern, request, matches, perFileMatches, headLimited);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return new CodeSearchResult("java", matches, false, e.getMessage());
        }

        boolean partial = matches.size() >= request.maxResults() || headLimited[0];
        String partialReason = matches.size() >= request.maxResults()
                ? "已达到 max_results=" + request.maxResults()
                : headLimited[0] ? "部分文件已达到 head_limit=" + request.headLimit() : "";
        return new CodeSearchResult("java", matches, partial, partialReason);
    }

    private void collectMatches(Path file, Path relative, Pattern contentPattern, CodeSearchRequest request,
                                List<GrepMatch> matches, Map<String, Integer> perFileMatches,
                                boolean[] headLimited) {
        try {
            if (Files.size(file) > MAX_SEARCH_FILE_BYTES || isLikelyBinary(file)) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String fileKey = relative.toString();
            for (int i = 0; i < lines.size() && matches.size() < request.maxResults(); i++) {
                int currentFileMatches = perFileMatches.getOrDefault(fileKey, 0);
                if (currentFileMatches >= request.headLimit()) {
                    if (contentPattern.matcher(lines.get(i)).find()) {
                        headLimited[0] = true;
                        break;
                    }
                    continue;
                }
                String line = lines.get(i);
                if (contentPattern.matcher(line).find()) {
                    int from = Math.max(0, i - request.contextLines());
                    int to = Math.min(lines.size() - 1, i + request.contextLines());
                    List<ContextLine> context = new ArrayList<>();
                    for (int j = from; j <= to; j++) {
                        context.add(new ContextLine(j + 1, lines.get(j)));
                    }
                    matches.add(new GrepMatch(fileKey, i + 1, context));
                    perFileMatches.put(fileKey, currentFileMatches + 1);
                }
            }
        } catch (Exception ignored) {
            // 编码不支持、权限异常或短暂文件变化时跳过该文件，保持搜索路径 fail-soft。
        }
    }

    private boolean isLikelyBinary(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
