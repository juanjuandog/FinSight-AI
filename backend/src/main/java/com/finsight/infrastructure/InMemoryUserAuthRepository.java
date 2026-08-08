package com.finsight.infrastructure;

import com.finsight.domain.model.PasswordResetToken;
import com.finsight.domain.model.UserAccount;
import com.finsight.domain.repository.UserAuthRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!postgres")
public class InMemoryUserAuthRepository implements UserAuthRepository {
    private final ConcurrentHashMap<String, UserAccount> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> emails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PasswordResetToken> resetTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VerificationCode> verificationCodes = new ConcurrentHashMap<>();

    @Override
    public synchronized UserAccount create(UserAccount user) {
        if (emails.putIfAbsent(user.email(), user.id()) != null) {
            throw new IllegalStateException("User email already exists");
        }
        users.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        String userId = emails.get(email);
        return userId == null ? Optional.empty() : findById(userId);
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public void saveSession(String tokenHash, String userId, Instant expiresAt) {
        sessions.put(tokenHash, new Session(userId, expiresAt, false));
    }

    @Override
    public Optional<UserAccount> findBySessionHash(String tokenHash) {
        Session session = sessions.get(tokenHash);
        if (session == null || session.revoked() || session.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return findById(session.userId());
    }

    @Override
    public void revokeSession(String tokenHash) {
        sessions.computeIfPresent(tokenHash, (ignored, session) -> new Session(session.userId(), session.expiresAt(), true));
    }

    @Override
    public void savePasswordResetToken(String userId, String tokenHash, Instant expiresAt) {
        resetTokens.put(tokenHash, new PasswordResetToken(userId, tokenHash, expiresAt));
    }

    @Override
    public synchronized Optional<PasswordResetToken> consumePasswordResetToken(String tokenHash) {
        PasswordResetToken token = resetTokens.remove(tokenHash);
        return token == null || token.expiresAt().isBefore(Instant.now()) ? Optional.empty() : Optional.of(token);
    }

    @Override
    public void updatePassword(String userId, String passwordHash) {
        users.computeIfPresent(userId, (ignored, user) -> new UserAccount(
                user.id(), user.email(), passwordHash, user.status(), user.createdAt(), user.deletedAt()
        ));
        sessions.replaceAll((ignored, session) -> session.userId().equals(userId)
                ? new Session(session.userId(), session.expiresAt(), true)
                : session);
    }

    @Override
    public void saveEmailVerificationCode(String email, String codeHash, Instant expiresAt) {
        verificationCodes.put(email, new VerificationCode(codeHash, expiresAt));
    }

    @Override
    public synchronized boolean consumeEmailVerificationCode(String email, String codeHash) {
        VerificationCode challenge = verificationCodes.get(email);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now()) || !challenge.codeHash().equals(codeHash)) {
            return false;
        }
        verificationCodes.remove(email, challenge);
        return true;
    }

    private record Session(String userId, Instant expiresAt, boolean revoked) {
    }

    private record VerificationCode(String codeHash, Instant expiresAt) {
    }
}
