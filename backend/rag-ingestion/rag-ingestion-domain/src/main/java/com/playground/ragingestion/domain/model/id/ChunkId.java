package com.playground.ragingestion.domain.model.id;

import java.util.Objects;

/**
 * Synthetic chunk identifier. Per ADR-13 §F the PK on {@code rag.document_chunks}
 * is the tuple {@code (document_id, chunk_index)} — this VO bundles the two
 * halves so domain code can hand around a typed reference without leaking the
 * tuple shape into method signatures.
 */
public record ChunkId(DocumentId documentId, int chunkIndex) {

    public ChunkId {
        Objects.requireNonNull(documentId, "ChunkId.documentId must not be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("ChunkId.chunkIndex must be non-negative, got " + chunkIndex);
        }
    }

    public static ChunkId of(DocumentId documentId, int chunkIndex) {
        return new ChunkId(documentId, chunkIndex);
    }

    @Override
    public String toString() {
        return documentId + "#" + chunkIndex;
    }
}
