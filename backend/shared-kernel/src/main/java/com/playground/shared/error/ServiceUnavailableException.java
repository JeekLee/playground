package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 503. Downstream dependency unreachable. */
public class ServiceUnavailableException extends AbstractException {

    public ServiceUnavailableException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public ServiceUnavailableException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
