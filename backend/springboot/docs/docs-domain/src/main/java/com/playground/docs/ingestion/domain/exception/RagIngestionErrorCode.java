package com.playground.docs.ingestion.domain.exception;

import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.InternalServerErrorException;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.ServiceUnavailableException;

/**
 * RAG-Ingestion BC error-code enum per ADR-11. Format:
 * {@code RAG-<SUBSYSTEM>-<NNN>}. M3 has no public HTTP surface so these codes
 * surface only via structured logs + the DLQ exception header — the HTTP
 * mappings are inert in practice but retained so the shared
 * {@code SharedExceptionHandler} can reason about every M3-thrown exception
 * if a future admin endpoint surfaces one.
 */
public enum RagIngestionErrorCode implements ErrorCode {

    @MappedTo(ServiceUnavailableException.class)
    EMBEDDING_GATEWAY_UNAVAILABLE("RAG-EMBED-001",
            "Embedding gateway unavailable after retries: {0}"),

    @MappedTo(ServiceUnavailableException.class)
    DOCS_BODY_FETCH_FAILED("RAG-DOCS-001",
            "Failed to fetch body from docs-api for document {0}"),

    @MappedTo(InternalServerErrorException.class)
    BODY_TOO_LARGE("RAG-DOCS-002",
            "Body fetch for document {0} exceeded the 1 MB cap"),

    @MappedTo(InternalServerErrorException.class)
    LOCK_ACQUISITION_FAILED("RAG-LOCK-001",
            "Failed to acquire distributed lock for document {0}"),

    @MappedTo(InternalServerErrorException.class)
    CHUNK_PERSIST_FAILED("RAG-CHUNK-001",
            "Failed to persist chunks for document {0}"),

    /**
     * Docs-api returned 404 for the body fetch — document was deleted between
     * the event arriving and the body being fetched. Used by
     * {@code ReembedService} to signal {@code Outcome.SKIPPED} rather than
     * routing to the DLQ.
     */
    @MappedTo(com.playground.shared.error.NotFoundException.class)
    DOCUMENT_NOT_FOUND("RAG-DOCS-003",
            "Document {0} not found in docs-api (404) — may have been deleted");

    private final String code;
    private final String defaultMessage;

    RagIngestionErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
