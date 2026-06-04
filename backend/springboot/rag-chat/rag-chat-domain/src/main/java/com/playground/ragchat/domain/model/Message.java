package com.playground.ragchat.domain.model;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * One turn in a session per ADR-14 §F. {@code role} is USER or ASSISTANT;
 * {@code tokensIn}, {@code tokensOut}, {@code retrievalK} are assistant-only
 * advisory counters used by the metrics rollup (M5) and the audit log
 * (ADR-14 §15).
 */
public record Message(
        MessageId id,
        SessionId sessionId,
        UserId userId,
        Role role,
        String content,
        Integer tokensIn,
        Integer tokensOut,
        Integer retrievalK,
        Instant createdAt) {

    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** New user turn — assistant-only counters left null. */
    public static Message newUserTurn(SessionId sessionId, UserId userId, String content, Instant now) {
        return new Message(MessageId.generate(), sessionId, userId, Role.USER, content, null, null, null, now);
    }

    /** New assistant turn — id generated, with counters from the LLM response. */
    public static Message newAssistantTurn(
            SessionId sessionId,
            UserId userId,
            String content,
            Integer tokensIn,
            Integer tokensOut,
            Integer retrievalK,
            Instant now) {
        return newAssistantTurn(
                MessageId.generate(), sessionId, userId, content, tokensIn, tokensOut, retrievalK, now);
    }

    /**
     * New assistant turn with an explicit, pre-allocated id. Used by the
     * tool-attachment flow (ADR-20 §D3): the assistant {@code messageId} is
     * allocated up-front so a tool callback can link an {@code Attachment} to it
     * mid-stream, before the assistant message text is finalized + persisted.
     */
    public static Message newAssistantTurn(
            MessageId id,
            SessionId sessionId,
            UserId userId,
            String content,
            Integer tokensIn,
            Integer tokensOut,
            Integer retrievalK,
            Instant now) {
        return new Message(
                id, sessionId, userId, Role.ASSISTANT, content, tokensIn, tokensOut, retrievalK, now);
    }
}
