package com.playground.docs.application.repository;

import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository port per ADR-02 v2 (placement = application layer, return types =
 * domain types). The JPA-backed implementation lives in {@code docs-infra} as
 * {@code DocumentRepositoryImpl}.
 *
 * <p>S2 adds:
 * <ul>
 *   <li>{@link #findPublicFeed(Instant, java.util.UUID, int)} —
 *       community-wide public feed (any author).</li>
 *   <li>{@link #findPublicFeedByAuthor(AuthorId, Instant, java.util.UUID, int)} —
 *       per-author public feed (used by the home tile).</li>
 * </ul>
 *
 * <p>Cursor pagination is a tuple {@code (published_at, id)} so a tie in
 * {@code published_at} between two documents still produces a stable ordering.
 * Pages are exclusive (the cursor row is the row last returned on the previous
 * page).
 */
public interface DocumentRepository {

    Optional<Document> findById(DocumentId id);

    /**
     * All documents owned by the supplied author, sorted
     * {@code updated_at DESC} per M2 spec §6.1 row {@code GET /api/docs?scope=mine}.
     */
    List<Document> findAllByAuthor(AuthorId author);

    /**
     * Community feed page — {@code visibility='public'} across every author,
     * sorted {@code (published_at DESC, id DESC)}. Cursor is the previous
     * page's last (publishedAt, id) tuple — both must be non-null for the
     * predicate to apply.
     *
     * @param cursorPublishedAt previous page's last {@code published_at}
     *                          (null on the first page)
     * @param cursorId          previous page's last {@code id} (null on the
     *                          first page)
     * @param limit             page size (already +1 for next-cursor detection
     *                          if the caller wants it)
     */
    List<Document> findPublicFeed(Instant cursorPublishedAt, java.util.UUID cursorId, int limit);

    /**
     * Per-author public feed page — same shape as {@link #findPublicFeed} but
     * scoped to {@code author}.
     */
    List<Document> findPublicFeedByAuthor(
            AuthorId author, Instant cursorPublishedAt, java.util.UUID cursorId, int limit);

    Document save(Document document);

    void deleteById(DocumentId id);
}
