package com.playground.docs.application.service;

import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque base64 cursor encoding {@code (published_at, id)} pairs for the
 * community / per-author feed pagination per M2 spec §6.1.
 *
 * <p>Wire shape: {@code base64url("<iso-instant>|<uuid>")}. Encoding is
 * url-safe-base64 (no padding) so the cursor stays clean in query strings.
 *
 * <p>The decoded form maps to the SQL predicate
 * {@code (published_at, id) < (cursorPublishedAt, cursorId)}; the repository
 * applies the predicate when the cursor is non-null.
 *
 * <p>POJO utility per ADR-02 v2 — no Spring imports.
 */
public final class FeedCursor {

    private FeedCursor() {}

    public record Decoded(Instant publishedAt, UUID id) {}

    public static String encode(Instant publishedAt, UUID id) {
        if (publishedAt == null || id == null) {
            return null;
        }
        String raw = publishedAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode the cursor; null / blank input returns null (= "first page"). A
     * malformed value throws a {@code DocsErrorCode.CURSOR_INVALID} 400.
     */
    public static Decoded decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.CURSOR_INVALID).throwIt();
            return null; // unreachable
        }
        int sep = raw.indexOf('|');
        if (sep < 0 || sep == raw.length() - 1) {
            ExceptionCreator.of(DocsErrorCode.CURSOR_INVALID).throwIt();
        }
        try {
            Instant ts = Instant.parse(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            return new Decoded(ts, id);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.CURSOR_INVALID).throwIt();
            return null; // unreachable
        }
    }
}
