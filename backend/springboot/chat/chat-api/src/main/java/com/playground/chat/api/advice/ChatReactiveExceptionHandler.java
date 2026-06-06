package com.playground.chat.api.advice;

import com.playground.chat.domain.exception.ChatErrorCode;
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
 * Reactive (WebFlux) exception advice per ADR-11 + ADR-14 §C. The
 * shared-kernel's {@code SharedExceptionHandler} is servlet-stack only
 * ({@code @ConditionalOnWebApplication(type = SERVLET)}); this BC needs the
 * WebFlux flavour because {@code chat-api} runs Netty + reactive
 * controllers.
 *
 * <p>Special-case: {@link ChatErrorCode#RATE_LIMITED} attaches a
 * {@code Retry-After} header per spec §5.1.
 */
@RestControllerAdvice
public class ChatReactiveExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatReactiveExceptionHandler.class);

    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<ErrorResponse> handleAbstract(AbstractException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), path, null);
        logAt(ex.logLevel(), ex, body);

        HttpHeaders headers = new HttpHeaders();
        if (ChatErrorCode.RATE_LIMITED.code().equals(ex.errorCode().code())
                && ex.messageArgs().length > 0
                && ex.messageArgs()[0] instanceof Long retryAfter) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        } else if (ChatErrorCode.GATEWAY_DOWN.code().equals(ex.errorCode().code())) {
            // ADR-14 §C — pre-stream 503 carries the Resilience4j OPEN duration.
            headers.set(HttpHeaders.RETRY_AFTER, "30");
        }

        return new ResponseEntity<>(body, headers, ex.httpStatus());
    }

    /**
     * Spring WebFlux's static-resource handler raises {@code
     * NoResourceFoundException} when a path matches neither a controller
     * nor a static file. Without this branch the fallback below would wrap
     * it as a {@code SHARED-INTERNAL-001} 500, masking the actual 404
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
