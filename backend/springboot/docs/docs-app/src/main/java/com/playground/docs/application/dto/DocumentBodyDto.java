package com.playground.docs.application.dto;

import com.playground.docs.domain.model.Document;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal body-fetch DTO for {@code GET /internal/docs/{id}/body} per
 * ADR-12 §2. Consumed by M3 rag-ingestion (after M3 ships) to pull the raw
 * Markdown body without re-reading the docs schema directly.
 *
 * <p>Lives under {@code /internal/**} so the gateway never exposes it; only
 * compose-network callers reach the route.
 */
public record DocumentBodyDto(
        UUID id,
        String body,
        String bodyChecksum,
        Instant updatedAt
) {

    public static DocumentBodyDto from(Document doc) {
        return new DocumentBodyDto(
                doc.id().value(),
                doc.body().value(),
                doc.body().checksum(),
                doc.updatedAt());
    }
}
