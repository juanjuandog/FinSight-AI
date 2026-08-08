package com.finsight.application;

import com.finsight.infrastructure.InMemoryUserAuthRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthenticationServiceTest {

    @Test
    void registrationCreatesAReusableSessionAndNormalizesEmail() {
        AuthenticationService service = service(mock(PasswordResetMailer.class));

        AuthenticationService.AuthSession session = service.register("  User@Example.COM ", "research2026");

        assertThat(session.user().email()).isEqualTo("user@example.com");
        assertThat(service.currentUser(session.token())).contains(session.user());
        assertThat(service.login("user@example.com", "research2026").user().id()).isEqualTo(session.user().id());
    }

    @Test
    void duplicateEmailAndWeakPasswordsAreRejected() {
        AuthenticationService service = service(mock(PasswordResetMailer.class));
        service.register("user@example.com", "research2026");

        assertThatThrownBy(() -> service.register("USER@example.com", "another2026"))
                .isInstanceOf(AuthenticationService.AuthenticationConflictException.class);
        assertThatThrownBy(() -> service.register("new@example.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginDistinguishesUnknownEmailFromWrongPassword() {
        AuthenticationService service = service(mock(PasswordResetMailer.class));

        assertThatThrownBy(() -> service.login("missing@example.com", "research2026"))
                .isInstanceOf(AuthenticationService.AuthenticationNotFoundException.class)
                .hasMessage("该邮箱尚未注册，请先注册。");

        service.register("user@example.com", "research2026");
        assertThatThrownBy(() -> service.login("user@example.com", "wrong2026pass"))
                .isInstanceOf(AuthenticationService.AuthenticationFailedException.class)
                .hasMessage("密码不正确，请重试。");
    }

    @Test
    void registrationRequiresAndConsumesAnEmailVerificationCode() {
        AuthenticationService service = service(mock(PasswordResetMailer.class));

        AuthenticationService.VerificationCodeIssue issue = service.issueVerificationCode("verified@example.com");
        assertThat(issue.devCode()).matches("\\d{6}");
        assertThat(service.register("verified@example.com", "research2026", issue.devCode()).user().email())
                .isEqualTo("verified@example.com");
        assertThatThrownBy(() -> service.register("verified@example.com", "research2026", issue.devCode()))
                .isInstanceOf(AuthenticationService.AuthenticationConflictException.class);
    }

    @Test
    void passwordResetUsesOneTimeTokenAndRevokesExistingSessions() {
        PasswordResetMailer mailer = mock(PasswordResetMailer.class);
        AuthenticationService service = service(mailer);
        AuthenticationService.AuthSession original = service.register("user@example.com", "research2026");

        service.requestPasswordReset("user@example.com");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(org.mockito.ArgumentMatchers.eq("user@example.com"), token.capture());
        service.resetPassword(token.getValue(), "updated2027");

        assertThat(service.currentUser(original.token())).isEmpty();
        assertThat(service.login("user@example.com", "updated2027").user().email()).isEqualTo("user@example.com");
        assertThatThrownBy(() -> service.resetPassword(token.getValue(), "updated2028"))
                .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);
    }

    private AuthenticationService service(PasswordResetMailer mailer) {
        return new AuthenticationService(new InMemoryUserAuthRepository(), mailer);
    }
}
