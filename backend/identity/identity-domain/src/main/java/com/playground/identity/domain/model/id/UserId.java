package com.playground.identity.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal user identifier. A {@link UUID} wrapper so domain signatures cannot
 * accidentally accept a Google subject string. Equals/hashCode delegate to the
 * wrapped UUID per record semantics.
 */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId.value must not be null");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    public static UserId fromString(String value) {
        return new UserId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
