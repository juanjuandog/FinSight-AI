package com.finsight.infrastructure;

import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!postgres")
public class InMemoryWorkflowTaskRepository implements WorkflowTaskRepository {
    private final ConcurrentHashMap<String, WorkflowTask> tasks = new ConcurrentHashMap<>();

    @Override
    public WorkflowTask save(WorkflowTask task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<WorkflowTask> saveIfOwned(
            WorkflowTask task,
            WorkflowStatus expectedStatus,
            Long expectedFencingToken
    ) {
        WorkflowTask updated = tasks.computeIfPresent(task.id(), (ignored, current) ->
                current.status() == expectedStatus
                        && java.util.Objects.equals(current.fencingToken(), expectedFencingToken)
                        ? task
                        : current
        );
        return task.equals(updated) ? Optional.of(task) : Optional.empty();
    }

    @Override
    public synchronized WorkflowTask createIfAbsent(WorkflowTask task) {
        return findByIdempotencyKey(task.idempotencyKey())
                .orElseGet(() -> save(task));
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return tasks.values().stream().anyMatch(task -> task.idempotencyKey().equals(idempotencyKey));
    }

    @Override
    public Optional<WorkflowTask> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public Optional<WorkflowTask> findByIdempotencyKey(String idempotencyKey) {
        return tasks.values().stream()
                .filter(task -> task.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public List<WorkflowTask> findAll() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public List<WorkflowTask> findByStatusUpdatedBefore(WorkflowStatus status, Instant cutoff) {
        return tasks.values().stream()
                .filter(task -> task.status() == status)
                .filter(task -> task.updatedAt().isBefore(cutoff))
                .toList();
    }
}
