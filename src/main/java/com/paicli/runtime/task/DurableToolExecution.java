package com.paicli.runtime.task;

import java.time.Instant;

public record DurableToolExecution(
        String id,
        String workflowId,
        String nodeId,
        String toolCallId,
        String idempotencyKey,
        String toolName,
        String argsJson,
        String status,
        String result,
        String error,
        String sideEffectLevel,
        String beforeSnapshotId,
        String afterSnapshotId,
        String beforeHashJson,
        String afterHashJson,
        String compensationJson,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean succeeded() {
        return "succeeded".equalsIgnoreCase(status);
    }

    public boolean reversible() {
        return compensationJson != null && !compensationJson.isBlank();
    }
}
