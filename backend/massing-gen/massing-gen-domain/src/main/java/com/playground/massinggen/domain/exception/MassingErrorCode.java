package com.playground.massinggen.domain.exception;

import com.playground.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * BC-scoped error-code enum per ADR-11 (shared exception hierarchy) and
 * ADR-18 §7. Each constant pins a stable wire {@code code} (carried inside
 * the {@code tool_error.message} prefix grammar of ADR-18 §6) and an HTTP
 * status mapping (used by the controller advice in {@code massing-gen-api}).
 *
 * <p>The error-code value itself ({@link #wireCode()}) is what the frontend
 * regex-extracts from the {@code <CODE>: <message>} prefix per ADR-18 §6.
 * The stable {@link #code()} value is the playground-wide
 * {@code MASSING-...-NNN} identifier per ADR-11 wire format.
 *
 * <p>HTTP status mapping (ADR-18 §7):
 * <ul>
 *   <li>{@link #BRIEF_NOT_FOUND} → 404</li>
 *   <li>{@link #BRIEF_NOT_ACCESSIBLE} → 403</li>
 *   <li>{@link #BRIEF_NOT_READY} → 422</li>
 *   <li>{@link #BRIEF_EXTRACTION_FAILED} → 422</li>
 *   <li>{@link #MASSING_ALGORITHM_FAILED} → 422</li>
 *   <li>{@link #SIDECAR_TIMEOUT} → 504</li>
 *   <li>{@link #SIDECAR_FAILED} → 502</li>
 *   <li>{@link #BRIEF_FETCH_FAILED} → 502</li>
 *   <li>{@link #INTERNAL} → 500</li>
 * </ul>
 */
public enum MassingErrorCode implements ErrorCode {

    BRIEF_NOT_FOUND(
            "MASSING-BRIEF-001",
            "Brief document {0} not found.",
            HttpStatus.NOT_FOUND),

    BRIEF_NOT_ACCESSIBLE(
            "MASSING-BRIEF-002",
            "You do not have access to this brief.",
            HttpStatus.FORBIDDEN),

    BRIEF_NOT_READY(
            "MASSING-BRIEF-003",
            "Brief is still being analyzed — please wait for extraction to complete.",
            HttpStatus.UNPROCESSABLE_ENTITY),

    BRIEF_EXTRACTION_FAILED(
            "MASSING-EXTRACT-001",
            "Could not extract room program from brief — is this a competition brief PDF?",
            HttpStatus.UNPROCESSABLE_ENTITY),

    BRIEF_FETCH_FAILED(
            "MASSING-BRIEF-004",
            "Brief body fetch from docs-api failed.",
            HttpStatus.BAD_GATEWAY),

    MASSING_ALGORITHM_FAILED(
            "MASSING-ALGO-001",
            "Room program exceeds maximum buildable volume — narrower site or fewer rooms required.",
            HttpStatus.UNPROCESSABLE_ENTITY),

    SIDECAR_TIMEOUT(
            "MASSING-SIDECAR-001",
            ".3dm serialization did not complete within the configured timeout.",
            HttpStatus.GATEWAY_TIMEOUT),

    SIDECAR_FAILED(
            "MASSING-SIDECAR-002",
            ".3dm serialization service is unavailable.",
            HttpStatus.BAD_GATEWAY),

    INTERNAL(
            "MASSING-INTERNAL-001",
            "An unexpected error occurred while generating the massing.",
            HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    MassingErrorCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    /** HTTP status for the controller advice mapping (ADR-18 §7). */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /**
     * Wire-level domain code carried in the {@code tool_error.message} prefix
     * per ADR-18 §6. The frontend parses {@code <wireCode>: <body>} to drive
     * the secondary-action label.
     */
    public String wireCode() {
        return name();
    }
}
