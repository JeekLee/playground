package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import java.util.Objects;

/**
 * One row in {@code chat.message_citations} per ADR-14 §F. Only the citations
 * whose {@code [N]} marker actually appeared in the streamed assistant text
 * are persisted (cite-persistence policy from ADR-14 §10).
 *
 * <p>{@code title}/{@code excerpt}/{@code visibility} are the snapshot frozen at
 * persist time (agentic-search spec D2) — history reload reads them back
 * directly instead of joining the {@code docs} schema. They are nullable: a
 * legacy or non-search citation may not carry them.
 *
 * @param messageId  parent assistant message
 * @param position   the 1-indexed {@code [N]} slot in the assistant body
 * @param documentId app-level FK to {@code docs.documents.id}
 * @param chunkIndex app-level FK to {@code docs.document_chunks.chunk_index}
 * @param title      snapshot of the document title (nullable)
 * @param excerpt    snapshot of the cited chunk excerpt (nullable)
 * @param visibility snapshot of the chunk visibility wire value (nullable)
 */
public record MessageCitation(
        MessageId messageId,
        int position,
        DocumentId documentId,
        int chunkIndex,
        String title,
        String excerpt,
        String visibility) {

    public MessageCitation {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(documentId, "documentId");
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1, got " + position);
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got " + chunkIndex);
        }
    }
}
