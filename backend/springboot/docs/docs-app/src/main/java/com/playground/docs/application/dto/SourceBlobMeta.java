package com.playground.docs.application.dto;

import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.model.Document;

/**
 * M6.1 — accessor DTO for the source-blob metadata stored on the document
 * row. Used by the {@code GET /api/docs/{id}/original} endpoint to decide
 * the MinIO key + Content-Disposition filename without exposing the full
 * domain aggregate.
 */
public record SourceBlobMeta(
        String objectKey,
        Long sizeBytes,
        String contentType,
        boolean isPdf) {

    public static SourceBlobMeta from(Document doc) {
        return new SourceBlobMeta(
                doc.sourceObjectKey(),
                doc.sourceSizeBytes(),
                doc.sourceMime() != null ? doc.sourceMime() : doc.mimeType().wireValue(),
                doc.mimeType() == MimeType.PDF);
    }
}
