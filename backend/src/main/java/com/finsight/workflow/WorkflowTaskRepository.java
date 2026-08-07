package com.finsight.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowTaskRepository {
    WorkflowTask save(WorkflowTask task);

    Optional<WorkflowTask> saveIfOwned(
            WorkflowTask task,
            WorkflowStatus expectedStatus,
            Long expectedFencingToken
    );

    WorkflowTask createIfAbsent(WorkflowTask task);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<WorkflowTask> findById(String id);

    Optional<WorkflowTask> findByIdempotencyKey(String idempotencyKey);

    List<WorkflowTask> findAll();

    List<WorkflowTask> findByRootTaskId(String rootTaskId);

    List<WorkflowTask> findByStatusUpdatedBefore(WorkflowStatus status, Instant cutoff);
}
