package com.finsight.it.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.it.AbstractRabbitIT;
import com.finsight.workflow.RabbitWorkflowTaskPublisher;
import com.finsight.workflow.WorkflowTask;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitWorkflowMessagingIT extends AbstractRabbitIT {
    @Autowired
    RabbitWorkflowTaskPublisher publisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void declaresWorkflowTopologyOnTheBroker() {
        assertThat(rabbitAdmin.getQueueProperties(workflowProperties.ingestionQueue())).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(workflowProperties.retryQueue())).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(workflowProperties.deadLetterQueue())).isNotNull();
    }

    @Test
    void publisherRoutesWorkflowMessageToIngestionQueue() throws Exception {
        WorkflowTask task = WorkflowTask.created(
                "stock-analysis",
                "analysis:600519",
                Map.of("stockCode", "600519", "forceRefresh", true)
        );

        publisher.publish(task);

        JsonNode message = receiveJson(workflowProperties.ingestionQueue(), Duration.ofSeconds(2));
        assertThat(message).isNotNull();
        assertThat(message.path("taskId").asText()).isEqualTo(task.id());
        assertThat(message.path("taskType").asText()).isEqualTo("stock-analysis");
        assertThat(message.path("idempotencyKey").asText()).isEqualTo("analysis:600519");
        assertThat(message.path("payload").path("stockCode").asText()).isEqualTo("600519");
        assertThat(message.path("dispatchedAt").asDouble()).isPositive();
    }

    @Test
    void retryQueueDelaysThenRedeliversToIngestionQueue() throws Exception {
        WorkflowTask task = WorkflowTask.created(
                "document-ingestion",
                "retry:document-42",
                Map.of("documentId", "document-42")
        );

        publisher.publishRetry(task);

        JsonNode message = receiveJson(workflowProperties.ingestionQueue(), Duration.ofSeconds(7));
        assertThat(message).isNotNull();
        assertThat(message.path("taskId").asText()).isEqualTo(task.id());
        assertThat(message.path("idempotencyKey").asText()).isEqualTo("retry:document-42");
        assertThat(rabbitAdmin.getQueueProperties(workflowProperties.retryQueue()))
                .containsEntry("QUEUE_MESSAGE_COUNT", 0);
    }

    @Test
    void rejectedIngestionMessageIsDeadLettered() throws Exception {
        WorkflowTask task = WorkflowTask.created(
                "stock-analysis",
                "dead-letter:000001",
                Map.of("stockCode", "000001")
        );
        publisher.publish(task);

        Boolean rejected = rabbitTemplate.execute(channel -> {
            GetResponse delivery = channel.basicGet(workflowProperties.ingestionQueue(), false);
            if (delivery == null) {
                return false;
            }
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
            return true;
        });

        assertThat(rejected).isTrue();
        JsonNode message = receiveJson(workflowProperties.deadLetterQueue(), Duration.ofSeconds(2));
        assertThat(message).isNotNull();
        assertThat(message.path("taskId").asText()).isEqualTo(task.id());
        assertThat(message.path("idempotencyKey").asText()).isEqualTo("dead-letter:000001");
    }

    private JsonNode receiveJson(String queue, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Message message;
        do {
            message = rabbitTemplate.receive(queue);
            if (message != null) {
                return objectMapper.readTree(message.getBody());
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return null;
    }
}
