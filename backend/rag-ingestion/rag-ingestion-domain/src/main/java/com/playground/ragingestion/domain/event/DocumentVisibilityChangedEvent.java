package com.playground.ragingestion.domain.event;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import java.time.Instant;

/**
 * Mirror of docs BC's {@code DocumentVisibilityChanged} per ADR-13 §4.5.
 * Triggers an UPDATE-only re-tag of every chunk row for {@link #documentId()}.
 * No body fetch, no embedding (ADR-13 §5).
 *
 * <p>{@link #publishedAt()} is present when {@link #newVisibility()} is
 * {@link Visibility#PUBLIC}; null when transitioning back to PRIVATE.
 */
public record DocumentVisibilityChangedEvent(
        DocumentId documentId,
        AuthorId userId,
        Visibility oldVisibility,
        Visibility newVisibility,
        Instant publishedAt
) {}
