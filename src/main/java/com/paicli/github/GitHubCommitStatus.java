package com.paicli.github;

public record GitHubCommitStatus(
        String context,
        String state,
        String description,
        String targetUrl
) {
}
