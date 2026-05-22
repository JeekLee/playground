package com.playground.ragchat.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier for {@link com.playground.ragchat.domain.model.Message} per ADR-14 §F. */
public record MessageId(UUID value) {

    public MessageId {
        Objects.requireNonNull(value, "MessageId.value must not be null");
    }

    public static MessageId of(UUID value) {
        return new MessageId(value);
    }

    public static MessageId fromString(String value) {
        return new MessageId(UUID.fromString(value));
    }

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
