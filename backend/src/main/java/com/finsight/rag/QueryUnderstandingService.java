package com.finsight.rag;

import com.finsight.domain.model.AskQuestionCommand;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class QueryUnderstandingService {
    public Map<String, Object> parse(AskQuestionCommand command) {
        String normalizedQuestion = command.question() == null ? "" : command.question().toLowerCase(Locale.ROOT);
        Map<String, Object> structured = new HashMap<>();
        structured.put("companySymbol", command.companySymbol());
        structured.put("timeRange", command.timeRange());
        structured.put("intent", inferIntent(normalizedQuestion));
        structured.put("requiresMetrics", containsAny(normalizedQuestion, "毛利率", "roe", "盈利能力", "现金流", "营收", "净利润", "资产负债率"));
        structured.put("requiresDocuments", true);
        return structured;
    }

    private String inferIntent(String question) {
        if (containsAny(question, "风险", "异常", "压力")) {
            return "RISK_ANALYSIS";
        }
        if (containsAny(question, "比较", "对比")) {
            return "COMPARISON";
        }
        if (containsAny(question, "总结", "发生了什么", "变化")) {
            return "TIMELINE_SUMMARY";
        }
        return "FINANCIAL_QA";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
