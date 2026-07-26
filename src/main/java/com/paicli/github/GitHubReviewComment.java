package com.paicli.github;

public record GitHubReviewComment(
        long id,
        String nodeId,
        String path,
        Integer line,
        Integer originalLine,
        String side,
        String body,
        String author,
        String state,
        String diffHunk,
        String createdAt,
        String updatedAt,
        Integer position,
        Integer originalPosition,
        String commitId,
        String originalCommitId
) {
    public boolean isOutdated() {
        if (line == null) {
            return originalLine != null || originalPosition != null;
        }
        if (position == null) {
            return originalPosition != null;
        }
        return originalCommitId != null
                && commitId != null
                && !originalCommitId.isBlank()
                && !commitId.isBlank()
                && !originalCommitId.equals(commitId);
    }
}
