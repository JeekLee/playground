package com.playground.shared.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/** 415. The resource exists but has no representation in the requested media shape. */
public class UnsupportedMediaTypeException extends AbstractException {

    public UnsupportedMediaTypeException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode, messageArgs);
    }

    public UnsupportedMediaTypeException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode, cause, messageArgs);
    }

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    }

    @Override
    public Level logLevel() {
        return Level.WARN;
    }
}
