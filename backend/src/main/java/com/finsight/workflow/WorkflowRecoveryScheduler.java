package com.finsight.workflow;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class WorkflowRecoveryScheduler {
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskPublisher taskPublisher;
    private final MeterRegistry meterRegistry;
    private final Duration runningTimeout;

    public WorkflowRecoveryScheduler(
            WorkflowTaskRepository taskRepository,
            WorkflowTaskPublisher taskPublisher,
            MeterRegistry meterRegistry,
            @Value("${finsight.workflow.running-timeout:PT10M}") Duration runningTimeout
    ) {
        this.taskRepository = taskRepository;
        this.taskPublisher = taskPublisher;
        this.meterRegistry = meterRegistry;
        this.runningTimeout = runningTimeout;
    }

    @Scheduled(fixedDelayString = "${finsight.workflow.recovery-delay-ms:60000}")
    public void recoverTimedOutTasks() {
        List<WorkflowTask> timedOut = taskRepository.findByStatusUpdatedBefore(
                WorkflowStatus.RUNNING,
                Instant.now().minus(runningTimeout)
        );
        for (WorkflowTask task : timedOut) {
            WorkflowTask recovered = taskRepository.saveIfOwned(
                    task.recoveredAfterTimeout("Workflow timed out at stage " + task.stage()),
                    WorkflowStatus.RUNNING,
                    task.fencingToken()
            ).orElse(null);
            if (recovered == null) {
                continue;
            }
            meterRegistry.counter(
                    "finsight.workflow.recovery.total",
                    "taskType", task.taskType(),
                    "stage", task.stage().name()
            ).increment();
            if (recovered.status() == WorkflowStatus.FAILED) {
                WorkflowTask retrying = taskRepository.saveIfOwned(
                        recovered.retrying(),
                        WorkflowStatus.FAILED,
                        null
                ).orElse(null);
                if (retrying != null) {
                    taskPublisher.publish(retrying);
                }
            }
        }
    }
}
