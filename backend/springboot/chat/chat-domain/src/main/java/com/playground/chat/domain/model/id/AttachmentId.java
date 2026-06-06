package com.playground.chat.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier for {@link com.playground.chat.domain.model.Attachment} per ADR-20 §D1. */
public record AttachmentId(UUID value) {

    public AttachmentId {
        Objects.requireNonNull(value, "AttachmentId.value must not be null");
    }

    public static AttachmentId of(UUID value) {
        return new AttachmentId(value);
    }

    public static AttachmentId fromString(String value) {
        return new AttachmentId(UUID.fromString(value));
    }

    public static AttachmentId generate() {
        return new AttachmentId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
