package com.finsight.domain.model;

import java.time.Instant;

public record UserAccount(
        String id,
        String email,
        String passwordHash,
        String status,
        Instant createdAt,
        Instant deletedAt
) {
    public boolean active() {
        return "ACTIVE".equals(status) && deletedAt == null;
    }
}
