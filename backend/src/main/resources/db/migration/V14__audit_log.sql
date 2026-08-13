-- V14: Audit log for security and compliance diagnostics.
-- Captures auth attempts, write actions, AI analysis, and rate-limit rejections.

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor VARCHAR(128),
    client_key VARCHAR(128),
    resource VARCHAR(256),
    detail TEXT,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_event_time
    ON audit_log(event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_time
    ON audit_log(actor, created_at DESC);
