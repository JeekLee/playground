package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 403. Authenticated but not permitted. */
public class ForbiddenException extends AbstractException {

    public ForbiddenException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public ForbiddenException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.FORBIDDEN;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
