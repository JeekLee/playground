package com.playground.docs.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity for {@link DocumentLikeJpaEntity} — mirrors the
 * {@code (document_id, user_id)} primary key of {@code docs.document_likes}
 * per M2 spec §4.1.
 *
 * <p>The hand-rolled equals/hashCode is mandatory for JPA composite keys.
 */
@Embeddable
public class DocumentLikeId implements Serializable {

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected DocumentLikeId() {
        // for JPA
    }

    public DocumentLikeId(UUID documentId, UUID userId) {
        this.documentId = documentId;
        this.userId = userId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentLikeId other)) return false;
        return Objects.equals(documentId, other.documentId)
                && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, userId);
    }
}
