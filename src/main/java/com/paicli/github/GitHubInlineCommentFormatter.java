package com.paicli.github;

public class GitHubInlineCommentFormatter {
    private static final int MAX_COMMENT_CHARS = 60_000;

    public String format(GitHubReviewFinding finding) {
        if (finding == null) {
            return "";
        }
        String body = normalize(finding.body());
        if (body.isBlank()) {
            body = "Review note.";
        }
        if (!isBlank(finding.title())) {
            body = "**" + normalize(finding.title()) + "**\n\n" + body;
        }
        if (!isBlank(finding.severity())) {
            body = "`" + normalize(finding.severity()) + "` " + body;
        }
        if (!isBlank(finding.suggestion())) {
            body = body + "\n\n```suggestion\n" + trimTrailing(finding.suggestion()) + "\n```";
        }
        return truncate(body);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String trimTrailing(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int end = normalized.length();
        while (end > 0 && Character.isWhitespace(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    private static String truncate(String body) {
        if (body.length() <= MAX_COMMENT_CHARS) {
            return body;
        }
        return body.substring(0, MAX_COMMENT_CHARS) + "\n\n[paicli: comment truncated before publishing]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
