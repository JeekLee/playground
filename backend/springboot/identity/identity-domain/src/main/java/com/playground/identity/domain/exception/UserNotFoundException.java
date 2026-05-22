package com.playground.identity.domain.exception;

import com.playground.identity.domain.model.id.UserId;
import com.playground.shared.error.NotFoundException;

/** Thrown when {@link com.playground.identity.domain.model.User} lookup misses. */
public final class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(UserId id) {
        super(IdentityErrorCode.USER_NOT_FOUND, id.value().toString());
    }

    public UserNotFoundException(String idText) {
        super(IdentityErrorCode.USER_NOT_FOUND, idText);
    }
}
