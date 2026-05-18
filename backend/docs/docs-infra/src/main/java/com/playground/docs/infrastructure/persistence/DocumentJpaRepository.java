package com.playground.docs.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
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
}
