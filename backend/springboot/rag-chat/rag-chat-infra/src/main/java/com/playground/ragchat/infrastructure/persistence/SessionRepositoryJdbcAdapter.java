package com.playground.ragchat.infrastructure.persistence;

import com.playground.ragchat.application.repository.SessionRepository;
import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-backed {@link SessionRepository} per ADR-14 §F. Every write is
 * filtered by {@code user_id = ?} so tenant isolation is enforced at the SQL
 * layer; passing the wrong caller never mutates another tenant's row.
 */
@Repository
public class SessionRepositoryJdbcAdapter implements SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepositoryJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChatSession save(ChatSession session) {
        jdbc.update(
                "INSERT INTO chat.sessions (id, user_id, title, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, session.id().value());
                    ps.setObject(2, session.userId().value());
                    ps.setString(3, session.title());
                    ps.setObject(4, OffsetDateTime.ofInstant(session.createdAt(), ZoneOffset.UTC));
                    ps.setObject(5, OffsetDateTime.ofInstant(session.updatedAt(), ZoneOffset.UTC));
                });
        return session;
    }

    @Override
    public Optional<ChatSession> findOwned(SessionId id, UserId caller) {
        try {
            ChatSession session = jdbc.queryForObject(
                    "SELECT id, user_id, title, created_at, updated_at "
                            + "FROM chat.sessions WHERE id = ? AND user_id = ?",
                    sessionRowMapper(),
                    id.value(), caller.value());
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<SessionSummary> listForUser(UserId caller) {
        return jdbc.query(
                "SELECT s.id, s.title, s.updated_at, "
                        + "  COALESCE((SELECT COUNT(*) FROM chat.messages m WHERE m.session_id = s.id), 0) AS message_count "
                        + "FROM chat.sessions s "
                        + "WHERE s.user_id = ? "
                        + "ORDER BY s.updated_at DESC",
                (RowMapper<SessionSummary>) (rs, n) -> new SessionSummary(
                        SessionId.of((UUID) rs.getObject("id")),
                        rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getInt("message_count")),
                caller.value());
    }

    @Override
    public boolean rename(SessionId id, UserId caller, String newTitle) {
        int updated = jdbc.update(
                "UPDATE chat.sessions SET title = ?, updated_at = now() "
                        + "WHERE id = ? AND user_id = ?",
                newTitle, id.value(), caller.value());
        return updated > 0;
    }

    @Override
    public boolean renameIfDefault(SessionId id, String newTitle) {
        int updated = jdbc.update(
                "UPDATE chat.sessions SET title = ?, updated_at = now() "
                        + "WHERE id = ? AND title = 'New chat'",
                newTitle, id.value());
        return updated > 0;
    }

    @Override
    public boolean deleteOwned(SessionId id, UserId caller) {
        int deleted = jdbc.update(
                "DELETE FROM chat.sessions WHERE id = ? AND user_id = ?",
                id.value(), caller.value());
        return deleted > 0;
    }

    private RowMapper<ChatSession> sessionRowMapper() {
        return (ResultSet rs, int n) -> new ChatSession(
                SessionId.of((UUID) rs.getObject("id")),
                UserId.of((UUID) rs.getObject("user_id")),
                rs.getString("title"),
                Instant.ofEpochMilli(rs.getTimestamp("created_at").getTime()),
                Instant.ofEpochMilli(rs.getTimestamp("updated_at").getTime()));
    }
}
