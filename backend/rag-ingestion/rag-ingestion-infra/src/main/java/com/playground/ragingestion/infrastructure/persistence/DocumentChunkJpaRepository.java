package com.playground.ragingestion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data adapter for {@link DocumentChunkJpaEntity}. The use-case
 * persistence path goes through {@code ChunkRepositoryJdbcAdapter} (native
 * SQL is required for the pgvector column); this interface is provisioned so
 * Modulith's Hibernate scan resolves the entity + diagnostic surfaces have a
 * typed accessor.
 */
public interface DocumentChunkJpaRepository
        extends JpaRepository<DocumentChunkJpaEntity, DocumentChunkJpaId> {}
