package com.paicli.github;

import java.io.IOException;
import java.util.List;

public class GitHubPrReviewService {
    private final GitHubPrClient client;
    private final GitHubReviewPreparer reviewPreparer;

    public GitHubPrReviewService(GitHubPrClient client) {
        this.client = client;
        this.reviewPreparer = new GitHubReviewPreparer();
    }

    public GitHubPrSnapshot loadReviewContext(GitHubPrReference ref) throws IOException {
        return client.fetchSnapshot(ref);
    }

    public GitHubReviewResult publishReview(GitHubPrReference ref, GitHubReviewRequest review) throws IOException {
        return client.publishReview(ref, review);
    }

    public GitHubPreparedReview prepareReview(GitHubPrSnapshot snapshot, String body,
                                              List<GitHubReviewFinding> findings) {
        return reviewPreparer.prepare(snapshot, "COMMENT", body, findings);
    }

    public GitHubReviewResult publishPreparedReview(GitHubPrReference ref, GitHubPreparedReview review)
            throws IOException {
        return client.publishReview(ref, review.request());
    }
}
