package com.playground.docs.api.response;

import com.playground.docs.application.dto.MyDocumentListItemDto;
import java.time.Instant;

/** List item for {@code GET /api/docs/mine}, mirroring M2 spec §6.4 {@code MyDocListItem} (S1 subset). */
public record MyDocumentListItemResponse(
        String id,
        String title,
        String visibility,
        String path,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MyDocumentListItemResponse from(MyDocumentListItemDto dto) {
        return new MyDocumentListItemResponse(
                dto.id(),
                dto.title(),
                dto.visibility(),
                dto.path(),
                dto.publishedAt(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
