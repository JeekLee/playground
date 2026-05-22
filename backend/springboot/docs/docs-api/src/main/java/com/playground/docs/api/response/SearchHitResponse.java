package com.playground.docs.api.response;

import com.playground.docs.application.dto.SearchHitDto;
import java.time.Instant;

/**
 * Search hit response per M2 spec §6.4 {@code SearchHit}. {@code author} is
 * present on public-scope hits and null on mine-scope hits.
 */
public record SearchHitResponse(
        String documentId,
        String title,
        String visibility,
        String path,
        AuthorResponse author,
        String snippet,
        Instant publishedAt,
        Instant updatedAt) {

    public static SearchHitResponse from(SearchHitDto dto) {
        return new SearchHitResponse(
                dto.documentId().toString(),
                dto.title(),
                dto.visibility(),
                dto.path(),
                AuthorResponse.from(dto.author()),
                dto.snippet(),
                dto.publishedAt(),
                dto.updatedAt());
    }
}
