package com.playground.docs.domain.event;

import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;

/**
 * M6.1 ADR-12 §A12.5 — in-BC domain event signaling the async extraction
 * worker should pick up a freshly-uploaded document.
 *
 * <p>Producer: the upload controller (via {@link com.playground.docs.application.service.DocumentAppService})
 * after a successful MinIO PUT + INSERT inside the create transaction.
 * Spring Modulith's outbox writes the event row + Kafka externalization
 * runs after commit so the worker only picks up persisted-and-MinIO-visible
 * documents.
 *
 * <p>Consumer: {@code @KafkaListener docs.document.extraction-requested} in
 * docs-infra (same JVM). Dispatches the work to the dedicated
 * {@link java.util.concurrent.ExecutorService}.
 *
 * <p>POJO record per ADR-02 v2 — no Spring, no Jackson annotations. The infra
 * outbox externalizer wraps it in the shared-kernel
 * {@link com.playground.shared.event.EventEnvelope} per ADR-03 before publish.
 */
public record DocumentExtractionRequested(
        DocumentId documentId,
        AuthorId userId,
        MimeType sourceMimeType,
        String sourceObjectKey,
        Instant occurredAt) {}
