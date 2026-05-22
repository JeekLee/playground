package com.playground.docs.infrastructure.ingestion.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite-PK class for {@link DocumentChunkJpaEntity} — Hibernate requires
 * an {@code @IdClass} POJO with field names that match the entity's
 * {@code @Id}-annotated columns. Equality + hash are tuple semantics.
 */
public class DocumentChunkJpaId implements Serializable {

    private UUID documentId;
    private int chunkIndex;

    public DocumentChunkJpaId() {}

    public DocumentChunkJpaId(UUID documentId, int chunkIndex) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentChunkJpaId that)) return false;
        return chunkIndex == that.chunkIndex && Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, chunkIndex);
    }
}
