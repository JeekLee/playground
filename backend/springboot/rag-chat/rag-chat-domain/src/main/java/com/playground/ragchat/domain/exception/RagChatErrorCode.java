package com.playground.ragchat.domain.exception;

import com.playground.shared.error.BadRequestException;
import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.InternalServerErrorException;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.NotFoundException;
import com.playground.shared.error.ServiceUnavailableException;
import com.playground.shared.error.UnauthorizedException;

/**
 * Rag-Chat BC error-code enum per ADR-11 + ADR-14 §C. Format:
 * {@code CHAT-&lt;SUBSYSTEM&gt;-NNN}.
 *
 * <p>HTTP status mappings come from {@link MappedTo} on each constant and are
 * resolved at throw site by {@code ExceptionCreator} per ADR-11.
 */
public enum RagChatErrorCode implements ErrorCode {

    @MappedTo(UnauthorizedException.class)
    AUTH_REQUIRED("AUTH-401-001", "Authentication required"),

    @MappedTo(NotFoundException.class)
    SESSION_NOT_FOUND("CHAT-NOT-FOUND-001", "Session not found"),

    // ADR-20 §D4 — owner-only attachment download. Non-owner OR missing → 404
    // (tenant isolation; don't leak existence, ADR-14 §6.5 style).
    @MappedTo(NotFoundException.class)
    ATTACHMENT_NOT_FOUND("CHAT-NOT-FOUND-002", "Attachment not found"),

    @MappedTo(BadRequestException.class)
    MESSAGE_TOO_LARGE("CHAT-VALIDATION-001", "Message exceeds 4 KB"),

    @MappedTo(BadRequestException.class)
    ACCEPT_HEADER_REQUIRED("CHAT-VALIDATION-002", "Accept must include text/event-stream"),

    @MappedTo(BadRequestException.class)
    MESSAGE_BLANK("CHAT-VALIDATION-003", "Message must not be blank"),

    @MappedTo(BadRequestException.class)
    SESSION_TITLE_BLANK("CHAT-VALIDATION-004", "Title must not be blank"),

    @MappedTo(ServiceUnavailableException.class)
    RATE_LIMITED("CHAT-RATE-LIMIT-001", "Rate limit exceeded"),

    @MappedTo(ServiceUnavailableException.class)
    GATEWAY_DOWN("CHAT-GATEWAY-DOWN-001", "AI service unavailable"),

    // ADR-20 §D3 — MinIO attachment blob store transient error / unreachable.
    @MappedTo(ServiceUnavailableException.class)
    BLOB_STORAGE_UNAVAILABLE("CHAT-BLOB-001", "Attachment storage unavailable"),

    @MappedTo(InternalServerErrorException.class)
    RETRIEVAL_FAILED("CHAT-RETRIEVAL-001", "Retrieval failed: {0}"),

    @MappedTo(InternalServerErrorException.class)
    EMBEDDING_FAILED("CHAT-EMBED-001", "Query embedding failed: {0}"),

    @MappedTo(InternalServerErrorException.class)
    PERSISTENCE_FAILED("CHAT-PERSIST-001", "Failed to persist chat artifact: {0}");

    private final String code;
    private final String defaultMessage;

    RagChatErrorCode(String code, String defaultMessage) {
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
