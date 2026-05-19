package com.playground.ragingestion.domain.model;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.ChunkId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Single embeddable chunk per ADR-13 §F. One document produces N chunks
 * (ordered by {@link ChunkId#chunkIndex()}), each carrying a copy of
 * {@code (userId, visibility, bodyChecksum)} so M4 retrieval can filter via
 * a single SQL {@code WHERE} clause.
 *
 * <p>{@code headingPath} (M3.1 amendment) is the breadcrumb of the section
 * this chunk came from, e.g. {@code ["API", "Auth"]}. Empty list = chunk
 * belongs to content above the first heading, or to a pre-migration row
 * predating the markdown-aware chunker.
 *
 * <p>POJO record per ADR-02 v2 — no Spring, no Jackson, no JPA. The
 * persistence mirror ({@code DocumentChunkJpaEntity}) lives in
 * {@code rag-ingestion-infra}.
 */
public record DocumentChunk(
        ChunkId id,
        AuthorId userId,
        Visibility visibility,
        ChunkText text,
        Embedding embedding,
        List<String> headingPath,
        BodyChecksum bodyChecksum,
        Instant createdAt
) {

    public DocumentChunk {
        Objects.requireNonNull(id, "DocumentChunk.id must not be null");
        Objects.requireNonNull(userId, "DocumentChunk.userId must not be null");
        Objects.requireNonNull(visibility, "DocumentChunk.visibility must not be null");
        Objects.requireNonNull(text, "DocumentChunk.text must not be null");
        Objects.requireNonNull(embedding, "DocumentChunk.embedding must not be null");
        Objects.requireNonNull(headingPath, "DocumentChunk.headingPath must not be null");
        Objects.requireNonNull(bodyChecksum, "DocumentChunk.bodyChecksum must not be null");
        Objects.requireNonNull(createdAt, "DocumentChunk.createdAt must not be null");
        headingPath = List.copyOf(headingPath);
    }

    public DocumentId documentId() {
        return id.documentId();
    }

    public int chunkIndex() {
        return id.chunkIndex();
    }
}
