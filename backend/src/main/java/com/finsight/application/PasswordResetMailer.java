package com.finsight.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetMailer {
    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String from;
    private final String publicBaseUrl;

    public PasswordResetMailer(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${finsight.auth.email-enabled:false}") boolean enabled,
            @Value("${finsight.auth.mail-from:noreply@finsight.local}") String from,
            @Value("${finsight.auth.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
    }

    public void send(String email, String rawToken) {
        if (!enabled) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("FinSight AI 密码重置");
        message.setText("你好，\n\n请在 30 分钟内打开以下链接重置 FinSight AI 密码：\n"
                + publicBaseUrl + "/?resetToken=" + rawToken + "\n\n如果不是你发起的请求，请忽略这封邮件。");
        try {
            sender.send(message);
        } catch (RuntimeException ignored) {
            // Password reset requests remain intentionally generic to avoid account enumeration.
        }
    }

    public void sendVerificationCode(String email, String code) {
        if (!enabled) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("FinSight AI 邮箱验证码");
        message.setText("你好，\n\n你的 FinSight AI 注册验证码是：" + code + "\n验证码 10 分钟内有效。\n\n如果不是你发起的请求，请忽略这封邮件。");
        try {
            sender.send(message);
        } catch (RuntimeException ignored) {
            // Registration remains generic when the mail provider is unavailable.
        }
    }
}
