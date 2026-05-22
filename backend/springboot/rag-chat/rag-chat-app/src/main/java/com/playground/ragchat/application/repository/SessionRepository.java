package com.playground.ragchat.application.repository;

import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Repository port for {@code chat.sessions}. Tenant isolation enforced at the
 * SQL layer via {@code user_id = ?} predicate on every read; passing the
 * wrong {@link UserId} returns empty / 0 rows, never another tenant's data.
 */
public interface SessionRepository {

    ChatSession save(ChatSession session);

    /** Returns only if the session exists AND is owned by {@code caller}. */
    Optional<ChatSession> findOwned(SessionId id, UserId caller);

    /** Sessions for the caller, sorted by {@code updated_at DESC}. */
    List<SessionSummary> listForUser(UserId caller);

    /** Rename if owned; returns true if a row was updated. */
    boolean rename(SessionId id, UserId caller, String newTitle);

    /** Auto-title rename — only applies if title is still the default 'New chat'. */
    boolean renameIfDefault(SessionId id, String newTitle);

    /** Hard-delete if owned (CASCADE removes messages + citations). */
    boolean deleteOwned(SessionId id, UserId caller);

    /** Per-row summary used in the session list endpoint. */
    record SessionSummary(
            SessionId id, String title, java.time.Instant updatedAt, int messageCount) {}
}
