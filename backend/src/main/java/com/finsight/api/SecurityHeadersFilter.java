package com.finsight.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds baseline security headers to every response. Static HTML pages and APIs both benefit
 * from a strict CSP, X-Content-Type-Options, and Referrer-Policy.
 *
 * <p>Allow-list for inline scripts covers the existing static frontend; tighten the rules as
 * the JS is split into modules.
 */
@Configuration
public class SecurityHeadersFilter {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data:",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'"
    );

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilterSupport> securityHeadersRegistration() {
        SecurityHeadersFilterSupport filter = new SecurityHeadersFilterSupport();
        FilterRegistrationBean<SecurityHeadersFilterSupport> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.addUrlPatterns("/*");
        registration.setName("securityHeadersFilter");
        return registration;
    }

    public static class SecurityHeadersFilterSupport extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            chain.doFilter(request, response);
        }
    }
}
