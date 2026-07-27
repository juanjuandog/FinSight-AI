package com.finsight.application;

import com.finsight.ai.AiServiceClient;
import com.finsight.domain.model.AnswerResponse;
import com.finsight.domain.model.AskQuestionCommand;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.domain.model.RagTrace;
import com.finsight.domain.repository.RagTraceRepository;
import com.finsight.rag.EvidenceRetriever;
import com.finsight.rag.EvidenceSnapshotHasher;
import com.finsight.rag.QueryUnderstandingService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalysisApplicationService {
    private final QueryUnderstandingService queryUnderstandingService;
    private final EvidenceRetriever evidenceRetriever;
    private final AiServiceClient aiServiceClient;
    private final RagTraceRepository ragTraceRepository;
    private final MeterRegistry meterRegistry;

    public AnalysisApplicationService(
            QueryUnderstandingService queryUnderstandingService,
            EvidenceRetriever evidenceRetriever,
            AiServiceClient aiServiceClient,
            RagTraceRepository ragTraceRepository,
            MeterRegistry meterRegistry
    ) {
        this.queryUnderstandingService = queryUnderstandingService;
        this.evidenceRetriever = evidenceRetriever;
        this.aiServiceClient = aiServiceClient;
        this.ragTraceRepository = ragTraceRepository;
        this.meterRegistry = meterRegistry;
    }

    public AnswerResponse ask(AskQuestionCommand command) {
        Instant startedAt = Instant.now();
        Timer.Sample sample = Timer.start(meterRegistry);
        Map<String, Object> structuredQuery = queryUnderstandingService.parse(command);
        List<EvidenceChunk> candidates = evidenceRetriever.retrieve(command.question(), structuredQuery);
        List<EvidenceChunk> evidence = aiServiceClient.rerank(command.question(), candidates).stream()
                .limit(5)
                .toList();
        String answer = aiServiceClient.generateAnswer(command.question(), structuredQuery, evidence);
        RagTrace trace = new RagTrace(
                UUID.randomUUID().toString(),
                startedAt,
                structuredQuery,
                List.of("keyword-search", "metric-store", "risk-store", "rerank"),
                evidence.size(),
                EvidenceSnapshotHasher.hash(evidence),
                Duration.between(startedAt, Instant.now()).toMillis()
        );
        ragTraceRepository.save(command.companySymbol(), command.question(), trace);
        String intent = String.valueOf(structuredQuery.getOrDefault("intent", "UNKNOWN"));
        sample.stop(Timer.builder("finsight.rag.ask.latency")
                .description("End-to-end RAG ask latency")
                .tag("intent", intent)
                .register(meterRegistry));
        DistributionSummary.builder("finsight.rag.evidence.count")
                .description("Evidence chunks bound to an answer")
                .tag("intent", intent)
                .register(meterRegistry)
                .record(evidence.size());
        return new AnswerResponse(answer, evidence, trace);
    }
}
