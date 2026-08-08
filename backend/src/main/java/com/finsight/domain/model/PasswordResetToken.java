package com.finsight.domain.model;

import java.time.Instant;

public record PasswordResetToken(
        String userId,
        String tokenHash,
        Instant expiresAt
) {
}
