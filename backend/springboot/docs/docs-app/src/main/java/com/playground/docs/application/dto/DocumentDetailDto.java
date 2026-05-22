package com.playground.docs.application.dto;

import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.service.MarkdownExcerpt;
import java.time.Instant;
import java.util.UUID;

/**
 * Use-case I/O DTO for the document detail / single-doc response shape per
 * ADR-02 v2. Per M2 spec §6.4 {@code DocDetail} carries the full body plus the
 * derived excerpt + engagement counters + author block.
 *
 * <p>{@code viewCount} / {@code likeCount} default to 0 in S2 — the increment
 * paths (POST /like, POST /view) land in S3 alongside the nightly resync per
 * ADR-12 §11. The columns are already on the row from migration
 * V202605190001__add_counter_columns.sql so the persisted value is read here
 * verbatim.
 *
 * <p>{@code likedByMe} is null in S2 (no like state to derive yet) and becomes
 * a Boolean in S3 once the {@code document_likes} table lands.
 *
 * <p>{@code author} is resolved by the application service via the identity
 * lookup port and may be {@code null} when the lookup misses (deleted author
 * or transient identity-api outage) — the controller serializes the null,
 * the frontend renders a fallback display name.
 *
 * @param id           document UUID
 * @param authorId     author UUID (cross-BC reference to identity.users.id)
 * @param author       resolved author block (display name + avatar) — may be null
 * @param title        non-empty title
 * @param body         raw GFM Markdown
 * @param excerpt      derived per {@link MarkdownExcerpt} (spec §4.3)
 * @param visibility   {@code "private"} or {@code "public"}
 * @param path         per-user directory path (always trailing-slashed)
 * @param viewCount    denormalized view counter (S3 wires the increment path)
 * @param likeCount    denormalized like counter (S3 wires the increment path)
 * @param likedByMe    null in S2 — S3 returns Boolean for authenticated callers
 * @param publishedAt  ISO instant or {@code null} when never published
 * @param createdAt    creation timestamp
 * @param updatedAt    last mutation timestamp
 */
public record DocumentDetailDto(
        String id,
        String authorId,
        AuthorDto author,
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
        boolean hasOriginal,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Build a detail DTO from a domain {@link Document}. {@code author} is
     * supplied by the caller (the app service resolves it via the identity
     * lookup port); {@code viewCount} / {@code likeCount} come from the JPA
     * row directly.
     */
    public static DocumentDetailDto from(
            Document doc, AuthorDto author, long viewCount, long likeCount, Boolean likedByMe) {
        return new DocumentDetailDto(
                doc.id().value().toString(),
                doc.authorId().value().toString(),
                author,
                doc.title().value(),
                doc.body().value(),
                MarkdownExcerpt.of(doc.body().value()),
                doc.visibility().wireValue(),
                doc.path().value(),
                viewCount,
                likeCount,
                likedByMe,
                doc.mimeType().wireValue(),
                doc.extractionStatus().wireValue(),
                doc.extractionReason(),
                doc.sourceObjectKey() != null,
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }

    /**
     * Build a detail DTO with no engagement counters / author resolution — used
     * by paths that don't need the cross-BC lookup (e.g. tests, or the
     * {@code /publish} / {@code /unpublish} response where the frontend already
     * has the row). Returns 0 for the counters and null for {@code likedByMe}
     * and {@code author}.
     */
    public static DocumentDetailDto from(Document doc) {
        return from(doc, null, 0L, 0L, null);
    }

    public UUID authorIdAsUuid() {
        return UUID.fromString(authorId);
    }
}
