package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root for one conversation thread per ADR-14 §F. {@code title}
 * defaults to {@code "New chat"} at creation; the auto-titler (ADR-14 §6)
 * later may rename via {@code UPDATE chat.sessions SET title = ? WHERE id = ?
 * AND title = 'New chat'}.
 *
 * <p>Plain POJO — JPA mirror lives in {@code chat-infra}.
 */
public final class ChatSession {

    public static final String DEFAULT_TITLE = "New chat";

    private final SessionId id;
    private final UserId userId;
    private String title;
    private final Instant createdAt;
    private Instant updatedAt;

    public ChatSession(SessionId id, UserId userId, String title, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /** Fresh session — id generated, title defaulted to "New chat". */
    public static ChatSession newSession(UserId userId, Instant now) {
        return new ChatSession(SessionId.generate(), userId, DEFAULT_TITLE, now, now);
    }

    public SessionId id() {
        return id;
    }

    public UserId userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Rename — caller must enforce ownership separately. */
    public void rename(String newTitle, Instant now) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        // ADR-14 §C: 60 char cap to defend against runaway auto-title outputs.
        this.title = newTitle.length() > 60 ? newTitle.substring(0, 60) : newTitle;
        this.updatedAt = now;
    }

    public boolean ownedBy(UserId caller) {
        return userId.equals(caller);
    }
}
