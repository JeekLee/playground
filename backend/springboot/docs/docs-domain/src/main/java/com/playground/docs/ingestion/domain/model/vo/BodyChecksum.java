package com.playground.docs.ingestion.domain.model.vo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SHA-256 of a document body, lowercase hex. Carried on every chunk row per
 * ADR-13 §12 so the {@code (documentId, bodyChecksum)} idempotency key can be
 * checked with a single {@code SELECT body_checksum FROM rag.document_chunks
 * WHERE document_id = ? LIMIT 1}.
 *
 * <p>The wire-format value is byte-identical to docs BC's
 * {@code DocumentBody.checksum()} (M2's {@code bodyChecksum}) — both sides
 * SHA-256 the UTF-8 octets of the raw markdown and render lowercase hex.
 */
public record BodyChecksum(String value) {

    /** Hex SHA-256 is exactly 64 ASCII characters. */
    private static final int EXPECTED_LENGTH = 64;

    public BodyChecksum {
        Objects.requireNonNull(value, "BodyChecksum.value must not be null");
        if (value.length() != EXPECTED_LENGTH) {
            throw new IllegalArgumentException(
                    "BodyChecksum must be 64 hex chars, got length " + value.length());
        }
    }

    public static BodyChecksum of(String value) {
        return new BodyChecksum(value);
    }

    /**
     * Compute SHA-256 of the supplied string's UTF-8 octets. Identical to
     * docs BC's {@code DocumentBody.checksum()}; M3 recomputes locally rather
     * than trust the event payload so a checksum mismatch surfaces as a
     * tamper / wire-corruption signal.
     */
    public static BodyChecksum compute(String body) {
        Objects.requireNonNull(body, "body must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return new BodyChecksum(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
