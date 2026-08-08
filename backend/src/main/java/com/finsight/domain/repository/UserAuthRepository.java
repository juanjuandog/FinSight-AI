package com.finsight.domain.repository;

import com.finsight.domain.model.PasswordResetToken;
import com.finsight.domain.model.UserAccount;

import java.time.Instant;
import java.util.Optional;

public interface UserAuthRepository {
    UserAccount create(UserAccount user);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findById(String id);

    void saveSession(String tokenHash, String userId, Instant expiresAt);

    Optional<UserAccount> findBySessionHash(String tokenHash);

    void revokeSession(String tokenHash);

    void savePasswordResetToken(String userId, String tokenHash, Instant expiresAt);

    Optional<PasswordResetToken> consumePasswordResetToken(String tokenHash);

    void updatePassword(String userId, String passwordHash);

    void saveEmailVerificationCode(String email, String codeHash, Instant expiresAt);

    boolean consumeEmailVerificationCode(String email, String codeHash);
}
