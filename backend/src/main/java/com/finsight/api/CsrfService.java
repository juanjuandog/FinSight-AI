package com.finsight.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CsrfService {
    public static final String COOKIE = "finsight_csrf";
    public static final String HEADER = "X-CSRF-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String ensureToken(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, token)
                .httpOnly(false)
                .sameSite("Lax")
                .path("/")
                .build().toString());
        return token;
    }

    public void require(HttpServletRequest request) {
        String cookie = readCookie(request);
        String header = request.getHeader(HEADER);
        if (cookie == null || header == null || !MessageDigest.isEqual(
                cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )) {
            throw new CsrfException();
        }
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static class CsrfException extends RuntimeException {
        public CsrfException() { super("请求校验已过期，请刷新页面后重试。"); }
    }
}
