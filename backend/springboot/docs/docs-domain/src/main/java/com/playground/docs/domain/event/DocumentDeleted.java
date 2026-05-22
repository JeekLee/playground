package com.playground.docs.domain.event;

import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;

/**
 * Terminal domain event for hard delete. Consumers MUST treat this as the
 * last event for {@link #documentId()} — search projector deletes the
 * OpenSearch doc, rag-ingestion deletes embedded chunks.
 *
 * <p>POJO record per ADR-02 v2. Wrapped in {@code EventEnvelope<T>} on topic
 * {@code docs.document.deleted}. Idempotency key per M2 spec §5: {@code documentId}.
 */
public record DocumentDeleted(
        DocumentId documentId,
        AuthorId userId,
        Instant occurredAt
) {}
