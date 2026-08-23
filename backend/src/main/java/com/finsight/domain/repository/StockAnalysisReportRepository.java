package com.finsight.domain.repository;

import com.finsight.domain.model.StockAnalysisReport;

import java.util.List;
import java.util.Optional;

public interface StockAnalysisReportRepository {
    StockAnalysisReport save(StockAnalysisReport report);

    Optional<StockAnalysisReport> findById(String reportId);

    Optional<StockAnalysisReport> findLatest(String companySymbol);

    List<StockAnalysisReport> findByCompanySymbol(String companySymbol, int limit);

    long countByCompanySymbol(String companySymbol);

    int nextVersion(String companySymbol);
}
