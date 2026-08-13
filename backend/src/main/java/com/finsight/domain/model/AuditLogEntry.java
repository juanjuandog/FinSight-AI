package com.finsight.domain.model;

import java.time.Instant;

public record AuditLogEntry(
        long id,
        String eventType,
        String actor,
        String clientKey,
        String resource,
        String detail,
        String status,
        Instant createdAt
) {
    public AuditLogEntry withoutId() {
        return new AuditLogEntry(0, eventType, actor, clientKey, resource, detail, status, createdAt);
    }
}
