package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.MessageId;
import com.playground.shared.chat.SourceRef;
import java.util.Objects;

/**
 * One row in {@code chat.message_citations} (SP3b spec D3). Only the citations
 * whose {@code [N]} marker actually appeared in the streamed assistant text are
 * persisted (cite-persistence policy from ADR-14 §10).
 *
 * <p>The corpus-agnostic {@link SourceRef} (sourceType/title/content/uri) is
 * the snapshot frozen at persist time — history reload reads it back directly
 * instead of joining the {@code docs} schema. {@code title}/{@code content}/
 * {@code uri} are nullable (a stale or non-search citation may not carry them);
 * {@code sourceType} is non-blank by the SourceRef contract.
 *
 * @param messageId parent assistant message
 * @param position  the 1-indexed {@code [N]} slot in the assistant body
 * @param source    the frozen corpus-agnostic source reference
 */
public record MessageCitation(MessageId messageId, int position, SourceRef source) {

    public MessageCitation {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(source, "source");
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1, got " + position);
        }
    }
}
