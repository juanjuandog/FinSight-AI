package com.finsight.it;

import com.finsight.workflow.RabbitWorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("rabbitmq")
@SpringBootTest(properties = {
        "finsight.ai-service.enabled=false",
        "finsight.scheduler.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "management.health.rabbit.enabled=false"
})
public abstract class AbstractRabbitIT {
    static {
        IntegrationContainers.startRabbit();
    }

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    @Autowired
    protected RabbitWorkflowProperties workflowProperties;

    @Autowired
    RabbitListenerEndpointRegistry listenerRegistry;

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", IntegrationContainers.RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", IntegrationContainers.RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "finsight");
        registry.add("spring.rabbitmq.password", () -> "finsight");
    }

    @BeforeEach
    void purgeRabbitQueues() {
        listenerRegistry.stop();
        rabbitAdmin.purgeQueue(workflowProperties.ingestionQueue(), true);
        rabbitAdmin.purgeQueue(workflowProperties.retryQueue(), true);
        rabbitAdmin.purgeQueue(workflowProperties.deadLetterQueue(), true);
    }
}
