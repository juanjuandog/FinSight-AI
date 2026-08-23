package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class ReportMarkdownExporter {
    private static final String TEMPLATE_ROOT = "templates/export/";
    private final Parser parser = Parser.builder().build();
    private final MarkdownRenderer renderer = MarkdownRenderer.builder().build();

    public String export(
            StockAnalysisReport report,
            Company company,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks
    ) {
        String markdown = render("report.md.tmpl", Map.ofEntries(
                Map.entry("companyName", escape(company.name())),
                Map.entry("symbol", escape(report.companySymbol())),
                Map.entry("rating", escape(report.rating())),
                Map.entry("confidence", Integer.toString(report.confidence())),
                Map.entry("generatedAt", report.generatedAt().toString()),
                Map.entry("model", escape(report.model())),
                Map.entry("summary", normalizeMarkdown(report.summary())),
                Map.entry("positivePoints", bullets(report.positivePoints(), "暂无支持依据。")),
                Map.entry("riskPoints", bullets(report.riskPoints(), "暂无风险信号。")),
                Map.entry("metricTable", metricTable(metrics)),
                Map.entry("riskTable", riskTable(risks)),
                Map.entry("citations", citations(report.citations())),
                Map.entry("reportVersion", Integer.toString(report.reportVersion())),
                Map.entry("dataSnapshotHash", escape(report.dataSnapshotHash())),
                Map.entry("contextHash", escape(report.contextHash())),
                Map.entry("source", escape(report.source()))
        ));
        return markdown.strip() + "\n";
    }

    private String metricTable(List<FinancialMetric> metrics) {
        String rows = safeList(metrics).stream()
                .sorted(Comparator.comparing(FinancialMetric::fiscalYear).reversed()
                        .thenComparing(FinancialMetric::code))
                .limit(12)
                .map(metric -> "| " + escape(metric.fiscalYear().toString())
                        + " | " + escape(metric.name())
                        + " | " + escape(format(metric.value()))
                        + " | " + escape(metric.formulaVersion()) + " |")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("| — | 暂无财务指标 | — | — |");
        return render("metric-table.md.tmpl", Map.of("rows", rows)).strip();
    }

    private String riskTable(List<RiskSignal> risks) {
        String rows = safeList(risks).stream()
                .sorted(Comparator.comparing(RiskSignal::detectedAt).reversed())
                .limit(12)
                .map(risk -> "| " + escape(risk.detectedAt().toString())
                        + " | " + escape(risk.title())
                        + " | " + risk.severity()
                        + " | " + escape(risk.explanation()) + " |")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("| — | 暂无结构化风险信号 | — | — |");
        return render("risk-table.md.tmpl", Map.of("rows", rows)).strip();
    }

    private String citations(List<String> citations) {
        List<String> values = citations == null ? List.of() : citations.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (values.isEmpty()) {
            return "- 暂无引用。";
        }
        return values.stream().map(this::citation).reduce((left, right) -> left + "\n" + right).orElseThrow();
    }

    private String citation(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return "- [" + escape(value) + "](" + value.replace(" ", "%20") + ")";
        }
        return "- " + escape(value);
    }

    private String bullets(List<String> values, String empty) {
        if (values == null || values.isEmpty()) {
            return "- " + empty;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> "- " + escape(value))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- " + empty);
    }

    private String normalizeMarkdown(String value) {
        String source = value == null || value.isBlank() ? "暂无分析摘要。" : value.trim();
        return renderer.render(parser.parse(source)).strip();
    }

    private String render(String name, Map<String, String> values) {
        String template = load(name);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        if (template.matches("(?s).*\\{\\{[A-Za-z][A-Za-z0-9]*}}.*")) {
            throw new IllegalStateException("Unresolved export template value in " + name);
        }
        return template;
    }

    private String load(String name) {
        try {
            return new ClassPathResource(TEMPLATE_ROOT + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to load export template " + name, ex);
        }
    }

    private String format(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
