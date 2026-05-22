package com.playground.docs.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Search hit per M2 spec §6.4 {@code SearchHit}. {@code author} is present for
 * public hits and omitted (null) for the caller's own hits (the caller is the
 * author of every row in the {@code scope=mine} corpus).
 *
 * @param snippet  highlighted text fragment from OpenSearch — already contains
 *                 {@code <mark>} tags
 */
public record SearchHitDto(
        UUID documentId,
        String title,
        String visibility,
        String path,
        AuthorDto author,
        String snippet,
        Instant publishedAt,
        Instant updatedAt
) {
}
