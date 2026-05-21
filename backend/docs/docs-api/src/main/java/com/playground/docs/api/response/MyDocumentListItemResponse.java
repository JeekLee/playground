package com.playground.docs.api.response;

import com.playground.docs.application.dto.MyDocumentListItemDto;
import java.time.Instant;

/**
 * List item for {@code GET /api/docs?scope=mine}, mirroring M2 spec §6.4
 * {@code MyDocListItem}. No {@code author} block (caller is the author).
 */
public record MyDocumentListItemResponse(
        String id,
        String title,
        String excerpt,
        String visibility,
        String path,
        long viewCount,
        long likeCount,
        String mimeType,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MyDocumentListItemResponse from(MyDocumentListItemDto dto) {
        return new MyDocumentListItemResponse(
                dto.id(),
                dto.title(),
                dto.excerpt(),
                dto.visibility(),
                dto.path(),
                dto.viewCount(),
                dto.likeCount(),
                dto.mimeType(),
                dto.publishedAt(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
