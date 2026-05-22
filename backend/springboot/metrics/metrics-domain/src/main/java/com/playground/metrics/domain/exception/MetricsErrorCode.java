package com.playground.metrics.domain.exception;

import com.playground.shared.error.BadRequestException;
import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.ServiceUnavailableException;
import com.playground.shared.error.UnauthorizedException;

/**
 * Metrics BC error-code enum per ADR-11 + ADR-15 §C. Format:
 * {@code METRICS-<SUBSYSTEM>-NNN}. HTTP status mappings come from
 * {@link MappedTo} on each constant and are resolved at throw site by
 * {@code ExceptionCreator} per ADR-11.
 */
public enum MetricsErrorCode implements ErrorCode {

    @MappedTo(BadRequestException.class)
    UNKNOWN_METRIC("METRICS-VALIDATION-001", "Unknown metric id: {0}"),

    @MappedTo(BadRequestException.class)
    UNKNOWN_SERVICE("METRICS-VALIDATION-002", "Unknown service: {0}"),

    @MappedTo(BadRequestException.class)
    INVALID_RANGE("METRICS-VALIDATION-003", "Invalid range: {0}"),

    @MappedTo(BadRequestException.class)
    INVALID_STEP("METRICS-VALIDATION-004", "Invalid step: {0}"),

    @MappedTo(UnauthorizedException.class)
    AUTH_REQUIRED("AUTH-401-001", "Authentication required"),

    @MappedTo(ServiceUnavailableException.class)
    RATE_LIMITED("METRICS-RATE-LIMIT-001", "Rate limit exceeded"),

    @MappedTo(ServiceUnavailableException.class)
    UPSTREAM_DOWN("METRICS-UPSTREAM-DOWN-001", "Metrics backend unavailable");

    private final String code;
    private final String defaultMessage;

    MetricsErrorCode(String code, String defaultMessage) {
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
