package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentDetailDto;
import java.time.Instant;

/**
 * Response body for the single-document endpoints (POST / GET / PATCH) per
 * M2 spec §6.4 {@code DocDetail} (S1 subset — see
 * {@link DocumentDetailDto} for the field list).
 */
public record DocumentDetailResponse(
        String id,
        String authorId,
        String title,
        String body,
        String visibility,
        String path,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentDetailResponse from(DocumentDetailDto dto) {
        return new DocumentDetailResponse(
                dto.id(),
                dto.authorId(),
                dto.title(),
                dto.body(),
                dto.visibility(),
                dto.path(),
                dto.publishedAt(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
