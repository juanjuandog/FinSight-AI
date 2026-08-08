package com.finsight.infrastructure.jdbc;

import com.finsight.domain.model.PasswordResetToken;
import com.finsight.domain.model.UserAccount;
import com.finsight.domain.repository.UserAuthRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
public class JdbcUserAuthRepository implements UserAuthRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcUserAuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserAccount create(UserAccount user) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO users(id, email, password_hash, status, created_at, deleted_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, user.id(), user.email(), user.passwordHash(), user.status(),
                    Timestamp.from(user.createdAt()), user.deletedAt() == null ? null : Timestamp.from(user.deletedAt()));
            return user;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("User email already exists", ex);
        }
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        return query("SELECT id, email, password_hash, status, created_at, deleted_at FROM users WHERE email = ?", email)
                .stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return query("SELECT id, email, password_hash, status, created_at, deleted_at FROM users WHERE id = ?", id)
                .stream().findFirst();
    }

    @Override
    public void saveSession(String tokenHash, String userId, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO user_sessions(token_hash, user_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, userId, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<UserAccount> findBySessionHash(String tokenHash) {
        return query("""
                SELECT u.id, u.email, u.password_hash, u.status, u.created_at, u.deleted_at
                FROM user_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                """, tokenHash).stream().findFirst();
    }

    @Override
    public void revokeSession(String tokenHash) {
        jdbcTemplate.update("UPDATE user_sessions SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL", tokenHash);
    }

    @Override
    public void savePasswordResetToken(String userId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO password_reset_tokens(user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userId, tokenHash, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<PasswordResetToken> consumePasswordResetToken(String tokenHash) {
        return jdbcTemplate.query("""
                UPDATE password_reset_tokens
                SET used_at = now()
                WHERE id = (
                    SELECT id FROM password_reset_tokens
                    WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                    ORDER BY created_at DESC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING user_id, token_hash, expires_at
                """, (rs, rowNum) -> new PasswordResetToken(
                rs.getString("user_id"), rs.getString("token_hash"), rs.getTimestamp("expires_at").toInstant()
        ), tokenHash).stream().findFirst();
    }

    @Override
    public void updatePassword(String userId, String passwordHash) {
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ? AND status = 'ACTIVE'", passwordHash, userId);
        jdbcTemplate.update("UPDATE user_sessions SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    @Override
    public void saveEmailVerificationCode(String email, String codeHash, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO email_verification_codes(email, code_hash, expires_at)
                VALUES (?, ?, ?)
                """, email, codeHash, Timestamp.from(expiresAt));
    }

    @Override
    public boolean consumeEmailVerificationCode(String email, String codeHash) {
        return !jdbcTemplate.query("""
                UPDATE email_verification_codes
                SET used_at = now()
                WHERE id = (
                    SELECT id FROM email_verification_codes
                    WHERE email = ? AND code_hash = ? AND used_at IS NULL AND expires_at > now()
                    ORDER BY created_at DESC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"), email, codeHash).isEmpty();
    }

    private List<UserAccount> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UserAccount(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()
        ), args);
    }
}
