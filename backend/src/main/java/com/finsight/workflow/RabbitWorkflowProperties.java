package com.finsight.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finsight.workflow")
public record RabbitWorkflowProperties(
        String exchange,
        String ingestionQueue,
        String ingestionRoutingKey,
        String retryQueue,
        String retryRoutingKey,
        long retryDelayMs,
        String deadLetterExchange,
        String deadLetterQueue
) {
}
