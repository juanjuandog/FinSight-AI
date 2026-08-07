package com.finsight.infrastructure.jdbc;

import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
public class JdbcWorkflowTaskRepository implements WorkflowTaskRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    public JdbcWorkflowTaskRepository(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    @Override
    public WorkflowTask save(WorkflowTask task) {
        jdbcTemplate.update("""
                INSERT INTO workflow_tasks(
                    id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                    payload, error_message, lease_owner, fencing_token
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id)
                DO UPDATE SET task_type = EXCLUDED.task_type,
                              idempotency_key = EXCLUDED.idempotency_key,
                              status = EXCLUDED.status,
                              stage = EXCLUDED.stage,
                              attempts = EXCLUDED.attempts,
                              updated_at = EXCLUDED.updated_at,
                              payload = EXCLUDED.payload,
                              error_message = EXCLUDED.error_message,
                              lease_owner = EXCLUDED.lease_owner,
                              fencing_token = EXCLUDED.fencing_token
                """,
                task.id(),
                task.taskType(),
                task.idempotencyKey(),
                task.status().name(),
                task.stage().name(),
                task.attempts(),
                Timestamp.from(task.createdAt()),
                Timestamp.from(task.updatedAt()),
                jsonColumnMapper.jsonb(task.payload()),
                task.errorMessage(),
                task.leaseOwner(),
                task.fencingToken()
        );
        return task;
    }

    @Override
    public Optional<WorkflowTask> saveIfOwned(
            WorkflowTask task,
            WorkflowStatus expectedStatus,
            Long expectedFencingToken
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE workflow_tasks
                SET task_type = ?,
                    idempotency_key = ?,
                    status = ?,
                    stage = ?,
                    attempts = ?,
                    updated_at = ?,
                    payload = ?,
                    error_message = ?,
                    lease_owner = ?,
                    fencing_token = ?
                WHERE id = ?
                  AND status = ?
                  AND fencing_token IS NOT DISTINCT FROM ?
                """,
                task.taskType(),
                task.idempotencyKey(),
                task.status().name(),
                task.stage().name(),
                task.attempts(),
                Timestamp.from(task.updatedAt()),
                jsonColumnMapper.jsonb(task.payload()),
                task.errorMessage(),
                task.leaseOwner(),
                task.fencingToken(),
                task.id(),
                expectedStatus.name(),
                expectedFencingToken
        );
        return updated == 1 ? Optional.of(task) : Optional.empty();
    }

    @Override
    public WorkflowTask createIfAbsent(WorkflowTask task) {
        try {
            return save(task);
        } catch (DataIntegrityViolationException ex) {
            return findByIdempotencyKey(task.idempotencyKey()).orElseThrow(() -> ex);
        }
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM workflow_tasks
                WHERE idempotency_key = ?
                """, Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    @Override
    public Optional<WorkflowTask> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                       payload::text, error_message, lease_owner, fencing_token
                FROM workflow_tasks
                WHERE id = ?
                """, this::mapTask, id).stream().findFirst();
    }

    @Override
    public Optional<WorkflowTask> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                       payload::text, error_message, lease_owner, fencing_token
                FROM workflow_tasks
                WHERE idempotency_key = ?
                """, this::mapTask, idempotencyKey).stream().findFirst();
    }

    @Override
    public List<WorkflowTask> findAll() {
        return jdbcTemplate.query("""
                SELECT id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                       payload::text, error_message, lease_owner, fencing_token
                FROM workflow_tasks
                ORDER BY created_at DESC
                """, this::mapTask);
    }

    @Override
    public List<WorkflowTask> findByRootTaskId(String rootTaskId) {
        return jdbcTemplate.query("""
                SELECT id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                       payload::text, error_message, lease_owner, fencing_token
                FROM workflow_tasks
                WHERE id = ? OR payload ->> 'rootTaskId' = ?
                ORDER BY created_at ASC
                """, this::mapTask, rootTaskId, rootTaskId);
    }

    @Override
    public List<WorkflowTask> findByStatusUpdatedBefore(WorkflowStatus status, Instant cutoff) {
        return jdbcTemplate.query("""
                SELECT id, task_type, idempotency_key, status, stage, attempts, created_at, updated_at,
                       payload::text, error_message, lease_owner, fencing_token
                FROM workflow_tasks
                WHERE status = ? AND updated_at < ?
                ORDER BY updated_at ASC
                """, this::mapTask, status.name(), Timestamp.from(cutoff));
    }

    private WorkflowTask mapTask(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WorkflowTask(
                rs.getString("id"),
                rs.getString("task_type"),
                rs.getString("idempotency_key"),
                WorkflowStatus.valueOf(rs.getString("status")),
                AgentWorkflowStage.valueOf(rs.getString("stage")),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                jsonColumnMapper.objectMap(rs.getString("payload")),
                rs.getString("error_message"),
                rs.getString("lease_owner"),
                rs.getObject("fencing_token") == null ? null : rs.getLong("fencing_token")
        );
    }
}
