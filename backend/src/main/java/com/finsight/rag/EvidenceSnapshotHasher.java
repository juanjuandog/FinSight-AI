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
 * <p>Fields are presence-marked and length-prefixed so null and empty values have
 * distinct, unambiguous encodings. Evidence order is intentionally significant because
 * reranking changes the model-facing context. Retrieval scores are excluded: they are
 * diagnostic metadata and do not enter the current answer-generation prompt.</p>
 */
public final class EvidenceSnapshotHasher {
    private EvidenceSnapshotHasher() {
    }

    public static String hash(List<EvidenceChunk> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, evidence.size());
            for (EvidenceChunk chunk : evidence) {
                updateNullableString(digest, chunk.documentId());
                updateNullableString(digest, chunk.title());
                updateNullableString(
                        digest,
                        chunk.documentType() == null ? null : chunk.documentType().name()
                );
                updateNullableString(
                        digest,
                        chunk.publishedAt() == null ? null : chunk.publishedAt().toString()
                );
                updateNullableString(digest, chunk.section());
                updateNullableString(digest, chunk.text());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private static void updateNullableString(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
