package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentDetailDto;
import java.time.Instant;

/**
 * Response body for the single-document endpoints (POST / GET / PATCH) per
 * M2 spec §6.4 {@code DocDetail}. M6 adds {@code mimeType}; M6.1 adds the
 * async {@code extractionStatus} + {@code extractionReason} fields.
 */
public record DocumentDetailResponse(
        String id,
        String authorId,
        AuthorResponse author,
        String title,
        String body,
        String excerpt,
        String visibility,
        String path,
        long viewCount,
        long likeCount,
        Boolean likedByMe,
        String mimeType,
        String extractionStatus,
        String extractionReason,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentDetailResponse from(DocumentDetailDto dto) {
        return new DocumentDetailResponse(
                dto.id(),
                dto.authorId(),
                AuthorResponse.from(dto.author()),
                dto.title(),
                dto.body(),
                dto.excerpt(),
                dto.visibility(),
                dto.path(),
                dto.viewCount(),
                dto.likeCount(),
                dto.likedByMe(),
                dto.mimeType(),
                dto.extractionStatus(),
                dto.extractionReason(),
                dto.publishedAt(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
