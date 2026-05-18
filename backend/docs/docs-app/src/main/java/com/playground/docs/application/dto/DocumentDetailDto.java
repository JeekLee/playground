package com.playground.docs.application.dto;

import com.playground.docs.domain.model.Document;
import java.time.Instant;

/**
 * Use-case I/O DTO for the document detail / single-doc response shape per
 * ADR-02 v2. Per M2 spec §6.4 {@code DocDetail} carries the full body. The
 * -api layer translates this into the HTTP response body.
 *
 * <p>S1 omits {@code author}, {@code excerpt}, {@code viewCount},
 * {@code likeCount}, {@code likedByMe} — those are M2 S2 (author resolution,
 * engagement counters, excerpt derivation). The frontend's S1 surface only
 * needs id / title / body / visibility / path / timestamps.
 *
 * @param id           document UUID
 * @param authorId     author UUID (cross-BC reference to identity.users.id)
 * @param title        non-empty title
 * @param body         raw GFM Markdown
 * @param visibility   {@code "private"} or {@code "public"}
 * @param path         per-user directory path (always trailing-slashed)
 * @param publishedAt  ISO instant or {@code null} when never published
 * @param createdAt    creation timestamp
 * @param updatedAt    last mutation timestamp
 */
public record DocumentDetailDto(
        String id,
        String authorId,
        String title,
        String body,
        String visibility,
        String path,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentDetailDto from(Document doc) {
        return new DocumentDetailDto(
                doc.id().value().toString(),
                doc.authorId().value().toString(),
                doc.title().value(),
                doc.body().value(),
                doc.visibility().wireValue(),
                doc.path().value(),
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }
}
