package com.playground.docs.application.dto;

import com.playground.docs.domain.enums.MimeType;
import java.util.UUID;

/**
 * Input record for the create-document use case. Mirrors M2 spec §6.4
 * {@code CreateDocRequest} plus the gateway-injected author id.
 *
 * <p>{@code body} and {@code path} are optional — the application service
 * normalizes nulls to {@code ""} and {@code "/"} respectively.
 *
 * <p>M6 ADR-16: {@code mimeType} is the source media type of the upload.
 *
 * <p>M6.1 ADR-12 §A12.5 — when {@code sourceObjectKey} is non-null the
 * application service routes the create through the async-extraction path:
 * INSERTs the row with empty body + {@code extraction_status='pending_extraction'},
 * publishes {@code docs.document.extraction-requested}, and returns the
 * detail DTO without the body materialized. When {@code sourceObjectKey} is
 * null (JSON POST or pre-M6.1 markdown multipart) the synchronous M2 path
 * applies: body provided up-front, INSERTed as
 * {@code extraction_status='extracted'}, publishes {@code docs.document.uploaded}.
 */
public record CreateDocumentCommand(
        UUID authorId,
        String title,
        String body,
        String path,
        MimeType mimeType,
        String sourceObjectKey,
        Long sourceSizeBytes,
        String sourceMime) {

    /** M2 four-field shape — defaults to synchronous Markdown path. */
    public CreateDocumentCommand(UUID authorId, String title, String body, String path) {
        this(authorId, title, body, path, MimeType.MARKDOWN, null, null, null);
    }

    /** M6 five-field shape (synchronous mime-aware) — preserves backwards compat. */
    public CreateDocumentCommand(UUID authorId, String title, String body, String path, MimeType mimeType) {
        this(authorId, title, body, path, mimeType, null, null, null);
    }

    /** True when the command should land via the async extraction path. */
    public boolean isAsyncExtraction() {
        return sourceObjectKey != null;
    }
}
