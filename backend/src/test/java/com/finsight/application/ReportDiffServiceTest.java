package com.finsight.application;

import com.finsight.domain.model.ReportDiff;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.infrastructure.InMemoryStockAnalysisReportRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportDiffServiceTest {

    private final InMemoryStockAnalysisReportRepository repository =
            new InMemoryStockAnalysisReportRepository();
    private final ReportDiffService service = new ReportDiffService(repository);

    @Test
    void comparesAllResearchFieldsAndSnapshotProvenance() {
        repository.save(report("r-1", "谨慎", "现金流稳定。估值偏高。",
                List.of("现金流稳定"), List.of("估值偏高"), List.of("公告 A"), "ctx-1", "data-1", 1));
        repository.save(report("r-2", "积极", "现金流稳定。盈利改善。",
                List.of("现金流稳定", "盈利改善"), List.of("行业波动"), List.of("公告 A", "年报 B"),
                "ctx-2", "data-2", 2));

        ReportDiff diff = service.diff("600519", "r-1", "r-2");

        assertThat(diff.companySymbol()).isEqualTo("600519");
        assertThat(diff.reportVersionDelta()).isEqualTo(1);
        assertThat(diff.contextHashChanged()).isTrue();
        assertThat(diff.dataSnapshotHashChanged()).isTrue();
        assertThat(diff.rating().changed()).isTrue();
        assertThat(diff.summary().segments()).extracting(ReportDiff.DiffSegment::op)
                .contains(ReportDiff.DiffOperation.EQUAL, ReportDiff.DiffOperation.DELETE,
                        ReportDiff.DiffOperation.INSERT);
        assertThat(diff.positivePoints().after()).containsExactly("现金流稳定", "盈利改善");
        assertThat(diff.riskPoints().before()).containsExactly("估值偏高");
        assertThat(diff.citations().after()).containsExactly("公告 A", "年报 B");
    }

    @Test
    void hidesReportsThatBelongToAnotherCompany() {
        repository.save(report("r-1", "谨慎", "摘要", List.of(), List.of(), List.of(),
                "ctx", "data", 1));

        assertThatThrownBy(() -> service.diff("000001", "r-1", "r-1"))
                .isInstanceOf(ReportDiffService.ReportNotFoundException.class);
    }

    private StockAnalysisReport report(
            String id,
            String rating,
            String summary,
            List<String> positive,
            List<String> risks,
            List<String> citations,
            String contextHash,
            String dataHash,
            int version
    ) {
        return new StockAnalysisReport(id, "600519", rating, summary, positive, risks, 82, citations,
                "gpt-test", "unit", true, contextHash, dataHash, version,
                Instant.parse("2026-08-23T01:02:03Z").plusSeconds(version));
    }
}
