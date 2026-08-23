package com.finsight.it.application;

import com.finsight.application.ReportDiffService;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.ReportDiff;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.it.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDiffServiceIT extends AbstractPostgresIT {
    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    StockAnalysisReportRepository reportRepository;

    @Autowired
    ReportDiffService diffService;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void seedReports() {
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "食品饮料"));
        reportRepository.save(report("report-1", "谨慎", "现金流稳定。估值偏高。",
                List.of("现金流稳定"), List.of("估值偏高"),
                List.of("公告 A", "旧证据", "年报 C"), "ctx-1", "snapshot-1", 1,
                Instant.parse("2026-08-22T01:00:00Z")));
        reportRepository.save(report("report-2", "积极", "现金流稳定。盈利质量改善。",
                List.of("现金流稳定", "盈利质量改善"), List.of("行业需求波动"),
                List.of("公告 A", "新证据", "年报 C", "调研 D"), "ctx-2", "snapshot-2", 2,
                Instant.parse("2026-08-23T01:00:00Z")));
    }

    @Test
    void reloadsReportsFromPostgresAndBuildsFiveSegmentDiffFixture() {
        assertThat(reportRepository.findById("report-1")).isPresent();
        assertThat(reportRepository.findByCompanySymbol("600519", 8))
                .extracting(StockAnalysisReport::id)
                .containsExactly("report-2", "report-1");

        ReportDiff diff = diffService.diff("600519", "report-1", "report-2");

        assertThat(diff.rating().before()).containsExactly("谨慎");
        assertThat(diff.rating().after()).containsExactly("积极");
        assertThat(diff.citations().segments()).containsExactly(
                new ReportDiff.DiffSegment(ReportDiff.DiffOperation.EQUAL, "公告 A"),
                new ReportDiff.DiffSegment(ReportDiff.DiffOperation.DELETE, "旧证据"),
                new ReportDiff.DiffSegment(ReportDiff.DiffOperation.INSERT, "新证据"),
                new ReportDiff.DiffSegment(ReportDiff.DiffOperation.EQUAL, "年报 C"),
                new ReportDiff.DiffSegment(ReportDiff.DiffOperation.INSERT, "调研 D")
        );
        assertThat(diff.contextHashChanged()).isTrue();
        assertThat(diff.dataSnapshotHashChanged()).isTrue();

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/research/stock/600519/reports/report-1/diff/report-2", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys(
                "rating", "summary", "positivePoints", "riskPoints", "citations", "contextHashChanged");
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
            int version,
            Instant generatedAt
    ) {
        return new StockAnalysisReport(id, "600519", rating, summary, positive, risks, 84, citations,
                "gpt-test", "AI_ANALYSIS", true, contextHash, dataHash, version, generatedAt);
    }
}
