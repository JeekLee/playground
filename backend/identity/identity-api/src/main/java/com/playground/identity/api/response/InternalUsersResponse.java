package com.playground.identity.api.response;

import com.playground.identity.application.dto.InternalUserDto;
import java.util.List;

/**
 * Wire shape for {@code GET /internal/users?ids=...} per ADR-12 §8.
 * {@code users} preserves the order returned by the service (which mirrors
 * the JPA {@code IN (...)} order, i.e. not guaranteed stable — callers should
 * key by id).
 */
public record InternalUsersResponse(List<InternalUserResponse> users) {

    public static InternalUsersResponse from(List<InternalUserDto> dtos) {
        return new InternalUsersResponse(dtos.stream().map(InternalUserResponse::from).toList());
    }
}
