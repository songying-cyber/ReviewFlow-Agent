package com.paicli.github;

public record GitHubReviewResult(
        long id,
        String nodeId,
        String state,
        String htmlUrl,
        String body
) {
}
