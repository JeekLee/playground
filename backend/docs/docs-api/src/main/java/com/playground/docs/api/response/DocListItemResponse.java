package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocListItemDto;
import java.time.Instant;

/**
 * Community feed / per-author feed list item per M2 spec §6.4
 * {@code DocListItem}. Distinct from {@link MyDocumentListItemResponse}
 * because the community shape always includes the author block.
 */
public record DocListItemResponse(
        String id,
        String title,
        String excerpt,
        String visibility,
        String path,
        AuthorResponse author,
        Instant publishedAt,
        long viewCount,
        long likeCount,
        Boolean likedByMe) {

    public static DocListItemResponse from(DocListItemDto dto) {
        return new DocListItemResponse(
                dto.id().toString(),
                dto.title(),
                dto.excerpt(),
                dto.visibility(),
                dto.path(),
                AuthorResponse.from(dto.author()),
                dto.publishedAt(),
                dto.viewCount(),
                dto.likeCount(),
                dto.likedByMe());
    }
}
