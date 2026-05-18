package com.playground.docs.domain.model.vo;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Document body — raw GFM-flavored Markdown per M2 spec §4.3. Per ADR-12 §4 the
 * body is capped at 1 MB ({@value #MAX_OCTET_LENGTH} octets). Empty body is
 * allowed (drafting); null is not.
 *
 * <p>The size cap is enforced against the UTF-8 octet length, not the Java
 * char-count, so multi-byte content is bounded correctly. The DB CHECK
 * constraint applies the same predicate (defense in depth).
 */
public record DocumentBody(String value) {

    /** 1 MB cap pinned by ADR-12 §4. */
    public static final int MAX_OCTET_LENGTH = 1_048_576;

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

    private static int octetLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return value;
    }
}
