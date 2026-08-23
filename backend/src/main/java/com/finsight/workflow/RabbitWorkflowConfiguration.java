package com.finsight.workflow;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

@Configuration
@Profile("rabbitmq")
@EnableRabbit
@EnableConfigurationProperties(RabbitWorkflowProperties.class)
public class RabbitWorkflowConfiguration {
    @Bean
    public DirectExchange workflowExchange(RabbitWorkflowProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    public DirectExchange workflowDeadLetterExchange(RabbitWorkflowProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Queue ingestionQueue(RabbitWorkflowProperties properties) {
        return new Queue(properties.ingestionQueue(), true, false, false, Map.of(
                "x-dead-letter-exchange", properties.deadLetterExchange()
        ));
    }

    @Bean
    public Queue deadLetterQueue(RabbitWorkflowProperties properties) {
        return new Queue(properties.deadLetterQueue(), true);
    }

    @Bean
    public Queue workflowRetryQueue(RabbitWorkflowProperties properties) {
        return new Queue(properties.retryQueue(), true, false, false, Map.of(
                "x-message-ttl", properties.retryDelayMs(),
                "x-dead-letter-exchange", properties.exchange(),
                "x-dead-letter-routing-key", properties.ingestionRoutingKey()
        ));
    }

    @Bean
    public Binding ingestionBinding(Queue ingestionQueue, DirectExchange workflowExchange, RabbitWorkflowProperties properties) {
        return BindingBuilder.bind(ingestionQueue).to(workflowExchange).with(properties.ingestionRoutingKey());
    }

    @Bean
    public Binding deadLetterBinding(
            Queue deadLetterQueue,
            DirectExchange workflowDeadLetterExchange,
            RabbitWorkflowProperties properties
    ) {
        return BindingBuilder.bind(deadLetterQueue).to(workflowDeadLetterExchange).with(properties.ingestionRoutingKey());
    }

    @Bean
    public Binding retryBinding(
            Queue workflowRetryQueue,
            DirectExchange workflowExchange,
            RabbitWorkflowProperties properties
    ) {
        return BindingBuilder.bind(workflowRetryQueue)
                .to(workflowExchange)
                .with(properties.retryRoutingKey());
    }

    @Bean
    public MessageConverter workflowMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter workflowMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(workflowMessageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter workflowMessageConverter,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(workflowMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(4);
        return factory;
    }
}
