package com.playground.docs.domain.exception;

import com.playground.shared.error.BadRequestException;
import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.NotFoundException;
import com.playground.shared.error.ServiceUnavailableException;
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
    BODY_TOO_LARGE("DOCS-VALIDATION-002", "Document body exceeds maximum size (10 MB)"),

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
            "multipart upload requires a non-empty 'file' part containing a Markdown document"),

    @MappedTo(BadRequestException.class)
    SEARCH_QUERY_BLANK("DOCS-VALIDATION-008",
            "search query parameter 'q' is required and must not be blank"),

    @MappedTo(BadRequestException.class)
    SEARCH_SCOPE_INVALID("DOCS-VALIDATION-009",
            "search scope must be one of: public, mine"),

    /**
     * agentic-search spec D1 — the {@code /internal/tools/search-documents}
     * tool route needs the caller's identity for the visibility filter; the
     * tool dispatcher must inject {@code X-User-Id}. 400 (not 401) per the
     * spec: a missing header here is a wiring bug in the dispatcher, not an
     * end-user auth failure.
     */
    @MappedTo(BadRequestException.class)
    TOOL_USER_HEADER_MISSING("DOCS-VALIDATION-012",
            "X-User-Id header is required on the search-documents tool route"),

    /**
     * agentic-search spec D1 — the search-documents tool query was missing or
     * blank.
     */
    @MappedTo(BadRequestException.class)
    TOOL_QUERY_BLANK("DOCS-VALIDATION-013",
            "search-documents tool query must not be blank"),

    @MappedTo(BadRequestException.class)
    CURSOR_INVALID("DOCS-VALIDATION-010",
            "pagination cursor is malformed"),

    @MappedTo(BadRequestException.class)
    AUTHOR_PARAM_INVALID("DOCS-VALIDATION-011",
            "author query parameter must be a UUID"),

    /**
     * M2 spec §10 "Search failure isolation" + spec §6.5: when OpenSearch is
     * unreachable, search routes return 503 with this code so callers can
     * distinguish "no hits" from "search subsystem down".
     */
    @MappedTo(ServiceUnavailableException.class)
    SEARCH_UNAVAILABLE("DOCS-SEARCH-001",
            "Search is temporarily unavailable"),

    // --- M6 (ADR-16) PDF upload errors ---

    /**
     * Upload rejected because the file is neither Markdown nor PDF. Surfaced
     * by the controller's 3-step gate (filename suffix → Content-Type →
     * PDF magic bytes).
     */
    @MappedTo(BadRequestException.class)
    INVALID_FILE_TYPE("DOCS-UPLOAD-001",
            "Uploaded file must be Markdown (.md, .markdown) or PDF (.pdf)"),

    /**
     * PDFBox could not parse the PDF (truncated, malformed, non-PDF bytes
     * past the magic check). 400 — the upload itself is bad.
     */
    @MappedTo(BadRequestException.class)
    PDF_CORRUPTED("DOCS-UPLOAD-002",
            "PDF file is corrupted or could not be parsed"),

    /**
     * PDFBox could open the document but it requires a password. M6 does not
     * support owner-password decryption; the user is asked to remove the
     * password and re-upload.
     */
    @MappedTo(BadRequestException.class)
    PDF_ENCRYPTED("DOCS-UPLOAD-003",
            "Encrypted PDFs are not supported; remove the password and re-upload"),

    /**
     * PDF page count exceeds the M6.1 cap (100 pages, ADR-12 §A12.9).
     * Supersedes the M6 ADR-16 §8 200-page cap; the OCR-pages sub-cap is
     * retired (every page is OCR'd in M6.1).
     */
    @MappedTo(BadRequestException.class)
    PDF_TOO_MANY_PAGES("DOCS-UPLOAD-004",
            "PDF has too many pages (maximum 100)"),

    /**
     * Multipart upload exceeded {@code spring.servlet.multipart.max-file-size}
     * (25 MB per ADR-16). Surfaced when {@code MultipartFile.getSize()} reports
     * a value over the cap; oversized requests at the transport layer surface
     * as a {@code MaxUploadSizeExceededException} which the shared advice maps
     * to a generic 400.
     */
    @MappedTo(BadRequestException.class)
    FILE_TOO_LARGE("DOCS-UPLOAD-006",
            "Uploaded file exceeds maximum size (25 MB)"),

    /**
     * M6.1 ADR-12 §A12.4 — MinIO put/get/delete failed. Wraps both transient
     * (gateway unreachable) and permanent (bucket missing, access denied)
     * failures; surfaces as 503 so the client may retry.
     */
    @MappedTo(ServiceUnavailableException.class)
    BLOB_STORAGE_UNAVAILABLE("DOCS-STORAGE-001",
            "Document blob storage is temporarily unavailable"),

    /**
     * M6.1 — the requested source blob was not found in MinIO (the row may
     * have lost its source_object_key, or the operator manually purged the
     * bucket). 404 — surfaced via {@code NotFoundException}.
     */
    @MappedTo(NotFoundException.class)
    SOURCE_BLOB_NOT_FOUND("DOCS-STORAGE-002",
            "Original source blob is no longer available for document: {0}");

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
