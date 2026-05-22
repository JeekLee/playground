package com.playground.docs.domain.event;

import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;

/**
 * Domain event for a publish/unpublish transition. Fires from
 * {@code POST /api/docs/{id}/publish} and {@code POST /api/docs/{id}/unpublish}
 * only; PATCH does not flip visibility per M2 spec §5 + §6.1.
 *
 * <p>{@link #publishedAt()} is present when {@link #newVisibility()} is
 * {@link Visibility#PUBLIC}; null when transitioning back to
 * {@link Visibility#PRIVATE} (consumers can still find the historical
 * {@code publishedAt} on the doc row).
 *
 * <p>POJO record per ADR-02 v2. Wrapped in {@code EventEnvelope<T>} on
 * topic {@code docs.document.visibility-changed}. Idempotency key per
 * M2 spec §5: {@code documentId + newVisibility}.
 */
public record DocumentVisibilityChanged(
        DocumentId documentId,
        AuthorId userId,
        Visibility oldVisibility,
        Visibility newVisibility,
        Instant publishedAt,
        Instant occurredAt
) {}
