package com.finsight.application;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
public class ReportExportService {
    private static final int MAX_HISTORY = 50;
    private final StockAnalysisReportRepository reportRepository;
    private final CompanyRepository companyRepository;
    private final MetricRepository metricRepository;
    private final ReportDiffService reportDiffService;
    private final ReportMarkdownExporter markdownExporter;
    private final ReportPdfExporter pdfExporter;
    private final StockAnalysisCache cache;
    private final Duration exportCacheTtl;

    public ReportExportService(
            StockAnalysisReportRepository reportRepository,
            CompanyRepository companyRepository,
            MetricRepository metricRepository,
            ReportDiffService reportDiffService,
            ReportMarkdownExporter markdownExporter,
            ReportPdfExporter pdfExporter,
            StockAnalysisCache cache,
            @Value("${finsight.cache.report-export-ttl:PT24H}") Duration exportCacheTtl
    ) {
        this.reportRepository = reportRepository;
        this.companyRepository = companyRepository;
        this.metricRepository = metricRepository;
        this.reportDiffService = reportDiffService;
        this.markdownExporter = markdownExporter;
        this.pdfExporter = pdfExporter;
        this.cache = cache;
        this.exportCacheTtl = exportCacheTtl;
    }

    public List<StockAnalysisReport> history(String symbol, int limit) {
        return reportRepository.findByCompanySymbol(normalizeSymbol(symbol), Math.min(Math.max(limit, 1), MAX_HISTORY));
    }

    public String markdown(String symbol, String reportId) {
        StockAnalysisReport report = reportDiffService.requireReport(normalizeSymbol(symbol), reportId);
        ExportContext context = context(report);
        return markdownExporter.export(context.report(), context.company(), context.metrics(), context.risks());
    }

    public byte[] pdf(String symbol, String reportId) {
        StockAnalysisReport report = reportDiffService.requireReport(normalizeSymbol(symbol), reportId);
        String key = report.companySymbol() + ":" + report.id() + ":pdf:v1";
        return cache.getBinary(key).orElseGet(() -> {
            ExportContext context = context(report);
            byte[] rendered = pdfExporter.export(
                    context.report(), context.company(), context.metrics(), context.risks());
            cache.putBinary(key, rendered, exportCacheTtl);
            return rendered;
        });
    }

    private ExportContext context(StockAnalysisReport report) {
        String symbol = report.companySymbol();
        Company company = companyRepository.findBySymbol(symbol)
                .orElseGet(() -> new Company(symbol, "股票 " + symbol, "CN", "待补充"));
        return new ExportContext(
                report,
                company,
                metricRepository.findMetrics(symbol),
                metricRepository.findRiskSignals(symbol)
        );
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Company symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record ExportContext(
            StockAnalysisReport report,
            Company company,
            List<com.finsight.domain.model.FinancialMetric> metrics,
            List<com.finsight.domain.model.RiskSignal> risks
    ) {
    }
}
