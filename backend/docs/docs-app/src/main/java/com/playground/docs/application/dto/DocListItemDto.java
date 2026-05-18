package com.playground.docs.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Community feed / per-author feed list item per M2 spec §6.4
 * {@code DocListItem}. Author is always populated (feed lists are public-only
 * surfaces).
 *
 * <p>S2 wires {@code viewCount} / {@code likeCount} columns but the increment
 * paths land in S3; existing rows on a fresh DB return 0 for both. The frontend
 * already renders them; populating them now keeps the wire shape stable across
 * the S3 follow-up.
 */
public record DocListItemDto(
        UUID id,
        String title,
        String excerpt,
        String visibility,
        String path,
        AuthorDto author,
        Instant publishedAt,
        long viewCount,
        long likeCount,
        Boolean likedByMe
) {
}
