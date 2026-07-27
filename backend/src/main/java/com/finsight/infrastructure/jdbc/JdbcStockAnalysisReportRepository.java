package com.finsight.infrastructure.jdbc;

import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
public class JdbcStockAnalysisReportRepository implements StockAnalysisReportRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    public JdbcStockAnalysisReportRepository(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    @Override
    public StockAnalysisReport save(StockAnalysisReport report) {
        jdbcTemplate.update("""
                INSERT INTO stock_analysis_reports(
                    id, company_symbol, rating, summary, positive_points, risk_points, confidence,
                    citations, model, source, ai_generated, context_hash, data_snapshot_hash, report_version, generated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id)
                DO UPDATE SET rating = EXCLUDED.rating,
                              summary = EXCLUDED.summary,
                              positive_points = EXCLUDED.positive_points,
                              risk_points = EXCLUDED.risk_points,
                              confidence = EXCLUDED.confidence,
                              citations = EXCLUDED.citations,
                              model = EXCLUDED.model,
                              source = EXCLUDED.source,
                              ai_generated = EXCLUDED.ai_generated,
                              context_hash = EXCLUDED.context_hash,
                              data_snapshot_hash = EXCLUDED.data_snapshot_hash,
                              report_version = EXCLUDED.report_version,
                              generated_at = EXCLUDED.generated_at
                """,
                report.id(),
                report.companySymbol(),
                report.rating(),
                report.summary(),
                jsonColumnMapper.jsonb(report.positivePoints()),
                jsonColumnMapper.jsonb(report.riskPoints()),
                report.confidence(),
                jsonColumnMapper.jsonb(report.citations()),
                report.model(),
                report.source(),
                report.aiGenerated(),
                report.contextHash(),
                report.dataSnapshotHash(),
                report.reportVersion(),
                Timestamp.from(report.generatedAt())
        );
        return report;
    }

    @Override
    public Optional<StockAnalysisReport> findLatest(String companySymbol) {
        return jdbcTemplate.query("""
                SELECT id, company_symbol, rating, summary, positive_points::text, risk_points::text,
                       confidence, citations::text, model, source, ai_generated, context_hash,
                       data_snapshot_hash, report_version, generated_at
                FROM stock_analysis_reports
                WHERE company_symbol = ?
                ORDER BY generated_at DESC
                LIMIT 1
                """, this::mapReport, companySymbol).stream().findFirst();
    }

    @Override
    public List<StockAnalysisReport> findByCompanySymbol(String companySymbol, int limit) {
        return jdbcTemplate.query("""
                SELECT id, company_symbol, rating, summary, positive_points::text, risk_points::text,
                       confidence, citations::text, model, source, ai_generated, context_hash,
                       data_snapshot_hash, report_version, generated_at
                FROM stock_analysis_reports
                WHERE company_symbol = ?
                ORDER BY generated_at DESC
                LIMIT ?
                """, this::mapReport, companySymbol, Math.max(1, limit));
    }

    @Override
    public long countByCompanySymbol(String companySymbol) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM stock_analysis_reports
                WHERE company_symbol = ?
                """, Long.class, companySymbol);
        return count == null ? 0 : count;
    }

    @Override
    public int nextVersion(String companySymbol) {
        Integer version = jdbcTemplate.queryForObject("""
                INSERT INTO stock_analysis_report_versions(company_symbol, last_version)
                VALUES (?, 1)
                ON CONFLICT (company_symbol)
                DO UPDATE SET last_version = stock_analysis_report_versions.last_version + 1
                RETURNING last_version
                """, Integer.class, companySymbol);
        if (version == null) {
            throw new IllegalStateException("Failed to allocate report version for " + companySymbol);
        }
        return version;
    }

    private StockAnalysisReport mapReport(ResultSet rs, int rowNum) throws SQLException {
        return new StockAnalysisReport(
                rs.getString("id"),
                rs.getString("company_symbol"),
                rs.getString("rating"),
                rs.getString("summary"),
                jsonColumnMapper.stringList(rs.getString("positive_points")),
                jsonColumnMapper.stringList(rs.getString("risk_points")),
                rs.getInt("confidence"),
                jsonColumnMapper.stringList(rs.getString("citations")),
                rs.getString("model"),
                rs.getString("source"),
                rs.getBoolean("ai_generated"),
                rs.getString("context_hash"),
                rs.getString("data_snapshot_hash"),
                rs.getInt("report_version"),
                rs.getTimestamp("generated_at").toInstant()
        );
    }
}
