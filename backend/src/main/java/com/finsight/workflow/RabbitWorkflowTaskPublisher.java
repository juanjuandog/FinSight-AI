package com.finsight.workflow;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("rabbitmq")
public class RabbitWorkflowTaskPublisher implements WorkflowTaskPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final RabbitWorkflowProperties properties;

    public RabbitWorkflowTaskPublisher(RabbitTemplate rabbitTemplate, RabbitWorkflowProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(WorkflowTask task) {
        publish(task, properties.ingestionRoutingKey());
    }

    @Override
    public void publishRetry(WorkflowTask task) {
        publish(task, properties.retryRoutingKey());
    }

    private void publish(WorkflowTask task, String routingKey) {
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(
                    properties.exchange(),
                    routingKey,
                    WorkflowMessage.from(task)
            );
            operations.waitForConfirmsOrDie(Duration.ofSeconds(5).toMillis());
            return null;
        });
    }
}
