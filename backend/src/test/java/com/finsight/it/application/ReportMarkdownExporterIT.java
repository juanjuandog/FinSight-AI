package com.finsight.it.application;

import com.finsight.application.ReportExportService;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.it.AbstractPostgresIT;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMarkdownExporterIT extends AbstractPostgresIT {
    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    StockAnalysisReportRepository reportRepository;

    @Autowired
    MetricRepository metricRepository;

    @Autowired
    ReportExportService exportService;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void seedReport() {
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "食品饮料"));
        reportRepository.save(new StockAnalysisReport(
                "report-2", "600519", "积极", "现金流稳定，盈利质量改善。",
                List.of("经营现金流覆盖净利润", "毛利率保持稳定"), List.of("估值仍处历史高位"), 86,
                List.of("https://example.test/annual-report", "2025 年年度报告第 18 页"),
                "gpt-test", "AI_ANALYSIS", true, "ctx-abc", "snapshot-def", 2,
                Instant.parse("2026-08-23T01:02:03Z")
        ));
        metricRepository.saveMetric(new FinancialMetric("600519", Year.of(2025), "ROE", "净资产收益率",
                new BigDecimal("0.3120"), "v1"));
        metricRepository.saveRiskSignal(new RiskSignal("risk-1", "600519", "VALUATION", "估值风险",
                "估值分位处于历史高位", 3, LocalDate.of(2026, 8, 22)));
    }

    @Test
    void exportsRealPersistedReportWithRequiredGoldenSections() throws Exception {
        String markdown = exportService.markdown("600519", "report-2");
        byte[] pdf = exportService.pdf("600519", "report-2");
        String expected = new ClassPathResource("golden/report-export.md")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(markdown).isEqualTo(expected);
        assertThat(pdf.length).isLessThanOrEqualTo(1_500_000);
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        } finally {
            reader.close();
        }

        ResponseEntity<String> markdownResponse = restTemplate.getForEntity(
                "/api/research/stock/600519/reports/report-2.md", String.class);
        ResponseEntity<byte[]> pdfResponse = restTemplate.getForEntity(
                "/api/research/stock/600519/reports/report-2.pdf", byte[].class);
        assertThat(markdownResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(markdownResponse.getHeaders().getContentType().toString()).startsWith("text/markdown");
        assertThat(pdfResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdfResponse.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
    }
}
