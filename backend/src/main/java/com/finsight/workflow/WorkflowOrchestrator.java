package com.finsight.workflow;

import com.finsight.application.MetricApplicationService;
import com.finsight.application.DocumentIndexingService;
import com.finsight.application.CompanyIntelligenceService;
import com.finsight.application.StockAiAnalysisService;
import com.finsight.domain.FinancialDataIngestionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

@Service
public class WorkflowOrchestrator {
    private final WorkflowTaskRepository taskRepository;
    private final FinancialDataIngestionTemplate dataSource;
    private final MetricApplicationService metricApplicationService;
    private final DocumentIndexingService documentIndexingService;
    private final CompanyIntelligenceService companyIntelligenceService;
    private final StockAiAnalysisService stockAiAnalysisService;
    private final WorkflowLeaseService leaseService;
    private final ObjectProvider<WorkflowTaskPublisher> taskPublisher;
    private final MeterRegistry meterRegistry;
    private final Duration leaseTtl = Duration.ofMinutes(5);
    private final ScheduledExecutorService leaseHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workflow-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public WorkflowOrchestrator(
            WorkflowTaskRepository taskRepository,
            FinancialDataIngestionTemplate dataSource,
            MetricApplicationService metricApplicationService,
            DocumentIndexingService documentIndexingService,
            CompanyIntelligenceService companyIntelligenceService,
            StockAiAnalysisService stockAiAnalysisService,
            WorkflowLeaseService leaseService,
            ObjectProvider<WorkflowTaskPublisher> taskPublisher,
            MeterRegistry meterRegistry
    ) {
        this.taskRepository = taskRepository;
        this.dataSource = dataSource;
        this.metricApplicationService = metricApplicationService;
        this.documentIndexingService = documentIndexingService;
        this.companyIntelligenceService = companyIntelligenceService;
        this.stockAiAnalysisService = stockAiAnalysisService;
        this.leaseService = leaseService;
        this.taskPublisher = taskPublisher;
        this.meterRegistry = meterRegistry;
    }

    public void execute(String taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow task not found: " + taskId));
        if (task.status() == WorkflowStatus.SUCCEEDED) {
            return;
        }
        Optional<WorkflowLease> lease = leaseService.tryAcquire(task.idempotencyKey(), leaseTtl);
        if (lease.isEmpty()) {
            taskRepository.save(task.waitingForLease());
            recordWorkflowMetric(task.taskType(), "lease_wait", Timer.start(meterRegistry));
            return;
        }
        AtomicReference<WorkflowLease> activeLease = new AtomicReference<>(lease.get());
        ScheduledFuture<?> heartbeat = startLeaseHeartbeat(task, activeLease);
        try {
            executeWithLease(task, activeLease.get());
        } finally {
            heartbeat.cancel(false);
            leaseService.release(activeLease.get());
        }
    }

    private ScheduledFuture<?> startLeaseHeartbeat(
            WorkflowTask task,
            AtomicReference<WorkflowLease> activeLease
    ) {
        long intervalMillis = Math.max(1000, leaseTtl.toMillis() / 3);
        return leaseHeartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                Optional<WorkflowLease> renewed = leaseService.renew(activeLease.get(), leaseTtl);
                if (renewed.isPresent()) {
                    activeLease.set(renewed.get());
                    meterRegistry.counter(
                            "finsight.workflow.lease.renewal.total",
                            "taskType", task.taskType(),
                            "result", "renewed"
                    ).increment();
                } else {
                    meterRegistry.counter(
                            "finsight.workflow.lease.renewal.total",
                            "taskType", task.taskType(),
                            "result", "ownership_lost"
                    ).increment();
                }
            } catch (RuntimeException ex) {
                meterRegistry.counter(
                        "finsight.workflow.lease.renewal.total",
                        "taskType", task.taskType(),
                        "result", "failed"
                ).increment();
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdownLeaseHeartbeatExecutor() {
        leaseHeartbeatExecutor.shutdownNow();
    }

    private void executeWithLease(WorkflowTask task, WorkflowLease lease) {
        switch (task.taskType()) {
            case WorkflowTaskType.FINANCIAL_DATA_INGESTION -> executeIngestion(task, lease);
            case WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION -> executeTask(task, lease, AgentWorkflowStage.METRIC_CALCULATING,
                    () -> metricApplicationService.recalculate(stringPayload(task.payload(), "companySymbol")));
            case WorkflowTaskType.DOCUMENT_INDEX_BUILD -> executeTask(task, lease, AgentWorkflowStage.DOCUMENT_INDEXING,
                    () -> documentIndexingService.indexCompany(stringPayload(task.payload(), "companySymbol")));
            case WorkflowTaskType.COMPANY_INTELLIGENCE_BUILD -> executeTask(task, lease, AgentWorkflowStage.INTELLIGENCE_BUILDING,
                    () -> companyIntelligenceService.rebuild(stringPayload(task.payload(), "companySymbol")));
            case WorkflowTaskType.STOCK_AI_ANALYSIS -> executeTask(task, lease, AgentWorkflowStage.AI_ANALYZING,
                    () -> stockAiAnalysisService.analyze(stringPayload(task.payload(), "companySymbol")));
            default -> throw new IllegalArgumentException("Unsupported workflow task type: " + task.taskType());
        }
    }

    private void executeIngestion(WorkflowTask task, WorkflowLease lease) {
        WorkflowTask runningTask = task.running(AgentWorkflowStage.INGESTING_DATA, lease);
        if (taskRepository.saveIfOwned(runningTask, task.status(), task.fencingToken()).isEmpty()) {
            return;
        }
        dataSource.executeIngestionTask(runningTask);
        String companySymbol = stringPayload(task.payload(), "companySymbol");
        publishNext(task, companySymbol);
    }

    private WorkflowTask createOrReuseTask(String taskType, String idempotencyKey, Map<String, Object> payload) {
        return taskRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> taskRepository.createIfAbsent(WorkflowTask.created(taskType, idempotencyKey, payload)));
    }

