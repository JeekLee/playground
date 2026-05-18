package com.playground.docs.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data adapter for {@link DocumentJpaEntity}. */
public interface DocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {

    /**
     * All documents owned by the supplied user, sorted {@code updated_at DESC}
     * per M2 spec §6.1 row {@code GET /api/docs?scope=mine} — last-touched
     * documents surface first.
     */
    @Query("""
            select d from DocumentJpaEntity d
            where d.userId = :userId
            order by d.updatedAt desc
            """)
    List<DocumentJpaEntity> findAllByUserOrderedForMine(@Param("userId") UUID userId);

    // --- M2 S2: community + per-author public feed (cursor pagination) ---

    /**
     * First page of the community feed — every author's {@code visibility='public'}
     * docs, sorted {@code published_at DESC, id DESC}. Hits the
     * {@code ix_docs_public_published} partial index.
     */
    @Query("""
            select d from DocumentJpaEntity d
            where d.visibility = 'public'
            order by d.publishedAt desc, d.id desc
            """)
    List<DocumentJpaEntity> findPublicFeedFirstPage(Limit limit);

    /**
     * Subsequent pages of the community feed — keyset predicate on
     * {@code (publishedAt, id)} for stable cursor pagination.
     */
    @Query("""
            select d from DocumentJpaEntity d
            where d.visibility = 'public'
              and (d.publishedAt < :cursorPublishedAt
                   or (d.publishedAt = :cursorPublishedAt and d.id < :cursorId))
            order by d.publishedAt desc, d.id desc
            """)
    List<DocumentJpaEntity> findPublicFeedAfter(
            @Param("cursorPublishedAt") Instant cursorPublishedAt,
            @Param("cursorId") UUID cursorId,
            Limit limit);

    /** Per-author first page. */
    @Query("""
            select d from DocumentJpaEntity d
            where d.visibility = 'public' and d.userId = :userId
            order by d.publishedAt desc, d.id desc
            """)
    List<DocumentJpaEntity> findPublicFeedByAuthorFirstPage(
            @Param("userId") UUID userId, Limit limit);

    /** Per-author subsequent pages. */
    @Query("""
            select d from DocumentJpaEntity d
            where d.visibility = 'public' and d.userId = :userId
              and (d.publishedAt < :cursorPublishedAt
                   or (d.publishedAt = :cursorPublishedAt and d.id < :cursorId))
            order by d.publishedAt desc, d.id desc
            """)
    List<DocumentJpaEntity> findPublicFeedByAuthorAfter(
            @Param("userId") UUID userId,
            @Param("cursorPublishedAt") Instant cursorPublishedAt,
            @Param("cursorId") UUID cursorId,
            Limit limit);

    // --- M2 S3 ---

    /**
     * Folder-scoped mine list per M2 spec §6.1 row
     * {@code GET /api/docs?scope=mine&path={folder}}. Sorted
     * {@code updated_at DESC} to match the unfiltered mine list.
     */
    @Query("""
            select d from DocumentJpaEntity d
            where d.userId = :userId and d.path = :path
            order by d.updatedAt desc
            """)
    List<DocumentJpaEntity> findAllByUserAndPathOrderedForMine(
            @Param("userId") UUID userId, @Param("path") String path);

    /**
     * Folder summary per M2 spec §6.1 row {@code GET /api/docs/folders}.
     * Returns rows of {@code (path, count)} for the caller's documents.
     * The projection materializes via {@link Object[]} for portability;
     * {@code DocumentRepositoryImpl} maps each row into the public
     * {@code FolderListItemDto}.
     */
    @Query("""
            select d.path as path, count(d) as cnt
            from DocumentJpaEntity d
            where d.userId = :userId
            group by d.path
            order by d.path asc
            """)
    List<Object[]> folderSummary(@Param("userId") UUID userId);

    /**
     * Atomic counter bumps. Bypass the aggregate save path so concurrent
     * increments serialize at the row lock without read-modify-write
     * round trips.
     */
    @Modifying
    @Query("update DocumentJpaEntity d set d.viewCount = d.viewCount + 1 where d.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    @Modifying
    @Query("update DocumentJpaEntity d set d.likeCount = d.likeCount + 1 where d.id = :id")
    int incrementLikeCount(@Param("id") UUID id);

    /**
     * Decrement clamped to 0 — {@code GREATEST(d.like_count - 1, 0)} via JPQL
     * uses {@code CASE WHEN} since JPQL has no GREATEST. The wrapped logic
     * is equivalent: {@code likeCount := max(likeCount - 1, 0)}.
     */
    @Modifying
    @Query("""
            update DocumentJpaEntity d
            set d.likeCount = case when d.likeCount > 0 then d.likeCount - 1 else 0 end
            where d.id = :id
            """)
    int decrementLikeCount(@Param("id") UUID id);

    /**
     * Nightly resync per ADR-12 §11. Recomputes {@code like_count} from
     * {@code COUNT(*) FROM document_likes} as a single native UPDATE
     * statement so the work happens server-side, not by paging through
     * rows in Java.
     */
    @Modifying
    @Query(
            value = "UPDATE docs.documents d "
                    + "SET like_count = ("
                    + "    SELECT COUNT(*) FROM docs.document_likes l "
                    + "    WHERE l.document_id = d.id"
                    + ")",
            nativeQuery = true)
    int resyncLikeCounts();
}
