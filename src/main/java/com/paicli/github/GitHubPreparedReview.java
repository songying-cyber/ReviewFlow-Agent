package com.paicli.github;

import java.util.List;

public record GitHubPreparedReview(
        GitHubReviewRequest request,
        List<GitHubSkippedReviewFinding> skippedFindings
) {
    public GitHubPreparedReview {
        skippedFindings = skippedFindings == null ? List.of() : List.copyOf(skippedFindings);
    }

    public boolean hasSkippedFindings() {
        return !skippedFindings.isEmpty();
    }
}
