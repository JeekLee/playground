package com.playground.chat.domain.model.vo;

/**
 * The 1-indexed {@code [N]} slot the assistant emits in its text. Matched
 * back to the retrieved citation list per ADR-14 §10's cite-persistence
 * policy.
 */
public record CitationMarker(int position) {

    public CitationMarker {
        if (position < 1) {
            throw new IllegalArgumentException("CitationMarker.position must be >= 1, got " + position);
        }
    }

    public static CitationMarker of(int position) {
        return new CitationMarker(position);
    }
}
