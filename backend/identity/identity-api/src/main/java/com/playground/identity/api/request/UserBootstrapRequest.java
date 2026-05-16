package com.playground.identity.api.request;

import com.playground.identity.application.dto.UserBootstrapCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /users/bootstrap} per ADR-10 §4. */
public record UserBootstrapRequest(
        @NotBlank String googleSub,
        @NotBlank @Email String email,
        @NotBlank String displayName,
        String avatarUrl) {

    public UserBootstrapCommand toCommand() {
        return new UserBootstrapCommand(googleSub, email, displayName, avatarUrl);
    }
}
