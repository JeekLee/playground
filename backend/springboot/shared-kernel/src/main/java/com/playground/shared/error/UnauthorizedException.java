package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 401. Authentication is missing or invalid. */
public class UnauthorizedException extends AbstractException {

    public UnauthorizedException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public UnauthorizedException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
