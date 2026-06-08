package com.playground.chat.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the caller's document manifest, injected into the prompt's
 * {@code [YOUR DOCUMENTS]} section so the model can resolve a natural-language
 * document reference (an ordinal like "두 번째 문서" / "the second document", or a
 * title) to a concrete {@code documentId} when calling a tool that requires one
 * (e.g. {@code generate_massing}'s {@code briefDocId}).
 *
 * <p>{@code ordinal} is 1-indexed in stable upload order (the docs-api manifest
 * endpoint returns documents {@code created_at ASC}), so "두 번째" means the
 * document the user uploaded second — not a retrieval rank and not last-touched
 * order.
 *
 * <p>SP3a spec D3 — slimmed to {@code {ordinal, documentId, title}}: the chat BC
 * no longer reads docs.* directly (the manifest now arrives via docs-api's
 * internal endpoint, which returns only id + title), so {@code mimeType} and
 * {@code extractionStatus} are gone — a document is referenced by ordinal/title,
 * not by type/readiness.
 */
public record UserDocumentRef(int ordinal, UUID documentId, String title) {

    public UserDocumentRef {
        Objects.requireNonNull(documentId, "documentId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }
}
