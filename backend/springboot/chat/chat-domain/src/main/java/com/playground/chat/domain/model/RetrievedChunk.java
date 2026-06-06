package com.playground.chat.domain.model;

import com.playground.chat.domain.enums.Visibility;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.UserId;
import java.util.Objects;

/**
 * One row returned by the pgvector retrieval SQL per ADR-14 §3.2 plus the
 * title enriched from the docs.documents cross-schema join (ADR-14 §3).
 * Carries everything the {@code retrieval} SSE event payload needs.
 *
 * <p>The {@code position} field is the 1-indexed slot assigned by the
 * orchestrator (the {@code [N]} the LLM is told to cite); preserved here so
 * the citation event + the {@code [N]} marker extraction match indices.
 */
public record RetrievedChunk(
        int position,
        DocumentId documentId,
        int chunkIndex,
        String text,
        String title,
        UserId chunkOwner,
        Visibility visibility) {

    public RetrievedChunk {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(visibility, "visibility");
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1, got " + position);
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0, got " + chunkIndex);
        }
    }
}
