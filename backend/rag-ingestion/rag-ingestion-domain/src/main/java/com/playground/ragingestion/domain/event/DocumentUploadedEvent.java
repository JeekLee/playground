package com.playground.ragingestion.domain.event;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.DocumentId;

/**
 * Mirror of docs BC's {@code DocumentUploaded} per ADR-13 §4.5. M3 owns its own
 * record so changes to the docs domain do not ripple into the rag-ingestion
 * compile graph until M3 actively consumes a new field. Payload shape pinned
 * by M2 spec §5 + docs-infra's
 * {@code DocsEventExternalizationConfig.UploadedPayload}.
 *
 * <p>Idempotency key: {@code documentId + bodyChecksum} (M2 spec §5).
 */
public record DocumentUploadedEvent(
        DocumentId documentId,
        AuthorId userId,
        Visibility visibility,
        String title,
        String path,
        String bodyChecksum
) {}
