package com.playground.docs.domain.event;

import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Instant;

/**
 * Domain event emitted when a document is created OR when its body changes on
 * PATCH. Title-only or visibility-only edits do NOT emit this event per M2
 * spec §5 + ADR-12 §9. Visibility flips emit
 * {@link DocumentVisibilityChanged} instead.
 *
 * <p>POJO record per ADR-02 v2 — no Spring, no Jackson annotations. The -infra
 * outbox externalizer wraps this in the shared-kernel {@code EventEnvelope<T>}
 * per ADR-03 before publishing on topic {@code docs.document.uploaded}.
 *
 * <p>Idempotency key per M2 spec §5: {@code documentId + bodyChecksum}.
 * Consumers (rag-ingestion, in-service search projector) skip re-processing
 * when they have already handled the same checksum on the same document.
 */
public record DocumentUploaded(
        DocumentId documentId,
        AuthorId userId,
        Visibility visibility,
        DocumentTitle title,
        DocumentPath path,
        String bodyChecksum,
        Instant occurredAt
) {}
