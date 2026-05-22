package com.playground.docs.application.dto;

import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.service.MarkdownExcerpt;
import java.time.Instant;

/**
 * Use-case I/O DTO for {@code GET /api/docs?scope=mine} list items per M2
 * spec §6.4 {@code MyDocListItem}.
 *
 * <p>No {@code author} block by definition — the caller is the author of every
 * row in their mine-list. Body is intentionally omitted from list items to
 * keep the response small (the detail endpoint returns the full body when the
 * user opens a doc); the excerpt is derived from body and surfaced here.
 *
 * <p>{@code viewCount} / {@code likeCount} default to 0 until S3 wires the
 * increment paths.
 */
public record MyDocumentListItemDto(
        String id,
        String title,
        String excerpt,
        String visibility,
        String path,
        long viewCount,
        long likeCount,
        String mimeType,
        String extractionStatus,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MyDocumentListItemDto from(Document doc, long viewCount, long likeCount) {
        return new MyDocumentListItemDto(
                doc.id().value().toString(),
                doc.title().value(),
                MarkdownExcerpt.of(doc.body().value()),
                doc.visibility().wireValue(),
                doc.path().value(),
                viewCount,
                likeCount,
                doc.mimeType().wireValue(),
                doc.extractionStatus().wireValue(),
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }

    /** Convenience for tests / paths that haven't loaded counters. */
    public static MyDocumentListItemDto from(Document doc) {
        return from(doc, 0L, 0L);
    }
}