    private void executeTask(WorkflowTask task, WorkflowLease lease, AgentWorkflowStage stage, Runnable runnable) {
        WorkflowTask runningTask = task.running(stage, lease);
        Timer.Sample sample = Timer.start(meterRegistry);
        if (taskRepository.saveIfOwned(runningTask, task.status(), task.fencingToken()).isEmpty()) {
            recordWorkflowMetric(task.taskType(), "ownership_lost", sample);
            return;
        }
        try {
            runnable.run();
            boolean saved = taskRepository.saveIfOwned(
                    runningTask.succeeded(),
                    WorkflowStatus.RUNNING,
                    lease.fencingToken()
            ).isPresent();
            recordWorkflowMetric(task.taskType(), saved ? "succeeded" : "ownership_lost", sample);
            if (saved) {
                publishNext(task, stringPayload(task.payload(), "companySymbol"));
            }
        } catch (RuntimeException ex) {
            boolean saved = taskRepository.saveIfOwned(
                    runningTask.failed(ex.getMessage()),
                    WorkflowStatus.RUNNING,
                    lease.fencingToken()
            ).isPresent();
            recordWorkflowMetric(task.taskType(), saved ? "failed" : "ownership_lost", sample);
            throw ex;
        }
    }

    private void publishNext(WorkflowTask completedTask, String companySymbol) {
        String nextType = switch (completedTask.taskType()) {
            case WorkflowTaskType.FINANCIAL_DATA_INGESTION -> WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION;
            case WorkflowTaskType.FINANCIAL_METRIC_RECALCULATION -> WorkflowTaskType.DOCUMENT_INDEX_BUILD;
            case WorkflowTaskType.DOCUMENT_INDEX_BUILD -> WorkflowTaskType.COMPANY_INTELLIGENCE_BUILD;
            case WorkflowTaskType.COMPANY_INTELLIGENCE_BUILD -> WorkflowTaskType.STOCK_AI_ANALYSIS;
            default -> null;
        };
        if (nextType == null) {
            return;
        }
        String rootTaskId = String.valueOf(completedTask.payload().getOrDefault("rootTaskId", completedTask.id()));
        WorkflowTask next = createOrReuseTask(
                nextType,
                nextType + ":" + companySymbol + ":" + rootTaskId,
                Map.of(
                        "companySymbol", companySymbol,
                        "parentTaskId", completedTask.id(),
                        "rootTaskId", rootTaskId
                )
        );
        if (next.status() == WorkflowStatus.CREATED || next.status() == WorkflowStatus.RETRYING) {
            taskPublisher.getObject().publish(next);
        }
    }

    private void recordWorkflowMetric(String taskType, String result, Timer.Sample sample) {
        sample.stop(Timer.builder("finsight.workflow.task.duration")
                .description("Workflow task execution duration")
                .tag("taskType", taskType)
                .tag("result", result)
                .register(meterRegistry));
        meterRegistry.counter("finsight.workflow.task.total", "taskType", taskType, "result", result).increment();
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing workflow payload key: " + key);
        }
        return String.valueOf(value);
    }
}
