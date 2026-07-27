package com.finsight.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RagTrace(
        String traceId,
        Instant startedAt,
        Map<String, Object> structuredQuery,
        List<String> retrievalChannels,
        int evidenceCount,
        String dataSnapshotHash,
        long latencyMillis
) {
}

