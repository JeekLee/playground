package com.playground.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unified {@link RestControllerAdvice} shipped via Spring Boot auto-config in
 * shared-kernel (ADR-11). BCs override by declaring their own
 * {@code @RestControllerAdvice} bean — {@code @ConditionalOnMissingBean} on the
 * auto-configuration registration steps this one aside.
 */
@RestControllerAdvice
public class SharedExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SharedExceptionHandler.class);

    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<ErrorResponse> handleAbstract(AbstractException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(ex.errorCode().code(), ex.getMessage(), req.getRequestURI(), traceId());
        logAt(ex.logLevel(), ex, body);
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = new ArrayList<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.add(new ErrorResponse.FieldError(fe.getField(), fe.getCode(), fe.getDefaultMessage()));
        }
        ErrorResponse body =
                ErrorResponse.of("SHARED-VALIDATION-001", "Validation failed", req.getRequestURI(), traceId(), fields);
        log.warn("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = new ArrayList<>();
        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            String field = cv.getPropertyPath() == null ? null : cv.getPropertyPath().toString();
            fields.add(new ErrorResponse.FieldError(field, "ConstraintViolation", cv.getMessage()));
        }
        ErrorResponse body =
                ErrorResponse.of("SHARED-VALIDATION-002", "Constraint violation", req.getRequestURI(), traceId(), fields);
        log.warn("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleDeserialization(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(
                "SHARED-DESERIALIZATION-001", "Malformed request body", req.getRequestURI(), traceId());
        log.warn("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(
                "SHARED-MISSING-PARAM-001",
                "Missing request parameter: " + ex.getParameterName(),
                req.getRequestURI(),
                traceId());
        log.warn("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest req) {
        ErrorResponse body =
                ErrorResponse.of("SHARED-NOT-FOUND-001", "Resource not found", req.getRequestURI(), traceId());
        log.warn("[{}] {} path={}", body.errorCode(), body.message(), body.path());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleFallback(Exception ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(
                "SHARED-INTERNAL-001", "Internal server error", req.getRequestURI(), traceId());
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

    private static String traceId() {
        // Micrometer Observation integration intentionally deferred — return null until M5 metrics ships.
        // The ErrorResponse omits null fields from JSON via @JsonInclude(NON_NULL).
        return null;
    }
}
