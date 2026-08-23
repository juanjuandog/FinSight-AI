package com.finsight.it.workflow;

import com.finsight.it.AbstractPostgresIT;
import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskRepository;
import com.finsight.workflow.WorkflowTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkflowTaskRepositoryIT extends AbstractPostgresIT {
    @Autowired
    WorkflowTaskRepository repository;

    @Test
    void savedTaskRoundTripsWithJsonPayload() {
        WorkflowTask task = task("task-1", "key-1", Instant.now(), Map.of("companySymbol", "600519"));

        repository.save(task);

        WorkflowTask saved = repository.findById("task-1").orElseThrow();
        assertThat(saved)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt")
                .isEqualTo(task);
        assertThat(saved.createdAt()).isEqualTo(task.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(saved.updatedAt()).isEqualTo(task.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void taskCanBeFoundByIdempotencyKey() {
        WorkflowTask task = task("task-1", "key-1", Instant.now(), Map.of());
        repository.save(task);

        assertThat(repository.findByIdempotencyKey("key-1"))
                .get()
                .extracting(WorkflowTask::id, WorkflowTask::idempotencyKey)
                .containsExactly("task-1", "key-1");
    }

    @Test
    void existsByIdempotencyKeyUsesDatabaseConstraint() {
        repository.save(task("task-1", "key-1", Instant.now(), Map.of()));

        assertThat(repository.existsByIdempotencyKey("key-1")).isTrue();
        assertThat(repository.existsByIdempotencyKey("missing")).isFalse();
    }

    @Test
    void createIfAbsentReturnsExistingTaskForDuplicateKey() {
        WorkflowTask existing = task("task-1", "same-key", Instant.now(), Map.of("value", "first"));
        WorkflowTask duplicate = task("task-2", "same-key", Instant.now(), Map.of("value", "second"));
        repository.save(existing);

        WorkflowTask returned = repository.createIfAbsent(duplicate);

        assertThat(returned.id()).isEqualTo("task-1");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void saveIfOwnedTransitionsTaskWithExpectedStatusAndFence() {
        WorkflowTask created = task("task-1", "key-1", Instant.now(), Map.of());
        repository.save(created);
        WorkflowLease lease = new WorkflowLease("key-1", "owner-a", 10, Instant.now().plusSeconds(30));

        WorkflowTask running = created.running(AgentWorkflowStage.AI_ANALYZING, lease);

        assertThat(repository.saveIfOwned(running, WorkflowStatus.CREATED, null))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(WorkflowStatus.RUNNING);
                    assertThat(saved.stage()).isEqualTo(AgentWorkflowStage.AI_ANALYZING);
                    assertThat(saved.fencingToken()).isEqualTo(10);
                });
        assertThat(repository.findById("task-1").orElseThrow().fencingToken()).isEqualTo(10);
    }

    @Test
    void saveIfOwnedRejectsUnexpectedStatus() {
        WorkflowTask created = task("task-1", "key-1", Instant.now(), Map.of());
        repository.save(created);

        assertThat(repository.saveIfOwned(created.succeeded(), WorkflowStatus.RUNNING, null)).isEmpty();
        assertThat(repository.findById("task-1").orElseThrow().status()).isEqualTo(WorkflowStatus.CREATED);
    }

    @Test
    void saveIfOwnedRejectsStaleFencingToken() {
        WorkflowTask created = task("task-1", "key-1", Instant.now(), Map.of());
        WorkflowLease lease = new WorkflowLease("key-1", "owner-a", 10, Instant.now().plusSeconds(30));
        WorkflowTask running = created.running(AgentWorkflowStage.AI_ANALYZING, lease);
        repository.save(running);

        assertThat(repository.saveIfOwned(running.succeeded(), WorkflowStatus.RUNNING, 9L)).isEmpty();
        assertThat(repository.findById("task-1").orElseThrow().status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    void findByRootTaskIdReturnsRootAndChildren() {
        Instant now = Instant.now();
        repository.save(task("root", "root-key", now.minusSeconds(2), Map.of()));
        repository.save(task("child-1", "child-key-1", now.minusSeconds(1), Map.of("rootTaskId", "root")));
        repository.save(task("child-2", "child-key-2", now, Map.of("rootTaskId", "root")));
        repository.save(task("other", "other-key", now, Map.of("rootTaskId", "other-root")));

        assertThat(repository.findByRootTaskId("root"))
                .extracting(WorkflowTask::id)
                .containsExactly("root", "child-1", "child-2");
    }

    @Test
    void findByStatusUpdatedBeforeReturnsTimedOutTasksOnly() {
        Instant now = Instant.now();
        repository.save(task("old", "old-key", now.minusSeconds(120), Map.of()));
        repository.save(task("new", "new-key", now, Map.of()));

        assertThat(repository.findByStatusUpdatedBefore(WorkflowStatus.CREATED, now.minusSeconds(60)))
                .extracting(WorkflowTask::id)
                .containsExactly("old");
    }

    @Test
    void findAllUsesNewestFirstOrdering() {
        Instant now = Instant.now();
        repository.save(task("old", "old-key", now.minusSeconds(10), Map.of()));
        repository.save(task("new", "new-key", now, Map.of()));

        assertThat(repository.findAll()).extracting(WorkflowTask::id)
                .containsExactly("new", "old");
    }

    private WorkflowTask task(String id, String key, Instant timestamp, Map<String, Object> payload) {
        Instant databaseTimestamp = timestamp.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new WorkflowTask(
                id,
                WorkflowTaskType.STOCK_AI_ANALYSIS,
                key,
                WorkflowStatus.CREATED,
                AgentWorkflowStage.CREATED,
                0,
                databaseTimestamp,
                databaseTimestamp,
                payload,
                null,
                null,
                null
        );
    }
}
