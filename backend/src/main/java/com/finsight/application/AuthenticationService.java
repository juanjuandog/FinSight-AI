package com.finsight.application;

import com.finsight.domain.model.PasswordResetToken;
import com.finsight.domain.model.UserAccount;
import com.finsight.domain.repository.UserAuthRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthenticationService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SESSION_TTL = Duration.ofDays(14);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(10);
    private static final Duration VERIFICATION_COOLDOWN = Duration.ofSeconds(60);

    private final UserAuthRepository repository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final PasswordResetMailer passwordResetMailer;
    @Value("${finsight.auth.email-enabled:false}")
    private boolean emailEnabled;
    private final Map<String, Instant> verificationIssuedAt = new ConcurrentHashMap<>();

    public AuthenticationService(UserAuthRepository repository, PasswordResetMailer passwordResetMailer) {
        this.repository = repository;
        this.passwordResetMailer = passwordResetMailer;
    }

    /**
     * Kept for internal callers and existing unit tests. Public registration goes
     * through the verification-code overload below.
     */
    public AuthSession register(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        return createUser(normalizedEmail, password);
    }

    public VerificationCodeIssue issueVerificationCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        if (repository.findByEmail(normalizedEmail).isPresent()) {
            throw new AuthenticationConflictException("该邮箱已注册，请直接登录。");
        }
        Instant now = Instant.now();
        Instant lastIssuedAt = verificationIssuedAt.get(normalizedEmail);
        if (lastIssuedAt != null && lastIssuedAt.plus(VERIFICATION_COOLDOWN).isAfter(now)) {
            long waitSeconds = Math.max(1, Duration.between(now, lastIssuedAt.plus(VERIFICATION_COOLDOWN)).toSeconds());
            throw new VerificationCodeRateLimitException("请等待 " + waitSeconds + " 秒后再获取验证码。");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        verificationIssuedAt.put(normalizedEmail, now);
        repository.saveEmailVerificationCode(normalizedEmail, hash(code), now.plus(VERIFICATION_TTL));
        passwordResetMailer.sendVerificationCode(normalizedEmail, code);
        return new VerificationCodeIssue(authEmailEnabled() ? null : code, VERIFICATION_TTL.toSeconds());
    }

    public AuthSession register(String email, String password, String verificationCode) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (repository.findByEmail(normalizedEmail).isPresent()) {
            throw new AuthenticationConflictException("该邮箱已注册，请直接登录。");
        }
        if (!repository.consumeEmailVerificationCode(normalizedEmail, hash(verificationCode == null ? "" : verificationCode.trim()))) {
            throw new AuthenticationFailedException("邮箱验证码无效或已过期，请重新获取。");
        }
        return createUser(normalizedEmail, password);
    }

    private AuthSession createUser(String normalizedEmail, String password) {
        if (repository.findByEmail(normalizedEmail).isPresent()) {
            throw new AuthenticationConflictException("该邮箱已注册，请直接登录。");
        }
        UserAccount user = new UserAccount(
                UUID.randomUUID().toString(),
                normalizedEmail,
                passwordEncoder.encode(password),
                "ACTIVE",
                Instant.now(),
                null
        );
        try {
            repository.create(user);
        } catch (IllegalStateException ex) {
            throw new AuthenticationConflictException("该邮箱已注册，请直接登录。");
        }
        return createSession(user);
    }

    private boolean authEmailEnabled() {
        return emailEnabled;
    }

    public AuthSession login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        UserAccount user = repository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            throw new AuthenticationNotFoundException("该邮箱尚未注册，请先注册。");
        }
        if (!user.active()) {
            throw new AuthenticationFailedException("该账号暂时不可用，请联系管理员。");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.passwordHash())) {
            throw new AuthenticationFailedException("密码不正确，请重试。");
        }
        return createSession(user);
    }

    public Optional<UserAccount> currentUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySessionHash(hash(rawToken)).filter(UserAccount::active);
    }

    public UserAccount requireUser(String rawToken) {
        return currentUser(rawToken).orElseThrow(AuthenticationRequiredException::new);
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            repository.revokeSession(hash(rawToken));
        }
    }

    public void requestPasswordReset(String email) {
        String normalizedEmail = normalizeEmail(email);
        repository.findByEmail(normalizedEmail).filter(UserAccount::active).ifPresent(user -> {
            String rawToken = randomToken();
            repository.savePasswordResetToken(user.id(), hash(rawToken), Instant.now().plus(RESET_TTL));
            passwordResetMailer.send(user.email(), rawToken);
        });
    }

    public void resetPassword(String rawToken, String newPassword) {
        validatePassword(newPassword);
        PasswordResetToken token = repository.consumePasswordResetToken(hash(rawToken == null ? "" : rawToken))
                .orElseThrow(() -> new AuthenticationFailedException("重置链接无效或已过期，请重新申请。"));
        repository.updatePassword(token.userId(), passwordEncoder.encode(newPassword));
    }

    private AuthSession createSession(UserAccount user) {
        String rawToken = randomToken();
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        repository.saveSession(hash(rawToken), user.id(), expiresAt);
        return new AuthSession(rawToken, user, expiresAt);
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("请输入有效的邮箱地址。");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10 || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException("密码至少 10 位，且同时包含字母和数字。");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record AuthSession(String token, UserAccount user, Instant expiresAt) {
    }

    public record VerificationCodeIssue(String devCode, long expiresInSeconds) {
    }

    public static class AuthenticationRequiredException extends RuntimeException {
        public AuthenticationRequiredException() { super("请先登录后再保存研究。"); }
    }

    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException() { this("邮箱或密码不正确。"); }
        public AuthenticationFailedException(String message) { super(message); }
    }

    public static class AuthenticationNotFoundException extends RuntimeException {
        public AuthenticationNotFoundException(String message) { super(message); }
    }

    public static class AuthenticationConflictException extends RuntimeException {
        public AuthenticationConflictException(String message) { super(message); }
    }

    public static class VerificationCodeRateLimitException extends RuntimeException {
        public VerificationCodeRateLimitException(String message) { super(message); }
    }
}
