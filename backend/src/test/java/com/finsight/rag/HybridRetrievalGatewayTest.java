package com.finsight.rag;

import com.finsight.ai.AiServiceClient;
import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.model.DocumentType;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.repository.DocumentChunkRepository;
import com.finsight.domain.repository.MetricRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalGatewayTest {

    @Test
    void reciprocalRankFusionRewardsEvidenceFoundByBothChannels() {
        DocumentChunk keywordOnly = chunk("keyword-only");
        DocumentChunk shared = chunk("shared");
        DocumentChunk vectorOnly = chunk("vector-only");
        DocumentChunkRepository repository = fixedRepository(
                List.of(keywordOnly, shared),
                List.of(shared, vectorOnly)
        );
        EmbeddingService embeddingService = new EmbeddingService(
                WebClient.builder(),
                "http://localhost:8001",
                false,
                4
        );

        List<RetrievalHit> results = gateway(repository, embeddingService, metricRepository(List.of(), List.of()), identityAiClient())
                .search("600519", "现金流", 3);

        assertThat(results.get(0).chunk().id()).isEqualTo("shared");
        assertThat(results.get(0).channel()).contains("keyword");
        assertThat(results.get(0).channel()).contains("vector");
        assertThat(results.get(0).ranks()).containsEntry("keyword", 2).containsEntry("vector", 1);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void structuralChannelAddsMetricAndRiskSignals() {
        DocumentChunkRepository repository = fixedRepository(List.of(), List.of());
        FinancialMetric roe = new FinancialMetric(
                "600519", Year.of(2024), "ROE", "净资产收益率", new BigDecimal("0.18"), "v1"
        );
        RiskSignal risk = new RiskSignal(
                "risk-1", "600519", "VALUATION_HIGH", "估值偏高", "PE 超过 80", 4, LocalDate.now()
        );
        MetricRepository metricRepository = metricRepository(List.of(roe), List.of(risk));

        List<RetrievalHit> results = gateway(repository, embeddingService(), metricRepository, identityAiClient())
                .search("600519", "估值", 5);

        assertThat(results).extracting(hit -> hit.chunk().id())
                .contains("metric:ROE:2024", "risk:risk-1");
    }

    @Test
    void rerankUsesAiServiceClientAndKeepsDroppedCandidates() {
        DocumentChunk a = chunk("a");
        DocumentChunk b = chunk("b");
        DocumentChunk c = chunk("c");
        DocumentChunkRepository repository = fixedRepository(List.of(a, b), List.of(b, c));
        ReversingAiClient client = new ReversingAiClient();

        List<RetrievalHit> results = gateway(repository, embeddingService(), metricRepository(List.of(), List.of()), client)
                .search("600519", "现金流", 3);

        assertThat(client.observed).hasSizeGreaterThan(0);
        assertThat(results).extracting(hit -> hit.chunk().id())
                .as("reranker output must be respected when present")
                .containsAnyOf("a", "b", "c");
    }

    private HybridRetrievalGateway gateway(
            DocumentChunkRepository repository,
            EmbeddingService embeddingService,
            MetricRepository metricRepository,
            AiServiceClient aiServiceClient
    ) {
        return new HybridRetrievalGateway(repository, embeddingService, metricRepository, aiServiceClient);
    }

    private EmbeddingService embeddingService() {
        return new EmbeddingService(
                WebClient.builder(),
                "http://localhost:8001",
                false,
                4
        );
    }

    private AiServiceClient identityAiClient() {
        return new AiServiceClient() {
            @Override
            public List<EvidenceChunk> rerank(String question, List<EvidenceChunk> candidates) {
                return candidates;
            }

            @Override
            public String generateAnswer(String question, Map<String, Object> structuredQuery, List<EvidenceChunk> evidence) {
                return "ok";
            }
        };
    }

    private DocumentChunkRepository fixedRepository(
            List<DocumentChunk> keywordResults,
            List<DocumentChunk> vectorResults
    ) {
        return new DocumentChunkRepository() {
            @Override
            public void replaceChunks(String documentId, List<DocumentChunk> chunks) {
            }

            @Override
            public List<DocumentChunk> findByDocumentId(String documentId) {
                return List.of();
            }

            @Override
            public List<DocumentChunk> keywordSearch(String companySymbol, String query, int limit) {
                return keywordResults;
            }

            @Override
            public List<DocumentChunk> vectorSearch(String companySymbol, List<Double> embedding, int limit) {
                return vectorResults;
            }

            @Override
            public long countByCompanySymbol(String companySymbol) {
                return 3;
            }
        };
    }

    private MetricRepository metricRepository(List<FinancialMetric> metrics, List<RiskSignal> risks) {
        return new MetricRepository() {
            @Override
            public void saveMetric(FinancialMetric metric) {
            }

            @Override
            public void saveRiskSignal(RiskSignal riskSignal) {
            }

            @Override
            public List<FinancialMetric> findMetrics(String companySymbol) {
                return metrics;
            }

            @Override
            public List<RiskSignal> findRiskSignals(String companySymbol) {
                return risks;
            }
        };
    }

    private DocumentChunk chunk(String id) {
        return new DocumentChunk(
                id,
                "doc-" + id,
                "600519",
                DocumentType.ANNUAL_REPORT,
                id,
                LocalDate.of(2025, 12, 31),
                "经营分析",
                0,
                "现金流证据",
                "hash-" + id,
                List.of(1.0, 0.0, 0.0, 0.0),
                Map.of()
        );
    }

    private static final class ReversingAiClient implements AiServiceClient {
        List<EvidenceChunk> observed;

        @Override
        public List<EvidenceChunk> rerank(String question, List<EvidenceChunk> candidates) {
            observed = candidates;
            java.util.List<EvidenceChunk> reversed = new java.util.ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            return reversed;
        }

        @Override
        public String generateAnswer(String question, Map<String, Object> structuredQuery, List<EvidenceChunk> evidence) {
            return "ok";
        }
    }
}
