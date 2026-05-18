package com.playground.docs.domain.exception;

import com.playground.shared.error.BadRequestException;
import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.NotFoundException;
import com.playground.shared.error.UnauthorizedException;

/**
 * Docs BC error-code enum per ADR-11. Format: {@code DOCS-<SUBSYSTEM>-<NNN>}.
 *
 * <p>Per M2 spec §6.5 the docs API never distinguishes 403 from 404 to
 * non-authors — leaking the existence of a private doc by status code defeats
 * tenant isolation. Hence {@link #DOCUMENT_NOT_FOUND} doubles as the
 * not-authorized signal for non-author callers.
 */
public enum DocsErrorCode implements ErrorCode {

    @MappedTo(NotFoundException.class)
    DOCUMENT_NOT_FOUND("DOCS-DOCUMENT-001", "Document not found: {0}"),

    @MappedTo(UnauthorizedException.class)
    USER_HEADER_MISSING("DOCS-USER-001", "X-User-Id header is required on this route"),

    @MappedTo(BadRequestException.class)
    TITLE_BLANK("DOCS-VALIDATION-001", "Document title must not be blank"),

    @MappedTo(BadRequestException.class)
    BODY_TOO_LARGE("DOCS-VALIDATION-002", "Document body exceeds maximum size (1 MB)"),

    @MappedTo(BadRequestException.class)
    PATH_INVALID("DOCS-VALIDATION-003", "Document path is not in the required format: {0}"),

    @MappedTo(BadRequestException.class)
    VISIBILITY_INVALID("DOCS-VALIDATION-004", "Document visibility must be one of: private, public"),

    @MappedTo(BadRequestException.class)
    SCOPE_REQUIRED("DOCS-VALIDATION-005",
            "scope=mine is required on GET /api/docs (community feed lands in M2 S2)"),

    @MappedTo(BadRequestException.class)
    SCOPE_FILTER_UNSUPPORTED("DOCS-VALIDATION-006",
            "filters on ?scope=mine (author, path) are not supported in M2 S1"),

    @MappedTo(BadRequestException.class)
    UPLOAD_FILE_MISSING("DOCS-VALIDATION-007",
            "multipart upload requires a non-empty 'file' part containing a Markdown document");

    private final String code;
    private final String defaultMessage;

    DocsErrorCode(String code, String defaultMessage) {
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
