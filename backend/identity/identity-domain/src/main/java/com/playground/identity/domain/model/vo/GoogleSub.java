package com.playground.identity.domain.model.vo;

import java.util.Objects;

/**
 * Wraps Google's OIDC {@code sub} claim. Numeric string of variable length;
 * the project does not enforce a digit-only invariant because Google reserves
 * the right to evolve the shape.
 */
public record GoogleSub(String value) {

    public GoogleSub {
        Objects.requireNonNull(value, "GoogleSub.value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("GoogleSub.value must not be blank");
        }
    }

    public static GoogleSub of(String value) {
        return new GoogleSub(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
