ALTER TABLE rag_traces
    ADD COLUMN IF NOT EXISTS data_snapshot_hash VARCHAR(64);
