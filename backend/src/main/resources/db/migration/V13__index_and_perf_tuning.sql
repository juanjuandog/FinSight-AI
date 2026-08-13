-- V13: Performance tuning for FinSight retrieval and analysis storage.
-- Adds indices supporting contextHash lookup, latest-version scan, and
-- replaces the IVFFLAT vector index with HNSW for stable recall at scale.

-- Covering index for "latest report by company" + cache key lookup.
CREATE INDEX IF NOT EXISTS idx_stock_analysis_reports_company_context_time
    ON stock_analysis_reports(company_symbol, context_hash, generated_at DESC);

-- BRIN index for time-range scans of historical reports.
CREATE INDEX IF NOT EXISTS idx_stock_analysis_reports_generated_brin
    ON stock_analysis_reports USING BRIN(generated_at) WITH (pages_per_range = 32);

-- Drop and recreate the ivfflat index as HNSW for better recall and graceful
-- rebuilds on small datasets. HNSW is supported by pgvector >= 0.5.
DROP INDEX IF EXISTS idx_document_chunks_embedding;
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Lookup index for workflow lease diagnostics and audit queries.
CREATE INDEX IF NOT EXISTS idx_workflow_tasks_idempotency
    ON workflow_tasks(idempotency_key);

CREATE INDEX IF NOT EXISTS idx_workflow_tasks_status_updated
    ON workflow_tasks(status, updated_at DESC);

-- Covering index for retrieval "chunks by company" scans used by both
-- fallback and rerank channels.
CREATE INDEX IF NOT EXISTS idx_document_chunks_company_type
    ON document_chunks(company_symbol, document_type, published_at DESC);
