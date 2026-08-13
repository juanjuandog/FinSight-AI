package com.finsight.application;

import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists AI analysis responses to the versioned stock_analysis_reports table and seeds
 * the analysis cache. Extracted from {@link StockAiAnalysisService} so the orchestration
 * class no longer mixes domain rules with storage I/O.
 */
@Service
public class ReportPersistenceService {
    private final StockAnalysisReportRepository reportRepository;
    private final StockAnalysisCache analysisCache;
    private final java.time.Duration analysisCacheTtl;

    public ReportPersistenceService(
            StockAnalysisReportRepository reportRepository,
            StockAnalysisCache analysisCache,
            @org.springframework.beans.factory.annotation.Value("${finsight.cache.analysis-ttl:PT6H}")
            java.time.Duration analysisCacheTtl
    ) {
        this.reportRepository = reportRepository;
        this.analysisCache = analysisCache;
        this.analysisCacheTtl = analysisCacheTtl;
    }

    public StockAiAnalysisService.StockAiAnalysisResponse persist(
            String symbol,
            String contextHash,
            String dataSnapshotHash,
            String cacheKey,
            StockAiAnalysisService.StockAiAnalysisResponse response
    ) {
        Instant generatedAt = Instant.now();
        String reportId = UUID.randomUUID().toString();
        int reportVersion = reportRepository.nextVersion(symbol);
        StockAiAnalysisService.StockAiAnalysisResponse enriched = response.withPersistence(
                reportId, generatedAt, false, dataSnapshotHash, reportVersion
        );
        reportRepository.save(new StockAnalysisReport(
                reportId,
                symbol,
                safe(enriched.rating(), "中性"),
                safe(enriched.summary(), "暂无分析摘要"),
                safeList(enriched.positivePoints()),
                safeList(enriched.riskPoints()),
                enriched.confidence(),
                safeList(enriched.citations()),
                safe(enriched.model(), "unknown"),
                safe(enriched.source(), "unknown"),
                enriched.aiGenerated(),
                contextHash,
                dataSnapshotHash,
                reportVersion,
                generatedAt
        ));
        analysisCache.put(cacheKey, enriched, analysisCacheTtl);
        return enriched;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
