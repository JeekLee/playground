package com.playground.massinggen.domain.exception;

import com.playground.shared.error.AbstractException;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/**
 * BC-scoped {@link AbstractException} carrying a {@link MassingErrorCode}.
 *
 * <p>ADR-11 prescribes six HTTP-typed subclasses living in shared-kernel.
 * M8's seven {@link MassingErrorCode} values span 403/404/422/500/502/504,
 * three of which (422 / 502 / 504) have no dedicated shared-kernel subclass
 * today. Rather than amending ADR-11, M8 collapses all M8 error codes onto
 * a single {@code MassingException} whose {@link #httpStatus()} is driven
 * by the carried error code per ADR-18 §7.
 *
 * <p>The controller advice in {@code massing-gen-api} reads
 * {@code errorCode().httpStatus()} when mapping to the wire response — the
 * shared {@link com.playground.shared.error.SharedExceptionHandler}'s
 * {@code @ExceptionHandler(AbstractException.class)} path picks up
 * {@code MassingException} via the carried base class and writes the
 * {@code MASSING_ALGORITHM_FAILED: ...} prefix grammar into the
 * {@code ErrorResponse.message} field.
 *
 * <p>Log level: WARN for client-facing 4xx and 422 codes (most M8 errors
 * are user-input quality issues, not system faults); ERROR for the
 * {@link MassingErrorCode#INTERNAL} fallback. SIDECAR / BRIEF_FETCH paths
 * log at WARN — they are upstream-availability signals the operator may
 * already be aware of via the breaker metrics.
 */
public class MassingException extends AbstractException {

    public MassingException(MassingErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public MassingException(MassingErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    public MassingErrorCode massingErrorCode() {
        return (MassingErrorCode) errorCode();
    }

    @Override
    public HttpStatus httpStatus() {
        return massingErrorCode().httpStatus();
    }

    @Override
    public Level logLevel() {
        return massingErrorCode() == MassingErrorCode.INTERNAL ? Level.ERROR : Level.WARN;
    }
}
