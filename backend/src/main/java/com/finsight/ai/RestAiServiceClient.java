package com.finsight.ai;

import com.finsight.domain.model.EvidenceChunk;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "finsight.ai-service.enabled", havingValue = "true")
public class RestAiServiceClient implements AiServiceClient {
    private static final Logger log = LoggerFactory.getLogger(RestAiServiceClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final FallbackAiServiceClient fallback = new FallbackAiServiceClient();

    public RestAiServiceClient(
            WebClient.Builder builder,
            @Value("${finsight.ai-service-url}") String aiServiceUrl,
            MeterRegistry meterRegistry
    ) {
        this.webClient = builder.baseUrl(aiServiceUrl).build();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<EvidenceChunk> rerank(String question, List<EvidenceChunk> candidates) {
        long start = System.nanoTime();
        try {
            RerankResponse response = invoke("/rerank", new RerankRequest(question, candidates), RerankResponse.class);
            if (response != null && response.evidence() != null) {
                recordOutcome("rerank", "success", start, candidates.size(), response.evidence().size());
                return response.evidence();
            }
            recordOutcome("rerank", "empty", start, candidates.size(), 0);
        } catch (RuntimeException ex) {
            recordOutcome("rerank", classify(ex), start, candidates.size(), 0);
        }
        return fallback.rerank(question, candidates);
    }

    @Override
    public String generateAnswer(String question, Map<String, Object> structuredQuery, List<EvidenceChunk> evidence) {
        long start = System.nanoTime();
        try {
            GenerateAnswerResponse response = invoke(
                    "/generate-answer",
                    new GenerateAnswerRequest(question, structuredQuery, evidence),
                    GenerateAnswerResponse.class
            );
            if (response != null && response.answer() != null && !response.answer().isBlank()) {
                recordOutcome("generate-answer", "success", start, evidence.size(), 1);
                return response.answer();
            }
            recordOutcome("generate-answer", "empty", start, evidence.size(), 0);
        } catch (RuntimeException ex) {
            recordOutcome("generate-answer", classify(ex), start, evidence.size(), 0);
        }
        return fallback.generateAnswer(question, structuredQuery, evidence);
    }

    private <T> T invoke(String path, Object body, Class<T> responseType) {
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .block(TIMEOUT);
    }

    private String classify(Throwable ex) {
        if (ex instanceof WebClientResponseException webEx) {
            return "http_" + webEx.getStatusCode().value();
        }
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        String message = ex.getClass().getSimpleName();
        return message.isBlank() ? "exception" : message.toLowerCase();
    }

    private void recordOutcome(String operation, String outcome, long startNanos, int inboundCount, int outboundCount) {
        long durationNanos = System.nanoTime() - startNanos;
        Timer.builder("finsight.ai.sidecar.call.duration")
                .description("AI sidecar call latency")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        meterRegistry.counter(
                "finsight.ai.sidecar.call.total",
                "operation", operation,
                "outcome", outcome
        ).increment();
        if (!"success".equals(outcome)) {
            log.warn("AI sidecar {} returned outcome={} in {}ms", operation, outcome, durationNanos / 1_000_000);
        }
    }

    private record RerankRequest(String question, List<EvidenceChunk> candidates) {
    }

    private record RerankResponse(List<EvidenceChunk> evidence) {
    }

    private record GenerateAnswerRequest(
            String question,
            Map<String, Object> structuredQuery,
            List<EvidenceChunk> evidence
    ) {
    }

    private record GenerateAnswerResponse(String answer) {
    }
}
