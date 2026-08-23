package com.finsight.it.rag;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.model.DocumentType;
import com.finsight.domain.model.FinancialDocument;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.DocumentChunkRepository;
import com.finsight.domain.repository.DocumentRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.it.AbstractPostgresIT;
import com.finsight.rag.EmbeddingService;
import com.finsight.rag.EvidenceRetriever;
import com.finsight.rag.HybridRetrievalGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalGatewayIT extends AbstractPostgresIT {
    @Autowired
    HybridRetrievalGateway retrievalGateway;

    @Autowired
    EvidenceRetriever evidenceRetriever;

    @Autowired
    EmbeddingService embeddingService;

    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    DocumentChunkRepository chunkRepository;

    @Autowired
    MetricRepository metricRepository;

    @BeforeEach
    void indexFiftyChunkFixture() {
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "白酒"));
        documentRepository.save(new FinancialDocument(
                "rag-doc",
                "600519",
                DocumentType.ANNUAL_REPORT,
                "2025 年年度报告",
                LocalDate.of(2025, 12, 31),
                "https://example.test/rag-doc",
                "现金流、盈利质量与存货风险",
                Map.of("source", "integration-fixture")
        ));
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            String text = index < 8
                    ? "cashflow operating cash quality improved row " + index
                    : "inventory and ordinary operations row " + index;
            chunks.add(new DocumentChunk(
                    "rag-chunk-" + index,
                    "rag-doc",
                    "600519",
                    DocumentType.ANNUAL_REPORT,
                    "2025 年年度报告",
                    LocalDate.of(2025, 12, 31),
                    index < 8 ? "cashflow" : "operations",
                    index,
                    text,
                    embeddingService.hash(text),
                    embeddingService.embed(text),
                    Map.of("row", String.valueOf(index))
            ));
        }
        chunkRepository.replaceChunks("rag-doc", chunks);
    }

    @Test
    void fusesKeywordAndPgvectorResultsFromFiftyRows() {
        var results = retrievalGateway.search("600519", "cashflow quality", 5);

        assertThat(chunkRepository.countByCompanySymbol("600519")).isEqualTo(50);
        assertThat(results).hasSize(5);
        assertThat(results).anySatisfy(hit -> {
            assertThat(hit.channel()).contains("keyword");
            assertThat(hit.ranks()).containsKey("keyword");
        });
        assertThat(results).allSatisfy(hit -> assertThat(hit.score()).isPositive());
    }

    @Test
    void evidenceRetrieverSurfacesMetricAndRiskChannelsAlongsideRag() {
        metricRepository.saveMetric(new FinancialMetric(
                "600519", Year.of(2025), "ROE", "净资产收益率", new BigDecimal("0.22"), "it"
        ));
        metricRepository.saveRiskSignal(new RiskSignal(
                "rag-risk", "600519", "INVENTORY", "存货周转风险", "周转天数上升", 3, LocalDate.now()
        ));

        var evidence = evidenceRetriever.retrieve(
                "cashflow quality",
                Map.of("companySymbol", "600519", "requiresMetrics", true)
        );

        assertThat(evidence).extracting(item -> item.documentId())
                .contains("metric-store-600519", "risk-store-600519");
        assertThat(evidence).anySatisfy(item ->
                assertThat(item.section()).containsAnyOf("keyword", "vector"));
    }
}
