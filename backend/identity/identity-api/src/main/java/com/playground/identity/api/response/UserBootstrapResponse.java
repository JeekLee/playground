package com.playground.identity.api.response;

import com.playground.identity.application.dto.UserBootstrapResult;

/** Response body for {@code POST /users/bootstrap}: only {@code id} per ADR-10 §4. */
public record UserBootstrapResponse(String id) {

    public static UserBootstrapResponse from(UserBootstrapResult result) {
        return new UserBootstrapResponse(result.id());
    }
}
