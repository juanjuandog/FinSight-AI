package com.finsight.rag;

import com.finsight.domain.model.DocumentType;
import com.finsight.domain.model.EvidenceChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSnapshotHasherTest {
    private static final EvidenceChunk REVENUE = new EvidenceChunk(
            "filing-2026-q2",
            "Q2 2026 filing",
            DocumentType.QUARTERLY_REPORT,
            LocalDate.of(2026, 7, 15),
            "Income statement",
            "Revenue was CNY 12.4 billion.",
            0.93
    );

    private static final EvidenceChunk RISK = new EvidenceChunk(
            "risk-2026-q2",
            "Q2 2026 risk review",
            DocumentType.RESEARCH_REPORT,
            LocalDate.of(2026, 7, 18),
            "Liquidity",
            "Short-term liquidity remained stable.",
            0.81
    );

    @Test
    void hashIsStableForTheSameOrderedEvidence() {
        String first = EvidenceSnapshotHasher.hash(List.of(REVENUE, RISK));
        String second = EvidenceSnapshotHasher.hash(List.of(REVENUE, RISK));

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    @Test
    void hashChangesWhenEvidenceContentChanges() {
        EvidenceChunk revisedRevenue = new EvidenceChunk(
                REVENUE.documentId(),
                REVENUE.title(),
                REVENUE.documentType(),
                REVENUE.publishedAt(),
                REVENUE.section(),
                "Revenue was CNY 13.1 billion.",
                REVENUE.score()
        );

        assertThat(EvidenceSnapshotHasher.hash(List.of(revisedRevenue, RISK)))
                .isNotEqualTo(EvidenceSnapshotHasher.hash(List.of(REVENUE, RISK)));
    }

    @Test
    void hashTreatsRerankingAsAContextChange() {
        assertThat(EvidenceSnapshotHasher.hash(List.of(RISK, REVENUE)))
                .isNotEqualTo(EvidenceSnapshotHasher.hash(List.of(REVENUE, RISK)));
    }

    @Test
    void hashSupportsEvidenceWithoutAPublishedDate() {
        EvidenceChunk undatedMetric = new EvidenceChunk(
                "metric-store:revenue",
                "Revenue metric",
                DocumentType.ANNUAL_REPORT,
                null,
                "metric-store",
                "Revenue was CNY 12.4 billion.",
                0.88
        );

        assertThat(EvidenceSnapshotHasher.hash(List.of(undatedMetric)))
                .hasSize(64)
                .isEqualTo(EvidenceSnapshotHasher.hash(List.of(undatedMetric)));
    }

    @Test
    void hashDistinguishesNullFromAnEmptyString() {
        EvidenceChunk nullSection = new EvidenceChunk(
                REVENUE.documentId(),
                REVENUE.title(),
                REVENUE.documentType(),
                REVENUE.publishedAt(),
                null,
                REVENUE.text(),
                REVENUE.score()
        );
        EvidenceChunk emptySection = new EvidenceChunk(
                REVENUE.documentId(),
                REVENUE.title(),
                REVENUE.documentType(),
                REVENUE.publishedAt(),
                "",
                REVENUE.text(),
                REVENUE.score()
        );

        assertThat(EvidenceSnapshotHasher.hash(List.of(nullSection)))
                .isNotEqualTo(EvidenceSnapshotHasher.hash(List.of(emptySection)));
    }

    @Test
    void hashIgnoresScoreWhenTheModelFacingEvidenceIsUnchanged() {
        EvidenceChunk rescoredRevenue = new EvidenceChunk(
                REVENUE.documentId(),
                REVENUE.title(),
                REVENUE.documentType(),
                REVENUE.publishedAt(),
                REVENUE.section(),
                REVENUE.text(),
                0.12
        );

        assertThat(EvidenceSnapshotHasher.hash(List.of(rescoredRevenue, RISK)))
                .isEqualTo(EvidenceSnapshotHasher.hash(List.of(REVENUE, RISK)));
    }
}
