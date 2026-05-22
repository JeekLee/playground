package com.playground.docs.application.dto;

import com.playground.docs.domain.enums.ExtractionStatus;
import com.playground.docs.domain.model.id.DocumentId;

/**
 * M6.1 — broadcast payload for the SSE extraction stream. Workflow stages
 * publish this to the in-process {@code ApplicationEventPublisher}; the
 * registry adapter fans out to per-document {@code SseEmitter} sets.
 *
 * <p>{@code reason} is non-null only on {@link ExtractionStatus#FAILED};
 * {@code pageDone} / {@code pageTotal} are optional progress counters
 * (in-flight extraction reporting). All emitters subscribed for the given
 * {@code documentId} receive the event.
 */
public record ExtractionStatusUpdate(
        DocumentId documentId,
        ExtractionStatus status,
        String reason,
        Integer pageDone,
        Integer pageTotal) {

    public static ExtractionStatusUpdate of(DocumentId documentId, ExtractionStatus status) {
        return new ExtractionStatusUpdate(documentId, status, null, null, null);
    }

    public static ExtractionStatusUpdate failed(DocumentId documentId, String reason) {
        return new ExtractionStatusUpdate(documentId, ExtractionStatus.FAILED, reason, null, null);
    }

    public static ExtractionStatusUpdate extracting(DocumentId documentId, int done, int total) {
        return new ExtractionStatusUpdate(documentId, ExtractionStatus.EXTRACTING, null, done, total);
    }
}
