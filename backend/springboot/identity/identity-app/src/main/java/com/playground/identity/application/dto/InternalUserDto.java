package com.playground.identity.application.dto;

import com.playground.identity.domain.model.User;

/**
 * Wire-shape mirror of the internal user lookup response per ADR-12 §8.
 *
 * <p>Distinct from {@link UserDto} (which carries the {@code email} field for
 * the {@code /me} endpoint) — the internal routes are reachable only on the
 * compose network and explicitly omit {@code email} so a future consumer can't
 * accidentally leak it. Returns exactly the fields the docs BC needs to render
 * the author block + resolve the owner.
 */
public record InternalUserDto(String id, String googleSub, String displayName, String avatarUrl) {

    public static InternalUserDto from(User user) {
        return new InternalUserDto(
                user.id().value().toString(),
                user.googleSub().value(),
                user.displayName().value(),
                user.avatarUrl().value());
    }
}
