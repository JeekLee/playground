package com.playground.ragingestion.domain.event;

import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.DocumentId;

/**
 * Mirror of docs BC's {@code DocumentDeleted} per ADR-13 §4.5. Triggers a
 * purge of every chunk row for {@link #documentId()}; M3 emits no further
 * event (terminal).
 */
public record DocumentDeletedEvent(
        DocumentId documentId,
        AuthorId userId
) {}
