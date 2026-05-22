package com.playground.docs.api.response;

import com.playground.docs.application.dto.AuthorDto;

/**
 * HTTP response shape for the {@code author} block on document responses per
 * M2 spec §6.4 {@code Author}. {@link #from(AuthorDto)} handles nulls so
 * call-sites stay readable.
 */
public record AuthorResponse(String id, String displayName, String avatarUrl) {

    public static AuthorResponse from(AuthorDto dto) {
        if (dto == null) {
            return null;
        }
        return new AuthorResponse(dto.id().toString(), dto.displayName(), dto.avatarUrl());
    }
}
