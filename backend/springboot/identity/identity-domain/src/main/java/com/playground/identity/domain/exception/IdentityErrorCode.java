package com.playground.identity.domain.exception;

import com.playground.shared.error.ErrorCode;
import com.playground.shared.error.MappedTo;
import com.playground.shared.error.NotFoundException;
import com.playground.shared.error.UnauthorizedException;

/**
 * Identity BC's error-code enum per ADR-11. Format: {@code IDENTITY-<SUBSYSTEM>-<NNN>}.
 */
public enum IdentityErrorCode implements ErrorCode {

    @MappedTo(NotFoundException.class)
    USER_NOT_FOUND("IDENTITY-USER-001", "User not found: {0}"),

    @MappedTo(UnauthorizedException.class)
    USER_HEADER_MISSING("IDENTITY-USER-002", "X-User-Id header is required on this route"),
    ;

    private final String code;
    private final String defaultMessage;

    IdentityErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
