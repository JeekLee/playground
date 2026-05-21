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
 * <p>M6 ADR-16: {@code mimeType} is the source media type of the upload —
 * {@link MimeType#MARKDOWN} for raw {@code .md} uploads + every JSON-body
 * {@code POST /api/docs} call, {@link MimeType#PDF} for PDF uploads (where
 * {@code body} carries the PDFBox/Vision-OCR-extracted Markdown). Defaults
 * to {@link MimeType#MARKDOWN} when null so M2-era callers keep working.
 */
public record CreateDocumentCommand(
        UUID authorId,
        String title,
        String body,
        String path,
        MimeType mimeType) {

    /**
     * Backwards-compatible factory matching the M2 four-field record shape —
     * defaults {@code mimeType = MARKDOWN}. New M6 call sites should pass
     * the mime type explicitly via the canonical record constructor.
     */
    public CreateDocumentCommand(UUID authorId, String title, String body, String path) {
        this(authorId, title, body, path, MimeType.MARKDOWN);
    }
}
