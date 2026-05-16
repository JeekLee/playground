package com.playground.identity.api.response;

import com.playground.identity.application.dto.UserDto;

/** Response body for {@code GET /me} per ADR-10 §7 + PRD §외부 인터페이스. */
public record MeResponse(String id, String googleSub, String email, String displayName, String avatarUrl) {

    public static MeResponse from(UserDto dto) {
        return new MeResponse(dto.id(), dto.googleSub(), dto.email(), dto.displayName(), dto.avatarUrl());
    }
}
