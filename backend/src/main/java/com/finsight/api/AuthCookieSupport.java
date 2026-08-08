package com.finsight.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieSupport {
    public static final String SESSION_COOKIE = "finsight_session";
    private final boolean secure;

    public AuthCookieSupport(@Value("${finsight.auth.secure-cookie:false}") boolean secure) {
        this.secure = secure;
    }

    public String readSessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void setSessionCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader("Set-Cookie", ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build().toString());
    }

    public void clearSessionCookie(HttpServletResponse response) {
        setSessionCookie(response, "", Duration.ZERO);
    }
}
