package com.finsight.application;

import com.finsight.application.diff.MyersDiff;
import com.finsight.domain.model.ReportDiff;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class ReportDiffService {
    private final StockAnalysisReportRepository reportRepository;

    public ReportDiffService(StockAnalysisReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public ReportDiff diff(String symbol, String fromId, String toId) {
        String normalizedSymbol = normalizeSymbol(symbol);
        StockAnalysisReport from = requireReport(normalizedSymbol, fromId);
        StockAnalysisReport to = requireReport(normalizedSymbol, toId);
        return new ReportDiff(
                normalizedSymbol,
                snapshot(from),
                snapshot(to),
                field("rating", List.of(safe(from.rating())), List.of(safe(to.rating()))),
                field("summary", sentences(from.summary()), sentences(to.summary())),
                field("positivePoints", safeList(from.positivePoints()), safeList(to.positivePoints())),
                field("riskPoints", safeList(from.riskPoints()), safeList(to.riskPoints())),
                field("citations", safeList(from.citations()), safeList(to.citations())),
                !Objects.equals(from.contextHash(), to.contextHash()),
                !Objects.equals(from.dataSnapshotHash(), to.dataSnapshotHash()),
                to.reportVersion() - from.reportVersion()
        );
    }

    public StockAnalysisReport requireReport(String symbol, String reportId) {
        String normalizedSymbol = normalizeSymbol(symbol);
        StockAnalysisReport report = reportRepository.findById(normalizeId(reportId))
                .orElseThrow(() -> new ReportNotFoundException("Research report not found"));
        if (!normalizedSymbol.equalsIgnoreCase(report.companySymbol())) {
            throw new ReportNotFoundException("Research report not found for " + normalizedSymbol);
        }
        return report;
    }

    private ReportDiff.ReportSnapshot snapshot(StockAnalysisReport report) {
        return new ReportDiff.ReportSnapshot(
                report.id(),
                report.reportVersion(),
                report.generatedAt(),
                report.contextHash(),
                report.dataSnapshotHash()
        );
    }

    private ReportDiff.FieldDiff field(String name, List<String> before, List<String> after) {
        List<ReportDiff.DiffSegment> segments = compact(MyersDiff.diff(before, after));
        return new ReportDiff.FieldDiff(name, before, after, segments, !before.equals(after));
    }

    private List<ReportDiff.DiffSegment> compact(List<MyersDiff.Edit<String>> edits) {
        List<ReportDiff.DiffSegment> segments = new ArrayList<>();
        MyersDiff.Operation operation = null;
        StringBuilder text = new StringBuilder();
        for (MyersDiff.Edit<String> edit : edits) {
            if (operation != edit.operation()) {
                appendSegment(segments, operation, text);
                operation = edit.operation();
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(edit.value());
        }
        appendSegment(segments, operation, text);
        return List.copyOf(segments);
    }

    private void appendSegment(
            List<ReportDiff.DiffSegment> segments,
            MyersDiff.Operation operation,
            StringBuilder text
    ) {
        if (operation == null || text.isEmpty()) {
            return;
        }
        segments.add(new ReportDiff.DiffSegment(
                ReportDiff.DiffOperation.valueOf(operation.name()),
                text.toString()
        ));
        text.setLength(0);
    }

    private List<String> sentences(String value) {
        String normalized = safe(value).trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.SIMPLIFIED_CHINESE);
        iterator.setText(normalized);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = normalized.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                result.add(sentence);
            }
        }
        return result.isEmpty() ? List.of(normalized) : List.copyOf(result);
    }

    private List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Company symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeId(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("Report id is required");
        }
        return reportId.trim();
    }

    public static class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(String message) {
            super(message);
        }
    }
}
