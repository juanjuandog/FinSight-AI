package com.finsight.workflow;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("rabbitmq")
public class RabbitWorkflowListener {
    private final WorkflowOrchestrator orchestrator;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskPublisher taskPublisher;

    public RabbitWorkflowListener(
            WorkflowOrchestrator orchestrator,
            WorkflowTaskRepository taskRepository,
            WorkflowTaskPublisher taskPublisher
    ) {
        this.orchestrator = orchestrator;
        this.taskRepository = taskRepository;
        this.taskPublisher = taskPublisher;
    }

    @RabbitListener(queues = "${finsight.workflow.ingestion-queue}")
    public void consume(WorkflowMessage message) {
        try {
            orchestrator.execute(message.taskId());
        } catch (RuntimeException ex) {
            WorkflowTask failed = taskRepository.findById(message.taskId()).orElse(null);
            if (failed != null && failed.status() == WorkflowStatus.FAILED) {
                WorkflowTask retrying = taskRepository.saveIfOwned(
                        failed.retrying(),
                        WorkflowStatus.FAILED,
                        null
                ).orElse(null);
                if (retrying != null) {
                    taskPublisher.publishRetry(retrying);
                    return;
                }
            }
            throw ex;
        }
    }
}
