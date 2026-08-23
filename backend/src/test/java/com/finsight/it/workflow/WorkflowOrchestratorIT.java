package com.finsight.it.workflow;

import com.finsight.application.CompanyIntelligenceService;
import com.finsight.application.DocumentIndexingService;
import com.finsight.application.MetricApplicationService;
import com.finsight.application.StockAiAnalysisService;
import com.finsight.domain.FinancialDataIngestionTemplate;
import com.finsight.it.AbstractPostgresRedisIT;
import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import com.finsight.workflow.WorkflowOrchestrator;
import com.finsight.workflow.WorkflowRecoveryScheduler;
import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskPublisher;
import com.finsight.workflow.WorkflowTaskRepository;
import com.finsight.workflow.WorkflowTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class WorkflowOrchestratorIT extends AbstractPostgresRedisIT {
    @Autowired
    WorkflowOrchestrator orchestrator;

    @Autowired
    WorkflowRecoveryScheduler recoveryScheduler;

    @Autowired
    WorkflowTaskRepository taskRepository;

    @Autowired
    WorkflowLeaseService leaseService;

    @MockBean
    FinancialDataIngestionTemplate dataSource;

    @MockBean
    MetricApplicationService metricApplicationService;

    @MockBean
    DocumentIndexingService documentIndexingService;

    @MockBean
    CompanyIntelligenceService companyIntelligenceService;

    @MockBean
    StockAiAnalysisService stockAiAnalysisService;

    @MockBean
    WorkflowTaskPublisher taskPublisher;

    @Test
    void progressesMetricTaskToSuccessAndPublishesNextStage() {
        WorkflowTask task = createTask(WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION, "metric:600519");

        orchestrator.execute(task.id());

        WorkflowTask saved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(WorkflowStatus.SUCCEEDED);
        assertThat(saved.stage()).isEqualTo(AgentWorkflowStage.SUCCEEDED);
        assertThat(saved.fencingToken()).isNull();
        verify(metricApplicationService).recalculate("600519");
        verify(taskPublisher).publish(any(WorkflowTask.class));
        assertThat(taskRepository.findByRootTaskId(task.id())).hasSize(2);
    }

    @Test
    void recordsFailureWithoutPublishingNextStage() {
        WorkflowTask task = createTask(WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION, "metric:failure");
        doThrow(new IllegalStateException("calculation failed"))
                .when(metricApplicationService).recalculate("600519");

        assertThatThrownBy(() -> orchestrator.execute(task.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("calculation failed");

        WorkflowTask failed = taskRepository.findById(task.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.stage()).isEqualTo(AgentWorkflowStage.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("calculation failed");
    }

    @Test
    void activeRedisLeaseMovesContendingTaskToLeaseWait() {
        WorkflowTask task = createTask(WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION, "metric:contended");
        WorkflowLease competing = leaseService.tryAcquire(task.idempotencyKey(), Duration.ofSeconds(30)).orElseThrow();
        try {
            orchestrator.execute(task.id());
        } finally {
            leaseService.release(competing);
        }

        WorkflowTask waiting = taskRepository.findById(task.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(WorkflowStatus.CREATED);
        assertThat(waiting.stage()).isEqualTo(AgentWorkflowStage.LEASE_WAIT);
    }

    @Test
    void recoverySchedulerRequeuesTimedOutOwnedTask() {
        Instant old = Instant.now().minus(Duration.ofHours(1));
        WorkflowTask running = new WorkflowTask(
                "timed-out-task",
                WorkflowTaskType.DOCUMENT_INDEX_BUILD,
                "document:timeout",
                WorkflowStatus.RUNNING,
                AgentWorkflowStage.DOCUMENT_INDEXING,
                1,
                old,
                old,
                Map.of("companySymbol", "600519"),
                null,
                "worker-a",
                9L
        );
        taskRepository.save(running);

        recoveryScheduler.recoverTimedOutTasks();

        WorkflowTask recovered = taskRepository.findById(running.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(WorkflowStatus.RETRYING);
        assertThat(recovered.stage()).isEqualTo(AgentWorkflowStage.RECOVERING);
        assertThat(recovered.leaseOwner()).isNull();
        verify(taskPublisher).publish(argThat(published ->
                published.id().equals(recovered.id())
                        && published.status() == WorkflowStatus.RETRYING
                        && published.stage() == AgentWorkflowStage.RECOVERING
                        && published.fencingToken() == null
        ));
    }

    private WorkflowTask createTask(String type, String idempotencyKey) {
        return taskRepository.createIfAbsent(WorkflowTask.created(
                type,
                idempotencyKey,
                Map.of("companySymbol", "600519")
        ));
    }
}
