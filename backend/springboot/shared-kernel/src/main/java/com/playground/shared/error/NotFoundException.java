package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 404. Aggregate not found by ID, or filtered out by visibility. */
public class NotFoundException extends AbstractException {

    public NotFoundException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public NotFoundException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
