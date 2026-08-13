package com.finsight.api;

import com.finsight.application.AuditEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Applies per-key rate limits to sensitive endpoints (auth, write actions, AI analysis).
 *
 * <p>Limits are intentionally low in the lightweight preview profile so accidental loops cannot
 * hammer the database. The filter returns {@code 429 Too Many Requests} with a JSON Problem body
 * when the bucket is empty.
 */
@Configuration
public class RateLimitFilter {

    @Bean
    public FilterRegistrationBean<RateLimitFilterSupport> rateLimitFilterRegistration(
            ObjectProvider<AuditEventService> auditEventService,
            @Value("${finsight.rate-limit.auth.requests-per-window:10}") int authRequests,
            @Value("${finsight.rate-limit.auth.window:PT1M}") Duration authWindow,
            @Value("${finsight.rate-limit.write.requests-per-window:60}") int writeRequests,
            @Value("${finsight.rate-limit.write.window:PT1M}") Duration writeWindow,
            @Value("${finsight.rate-limit.analysis.requests-per-window:20}") int analysisRequests,
            @Value("${finsight.rate-limit.analysis.window:PT1M}") Duration analysisWindow
    ) {
        RateLimitFilterSupport filter = new RateLimitFilterSupport(
                auditEventService.getIfAvailable(),
                new RateLimiter(authRequests, authWindow),
                new RateLimiter(writeRequests, writeWindow),
                new RateLimiter(analysisRequests, analysisWindow)
        );
        FilterRegistrationBean<RateLimitFilterSupport> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        registration.addUrlPatterns("/*");
        registration.setName("rateLimitFilter");
        return registration;
    }

    public static class RateLimitFilterSupport extends OncePerRequestFilter {
        private static final Set<String> AUTH_PREFIXES = Set.of(
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/reset",
                "/api/auth/verify"
        );
        private static final Set<String> ANALYSIS_PREFIXES = Set.of(
                "/api/research"
        );
        private final AuditEventService auditEventService;
        private final RateLimiter authLimiter;
        private final RateLimiter writeLimiter;
        private final RateLimiter analysisLimiter;

        public RateLimitFilterSupport(
                AuditEventService auditEventService,
                RateLimiter authLimiter,
                RateLimiter writeLimiter,
                RateLimiter analysisLimiter
        ) {
            this.auditEventService = auditEventService;
            this.authLimiter = authLimiter;
            this.writeLimiter = writeLimiter;
            this.analysisLimiter = analysisLimiter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getRequestURI();
            String method = request.getMethod();
            String key = clientKey(request);
            BucketKind kind = limiterFor(path, method);
            RateLimiter limiter = kind == null ? null : limiterFor(kind);
            if (limiter != null && !limiter.tryAcquire(key)) {
                response.setStatus(429);
                response.setContentType("application/problem+json");
                response.getWriter().write(
                        "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                                + "\"status\":429,\"detail\":\"Rate limit exceeded, slow down\"}"
                );
                if (auditEventService != null) {
                    auditEventService.recordRejection(
                            "rate_limit." + (kind == null ? "global" : kind.name().toLowerCase()),
                            null, key, path, "rate limit exceeded");
                }
                return;
            }
            chain.doFilter(request, response);
        }

        private BucketKind limiterFor(String path, String method) {
            for (String prefix : AUTH_PREFIXES) {
                if (path.startsWith(prefix)) {
                    return BucketKind.AUTH;
                }
            }
            for (String prefix : ANALYSIS_PREFIXES) {
                if (path.startsWith(prefix)) {
                    return BucketKind.ANALYSIS;
                }
            }
            if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
                    && path.startsWith("/api/")) {
                return BucketKind.WRITE;
            }
            return null;
        }

        private RateLimiter limiterFor(BucketKind kind) {
            return switch (kind) {
                case AUTH -> authLimiter;
                case WRITE -> writeLimiter;
                case ANALYSIS -> analysisLimiter;
            };
        }

        private String clientKey(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        }
    }

    private enum BucketKind {
        AUTH, WRITE, ANALYSIS
    }
}

