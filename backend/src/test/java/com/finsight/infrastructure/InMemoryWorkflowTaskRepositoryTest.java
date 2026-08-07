package com.finsight.infrastructure;

import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorkflowTaskRepositoryTest {

    @Test
    void staleWorkerCannotOverwriteRecoveredTask() {
        InMemoryWorkflowTaskRepository repository = new InMemoryWorkflowTaskRepository();
        WorkflowTask created = repository.createIfAbsent(
                WorkflowTask.created("analysis", "analysis:600519", Map.of())
        );
        WorkflowLease lease = new WorkflowLease(
                created.idempotencyKey(),
                "worker-a",
                7,
                Instant.now().plusSeconds(30)
        );
        WorkflowTask running = created.running(AgentWorkflowStage.AI_ANALYZING, lease);
        assertThat(repository.saveIfOwned(running, WorkflowStatus.CREATED, null)).isPresent();

        WorkflowTask recovered = running.recoveredAfterTimeout("timed out");
        assertThat(repository.saveIfOwned(recovered, WorkflowStatus.RUNNING, 7L)).isPresent();

        assertThat(repository.saveIfOwned(running.succeeded(), WorkflowStatus.RUNNING, 7L)).isEmpty();
        assertThat(repository.findById(created.id()).orElseThrow().status()).isEqualTo(WorkflowStatus.FAILED);
    }

    @Test
    void wrongFencingTokenCannotCompleteTask() {
        InMemoryWorkflowTaskRepository repository = new InMemoryWorkflowTaskRepository();
        WorkflowTask created = repository.createIfAbsent(
                WorkflowTask.created("analysis", "analysis:000001", Map.of())
        );
        WorkflowTask running = created.running(
                AgentWorkflowStage.AI_ANALYZING,
                new WorkflowLease(created.idempotencyKey(), "worker-a", 9, Instant.now().plusSeconds(30))
        );
        repository.saveIfOwned(running, WorkflowStatus.CREATED, null);

        assertThat(repository.saveIfOwned(running.succeeded(), WorkflowStatus.RUNNING, 8L)).isEmpty();
        assertThat(repository.findById(created.id()).orElseThrow().status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    void findsTheFullTaskChainForTheSubmittedResearchTask() {
        InMemoryWorkflowTaskRepository repository = new InMemoryWorkflowTaskRepository();
        WorkflowTask root = repository.createIfAbsent(
                WorkflowTask.created("FINANCIAL_DATA_INGESTION", "ingestion:600519", Map.of("companySymbol", "600519"))
        );
        WorkflowTask analysis = repository.createIfAbsent(
                WorkflowTask.created("STOCK_AI_ANALYSIS", "analysis:600519", Map.of(
                        "companySymbol", "600519",
                        "rootTaskId", root.id()
                ))
        );
        repository.createIfAbsent(
                WorkflowTask.created("STOCK_AI_ANALYSIS", "analysis:000001", Map.of("rootTaskId", "another-task"))
        );

        assertThat(repository.findByRootTaskId(root.id()))
                .extracting(WorkflowTask::id)
                .containsExactlyInAnyOrder(root.id(), analysis.id());
    }
}
