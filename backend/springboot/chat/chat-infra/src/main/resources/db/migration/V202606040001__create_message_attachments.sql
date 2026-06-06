-- ADR-20 §D1 — chat.message_attachments: tool-produced file artifacts bound to
-- the assistant message that produced them. The row holds the MinIO storage
-- key + metadata only — NEVER the bytes (the bytes live in MinIO under
-- storage_key). message_id is an app-level FK reference to chat.messages.id
-- per ADR-14 §11 style (FK-less; orphan rows tolerated like message_citations).
--
-- Flyway picks this up via flyway.schemas=chat (application.yml).

CREATE TABLE chat.message_attachments (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id    UUID         NOT NULL,                   -- chat.messages.id (app-level FK)
    kind          TEXT         NOT NULL,                   -- e.g. 'tool-artifact'
    filename      TEXT         NOT NULL,                   -- RFC-6266 download name
    content_type  TEXT         NOT NULL,                   -- MIME type (type-aware FE rendering)
    size_bytes    BIGINT       NOT NULL,                   -- Content-Length + FE size display
    storage_key   TEXT         NOT NULL,                   -- MinIO object key (only pointer to bytes)
    tool_name     TEXT,                                    -- producing tool, e.g. 'generate_massing'
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Owner-only download + the per-message attachment lookup both filter by
-- message_id (the download endpoint resolves attachment -> message -> owner).
CREATE INDEX chat_message_attachments_by_message
    ON chat.message_attachments (message_id);

COMMENT ON TABLE chat.message_attachments IS
  'ADR-20 §D1 — tool-produced file artifacts bound to the assistant message that produced them. Holds the MinIO storage key + metadata only; the bytes live in MinIO. message_id is an app-level FK to chat.messages.id (no DB FK, per ADR-14 §11 style).';
