package com.playground.chat.application.repository;

import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.SessionId;
import java.util.List;

/**
 * Repository port for {@code chat.messages} + {@code chat.message_citations}
 * per ADR-14 §F. Citations are stored in batches keyed by message id.
 */
public interface MessageRepository {

    /** Insert one row; returns the persisted message (with id assigned at construction). */
    Message save(Message message);

    /** Bulk-insert citation rows for an assistant message. No-op on empty list. */
    void saveCitations(List<MessageCitation> citations);

    /** Load all messages for a session ordered by {@code created_at} ascending. */
    List<Message> findBySession(SessionId sessionId);

    /** Load citations for a set of assistant message ids. Used by the history endpoint. */
    List<MessageCitation> findCitationsForMessages(List<com.playground.chat.domain.model.id.MessageId> messageIds);

    /** Count user-role messages in a session. Used by the auto-title trigger (first user turn). */
    int countUserMessages(SessionId sessionId);
}
