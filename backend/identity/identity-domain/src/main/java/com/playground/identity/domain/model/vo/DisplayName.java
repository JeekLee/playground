package com.playground.identity.domain.model.vo;

import java.util.Objects;

/** Google-supplied display name. */
public record DisplayName(String value) {

    public DisplayName {
        Objects.requireNonNull(value, "DisplayName.value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DisplayName.value must not be blank");
        }
    }

    public static DisplayName of(String value) {
        return new DisplayName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
