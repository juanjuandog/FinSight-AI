package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.market.MarketQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuidanceScorerTest {

    @Test
    void qualityAndLiquidityProducesPriority() {
        GuidanceScorer scorer = new GuidanceScorer();
        Company company = new Company("600519", "贵州茅台", "SH", "白酒");
        MarketQuote quote = new MarketQuote("600519", "SH", "贵州茅台",
                BigDecimal.valueOf(1700), BigDecimal.valueOf(0.5), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.now(), LocalTime.now(), "REALTIME", true, null);
        List<FinancialMetric> metrics = List.of(
                new FinancialMetric("600519", Year.of(2024), "ROE", "净资产收益率", new BigDecimal("0.22"), "v1"),
                new FinancialMetric("600519", Year.of(2024), "OCF_NET_PROFIT", "现金流", new BigDecimal("1.10"), "v1"),
                new FinancialMetric("600519", Year.of(2024), "GROSS", "毛利率", new BigDecimal("0.85"), "v1"),
                new FinancialMetric("600519", Year.of(2024), "REV", "营收同比", new BigDecimal("0.20"), "v1")
        );
        List<StockAiAnalysisService.EvidencePayload> evidence = List.of(
                new StockAiAnalysisService.EvidencePayload("d1", "公告", "ANNUAL", null, "s", "t"),
                new StockAiAnalysisService.EvidencePayload("d2", "公告", "ANNUAL", null, "s", "t"),
                new StockAiAnalysisService.EvidencePayload("d3", "公告", "ANNUAL", null, "s", "t"),
                new StockAiAnalysisService.EvidencePayload("d4", "公告", "ANNUAL", null, "s", "t")
        );

        StockAiAnalysisService.ResearchGuidance guidance = scorer.score(
                company, quote, metrics, List.of(), evidence,
                List.of("ROE 高"), List.of()
        );

        assertThat(guidance.researchPriority()).isEqualTo("优先研究");
        assertThat(guidance.dataCompleteness()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void materialRiskDowngradesToDefer() {
        GuidanceScorer scorer = new GuidanceScorer();
        Company company = new Company("600519", "贵州茅台", "SH", "白酒");
        MarketQuote quote = new MarketQuote("600519", "SH", "贵州茅台",
                BigDecimal.valueOf(1500), BigDecimal.valueOf(-6), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.now(), LocalTime.now(), "REALTIME", true, null);
        List<RiskSignal> risks = List.of(
                new RiskSignal("r1", "600519", "VAL", "估值过高", null, 4, LocalDate.now()),
                new RiskSignal("r2", "600519", "FLOW", "流动性差", null, 3, LocalDate.now()),
                new RiskSignal("r3", "600519", "GOV", "监管", null, 3, LocalDate.now())
        );
        List<FinancialMetric> metrics = List.of(
                new FinancialMetric("600519", Year.of(2024), "ROE", "ROE", new BigDecimal("0.20"), "v1"),
                new FinancialMetric("600519", Year.of(2024), "OCF_NET_PROFIT", "OCF", new BigDecimal("1.0"), "v1")
        );
        List<StockAiAnalysisService.EvidencePayload> evidence = List.of(
                new StockAiAnalysisService.EvidencePayload("d1", "公告", "ANNUAL", null, "s", "t"),
                new StockAiAnalysisService.EvidencePayload("d2", "公告", "ANNUAL", null, "s", "t"),
                new StockAiAnalysisService.EvidencePayload("d3", "公告", "ANNUAL", null, "s", "t")
        );

        StockAiAnalysisService.ResearchGuidance guidance = scorer.score(
                company, quote, metrics, risks, evidence,
                List.of(), List.of("估值过高")
        );

        assertThat(guidance.researchPriority()).isEqualTo("暂不进入候选");
    }

    @Test
    void lowCompletenessFallsBackToWait() {
        GuidanceScorer scorer = new GuidanceScorer();
        Company company = new Company("600519", "贵州茅台", "SH", "白酒");
        MarketQuote quote = new MarketQuote("600519", "SH", "贵州茅台",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.now(), LocalTime.now(), "FALLBACK", false, null);

        StockAiAnalysisService.ResearchGuidance guidance = scorer.score(
                company, quote, List.of(), List.of(), List.of(),
                List.of(), List.of()
        );

        assertThat(guidance.researchPriority()).isEqualTo("等待确认");
        assertThat(guidance.confirmationConditions()).isNotEmpty();
    }
}
