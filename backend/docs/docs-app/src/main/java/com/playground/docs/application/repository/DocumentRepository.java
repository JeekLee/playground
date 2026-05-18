package com.playground.docs.application.repository;

import com.playground.docs.application.dto.FolderListItemDto;
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
     * Filtered variant of {@link #findAllByAuthor} per M2 spec §6.1 row
     * {@code GET /api/docs?scope=mine&path={folder}}. Documents owned by
     * {@code author} whose {@code path} column equals the supplied value,
     * sorted {@code updated_at DESC}.
     */
    List<Document> findAllByAuthorAndPath(AuthorId author, String path);

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

    // --- M2 S3 counter + folder surfaces ---

    /**
     * Atomically increment {@code view_count} by one for the given document.
     * Does <em>not</em> bump {@code updated_at} — view increments are not
     * "edits". Returns the row count of the update (0 = doc disappeared
     * between the visibility gate and the increment; treated as a no-op).
     *
     * <p>Backed by a single {@code UPDATE} so concurrent increments serialize
     * at the row lock without round-tripping the aggregate.
     */
    int incrementViewCount(DocumentId id);

    /**
     * Atomically increment {@code like_count} by one. Returns the row count
     * (0 = doc not found). Called from the {@code POST /like} path after
     * {@link DocumentLikeRepository#insertIfAbsent} reported a real insert.
     */
    int incrementLikeCount(DocumentId id);

    /**
     * Atomically decrement {@code like_count} by one, clamped to 0
     * ({@code GREATEST(like_count - 1, 0)}). Returns the row count.
     * Called from the {@code DELETE /like} path after
     * {@link DocumentLikeRepository#deleteIfPresent} reported a real delete.
     */
    int decrementLikeCount(DocumentId id);

    /**
     * Folder list (M2 spec §6.1 row {@code GET /api/docs/folders}): every
     * distinct {@code path} value for {@code user_id = author} with the row
     * count, sorted by path ascending. The non-functional requirement (spec
     * §10) is "folder counts must equal {@code COUNT(*) FROM docs.documents
     * WHERE user_id=? GROUP BY path}".
     */
    List<FolderListItemDto> listFolders(AuthorId author);

    /**
     * Nightly resync (ADR-12 §11) — for every row, set {@code like_count} to
     * {@code COUNT(*) FROM docs.document_likes WHERE document_id = row.id}.
     * Single SQL statement; returns the row count touched (every row, not
     * just the ones that drifted — Postgres updates the row in place either
     * way).
     */
    int resyncLikeCounts();
}
