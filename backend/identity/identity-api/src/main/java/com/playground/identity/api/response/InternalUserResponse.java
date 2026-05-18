package com.playground.identity.api.response;

import com.playground.identity.application.dto.InternalUserDto;

/**
 * Wire shape for the single-user routes
 * ({@code GET /internal/users/{id}}, {@code GET /internal/users/by-google-sub/{sub}})
 * per ADR-12 §8. Distinct from {@code MeResponse} (which carries the email);
 * internal routes deliberately omit email so a future consumer cannot
 * accidentally leak it.
 */
public record InternalUserResponse(String id, String googleSub, String displayName, String avatarUrl) {

    public static InternalUserResponse from(InternalUserDto dto) {
        return new InternalUserResponse(
                dto.id(),
                dto.googleSub(),
                dto.displayName(),
                dto.avatarUrl());
    }
}
