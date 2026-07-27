package com.finsight.infrastructure.jdbc;

import com.finsight.domain.model.RagTrace;
import com.finsight.domain.repository.RagTraceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcRagTraceRepository implements RagTraceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    public JdbcRagTraceRepository(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    @Override
    public void save(String companySymbol, String question, RagTrace trace) {
        jdbcTemplate.update("""
                INSERT INTO rag_traces(
                    trace_id, company_symbol, question, structured_query, retrieval_channels,
                    evidence_count, data_snapshot_hash, latency_millis, model_name, prompt_version, token_cost
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trace_id) DO NOTHING
                """,
                trace.traceId(),
                companySymbol,
                question,
                jsonColumnMapper.jsonb(trace.structuredQuery()),
                jsonColumnMapper.jsonb(trace.retrievalChannels()),
                trace.evidenceCount(),
                trace.dataSnapshotHash(),
                trace.latencyMillis(),
                "fallback-local",
                "rag-v1",
                null
        );
    }
}

