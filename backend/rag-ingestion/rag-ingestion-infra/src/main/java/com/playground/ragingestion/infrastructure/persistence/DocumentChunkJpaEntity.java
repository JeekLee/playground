package com.playground.ragingestion.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mirror of {@link com.playground.ragingestion.domain.model.DocumentChunk}
 * per ADR-02 v2. The {@code embedding} column (pgvector {@code vector(1024)})
 * is not modeled here — Hibernate has no native binding for pgvector and
 * writes to {@code rag.document_chunks} go through {@code JdbcTemplate}
 * native SQL (with the {@code com.pgvector.PGvector} type) inside
 * {@link ChunkRepositoryJdbcAdapter}.
 *
 * <p>The entity is retained so the existing Modulith {@code event_publication}
 * scan + {@code spring.jpa.hibernate.ddl-auto=validate} have a class to
 * resolve against, and so M3.1's eventual JPA-friendly diagnostics surface
 * (`SELECT document_id, body_checksum FROM rag.document_chunks ...`) gets a
 * typed binding for free.
 */
@Entity
@Table(name = "document_chunks", schema = "rag")
@IdClass(DocumentChunkJpaId.class)
public class DocumentChunkJpaEntity {

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Id
    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "visibility", nullable = false)
    private String visibility;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "body_checksum", nullable = false)
    private String bodyChecksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "heading_path", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] headingPath;

    protected DocumentChunkJpaEntity() {
        // for JPA
    }

    public DocumentChunkJpaEntity(
            UUID documentId,
            int chunkIndex,
            UUID userId,
            String visibility,
            String text,
            String bodyChecksum,
            Instant createdAt,
            Instant updatedAt,
            String[] headingPath) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.userId = userId;
        this.visibility = visibility;
        this.text = text;
        this.bodyChecksum = bodyChecksum;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.headingPath = headingPath;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getText() {
        return text;
    }

    public String getBodyChecksum() {
        return bodyChecksum;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String[] getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String[] headingPath) {
        this.headingPath = headingPath;
    }
}
