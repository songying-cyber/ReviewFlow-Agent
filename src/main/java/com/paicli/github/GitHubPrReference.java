package com.paicli.github;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record GitHubPrReference(String owner, String repo, int number) {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^/]+/([^/]+)/([^/]+)/pull/(\\d+)(?:[/?#].*)?");
    private static final Pattern SHORT_PATTERN = Pattern.compile("([^/\\s]+)/([^#\\s]+)#(\\d+)");

    public static GitHubPrReference parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub PR 引用不能为空");
        }
        String trimmed = value.trim();
        Matcher url = URL_PATTERN.matcher(trimmed);
        if (url.matches()) {
            return new GitHubPrReference(url.group(1), stripGitSuffix(url.group(2)), Integer.parseInt(url.group(3)));
        }
        Matcher shortRef = SHORT_PATTERN.matcher(trimmed);
        if (shortRef.matches()) {
            return new GitHubPrReference(shortRef.group(1), stripGitSuffix(shortRef.group(2)),
                    Integer.parseInt(shortRef.group(3)));
        }
        throw new IllegalArgumentException("无法解析 GitHub PR 引用: " + value);
    }

    public String repoFullName() {
        return owner + "/" + repo;
    }

    private static String stripGitSuffix(String repo) {
        return repo != null && repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }
}
