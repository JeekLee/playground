package com.playground.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Wire-format error body per ADR-11 §"Unified response body shape".
 *
 * @param errorCode BC-scoped machine-readable code (e.g. {@code IDENTITY-BOOTSTRAP-001})
 * @param message   rendered default message (may be localized later)
 * @param timestamp instant the advice fired (ISO-8601 UTC)
 * @param path      request path
 * @param traceId   current trace id (Micrometer Observation) or {@code null} if absent
 * @param details   per-field validation details; {@code null} (omitted from JSON) on non-validation errors
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String errorCode,
        String message,
        Instant timestamp,
        String path,
        String traceId,
        List<FieldError> details
) {

    public static ErrorResponse of(String errorCode, String message, String path, String traceId) {
        return new ErrorResponse(errorCode, message, Instant.now(), path, traceId, null);
    }

    public static ErrorResponse of(
            String errorCode, String message, String path, String traceId, List<FieldError> details) {
        return new ErrorResponse(errorCode, message, Instant.now(), path, traceId, details);
    }

    public record FieldError(String field, String code, String message) {}
}
