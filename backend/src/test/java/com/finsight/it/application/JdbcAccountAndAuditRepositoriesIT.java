package com.finsight.it.application;

import com.finsight.domain.model.AuditLogEntry;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.UserAccount;
import com.finsight.domain.repository.AuditLogRepository;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.UserAuthRepository;
import com.finsight.domain.repository.UserWatchlistRepository;
import com.finsight.it.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcAccountAndAuditRepositoriesIT extends AbstractPostgresIT {
    @Autowired
    UserAuthRepository authRepository;

    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    UserWatchlistRepository watchlistRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void seedUserAndCompany() {
        authRepository.create(user("user-1", "owner@example.com"));
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "白酒"));
    }

    @Test
    void userCanBeFoundByEmailAndId() {
        assertThat(authRepository.findByEmail("owner@example.com")).contains(user("user-1", "owner@example.com"));
        assertThat(authRepository.findById("user-1")).contains(user("user-1", "owner@example.com"));
    }

    @Test
    void duplicateEmailIsRejectedByPostgres() {
        assertThatThrownBy(() -> authRepository.create(user("user-2", "owner@example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void activeSessionResolvesUser() {
        authRepository.saveSession("session-hash", "user-1", Instant.now().plusSeconds(60));

        assertThat(authRepository.findBySessionHash("session-hash"))
                .get()
                .extracting(UserAccount::id)
                .isEqualTo("user-1");
    }

    @Test
    void expiredSessionDoesNotResolveUser() {
        authRepository.saveSession("session-hash", "user-1", Instant.now().minusSeconds(1));

        assertThat(authRepository.findBySessionHash("session-hash")).isEmpty();
    }

    @Test
    void revokedSessionDoesNotResolveUser() {
        authRepository.saveSession("session-hash", "user-1", Instant.now().plusSeconds(60));
        authRepository.revokeSession("session-hash");

        assertThat(authRepository.findBySessionHash("session-hash")).isEmpty();
    }

    @Test
    void passwordResetTokenCanOnlyBeConsumedOnce() {
        authRepository.savePasswordResetToken("user-1", "reset-hash", Instant.now().plusSeconds(60));

        assertThat(authRepository.consumePasswordResetToken("reset-hash")).isPresent();
        assertThat(authRepository.consumePasswordResetToken("reset-hash")).isEmpty();
    }

    @Test
    void expiredPasswordResetTokenCannotBeConsumed() {
        authRepository.savePasswordResetToken("user-1", "reset-hash", Instant.now().minusSeconds(1));

        assertThat(authRepository.consumePasswordResetToken("reset-hash")).isEmpty();
    }

    @Test
    void updatingPasswordRevokesExistingSessions() {
        authRepository.saveSession("session-hash", "user-1", Instant.now().plusSeconds(60));

        authRepository.updatePassword("user-1", "new-password-hash");

        assertThat(authRepository.findById("user-1").orElseThrow().passwordHash()).isEqualTo("new-password-hash");
        assertThat(authRepository.findBySessionHash("session-hash")).isEmpty();
    }

    @Test
    void emailVerificationCodeCanOnlyBeConsumedOnce() {
        authRepository.saveEmailVerificationCode("owner@example.com", "code-hash", Instant.now().plusSeconds(60));

        assertThat(authRepository.consumeEmailVerificationCode("owner@example.com", "code-hash")).isTrue();
        assertThat(authRepository.consumeEmailVerificationCode("owner@example.com", "code-hash")).isFalse();
    }

    @Test
    void wrongEmailVerificationCodeIsRejected() {
        authRepository.saveEmailVerificationCode("owner@example.com", "code-hash", Instant.now().plusSeconds(60));

        assertThat(authRepository.consumeEmailVerificationCode("owner@example.com", "wrong-hash")).isFalse();
    }

    @Test
    void watchlistAddIsIdempotent() {
        watchlistRepository.add("user-1", "600519");
        watchlistRepository.add("user-1", "600519");

        assertThat(watchlistRepository.findByUserId("user-1"))
                .hasSize(1)
                .first()
                .satisfies(item -> assertThat(item.company().symbol()).isEqualTo("600519"));
    }

    @Test
    void watchlistRemoveDeletesOnlyRequestedItem() {
        companyRepository.save(new Company("000001", "平安银行", "SZ", "银行"));
        watchlistRepository.add("user-1", "600519");
        watchlistRepository.add("user-1", "000001");

        watchlistRepository.remove("user-1", "600519");

        assertThat(watchlistRepository.findByUserId("user-1"))
                .extracting(item -> item.company().symbol())
                .containsExactly("000001");
    }

    @Test
    void auditSaveAllocatesDatabaseId() {
        AuditLogEntry saved = auditLogRepository.save(audit("auth.login", "owner@example.com", Instant.now()));

        assertThat(saved.id()).isPositive();
    }

    @Test
    void auditQueryFiltersByEventTypeAndUsesNewestFirstOrdering() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        auditLogRepository.save(audit("auth.login", "owner@example.com", now.minusSeconds(10)));
        auditLogRepository.save(audit("auth.login", "owner@example.com", now));
        auditLogRepository.save(audit("auth.logout", "owner@example.com", now.plusSeconds(1)));

        assertThat(auditLogRepository.findRecentByEventType("auth.login", 10))
                .extracting(AuditLogEntry::eventType, AuditLogEntry::createdAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("auth.login", now),
                        org.assertj.core.groups.Tuple.tuple("auth.login", now.minusSeconds(10))
                );
    }

    @Test
    void auditQueryFiltersByActorAndHonoursLimit() {
        Instant now = Instant.now();
        auditLogRepository.save(audit("auth.login", "owner@example.com", now.minusSeconds(1)));
        auditLogRepository.save(audit("auth.logout", "owner@example.com", now));
        auditLogRepository.save(audit("auth.login", "other@example.com", now.plusSeconds(1)));

        assertThat(auditLogRepository.findRecentByActor("owner@example.com", 1))
                .hasSize(1)
                .first()
                .extracting(AuditLogEntry::eventType)
                .isEqualTo("auth.logout");
    }

    private UserAccount user(String id, String email) {
        return new UserAccount(id, email, "password-hash", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private AuditLogEntry audit(String eventType, String actor, Instant createdAt) {
        return new AuditLogEntry(
                0,
                eventType,
                actor,
                "client-key",
                "/api/auth",
                "integration test",
                "SUCCESS",
                createdAt
        );
    }
}
