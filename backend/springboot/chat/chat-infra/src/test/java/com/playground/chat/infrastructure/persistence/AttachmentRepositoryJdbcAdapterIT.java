package com.playground.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Adapter integration test for {@link AttachmentRepositoryJdbcAdapter#findModelAttachments}
 * (Task 11 — the {@code [YOUR MODELS]} prompt manifest query). Runs Flyway against
 * a Testcontainers Postgres to get the real {@code chat.message_attachments} +
 * {@code chat.messages} schema, then drives the adapter directly with a plain
 * {@link JdbcTemplate} — no Spring context (which would also demand Redis / MinIO /
 * the Spark gateway at startup). Tagged {@code integration} so the default
 * {@code :chat:chat-infra:test} pass skips it when Docker is unavailable, mirroring
 * docs-infra's {@code DocumentCrudIT}.
 */
@Testcontainers
@Tag("integration")
class AttachmentRepositoryJdbcAdapterIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("playground")
            .withUsername("playground")
            .withPassword("playground");

    static JdbcTemplate jdbc;
    static AttachmentRepositoryJdbcAdapter repository;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("chat")
                .defaultSchema("chat")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=chat",
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        repository = new AttachmentRepositoryJdbcAdapter(jdbc);
    }

    @Test
    void findModelAttachments_returnsOnlyModelRowsForSessionAndOwner() {
        UserId owner = UserId.of(UUID.randomUUID());
        UserId stranger = UserId.of(UUID.randomUUID());

        // Owner's session with three attachments produced on one assistant message.
        SessionId session = insertSession(owner);
        MessageId message = insertMessage(session, owner);

        // (a) a generate_massing .3dm model attachment — should be returned.
        insertAttachment(message, "generate_massing", "massing-tower-1.3dm",
                Instant.parse("2026-06-04T12:00:00Z"));
        // (b) a non-model attachment for the same owner — a non-massing tool. Excluded.
        insertAttachment(message, "search_documents", "results.json",
                Instant.parse("2026-06-04T12:01:00Z"));
        // (b2) a massing tool but non-.3dm filename — excluded by the filename filter.
        insertAttachment(message, "generate_massing", "summary.txt",
                Instant.parse("2026-06-04T12:02:00Z"));

        // (c) a .3dm model owned by a DIFFERENT user in a DIFFERENT session — excluded.
        SessionId otherSession = insertSession(stranger);
        MessageId otherMessage = insertMessage(otherSession, stranger);
        insertAttachment(otherMessage, "generate_massing", "other-tower.3dm",
                Instant.parse("2026-06-04T12:03:00Z"));

        List<Attachment> models = repository.findModelAttachments(session, owner, 30);

        assertThat(models).extracting(Attachment::toolName).containsExactly("generate_massing");
        assertThat(models).allMatch(a -> a.filename().endsWith(".3dm"));
    }

    @Test
    void findModelAttachments_includesRefineMassing_newestFirst() {
        UserId owner = UserId.of(UUID.randomUUID());
        SessionId session = insertSession(owner);
        MessageId message = insertMessage(session, owner);

        insertAttachment(message, "generate_massing", "base.3dm",
                Instant.parse("2026-06-04T10:00:00Z"));
        insertAttachment(message, "refine_massing", "refined.3dm",
                Instant.parse("2026-06-04T11:00:00Z"));

        List<Attachment> models = repository.findModelAttachments(session, owner, 30);

        // newest (refined) first.
        assertThat(models).extracting(Attachment::filename)
                .containsExactly("refined.3dm", "base.3dm");
        assertThat(models).extracting(Attachment::toolName)
                .containsExactly("refine_massing", "generate_massing");
    }

    @Test
    void findModelAttachments_limitZeroReturnsEmpty() {
        UserId owner = UserId.of(UUID.randomUUID());
        SessionId session = insertSession(owner);
        MessageId message = insertMessage(session, owner);
        insertAttachment(message, "generate_massing", "m.3dm", Instant.parse("2026-06-04T12:00:00Z"));

        assertThat(repository.findModelAttachments(session, owner, 0)).isEmpty();
    }

    private static SessionId insertSession(UserId owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat.sessions (id, user_id, title) VALUES (?, ?, ?)",
                id, owner.value(), "Massing session");
        return SessionId.of(id);
    }

    private static MessageId insertMessage(SessionId session, UserId owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO chat.messages (id, session_id, user_id, role, content) "
                        + "VALUES (?, ?, ?, 'assistant', ?)",
                id, session.value(), owner.value(), "ok");
        return MessageId.of(id);
    }

    private static void insertAttachment(MessageId message, String toolName, String filename, Instant createdAt) {
        jdbc.update(
                "INSERT INTO chat.message_attachments "
                        + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, brief_title, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                message.value(),
                Attachment.KIND_TOOL_ARTIFACT,
                filename,
                "application/octet-stream",
                42L,
                "chat/k/" + filename,
                toolName,
                "타워 매싱",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
