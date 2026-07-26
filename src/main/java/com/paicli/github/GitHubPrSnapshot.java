package com.paicli.github;

import java.util.List;
import java.util.stream.Collectors;

public record GitHubPrSnapshot(
        GitHubPullRequest pullRequest,
        String diff,
        List<GitHubChangedFile> changedFiles,
        List<GitHubReviewComment> reviewComments,
        GitHubCiStatus ciStatus
) {
    public GitHubDiffLineMap diffLineMap() {
        return GitHubDiffLineMap.fromChangedFiles(changedFiles);
    }

    public List<GitHubReviewComment> outdatedReviewComments() {
        GitHubDiffLineMap lineMap = diffLineMap();
        return reviewComments == null ? List.of() : reviewComments.stream()
                .filter(comment -> comment != null
                        && (comment.isOutdated()
                        || !lineMap.contains(comment.path(), comment.line(), comment.side())))
                .collect(Collectors.toList());
    }
}
