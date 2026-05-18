package com.playground.docs.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
