package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 400. Client supplied invalid input. */
public class BadRequestException extends AbstractException {

    public BadRequestException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public BadRequestException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
