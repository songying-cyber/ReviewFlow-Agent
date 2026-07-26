package com.paicli.github;

import java.util.List;

public record GitHubCiStatus(
        String combinedState,
        List<GitHubCommitStatus> statuses,
        List<GitHubCheckRun> checkRuns
) {
}
