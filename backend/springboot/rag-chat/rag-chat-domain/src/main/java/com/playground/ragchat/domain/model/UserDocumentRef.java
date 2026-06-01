package com.playground.ragchat.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the caller's document manifest, injected into the prompt's
 * {@code [YOUR DOCUMENTS]} section so the model can resolve a natural-language
 * document reference (an ordinal like "두 번째 문서" / "the second document", a
 * title, or a type) to a concrete {@code documentId} when calling a tool that
 * requires one (e.g. {@code generate_massing}'s {@code briefDocId}).
 *
 * <p>{@code ordinal} is 1-indexed in stable upload order (docs.documents
 * {@code created_at ASC}), so "두 번째" means the document the user uploaded
 * second — not a retrieval rank and not last-touched order.
 */
public record UserDocumentRef(
        int ordinal,
        UUID documentId,
        String title,
        String mimeType,
        String extractionStatus) {

    public UserDocumentRef {
        Objects.requireNonNull(documentId, "documentId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }
}
