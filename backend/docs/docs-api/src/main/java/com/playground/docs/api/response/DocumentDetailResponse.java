package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentDetailDto;
import java.time.Instant;

/**
 * Response body for the single-document endpoints (POST / GET / PATCH) per
 * M2 spec §6.4 {@code DocDetail}. S2 includes the author block, derived
 * excerpt, and view/like counters (the increment paths land in S3).
 *
 * <p>{@code likedByMe} is null in S2 (the {@code document_likes} table is S3).
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
                dto.publishedAt(),
                dto.createdAt(),
                dto.updatedAt());
    }
}
