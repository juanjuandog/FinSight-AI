package com.finsight.rag;

import com.finsight.domain.model.DocumentChunk;

import java.util.Map;

public record RetrievalHit(
        DocumentChunk chunk,
        double score,
        String channel,
        Map<String, Integer> ranks
) {
}
