package com.playground.docs.domain.model.vo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Document body — raw GFM-flavored Markdown per M2 spec §4.3. Per M6 ADR-16
 * the body is capped at 10 MB ({@value #MAX_OCTET_LENGTH} octets); the prior
 * 1 MB cap (M2 ADR-12 §4) was widened so PDF uploads whose extracted Markdown
 * grows past the original limit are accepted. Empty body is allowed
 * (drafting); null is not.
 *
 * <p>The size cap is enforced against the UTF-8 octet length, not the Java
 * char-count, so multi-byte content is bounded correctly. The DB CHECK
 * constraint applies the same predicate (defense in depth).
 *
 * <p>{@link #checksum()} returns the SHA-256 of the UTF-8 bytes of the raw MD,
 * lowercase hex. This is the {@code bodyChecksum} carried by every
 * {@code docs.document.uploaded} event per M2 spec §5; the application service
 * also uses it to gate "did the body actually change on PATCH" so we don't
 * fire a spurious uploaded event when only the title or path were edited.
 */
public record DocumentBody(String value) {

    /** 10 MB cap pinned by M6 ADR-16 (was 1 MB under M2 ADR-12 §4). */
    public static final int MAX_OCTET_LENGTH = 10 * 1_048_576;

    public DocumentBody {
        Objects.requireNonNull(value, "DocumentBody.value must not be null");
        if (octetLength(value) > MAX_OCTET_LENGTH) {
            throw new IllegalArgumentException(
                    "DocumentBody exceeds " + MAX_OCTET_LENGTH + " octets");
        }
    }

    public static DocumentBody of(String value) {
        return new DocumentBody(value == null ? "" : value);
    }

    public static DocumentBody empty() {
        return new DocumentBody("");
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    /**
     * SHA-256 of the body's UTF-8 octets, lowercase hex. Stable byte-by-byte
     * across hosts so the {@code uploaded} event's {@code bodyChecksum} is
     * comparable across producer + consumer.
     */
    public String checksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm; the catch is defensive only.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static int octetLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return value;
    }
}
