package com.playground.docs.application.port;

import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.SearchHitDto;
import com.playground.docs.domain.model.Document;
import java.util.UUID;

/**
 * OpenSearch projection port per ADR-12 §5 + spec §5.1. The application layer
 * (the {@code DocsSearchProjector} bean) writes through this port from a Kafka
 * listener; the search query side ({@code DocumentSearchService}) reads
 * through the same port.
 *
 * <p>Implementation lives in docs-infra as {@code OpenSearchSearchIndexAdapter}
 * — talks to the {@code docs-v1} index via the {@code opensearch-java} 2.x
 * client.
 *
 * <p>Failure isolation per spec §10: an underlying OpenSearch failure during
 * {@link #index(Document, String)} / {@link #delete(UUID)} surfaces as a
 * thrown {@link RuntimeException}; the projector catches and logs at WARN, the
 * Kafka offset still advances (the index is rebuilt-able from Postgres).
 * Failures during {@link #searchPublic(String, String)} /
 * {@link #searchMine(UUID, String, String)} surface as
 * {@link com.playground.docs.domain.exception.DocsErrorCode#SEARCH_UNAVAILABLE}
 * (503) at the HTTP boundary — the controller maps the runtime exception
 * accordingly.
 */
public interface SearchIndexPort {

    /**
     * Upsert the document into the {@code docs-v1} index. Pulls the latest
     * persisted state directly (the consumer fetches from Postgres first, then
     * calls this).
     *
     * @param document    the latest doc state
     * @param authorName  denormalized author display name; null acceptable
     *                    (the index field stays empty for the row)
     */
    void index(Document document, String authorName);

    /** Remove the document from the search index. Idempotent — missing id is OK. */
    void delete(UUID documentId);

    /**
     * Public-corpus search. Hits the entire community corpus (every author's
     * public docs). Returns a cursor-paginated page of hits.
     */
    CursorPage<SearchHitDto> searchPublic(String query, String cursor);

    /**
     * Mine-scope search. Restricts the corpus to the caller's own docs (any
     * visibility).
     */
    CursorPage<SearchHitDto> searchMine(UUID callerUserId, String query, String cursor);
}
