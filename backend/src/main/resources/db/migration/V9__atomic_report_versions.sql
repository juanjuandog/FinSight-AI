CREATE TABLE IF NOT EXISTS stock_analysis_report_versions (
    company_symbol VARCHAR(32) PRIMARY KEY REFERENCES companies(symbol) ON DELETE CASCADE,
    last_version INTEGER NOT NULL CHECK (last_version > 0)
);

INSERT INTO stock_analysis_report_versions(company_symbol, last_version)
SELECT company_symbol, max(report_version)
FROM stock_analysis_reports
GROUP BY company_symbol
ON CONFLICT (company_symbol)
DO UPDATE SET last_version = GREATEST(
    stock_analysis_report_versions.last_version,
    EXCLUDED.last_version
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_analysis_reports_company_version
    ON stock_analysis_reports(company_symbol, report_version);
