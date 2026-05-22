package com.playground.shared.error;

import java.text.MessageFormat;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/**
 * Root of the playground exception hierarchy per ADR-11. Subclasses pin the
 * HTTP status and the log level — the throwing code chooses the BC-scoped
 * {@link ErrorCode} and supplies message arguments.
 */
public abstract class AbstractException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] messageArgs;

    protected AbstractException(ErrorCode errorCode, Object... messageArgs) {
        super(format(errorCode, messageArgs));
        this.errorCode = errorCode;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs;
    }

    protected AbstractException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(format(errorCode, messageArgs), cause);
        this.errorCode = errorCode;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs;
    }

    /** HTTP status pinned by the concrete subclass (per ADR-11 table). */
    public abstract HttpStatus httpStatus();

    /** Log level pinned by the concrete subclass (per ADR-11 §logLevel()). */
    public abstract Level logLevel();

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object[] messageArgs() {
        return messageArgs.clone();
    }

    private static String format(ErrorCode code, Object[] args) {
        if (code == null) {
            return "unknown error";
        }
        String template = code.defaultMessage();
        if (template == null || template.isBlank()) {
            return code.code();
        }
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return MessageFormat.format(template, args);
        } catch (IllegalArgumentException unused) {
            // Template has no placeholders or placeholders mismatch — fall back to raw.
            return template;
        }
    }
}
