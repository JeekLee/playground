package com.playground.docs.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA mirror of {@code docs.document_likes} per M2 spec §4.1. The composite
 * key is embedded via {@link DocumentLikeId}. The {@code liked_at} column is
 * {@code DEFAULT now()} at the DB layer, so we let the DB stamp it on
 * insert.
 *
 * <p>In practice the application layer goes through native SQL ({@code INSERT
 * ... ON CONFLICT DO NOTHING} / {@code DELETE WHERE ...}) for idempotent
 * toggle semantics — this JPA entity exists primarily to keep Hibernate's
 * {@code ddl-auto=validate} happy (it scans the persistence unit for
 * declared mappings on every table the schema migration creates).
 */
@Entity
@Table(name = "document_likes", schema = "docs")
public class DocumentLikeJpaEntity {

    @EmbeddedId
    private DocumentLikeId id;

    @Column(name = "liked_at", nullable = false, insertable = false, updatable = false)
    private Instant likedAt;

    protected DocumentLikeJpaEntity() {
        // for JPA
    }

    public DocumentLikeJpaEntity(DocumentLikeId id) {
        this.id = id;
    }

    public DocumentLikeId getId() {
        return id;
    }

    public Instant getLikedAt() {
        return likedAt;
    }
}
