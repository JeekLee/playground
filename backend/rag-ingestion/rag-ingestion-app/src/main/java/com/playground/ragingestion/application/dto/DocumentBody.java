package com.playground.ragingestion.application.dto;

import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import java.time.Instant;
import java.util.Objects;

/**
 * DTO returned by {@code BodyFetchPort.fetchBody} — mirrors the wire shape of
 * docs-api's {@code DocumentBodyResponse} (ADR-12 §2). M3 stores the raw
 * markdown body, the docs-asserted checksum, and the docs row's
 * {@code updated_at} (informational only).
 */
public record DocumentBody(
        DocumentId documentId,
        String body,
        BodyChecksum bodyChecksum,
        Instant updatedAt
) {

    public DocumentBody {
        Objects.requireNonNull(documentId, "DocumentBody.documentId must not be null");
        Objects.requireNonNull(body, "DocumentBody.body must not be null");
        Objects.requireNonNull(bodyChecksum, "DocumentBody.bodyChecksum must not be null");
    }
}
