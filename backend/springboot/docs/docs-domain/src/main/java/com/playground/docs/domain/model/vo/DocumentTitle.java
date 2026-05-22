package com.playground.docs.domain.model.vo;

import java.util.Objects;

/**
 * Document title. Required at create time per M2 spec §4.3; empty / blank
 * titles rejected at the API and re-checked here as defense in depth.
 */
public record DocumentTitle(String value) {

    /** Hard cap matching the column type. 1 KB is plenty for any realistic title. */
    public static final int MAX_LENGTH = 1024;

    public DocumentTitle {
        Objects.requireNonNull(value, "DocumentTitle.value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DocumentTitle must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "DocumentTitle exceeds " + MAX_LENGTH + " characters");
        }
    }

    public static DocumentTitle of(String value) {
        return new DocumentTitle(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
