package com.playground.ragingestion.domain.event;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import java.time.Instant;

/**
 * M3's only published domain event per ADR-13 §3. Emitted via Spring's
 * {@link org.springframework.context.ApplicationEventPublisher} inside the
 * chunk-upsert transaction; Spring Modulith's JPA outbox + Kafka bridge
 * externalize it on topic {@code rag.document.ingested} with the envelope
 * shape per ADR-03.
 *
 * <p>Payload fields per ADR-13 §3. Key = {@code documentId} for partition
 * affinity with the docs topics.
 */
public record DocumentIngested(
        DocumentId documentId,
        AuthorId userId,
        Visibility visibility,
        int chunkCount,
        String bodyChecksum,
        Instant embeddedAt
) {}
