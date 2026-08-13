package com.finsight.application;

import com.finsight.domain.model.AuditLogEntry;
import com.finsight.domain.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditEventService {
    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditEventService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void recordSuccess(String eventType, String actor, String clientKey, String resource, String detail) {
        save(eventType, actor, clientKey, resource, detail, "SUCCESS");
    }

    public void recordFailure(String eventType, String actor, String clientKey, String resource, String detail) {
        save(eventType, actor, clientKey, resource, detail, "FAILURE");
    }

    public void recordRejection(String eventType, String actor, String clientKey, String resource, String detail) {
        save(eventType, actor, clientKey, resource, detail, "REJECTED");
    }

    private void save(String eventType, String actor, String clientKey, String resource, String detail, String status) {
        try {
            auditLogRepository.save(new AuditLogEntry(
                    0, eventType, actor, clientKey, resource, detail, status, Instant.now()
            ));
        } catch (RuntimeException ex) {
            log.warn("Audit log write failed for {} resource={}: {}", eventType, resource, ex.getMessage());
        }
    }
}
