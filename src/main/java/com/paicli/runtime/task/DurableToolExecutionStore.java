package com.paicli.runtime.task;

import java.util.List;
import java.util.Optional;

public interface DurableToolExecutionStore {
    Optional<DurableToolExecution> findToolExecutionById(String id);

    Optional<DurableToolExecution> findToolExecutionByKey(String idempotencyKey);

    void recordToolRunning(DurableToolExecution execution);

    void recordToolFinished(String idempotencyKey, String status, String result, String error,
                            String beforeSnapshotId, String afterSnapshotId,
                            String beforeHashJson, String afterHashJson,
                            String compensationJson);

    List<DurableToolExecution> succeededReversibleTools(String workflowId);

    void markToolCompensated(String executionId);
}
