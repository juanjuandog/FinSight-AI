package com.finsight.rag;

import com.finsight.ai.AiServiceClient;
import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.repository.DocumentChunkRepository;
import com.finsight.domain.repository.MetricRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class HybridRetrievalGateway {
    private static final double RRF_K = 60.0;
    private static final int RERANK_FETCH_MULTIPLIER = 4;
    private static final int STRUCTURAL_LIMIT = 6;

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final MetricRepository metricRepository;
    private final AiServiceClient aiServiceClient;

    public HybridRetrievalGateway(
            DocumentChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            MetricRepository metricRepository,
            AiServiceClient aiServiceClient
    ) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.metricRepository = metricRepository;
        this.aiServiceClient = aiServiceClient;
    }

    public List<RetrievalHit> search(String companySymbol, String question, int limit) {
        Map<String, FusionCandidate> candidates = new LinkedHashMap<>();

        List<DocumentChunk> keywordChunks = chunkRepository.keywordSearch(companySymbol, question, limit);
        for (int i = 0; i < keywordChunks.size(); i++) {
            addRank(candidates, keywordChunks.get(i), "keyword", i + 1);
        }

        List<Double> queryEmbedding = embeddingService.embed(question);
        List<DocumentChunk> vectorChunks = chunkRepository.vectorSearch(companySymbol, queryEmbedding, limit);
        for (int i = 0; i < vectorChunks.size(); i++) {
            addRank(candidates, vectorChunks.get(i), "vector", i + 1);
        }

        List<DocumentChunk> structuralChunks = buildStructuralSignals(companySymbol);
        for (int i = 0; i < structuralChunks.size(); i++) {
            addRank(candidates, structuralChunks.get(i), "structural", i + 1);
        }

        List<RetrievalHit> fused = candidates.values().stream()
                .map(candidate -> new RetrievalHit(
                        candidate.chunk(),
                        candidate.ranks().values().stream()
                                .mapToDouble(rank -> 1.0 / (RRF_K + rank))
                                .sum(),
                        String.join("+", candidate.ranks().keySet()),
                        Map.copyOf(candidate.ranks())
                ))
                .sorted(Comparator.comparingDouble(RetrievalHit::score).reversed())
                .toList();

        return rerank(question, fused, limit);
    }

    private List<RetrievalHit> rerank(String question, List<RetrievalHit> fused, int limit) {
        if (fused.isEmpty()) {
            return fused;
        }
        int rerankFetch = Math.min(fused.size(), Math.max(limit * RERANK_FETCH_MULTIPLIER, limit));
        List<RetrievalHit> head = fused.subList(0, rerankFetch);
        List<EvidenceChunk> candidates = head.stream()
                .map(hit -> toEvidenceChunk(hit, 0.0))
                .collect(Collectors.toList());
        try {
            List<EvidenceChunk> reranked = aiServiceClient.rerank(question, candidates);
            Set<String> orderedIds = reranked.stream()
                    .map(EvidenceChunk::documentId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<RetrievalHit> reordered = new ArrayList<>();
            for (String id : orderedIds) {
                head.stream()
                        .filter(hit -> hit.chunk().id().equals(id) || hit.chunk().documentId().equals(id))
                        .findFirst()
                        .ifPresent(reordered::add);
            }
            for (RetrievalHit hit : head) {
                if (reordered.stream().noneMatch(r -> r.chunk().id().equals(hit.chunk().id()))) {
                    reordered.add(hit);
                }
            }
            return reordered.stream().limit(limit).toList();
        } catch (RuntimeException ex) {
            return fused.stream().limit(limit).toList();
        }
    }

    private EvidenceChunk toEvidenceChunk(RetrievalHit hit, double score) {
        DocumentChunk chunk = hit.chunk();
        return new EvidenceChunk(
                chunk.id(),
                chunk.title(),
                chunk.documentType(),
                chunk.publishedAt(),
                chunk.section(),
                chunk.text(),
                score
        );
    }

    private List<DocumentChunk> buildStructuralSignals(String companySymbol) {
        List<DocumentChunk> signals = new ArrayList<>();
        metricRepository.findMetrics(companySymbol).stream()
                .sorted(Comparator.comparing(FinancialMetric::fiscalYear).reversed())
                .limit(STRUCTURAL_LIMIT)
                .forEach(metric -> signals.add(toStructuralChunk(metric)));
        metricRepository.findRiskSignals(companySymbol).stream()
                .sorted(Comparator.comparingInt(RiskSignal::severity).reversed())
                .limit(STRUCTURAL_LIMIT)
                .forEach(risk -> signals.add(toStructuralChunk(risk)));
        return signals;
    }

    private DocumentChunk toStructuralChunk(FinancialMetric metric) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "metric");
        metadata.put("code", metric.code());
        metadata.put("fiscalYear", String.valueOf(metric.fiscalYear()));
        metadata.put("value", metric.value() == null ? "" : metric.value().toPlainString());
        String text = metric.name() + " (" + metric.code() + "): " + metric.value();
        return new DocumentChunk(
                "metric:" + metric.code() + ":" + metric.fiscalYear(),
                "metric:" + metric.code(),
                metric.companySymbol(),
                com.finsight.domain.model.DocumentType.FINANCIAL_REPORT,
                metric.name(),
                LocalDate.of(metric.fiscalYear().getValue(), 12, 31),
                "structured-metric",
                0,
                text,
                "metric-" + metric.code(),
                List.of(),
                metadata
        );
    }

    private DocumentChunk toStructuralChunk(RiskSignal risk) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "risk");
        metadata.put("code", risk.code());
        metadata.put("severity", String.valueOf(risk.severity()));
        String text = "风险信号 / " + risk.code() + "（严重度 " + risk.severity() + "）：" + risk.title()
                + (risk.explanation() == null ? "" : " — " + risk.explanation());
        return new DocumentChunk(
                "risk:" + risk.id(),
                "risk:" + risk.code(),
                risk.companySymbol(),
                com.finsight.domain.model.DocumentType.RISK_REPORT,
                risk.title(),
                risk.detectedAt(),
                "structured-risk",
                0,
                text,
                "risk-" + risk.code(),
                List.of(),
                metadata
        );
    }

    @SuppressWarnings("unused")
    private static String syntheticId() {
        return "structural-" + UUID.randomUUID();
    }

    @SuppressWarnings("unused")
    private static Year intYear(int value) {
        return Year.of(value);
    }

    private void addRank(
            Map<String, FusionCandidate> candidates,
            DocumentChunk chunk,
            String channel,
            int rank
    ) {
        candidates.computeIfAbsent(
                chunk.id(),
                ignored -> new FusionCandidate(chunk, new LinkedHashMap<>())
        ).ranks().put(channel, rank);
    }

    private record FusionCandidate(DocumentChunk chunk, Map<String, Integer> ranks) {
    }
}
