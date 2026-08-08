package com.finsight.api;

import com.finsight.application.AuthenticationService;
import com.finsight.domain.model.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Duration SESSION_TTL = Duration.ofDays(14);

    private final AuthenticationService authenticationService;
    private final AuthCookieSupport cookies;
    private final CsrfService csrfService;

    public AuthController(AuthenticationService authenticationService, AuthCookieSupport cookies, CsrfService csrfService) {
        this.authenticationService = authenticationService;
        this.cookies = cookies;
        this.csrfService = csrfService;
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request, HttpServletResponse response) {
        String csrfToken = csrfService.ensureToken(request, response);
        return new SessionResponse(
                authenticationService.currentUser(cookies.readSessionToken(request)).map(UserResponse::from).orElse(null),
                csrfToken
        );
    }

    @PostMapping("/register")
    public UserResponse register(
            @RequestBody AuthRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        csrfService.require(request);
        AuthenticationService.AuthSession session = authenticationService.register(
                body == null ? null : body.email(),
                body == null ? null : body.password(),
                body == null ? null : body.verificationCode()
        );
        cookies.setSessionCookie(response, session.token(), SESSION_TTL);
        return UserResponse.from(session.user());
    }

    @PostMapping("/verification-code")
    public VerificationCodeResponse requestVerificationCode(
            @RequestBody EmailRequest body,
            HttpServletRequest request
    ) {
        csrfService.require(request);
        AuthenticationService.VerificationCodeIssue issue = authenticationService.issueVerificationCode(body == null ? null : body.email());
        String message = issue.devCode() == null
                ? "验证码已发送到你的邮箱，10 分钟内有效。"
                : "开发模式验证码：" + issue.devCode() + "（10 分钟内有效）";
        return new VerificationCodeResponse(message, issue.expiresInSeconds());
    }

    @PostMapping("/login")
    public UserResponse login(
            @RequestBody AuthRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        csrfService.require(request);
        AuthenticationService.AuthSession session = authenticationService.login(body == null ? null : body.email(), body == null ? null : body.password());
        cookies.setSessionCookie(response, session.token(), SESSION_TTL);
        return UserResponse.from(session.user());
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        csrfService.require(request);
        authenticationService.logout(cookies.readSessionToken(request));
        cookies.clearSessionCookie(response);
    }

    @PostMapping("/password-reset/request")
    public MessageResponse requestPasswordReset(
            @RequestBody EmailRequest body,
            HttpServletRequest request
    ) {
        csrfService.require(request);
        authenticationService.requestPasswordReset(body == null ? null : body.email());
        return new MessageResponse("如果该邮箱已注册，密码重置邮件会发送到你的邮箱。");
    }

    @PostMapping("/password-reset/confirm")
    public MessageResponse confirmPasswordReset(
            @RequestBody PasswordResetRequest body,
            HttpServletRequest request
    ) {
        csrfService.require(request);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重置请求不能为空。");
        }
        authenticationService.resetPassword(body.token(), body.password());
        return new MessageResponse("密码已更新，请使用新密码登录。");
    }

    public record AuthRequest(String email, String password, String verificationCode) {
    }

    public record EmailRequest(String email) {
    }

    public record PasswordResetRequest(String token, String password) {
    }

    public record MessageResponse(String message) {
    }

    public record VerificationCodeResponse(String message, long expiresInSeconds) {
    }

    public record SessionResponse(UserResponse user, String csrfToken) {
    }

    public record UserResponse(String id, String email) {
        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.email());
        }
    }
}
