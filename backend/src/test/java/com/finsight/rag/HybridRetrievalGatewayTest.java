package com.finsight.rag;

import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.model.DocumentType;
import com.finsight.domain.repository.DocumentChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
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

        List<RetrievalHit> results =
                new HybridRetrievalGateway(repository, embeddingService).search("600519", "现金流", 3);

        assertThat(results.get(0).chunk().id()).isEqualTo("shared");
        assertThat(results.get(0).channel()).isEqualTo("keyword+vector");
        assertThat(results.get(0).ranks()).containsEntry("keyword", 2).containsEntry("vector", 1);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
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
}
