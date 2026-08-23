package com.finsight.infrastructure;

import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
@Profile("!postgres")
public class InMemoryStockAnalysisReportRepository implements StockAnalysisReportRepository {
    private final CopyOnWriteArrayList<StockAnalysisReport> reports = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> versions = new ConcurrentHashMap<>();

    @Override
    public StockAnalysisReport save(StockAnalysisReport report) {
        reports.removeIf(existing -> existing.id().equals(report.id()));
        reports.add(report);
        return report;
    }

    @Override
    public Optional<StockAnalysisReport> findById(String reportId) {
        return reports.stream()
                .filter(report -> report.id().equals(reportId))
                .findFirst();
    }

    @Override
    public Optional<StockAnalysisReport> findLatest(String companySymbol) {
        return reports.stream()
                .filter(report -> report.companySymbol().equals(companySymbol))
                .max(Comparator.comparing(StockAnalysisReport::generatedAt));
    }

    @Override
    public List<StockAnalysisReport> findByCompanySymbol(String companySymbol, int limit) {
        return reports.stream()
                .filter(report -> report.companySymbol().equals(companySymbol))
                .sorted(Comparator.comparing(StockAnalysisReport::generatedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public long countByCompanySymbol(String companySymbol) {
        return reports.stream()
                .filter(report -> report.companySymbol().equals(companySymbol))
                .count();
    }

    @Override
    public int nextVersion(String companySymbol) {
        return versions.computeIfAbsent(companySymbol, ignored -> new AtomicInteger()).incrementAndGet();
    }
}
