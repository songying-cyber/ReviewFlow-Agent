package com.paicli.github;

public record GitHubSkippedReviewFinding(
        GitHubReviewFinding finding,
        String reason
) {
}
