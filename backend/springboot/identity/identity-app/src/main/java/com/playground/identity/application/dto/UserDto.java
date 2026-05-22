package com.playground.identity.application.dto;

import com.playground.identity.domain.model.User;

/**
 * Use-case I/O DTO per ADR-02 v2. Carries the {@link User} aggregate's data in
 * a wire-friendly shape — the -api layer translates this into the response
 * body, infrastructure does the opposite.
 *
 * @param id           internal user UUID (matches {@code X-User-Id})
 * @param googleSub    OIDC {@code sub} claim
 * @param email        primary email address
 * @param displayName  display name (Google)
 * @param avatarUrl    avatar URL or {@code null}
 */
public record UserDto(String id, String googleSub, String email, String displayName, String avatarUrl) {

    public static UserDto from(User user) {
        return new UserDto(
                user.id().value().toString(),
                user.googleSub().value(),
                user.email().value(),
                user.displayName().value(),
                user.avatarUrl().value());
    }
}
