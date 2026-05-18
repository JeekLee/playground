package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentBodyDto;
import java.time.Instant;

/**
 * Response shape for the internal body-fetch route
 * {@code GET /internal/docs/{id}/body} per ADR-12 §2. M3 rag-ingestion
 * consumes this when an event arrives.
 */
public record DocumentBodyResponse(
        String id,
        String body,
        String bodyChecksum,
        Instant updatedAt) {

    public static DocumentBodyResponse from(DocumentBodyDto dto) {
        return new DocumentBodyResponse(
                dto.id().toString(),
                dto.body(),
                dto.bodyChecksum(),
                dto.updatedAt());
    }
}
