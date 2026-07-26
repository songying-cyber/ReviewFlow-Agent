package com.paicli.github;

public record GitHubReviewFinding(
        String path,
        Integer line,
        String side,
        String body,
        String title,
        String severity,
        String suggestion
) {
    public GitHubReviewFinding(String path, Integer line, String side, String body) {
        this(path, line, side, body, null, null, null);
    }

    public static GitHubReviewFinding fileComment(String path, int line, String body) {
        return new GitHubReviewFinding(path, line, "RIGHT", body);
    }

    public static GitHubReviewFinding suggestion(String path, int line, String title, String body, String suggestion) {
        return new GitHubReviewFinding(path, line, "RIGHT", body, title, "suggestion", suggestion);
    }
}
