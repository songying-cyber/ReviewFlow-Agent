package com.paicli.github;

import java.util.List;

public record GitHubReviewRequest(
        String commitId,
        String event,
        String body,
        List<GitHubReviewFinding> comments
) {
    public GitHubReviewRequest {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    public static GitHubReviewRequest comment(String commitId, String body, List<GitHubReviewFinding> comments) {
        return new GitHubReviewRequest(commitId, "COMMENT", body, comments);
    }
}
