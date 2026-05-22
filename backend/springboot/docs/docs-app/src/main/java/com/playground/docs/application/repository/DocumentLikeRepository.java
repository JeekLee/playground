package com.playground.docs.application.repository;

import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Repository port for the {@code docs.document_likes} table per M2 spec §4.1 +
 * ADR-12 §11. Idempotent upsert + delete semantics are enforced at the SQL
 * layer ({@code INSERT ... ON CONFLICT DO NOTHING}); the boolean return on
 * the toggle methods reports whether the row actually changed so the caller
 * can keep the denormalized counter on {@code docs.documents.like_count} in
 * sync inside the same {@code @Transactional} boundary.
 *
 * <p>Placement = application layer per ADR-02 v2; the JPA-backed impl lives
 * in {@code docs-infra} as {@code DocumentLikeRepositoryImpl}.
 */
public interface DocumentLikeRepository {

    /**
     * Insert a like row if not present. Returns {@code true} when a row was
     * actually inserted (the user had not liked this doc before), {@code false}
     * when the row already existed.
     *
     * <p>Idempotency contract: repeated calls return {@code false} after the
     * first insert. Backed by {@code INSERT ... ON CONFLICT DO NOTHING} on the
     * composite PK {@code (document_id, user_id)} (M2 spec §10 "Like
     * idempotency").
     */
    boolean insertIfAbsent(DocumentId documentId, AuthorId userId);

    /**
     * Delete the like row for the given pair. Returns {@code true} when a row
     * was actually deleted, {@code false} when no row existed.
     *
     * <p>Idempotency contract: repeated calls return {@code false} after the
     * first delete (M2 spec §6.1 row {@code DELETE /api/docs/{id}/like}: "no-op
     * if absent").
     */
    boolean deleteIfPresent(DocumentId documentId, AuthorId userId);

    /**
     * "Did the caller like this doc?" — used by the single-doc detail
     * response when the caller is authenticated. Returns {@code false} for
     * anonymous callers (the controller should not invoke this method with a
     * null caller; the application service short-circuits to {@code null}
     * {@code likedByMe}).
     */
    boolean existsBy(DocumentId documentId, AuthorId userId);

    /**
     * Batch variant of {@link #existsBy} for paged community / per-author
     * feeds. Returns the subset of {@code documentIds} that the caller has
     * liked. Avoids the N+1 query pattern the community feed would otherwise
     * suffer (one query per row).
     */
    Set<UUID> findLikedDocumentIds(AuthorId userId, Collection<UUID> documentIds);
}
