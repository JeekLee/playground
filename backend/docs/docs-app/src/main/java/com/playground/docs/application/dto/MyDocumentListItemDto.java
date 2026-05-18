package com.playground.docs.application.dto;

import com.playground.docs.domain.model.Document;
import java.time.Instant;

/**
 * Use-case I/O DTO for {@code GET /api/docs/mine} list items, mirroring the
 * spec §6.4 {@code MyDocListItem} shape minus the S2 fields (excerpt,
 * viewCount, likeCount). S1 lists the bare minimum the frontend needs to
 * render the my-docs view.
 *
 * <p>Body is intentionally omitted from list items to keep the response small
 * — the detail endpoint returns the full body when the user opens a doc.
 */
public record MyDocumentListItemDto(
        String id,
        String title,
        String visibility,
        String path,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MyDocumentListItemDto from(Document doc) {
        return new MyDocumentListItemDto(
                doc.id().value().toString(),
                doc.title().value(),
                doc.visibility().wireValue(),
                doc.path().value(),
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }
}
