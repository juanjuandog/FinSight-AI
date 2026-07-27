package com.finsight.rag;

import com.finsight.domain.model.EvidenceChunk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes a stable digest of the ordered evidence passed to answer generation.
 *
 * <p>Fields are length-prefixed so the encoding is unambiguous. Evidence order is
 * intentionally significant because reranking changes the model-facing context.</p>
 */
public final class EvidenceSnapshotHasher {
    private EvidenceSnapshotHasher() {
    }

    public static String hash(List<EvidenceChunk> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, evidence.size());
            for (EvidenceChunk chunk : evidence) {
                updateString(digest, chunk.documentId());
                updateString(digest, chunk.title());
                updateString(digest, chunk.documentType().name());
                updateString(digest, chunk.publishedAt().toString());
                updateString(digest, chunk.section());
                updateString(digest, chunk.text());
                updateLong(digest, Double.doubleToLongBits(chunk.score()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
}
