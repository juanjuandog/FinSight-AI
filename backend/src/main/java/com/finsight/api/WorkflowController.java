package com.finsight.api;

import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskPublisher;
import com.finsight.workflow.WorkflowTaskRepository;
import com.finsight.workflow.WorkflowStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskPublisher taskPublisher;

    public WorkflowController(WorkflowTaskRepository taskRepository, WorkflowTaskPublisher taskPublisher) {
        this.taskRepository = taskRepository;
        this.taskPublisher = taskPublisher;
    }

    @GetMapping
    public List<WorkflowTask> tasks() {
        return taskRepository.findAll();
    }

    @GetMapping("/summary")
    public WorkflowSummary summary() {
        List<WorkflowTask> tasks = taskRepository.findAll();
        Map<WorkflowStatus, Long> counts = new EnumMap<>(WorkflowStatus.class);
        Map<AgentWorkflowStage, Long> stageCounts = new EnumMap<>(AgentWorkflowStage.class);
        for (WorkflowStatus status : WorkflowStatus.values()) {
            counts.put(status, 0L);
        }
        for (AgentWorkflowStage stage : AgentWorkflowStage.values()) {
            stageCounts.put(stage, 0L);
        }
        for (WorkflowTask task : tasks) {
            counts.computeIfPresent(task.status(), (ignored, count) -> count + 1);
            stageCounts.computeIfPresent(task.stage(), (ignored, count) -> count + 1);
        }
        long failed = counts.get(WorkflowStatus.FAILED) + counts.get(WorkflowStatus.DEAD_LETTER);
        return new WorkflowSummary(
                tasks.size(),
                counts,
                stageCounts,
                failed,
                tasks.stream().map(WorkflowTask::createdAt).max(Instant::compareTo).orElse(null)
        );
    }

    @GetMapping("/{taskId}")
    public WorkflowTask task(@PathVariable String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow task not found"));
    }

    @PostMapping("/{taskId}/retry")
    public WorkflowRetryResult retry(@PathVariable String taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow task not found"));
        if (!task.retryable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED or DEAD_LETTER workflow tasks can be retried");
        }
        WorkflowTask retrying = taskRepository.saveIfOwned(
                task.retrying(),
                task.status(),
                task.fencingToken()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Workflow task changed while retry was requested"
        ));
        taskPublisher.publish(retrying);
        WorkflowTask current = taskRepository.findById(taskId).orElse(retrying);
        return new WorkflowRetryResult(
                current.id(),
                current.status(),
                current.attempts(),
                Duration.between(current.createdAt(), Instant.now()).toMillis()
        );
    }

    public record WorkflowSummary(
            int total,
            Map<WorkflowStatus, Long> counts,
            Map<AgentWorkflowStage, Long> stageCounts,
            long failedOrDeadLetter,
            Instant latestCreatedAt
    ) {
    }

    public record WorkflowRetryResult(
            String taskId,
            WorkflowStatus status,
            int attempts,
            long ageMillis
    ) {
    }
}
