package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.market.MarketQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function component that maps (quote, metrics, risks, evidence) into a
 * {@link StockAiAnalysisService.ResearchGuidance} decision. Splitting it out keeps
 * the AI orchestration class small and makes the rules independently testable.
 */
@Component
public class GuidanceScorer {
    private static final String GUIDANCE_VERSION = "research-guidance-v1";

    public String guidanceVersion() {
        return GUIDANCE_VERSION;
    }

    public StockAiAnalysisService.ResearchGuidance score(
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<StockAiAnalysisService.EvidencePayload> evidence,
            List<String> positivePoints,
            List<String> riskPoints
    ) {
        int completeness = Math.min(100,
                (quote.realtime() ? 25 : 12)
                        + Math.min(30, metrics.size() * 4)
                        + Math.min(25, evidence.size() * 4)
                        + (risks.isEmpty() ? 8 : 15));
        BigDecimal roe = metric(metrics, "ROE");
        BigDecimal cashQuality = metric(metrics, "OCF_NET_PROFIT");
        boolean materialRisk = risks.size() >= 3 || quote.changePercent().compareTo(BigDecimal.valueOf(-5)) <= 0;
        boolean qualitySupported = roe != null && roe.compareTo(BigDecimal.valueOf(0.10)) >= 0
                && cashQuality != null && cashQuality.compareTo(BigDecimal.valueOf(0.80)) >= 0;
        String priority = completeness < 52 ? "等待确认"
                : materialRisk ? "暂不进入候选"
                : qualitySupported && quote.changePercent().compareTo(BigDecimal.ZERO) >= 0 ? "优先研究"
                : "等待确认";

        List<String> supporting = positivePoints == null || positivePoints.isEmpty()
                ? List.of("当前已有行情快照；财务和公告证据仍需补齐后再判断研究优先级")
                : positivePoints.stream().limit(3).toList();
        List<String> confirmations = new ArrayList<>();
        if (metrics.isEmpty()) confirmations.add("补齐最新财务指标后，确认盈利质量、现金流与负债变化。");
        if (evidence.size() < 3) confirmations.add("补充最近公告、财报或行业资料，避免只依据行情作判断。");
        if (!quote.realtime()) confirmations.add("等待下一次实时行情快照，确认价格和流动性没有反向变化。");
        if (confirmations.isEmpty()) confirmations.add("跟踪后续公告与经营数据，验证当前支撑因素是否持续。");

        List<String> invalidations = riskPoints == null || riskPoints.isEmpty()
                ? List.of("若后续披露出现高严重度风险信号，或价格与流动性同步转弱，应从候选池移除。")
                : riskPoints.stream().limit(3).toList();
        List<String> actions = List.of(
                "在\u201c证据来源\u201d中检索最新财报或公告，核验支持因素。",
                "在\u201c近期事件\u201d中检查是否存在新增经营、监管或行业风险。",
                "将关键确认条件加入关注列表，等待下一次数据更新。"
        );
        String summary = switch (priority) {
            case "优先研究" -> "数据与基础条件已形成初步支撑，建议优先核验其持续性与估值安全边际。";
            case "暂不进入候选" -> "当前风险或价格波动尚未满足研究进入条件，先记录失效原因并等待变化。";
            default -> "已有部分信号，但关键财务或公开证据尚不完整；先完成待确认项再决定是否深入研究。";
        };
        return new StockAiAnalysisService.ResearchGuidance(
                priority, completeness, summary, supporting, confirmations, invalidations, actions
        );
    }

    public StockAiAnalysisService.ResearchGuidance restoredFromLegacy(StockAiAnalysisService.StockAiAnalysisResponse ignored) {
        return new StockAiAnalysisService.ResearchGuidance(
                "等待确认",
                0,
                "这是一份旧版报告；请重新生成分析以获得数据完整度、确认条件与失效信号。",
                List.of(),
                List.of("重新生成分析以核验最新行情、财务和证据。"),
                List.of(),
                List.of("查看证据来源并重新生成分析。")
        );
    }

    private BigDecimal metric(List<FinancialMetric> metrics, String code) {
        return metrics.stream()
                .filter(m -> code.equals(m.code()))
                .findFirst()
                .map(FinancialMetric::value)
                .orElse(null);
    }
}
