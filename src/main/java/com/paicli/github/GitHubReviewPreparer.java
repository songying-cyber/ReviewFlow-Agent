package com.paicli.github;

import java.util.ArrayList;
import java.util.List;

public class GitHubReviewPreparer {
    private final GitHubInlineCommentFormatter formatter;

    public GitHubReviewPreparer() {
        this(new GitHubInlineCommentFormatter());
    }

    GitHubReviewPreparer(GitHubInlineCommentFormatter formatter) {
        this.formatter = formatter;
    }

    public GitHubPreparedReview prepare(GitHubPrSnapshot snapshot, String event, String body,
                                        List<GitHubReviewFinding> findings) {
        if (snapshot == null || snapshot.pullRequest() == null) {
            throw new IllegalArgumentException("snapshot and pullRequest are required");
        }
        GitHubDiffLineMap lineMap = snapshot.diffLineMap();
        List<GitHubReviewFinding> comments = new ArrayList<>();
        List<GitHubSkippedReviewFinding> skipped = new ArrayList<>();
        if (findings != null) {
            for (GitHubReviewFinding finding : findings) {
                if (finding == null) {
                    continue;
                }
                if (isBlank(finding.path())) {
                    skipped.add(new GitHubSkippedReviewFinding(finding, "missing path"));
                    continue;
                }
                if (finding.line() == null || finding.line() <= 0) {
                    skipped.add(new GitHubSkippedReviewFinding(finding, "missing or invalid line"));
                    continue;
                }
                if (!lineMap.hasFile(finding.path())) {
                    skipped.add(new GitHubSkippedReviewFinding(finding, "file is not in the current PR diff"));
                    continue;
                }
                GitHubDiffPosition position = lineMap.resolve(finding).orElse(null);
                if (position == null) {
                    skipped.add(new GitHubSkippedReviewFinding(finding,
                            "line is not in the current PR diff or is outdated"));
                    continue;
                }
                comments.add(new GitHubReviewFinding(
                        position.path(),
                        position.line(),
                        position.side(),
                        formatter.format(finding),
                        null,
                        null,
                        null));
            }
        }
        GitHubReviewRequest request = new GitHubReviewRequest(
                snapshot.pullRequest().headSha(),
                isBlank(event) ? "COMMENT" : event,
                body == null ? "" : body,
                comments);
        return new GitHubPreparedReview(request, skipped);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
