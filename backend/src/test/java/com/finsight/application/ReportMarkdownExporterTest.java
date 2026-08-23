package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMarkdownExporterTest {

    @Test
    void matchesTheGoldenResearchReport() throws Exception {
        String actual = new ReportMarkdownExporter().export(report(), company(), metrics(), risks());
        String expected = new ClassPathResource("golden/report-export.md")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(actual).isEqualTo(expected);
    }

    static Company company() {
        return new Company("600519", "贵州茅台", "SH", "食品饮料");
    }

    static StockAnalysisReport report() {
        return new StockAnalysisReport(
                "report-2", "600519", "积极", "现金流稳定，盈利质量改善。",
                List.of("经营现金流覆盖净利润", "毛利率保持稳定"),
                List.of("估值仍处历史高位"), 86,
                List.of("https://example.test/annual-report", "2025 年年度报告第 18 页"),
                "gpt-test", "AI_ANALYSIS", true, "ctx-abc", "snapshot-def", 2,
                Instant.parse("2026-08-23T01:02:03Z")
        );
    }

    static List<FinancialMetric> metrics() {
        return List.of(new FinancialMetric("600519", Year.of(2025), "ROE", "净资产收益率",
                new BigDecimal("0.3120"), "v1"));
    }

    static List<RiskSignal> risks() {
        return List.of(new RiskSignal("risk-1", "600519", "VALUATION", "估值风险",
                "估值分位处于历史高位", 3, LocalDate.of(2026, 8, 22)));
    }
}
