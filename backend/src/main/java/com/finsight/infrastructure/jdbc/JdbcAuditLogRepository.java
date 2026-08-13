package com.finsight.infrastructure.jdbc;

import com.finsight.domain.model.AuditLogEntry;
import com.finsight.domain.repository.AuditLogRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@Profile("postgres")
public class JdbcAuditLogRepository implements AuditLogRepository {
    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insert;

    public JdbcAuditLogRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.insert = new SimpleJdbcInsert(dataSource)
                .withTableName("audit_log")
                .usingGeneratedKeyColumns("id")
                .usingColumns("event_type", "actor", "client_key", "resource", "detail", "status", "created_at");
    }

    @Override
    public AuditLogEntry save(AuditLogEntry entry) {
        Map<String, Object> params = new HashMap<>();
        params.put("event_type", entry.eventType());
        params.put("actor", entry.actor());
        params.put("client_key", entry.clientKey());
        params.put("resource", entry.resource());
        params.put("detail", entry.detail());
        params.put("status", entry.status());
        params.put("created_at", entry.createdAt() == null ? Instant.now() : entry.createdAt());
        Number id = insert.executeAndReturnKey(params);
        return new AuditLogEntry(id.longValue(), entry.eventType(), entry.actor(), entry.clientKey(),
                entry.resource(), entry.detail(), entry.status(), entry.createdAt());
    }

    @Override
    public List<AuditLogEntry> findRecentByEventType(String eventType, int limit) {
        return jdbcTemplate.query(
                "SELECT id, event_type, actor, client_key, resource, detail, status, created_at "
                        + "FROM audit_log WHERE event_type = ? ORDER BY created_at DESC LIMIT ?",
                (rs, row) -> new AuditLogEntry(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("actor"),
                        rs.getString("client_key"),
                        rs.getString("resource"),
                        rs.getString("detail"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                eventType, Math.min(Math.max(limit, 1), 200)
        );
    }

    @Override
    public List<AuditLogEntry> findRecentByActor(String actor, int limit) {
        return jdbcTemplate.query(
                "SELECT id, event_type, actor, client_key, resource, detail, status, created_at "
                        + "FROM audit_log WHERE actor = ? ORDER BY created_at DESC LIMIT ?",
                (rs, row) -> new AuditLogEntry(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("actor"),
                        rs.getString("client_key"),
                        rs.getString("resource"),
                        rs.getString("detail"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                actor, Math.min(Math.max(limit, 1), 200)
        );
    }
}
