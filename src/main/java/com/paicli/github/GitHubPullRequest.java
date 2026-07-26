package com.paicli.github;

public record GitHubPullRequest(
        String id,
        String nodeId,
        String owner,
        String repo,
        int number,
        String title,
        String body,
        String state,
        String htmlUrl,
        String baseRef,
        String baseSha,
        String headRef,
        String headSha,
        String headRepoFullName,
        String author
) {
}
