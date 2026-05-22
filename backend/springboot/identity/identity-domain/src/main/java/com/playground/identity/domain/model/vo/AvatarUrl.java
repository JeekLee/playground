package com.playground.identity.domain.model.vo;

import java.util.Optional;

/**
 * Google-supplied avatar URL. Nullable per PRD — Google may not return a
 * picture, the frontend falls back to khaki initials.
 */
public record AvatarUrl(String value) {

    public static final AvatarUrl EMPTY = new AvatarUrl(null);

    /** Compact constructor: normalize blank → null so equals() doesn't differ. */
    public AvatarUrl {
        if (value != null && value.isBlank()) {
            value = null;
        }
    }

    public static AvatarUrl of(String value) {
        return value == null || value.isBlank() ? EMPTY : new AvatarUrl(value);
    }

    public Optional<String> asOptional() {
        return Optional.ofNullable(value);
    }
}
