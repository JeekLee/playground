package com.playground.chat.infrastructure.persistence;

import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate-backed {@link MessageRepository} per ADR-14 §F. */
@Repository
public class MessageRepositoryJdbcAdapter implements MessageRepository {

    private final JdbcTemplate jdbc;

    public MessageRepositoryJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Message save(Message message) {
        jdbc.update(
                "INSERT INTO chat.messages "
                        + "(id, session_id, user_id, role, content, tokens_in, tokens_out, retrieval_k, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject(1, message.id().value());
                    ps.setObject(2, message.sessionId().value());
                    ps.setObject(3, message.userId().value());
                    ps.setString(4, message.role().wireValue());
                    ps.setString(5, message.content());
                    setNullableInt(ps, 6, message.tokensIn());
                    setNullableInt(ps, 7, message.tokensOut());
                    setNullableInt(ps, 8, message.retrievalK());
                    ps.setObject(9, OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC));
                });
        return message;
    }

    @Override
    public void saveCitations(List<MessageCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO chat.message_citations "
                        + "(message_id, position, document_id, chunk_index) "
                        + "VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        MessageCitation c = citations.get(i);
                        ps.setObject(1, c.messageId().value());
                        ps.setInt(2, c.position());
                        ps.setObject(3, c.documentId().value());
                        ps.setInt(4, c.chunkIndex());
                    }

                    @Override
                    public int getBatchSize() {
                        return citations.size();
                    }
                });
    }

    @Override
    public List<Message> findBySession(SessionId sessionId) {
        return jdbc.query(
                "SELECT id, session_id, user_id, role, content, tokens_in, tokens_out, retrieval_k, created_at "
                        + "FROM chat.messages WHERE session_id = ? ORDER BY created_at ASC, id ASC",
                messageRowMapper(),
                sessionId.value());
    }

    @Override
    public List<MessageCitation> findCitationsForMessages(List<MessageId> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        // Build an ANY (?::uuid[]) parameter so we hit the index once.
        String idArray = messageIds.stream()
                .map(id -> id.value().toString())
                .collect(Collectors.joining(",", "{", "}"));
        return jdbc.query(
                "SELECT message_id, position, document_id, chunk_index "
                        + "FROM chat.message_citations "
                        + "WHERE message_id = ANY (?::uuid[]) "
                        + "ORDER BY message_id, position",
                (RowMapper<MessageCitation>) (rs, n) -> new MessageCitation(
                        MessageId.of((UUID) rs.getObject("message_id")),
                        rs.getInt("position"),
                        DocumentId.of((UUID) rs.getObject("document_id")),
                        rs.getInt("chunk_index")),
                idArray);
    }

    @Override
    public int countUserMessages(SessionId sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat.messages WHERE session_id = ? AND role = 'user'",
                Integer.class,
                sessionId.value());
        return count == null ? 0 : count;
    }

    private RowMapper<Message> messageRowMapper() {
        return (ResultSet rs, int n) -> {
            int tokensIn = rs.getInt("tokens_in");
            boolean tokensInNull = rs.wasNull();
            int tokensOut = rs.getInt("tokens_out");
            boolean tokensOutNull = rs.wasNull();
            int retrievalK = rs.getInt("retrieval_k");
            boolean retrievalKNull = rs.wasNull();
            return new Message(
                    MessageId.of((UUID) rs.getObject("id")),
                    SessionId.of((UUID) rs.getObject("session_id")),
                    UserId.of((UUID) rs.getObject("user_id")),
                    Role.fromWire(rs.getString("role")),
                    rs.getString("content"),
                    tokensInNull ? null : tokensIn,
                    tokensOutNull ? null : tokensOut,
                    retrievalKNull ? null : retrievalK,
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }
}
