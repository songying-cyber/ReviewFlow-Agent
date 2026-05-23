package com.paicli.snapshot;

import java.time.Instant;

public record TurnSnapshot(
        String commitId,
        SnapshotPhase phase,
        String turnId,
        Instant createdAt,
        String summary
) {
    public String shortCommitId() {
        return commitId == null || commitId.length() <= 10 ? commitId : commitId.substring(0, 10);
    }
}
