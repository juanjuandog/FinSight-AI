package com.finsight.infrastructure;

import com.finsight.domain.model.AuditLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditLogRepositoryTest {

    @Test
    void savesAndRetrievesByEventType() {
        InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
        repository.save(new AuditLogEntry(0, "auth.login", "u1", "u1@x.com", "user",
                "ok", "SUCCESS", Instant.now()));
        repository.save(new AuditLogEntry(0, "auth.login", "u2", "u2@x.com", "user",
                "wrong password", "FAILURE", Instant.now()));
        repository.save(new AuditLogEntry(0, "rate_limit.auth", null, "1.2.3.4", "/api/auth/login",
                "rejected", "REJECTED", Instant.now()));

        assertThat(repository.findRecentByEventType("auth.login", 10))
                .hasSize(2)
                .allMatch(entry -> "auth.login".equals(entry.eventType()));
        assertThat(repository.findRecentByActor("u1", 10)).hasSize(1);
    }

    @Test
    void capsInMemoryStoreAtMaxEntries() {
        InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
        for (int i = 0; i < 1200; i++) {
            repository.save(new AuditLogEntry(0, "spam", "u" + i, "client", "res",
                    "detail", "SUCCESS", Instant.now()));
        }
        assertThat(repository.findRecentByEventType("spam", 1000)).hasSizeLessThanOrEqualTo(200);
    }
}
