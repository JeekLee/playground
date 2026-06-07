package com.playground.docs.search.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for pgvector similarity search over {@code docs.document_chunks}
 * used by the {@code search_documents} tool (agentic-search spec D1). Ported
 * from chat's {@code ChunkRetrievalPort} + {@code PgvectorChunkRetrievalAdapter}
 * (visibility filter, HNSW ef_search tuning) with a {@code documentId} filter
 * and {@code docs.documents} title join added.
 */
public interface ChunkSearchPort {

    /**
     * One ranked chunk row. {@code text} is the full chunk text (the service
     * head-truncates to the excerpt budget).
     */
    record Row(UUID documentId, int chunkIndex, String title, String text, String visibility) {}

    /**
     * Rank chunks by cosine distance to {@code embedding}, scoped to the
     * caller's visibility (public OR the caller's own private) and optionally
     * narrowed to a single document.
     *
     * @param callerId         the caller whose private docs are in scope
     * @param embedding        query embedding vector
     * @param k                max rows to return
     * @param documentIdOrNull when non-null, restrict to this document
     */
    List<Row> search(UUID callerId, float[] embedding, int k, UUID documentIdOrNull);
}
