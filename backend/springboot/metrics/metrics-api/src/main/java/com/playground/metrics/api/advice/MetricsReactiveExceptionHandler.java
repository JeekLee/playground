package com.playground.metrics.api.advice;

import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.AbstractException;
import com.playground.shared.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ServerWebExchange;

/**
 * WebFlux exception advice per ADR-11 + ADR-15 §C. Mirrors
 * {@code ChatReactiveExceptionHandler}'s shape — the shared-kernel's
 * {@code SharedExceptionHandler} is servlet-stack-only, so reactive BCs
 * ship their own advice.
 *
 * <p>Special-case: {@link MetricsErrorCode#RATE_LIMITED} overrides the
 * mapped status to HTTP 429 (Too Many Requests) per ADR-15 §C row 6 + spec
 * §8.2 — the shared-kernel hierarchy does not yet expose a dedicated 429
 * exception subclass, and the per-IP / per-user buckets land at this entry
 * point with a {@code ServiceUnavailableException} mapping that we override
 * here. Retry-After header carries the bucket refill ETA (~60s for a
 * 30/min bucket, computed downstream of the adapter).
 */
@RestControllerAdvice
public class MetricsReactiveExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MetricsReactiveExceptionHandler.class);

    /** Conservative refill ETA for the per-IP bucket (60/30 = 2s typical; round up). */
    private static final long RATE_LIMIT_RETRY_AFTER_SECONDS = 60L;

    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<ErrorResponse> handleAbstract(AbstractException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), path, null);
        logAt(ex.logLevel(), ex, body);

        HttpHeaders headers = new HttpHeaders();
        HttpStatus status = ex.httpStatus();

        if (MetricsErrorCode.RATE_LIMITED.code().equals(ex.errorCode().code())) {
            // ADR-15 §C row 6 — bucket-empty returns 429 with Retry-After.
            // Override the mapped 503 from ServiceUnavailableException so the
            // wire status matches the ADR. The retry-after value is best-effort
            // (60s; the bucket actually refills every 2s for the 30/min IP
            // budget, but Retry-After is a hint to the caller, not a hard SLA).
            status = HttpStatus.TOO_MANY_REQUESTS;
            long retryAfter = ex.messageArgs().length > 0
                    && ex.messageArgs()[0] instanceof Long ra
                    ? ra
                    : RATE_LIMIT_RETRY_AFTER_SECONDS;
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        } else if (MetricsErrorCode.UPSTREAM_DOWN.code().equals(ex.errorCode().code())) {
            headers.set(HttpHeaders.RETRY_AFTER, "30");
        }

        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * Spring WebFlux's static-resource handler raises {@code
     * NoResourceFoundException} when a path doesn't match any controller and
     * also has no static file. Without this branch the fallback below would
     * wrap it as a {@code SHARED-INTERNAL-001} 500, masking the actual 404
     * (we hit this during M5 integration where {@code /actuator/prometheus}
     * 404s if {@code micrometer-registry-prometheus} is missing).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        ErrorResponse body = ErrorResponse.of(
                "SHARED-NOT_FOUND-001", "Resource not found", path, null);
        log.debug("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleFallback(Exception ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        ErrorResponse body = ErrorResponse.of(
                "SHARED-INTERNAL-001", "Internal server error", path, null);
        log.error("[{}] {} path={}", body.errorCode(), body.message(), body.path(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static void logAt(Level level, AbstractException ex, ErrorResponse body) {
        switch (level) {
            case ERROR -> log.error(
                    "[{}] {} path={}", body.errorCode(), body.message(), body.path(), ex);
            case WARN -> log.warn(
                    "[{}] {} path={}", body.errorCode(), body.message(), body.path());
            case INFO -> log.info(
                    "[{}] {} path={}", body.errorCode(), body.message(), body.path());
            case DEBUG -> log.debug(
                    "[{}] {} path={}", body.errorCode(), body.message(), body.path());
            case TRACE -> log.trace(
                    "[{}] {} path={}", body.errorCode(), body.message(), body.path());
        }
    }
}
