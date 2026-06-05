package com.playground.ragchat.infrastructure.persistence;

import com.playground.ragchat.application.repository.AttachmentRepository;
import com.playground.ragchat.domain.model.Attachment;
import com.playground.ragchat.domain.model.id.AttachmentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.UserId;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate-backed {@link AttachmentRepository} per ADR-20 §D1. */
@Repository
public class AttachmentRepositoryJdbcAdapter implements AttachmentRepository {

    private final JdbcTemplate jdbc;

    public AttachmentRepositoryJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Attachment save(Attachment attachment) {
        jdbc.update(
                "INSERT INTO chat.message_attachments "
                        + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, brief_title, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> bindInsert(ps, attachment));
        return attachment;
    }

    @Override
    public void saveAll(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO chat.message_attachments "
                        + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, brief_title, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        bindInsert(ps, attachments.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return attachments.size();
                    }
                });
    }

    @Override
    public Optional<Attachment> findOwned(AttachmentId attachmentId, UserId caller) {
        // Owner-only: join the attachment to its owning message's user_id and
        // require it to equal the caller. A non-owner caller (or missing id)
        // selects no row, which the controller surfaces as 404 (ADR-14 §6.5).
        try {
            Attachment row = jdbc.queryForObject(
                    "SELECT a.id, a.message_id, a.kind, a.filename, a.content_type, "
                            + "a.size_bytes, a.storage_key, a.tool_name, a.brief_title, a.created_at "
                            + "FROM chat.message_attachments a "
                            + "JOIN chat.messages m ON m.id = a.message_id "
                            + "WHERE a.id = ? AND m.user_id = ?",
                    attachmentRowMapper(),
                    attachmentId.value(),
                    caller.value());
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Attachment> findByMessages(List<MessageId> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        String idArray = messageIds.stream()
                .map(id -> id.value().toString())
                .collect(Collectors.joining(",", "{", "}"));
        return jdbc.query(
                "SELECT id, message_id, kind, filename, content_type, "
                        + "size_bytes, storage_key, tool_name, brief_title, created_at "
                        + "FROM chat.message_attachments "
                        + "WHERE message_id = ANY (?::uuid[]) "
                        + "ORDER BY message_id, created_at",
                attachmentRowMapper(),
                idArray);
    }

    private static void bindInsert(PreparedStatement ps, Attachment a) throws SQLException {
        ps.setObject(1, a.id().value());
        ps.setObject(2, a.messageId().value());
        ps.setString(3, a.kind());
        ps.setString(4, a.filename());
        ps.setString(5, a.contentType());
        ps.setLong(6, a.sizeBytes());
        ps.setString(7, a.storageKey());
        ps.setString(8, a.toolName());
        ps.setString(9, a.briefTitle());
        ps.setObject(10, OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
    }

    private RowMapper<Attachment> attachmentRowMapper() {
        return (rs, n) -> new Attachment(
                AttachmentId.of((UUID) rs.getObject("id")),
                MessageId.of((UUID) rs.getObject("message_id")),
                rs.getString("kind"),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("storage_key"),
                rs.getString("tool_name"),
                rs.getString("brief_title"),
                rs.getTimestamp("created_at").toInstant());
    }
}
