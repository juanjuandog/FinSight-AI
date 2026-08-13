package com.finsight.domain.repository;

import com.finsight.domain.model.AuditLogEntry;

import java.util.List;

public interface AuditLogRepository {
    AuditLogEntry save(AuditLogEntry entry);

    List<AuditLogEntry> findRecentByEventType(String eventType, int limit);

    List<AuditLogEntry> findRecentByActor(String actor, int limit);
}
