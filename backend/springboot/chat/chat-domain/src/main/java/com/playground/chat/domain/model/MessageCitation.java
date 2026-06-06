package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import java.util.Objects;

/**
 * One row in {@code chat.message_citations} per ADR-14 §F. Only the citations
 * whose {@code [N]} marker actually appeared in the streamed assistant text
 * are persisted (cite-persistence policy from ADR-14 §10).
 *
 * @param messageId  parent assistant message
 * @param position   the 1-indexed {@code [N]} slot in the assistant body
 * @param documentId app-level FK to {@code docs.documents.id}
 * @param chunkIndex app-level FK to {@code docs.document_chunks.chunk_index}
 */
public record MessageCitation(MessageId messageId, int position, DocumentId documentId, int chunkIndex) {

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
