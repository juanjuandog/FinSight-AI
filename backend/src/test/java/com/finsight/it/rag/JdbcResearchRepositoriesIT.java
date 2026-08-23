package com.finsight.it.rag;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.model.DocumentType;
import com.finsight.domain.model.FinancialDocument;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.DocumentChunkRepository;
import com.finsight.domain.repository.DocumentRepository;
import com.finsight.it.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcResearchRepositoriesIT extends AbstractPostgresIT {
    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    DocumentChunkRepository chunkRepository;

    @BeforeEach
    void seedCompanyAndDocument() {
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "白酒"));
        documentRepository.save(document("doc-1", LocalDate.of(2025, 3, 31), "年度现金流报告", "cashflow improved"));
    }

    @Test
    void companyRoundTripsThroughPostgres() {
        assertThat(companyRepository.findBySymbol("600519")).contains(
                new Company("600519", "贵州茅台", "SH", "白酒")
        );
    }

    @Test
    void companySaveUpdatesExistingRow() {
        companyRepository.save(new Company("600519", "贵州茅台股份", "SSE", "食品饮料"));

        assertThat(companyRepository.findBySymbol("600519").orElseThrow())
                .extracting(Company::name, Company::exchange, Company::industry)
                .containsExactly("贵州茅台股份", "SSE", "食品饮料");
    }

    @Test
    void companyCountReflectsPersistedRows() {
        companyRepository.save(new Company("000001", "平安银行", "SZ", "银行"));

        assertThat(companyRepository.count()).isEqualTo(2);
    }

    @Test
    void companyFindAllUsesStableSymbolOrdering() {
        companyRepository.save(new Company("000001", "平安银行", "SZ", "银行"));

        assertThat(companyRepository.findAll()).extracting(Company::symbol)
                .containsExactly("000001", "600519");
    }

    @Test
    void companySearchMatchesSymbol() {
        assertThat(companyRepository.search("600", 10)).extracting(Company::symbol)
                .containsExactly("600519");
    }

    @Test
    void companySearchMatchesChineseName() {
        assertThat(companyRepository.search("茅台", 10)).extracting(Company::symbol)
                .containsExactly("600519");
    }

    @Test
    void companySearchMatchesIndustryAndHonoursLimit() {
        companyRepository.save(new Company("000858", "五粮液", "SZ", "白酒"));

        assertThat(companyRepository.search("白酒", 1)).hasSize(1);
    }

    @Test
    void documentRoundTripsWithJsonMetadata() {
        FinancialDocument stored = documentRepository.findById("doc-1").orElseThrow();

        assertThat(stored.metadata()).containsEntry("source", "exchange");
        assertThat(stored.content()).isEqualTo("cashflow improved");
    }

    @Test
    void documentSaveUpdatesExistingRow() {
        documentRepository.save(document("doc-1", LocalDate.of(2025, 4, 1), "更新报告", "updated content"));

        assertThat(documentRepository.findById("doc-1").orElseThrow())
                .extracting(FinancialDocument::title, FinancialDocument::content)
                .containsExactly("更新报告", "updated content");
    }

    @Test
    void documentsAreReturnedNewestFirst() {
        documentRepository.save(document("doc-2", LocalDate.of(2025, 6, 30), "半年报", "revenue expanded"));

        assertThat(documentRepository.findByCompanySymbol("600519"))
                .extracting(FinancialDocument::id)
                .containsExactly("doc-2", "doc-1");
    }

    @Test
    void documentFullTextSearchFindsContent() {
        assertThat(documentRepository.search("600519", "cashflow", 10))
                .extracting(FinancialDocument::id)
                .containsExactly("doc-1");
    }

    @Test
    void blankDocumentSearchReturnsRecentDocuments() {
        documentRepository.save(document("doc-2", LocalDate.of(2025, 6, 30), "半年报", "revenue expanded"));

        assertThat(documentRepository.search("600519", "", 1))
                .extracting(FinancialDocument::id)
                .containsExactly("doc-2");
    }

    @Test
    void chunksRoundTripInChunkIndexOrder() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("chunk-2", 2, "risk factors", vector(0.2), Map.of("page", "2")),
                chunk("chunk-1", 1, "cashflow quality", vector(0.1), Map.of("page", "1"))
        ));

        assertThat(chunkRepository.findByDocumentId("doc-1"))
                .extracting(DocumentChunk::id)
                .containsExactly("chunk-1", "chunk-2");
        assertThat(chunkRepository.findByDocumentId("doc-1").get(0).metadata())
                .containsEntry("page", "1");
    }

    @Test
    void chunkCountIsScopedToCompany() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("chunk-1", 1, "cashflow quality", vector(0.1), Map.of()),
                chunk("chunk-2", 2, "risk factors", vector(0.2), Map.of())
        ));

        assertThat(chunkRepository.countByCompanySymbol("600519")).isEqualTo(2);
        assertThat(chunkRepository.countByCompanySymbol("000001")).isZero();
    }

    @Test
    void keywordSearchUsesPostgresFullTextIndex() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("chunk-1", 1, "cashflow quality improved", vector(0.1), Map.of()),
                chunk("chunk-2", 2, "inventory pressure", vector(0.2), Map.of())
        ));

        assertThat(chunkRepository.keywordSearch("600519", "cashflow", 5))
                .extracting(DocumentChunk::id)
                .containsExactly("chunk-1");
    }

    @Test
    void vectorSearchReturnsNearestEmbeddingFirst() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("near", 1, "near", vector(0.9), Map.of()),
                chunk("far", 2, "far", vector(-0.9), Map.of())
        ));

        assertThat(chunkRepository.vectorSearch("600519", vector(1.0), 2))
                .extracting(DocumentChunk::id)
                .containsExactly("near", "far");
    }

    @Test
    void replacingChunksRemovesObsoleteRows() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("old-1", 1, "old", vector(0.1), Map.of()),
                chunk("old-2", 2, "old", vector(0.2), Map.of())
        ));

        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("new-1", 1, "new", vector(0.3), Map.of())
        ));

        assertThat(chunkRepository.findByDocumentId("doc-1"))
                .extracting(DocumentChunk::id)
                .containsExactly("new-1");
    }

    @Test
    void deletingDocumentCascadesToChunks() {
        chunkRepository.replaceChunks("doc-1", List.of(
                chunk("chunk-1", 1, "cashflow", vector(0.1), Map.of())
        ));

        jdbcTemplate.update("DELETE FROM financial_documents WHERE id = ?", "doc-1");

        assertThat(chunkRepository.countByCompanySymbol("600519")).isZero();
    }

    private FinancialDocument document(String id, LocalDate date, String title, String content) {
        return new FinancialDocument(
                id,
                "600519",
                DocumentType.ANNUAL_REPORT,
                title,
                date,
                "https://example.test/" + id,
                content,
                Map.of("source", "exchange")
        );
    }

    private DocumentChunk chunk(
            String id,
            int index,
            String text,
            List<Double> embedding,
            Map<String, String> metadata
    ) {
        return new DocumentChunk(
                id,
                "doc-1",
                "600519",
                DocumentType.ANNUAL_REPORT,
                "年度报告",
                LocalDate.of(2025, 3, 31),
                "management-discussion",
                index,
                text,
                "hash-" + id,
                embedding,
                metadata
        );
    }

    private List<Double> vector(double firstValue) {
        List<Double> values = new ArrayList<>(384);
        values.add(firstValue);
        for (int index = 1; index < 384; index++) {
            values.add(0.0);
        }
        return List.copyOf(values);
    }
}
