package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 500. Unrecoverable invariant violation. Default catch-all. */
public class InternalServerErrorException extends AbstractException {

    public InternalServerErrorException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public InternalServerErrorException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @Override
    public Level logLevel() {
        return Level.ERROR;
    }
}
