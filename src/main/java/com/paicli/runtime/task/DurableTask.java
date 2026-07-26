package com.paicli.runtime.task;

import java.time.Instant;

public record DurableTask(
        String id,
        TaskStatus status,
        String prompt,
        String result,
        String error,
        String projectPath,
        String currentNodeId,
        String workflowVersion,
        int attempt,
        String ownerId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        Instant leaseUntil,
        long durationMs
) {
    public boolean terminal() {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELED
                || status == TaskStatus.COMPENSATED;
    }

    public boolean runnable() {
        return status == TaskStatus.ENQUEUED;
    }

    public String shortPrompt() {
        if (prompt == null) {
            return "";
        }
        String normalized = prompt.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ').trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }
}
