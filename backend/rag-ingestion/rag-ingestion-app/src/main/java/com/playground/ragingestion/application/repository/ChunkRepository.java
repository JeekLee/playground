package com.playground.ragingestion.application.repository;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import java.util.List;
import java.util.Optional;

/**
 * Repository port for {@code rag.document_chunks} per ADR-02 v2 (port lives
 * in the application layer; impl in {@code rag-ingestion-infra}). All
 * operations are document-scoped — M4's vector retrieval is M4's own port.
 */
public interface ChunkRepository {

    /**
     * Returns the {@code bodyChecksum} of any chunk row for {@code documentId},
     * or empty if no chunks exist. Used by the idempotency check (ADR-13 §12):
     * matching checksum means the body has not changed and the {@code uploaded}
     * event is redundant.
     */
    Optional<BodyChecksum> findBodyChecksum(DocumentId documentId);

    /**
     * Replace every chunk for {@code documentId} with the supplied list,
     * atomically. Implementation: DELETE-then-bulk-INSERT within a single
     * transaction (ADR-13 §12 step 4). The supplied list MUST be non-empty
     * — empty bodies short-circuit at the use-case layer.
     */
    void replaceAll(DocumentId documentId, List<DocumentChunk> chunks);

    /**
     * UPDATE-only re-tag of every chunk for {@code documentId}. Per ADR-13
     * §5 this is the {@code visibility-changed} handler's sole DB operation;
     * no body fetch, no embedding. Returns the number of rows touched.
     */
    int updateVisibility(DocumentId documentId, Visibility visibility);

    /**
     * Purge every chunk for {@code documentId}. Returns the number of rows
     * deleted (idempotent — calling on a non-existent document returns 0).
     */
    int deleteAll(DocumentId documentId);

    /** Count chunks for a document (test / observability aid). */
    int countByDocument(DocumentId documentId);
}
