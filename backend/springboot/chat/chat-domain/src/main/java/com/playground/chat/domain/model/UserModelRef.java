package com.playground.chat.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the caller's session {@code [YOUR MODELS]} manifest — a massing
 * model (.3dm) already generated in this session, injected so the model can
 * resolve a reference ("the second model", "the library massing") to a concrete
 * {@code attachmentId} for {@code refine_massing}'s {@code baseAttachmentId}.
 *
 * <p>{@code ordinal} is 1-indexed in creation order. {@code label} is the
 * human-facing name (briefTitle, else filename).
 */
public record UserModelRef(int ordinal, UUID attachmentId, String label) {

    public UserModelRef {
        Objects.requireNonNull(attachmentId, "attachmentId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }
}
