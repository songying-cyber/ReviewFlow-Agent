package com.paicli.github;

public record GitHubCheckRun(
        long id,
        String name,
        String status,
        String conclusion,
        String detailsUrl,
        String htmlUrl,
        String startedAt,
        String completedAt
) {
}
