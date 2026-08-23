package com.finsight.domain.model;

import java.time.Instant;
import java.util.List;

public record ReportDiff(
        String companySymbol,
        ReportSnapshot from,
        ReportSnapshot to,
        FieldDiff rating,
        FieldDiff summary,
        FieldDiff positivePoints,
        FieldDiff riskPoints,
        FieldDiff citations,
        boolean contextHashChanged,
        boolean dataSnapshotHashChanged,
        int reportVersionDelta
) {
    public record ReportSnapshot(
            String reportId,
            int reportVersion,
            Instant generatedAt,
            String contextHash,
            String dataSnapshotHash
    ) {
    }

    public record FieldDiff(
            String field,
            List<String> before,
            List<String> after,
            List<DiffSegment> segments,
            boolean changed
    ) {
    }

    public record DiffSegment(DiffOperation op, String text) {
    }

    public enum DiffOperation {
        EQUAL,
        INSERT,
        DELETE
    }
}
