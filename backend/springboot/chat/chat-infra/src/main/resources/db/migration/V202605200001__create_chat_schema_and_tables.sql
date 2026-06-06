-- ADR-14 §F — chat BC's persistence root. New `chat` schema isolated by
-- Flyway's `flyway.schemas=chat` setting. Cross-schema reads into `rag`,
-- `docs`, and `identity` happen at app runtime via the connection-level
-- search_path (set in application.yml) — no DDL coupling here.

CREATE SCHEMA IF NOT EXISTS chat;

-- chat.sessions: one conversation per row. user_id is an app-level FK to
-- identity.users.id (no cross-schema DB constraint per ADR-05).
CREATE TABLE chat.sessions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    title       TEXT         NOT NULL DEFAULT 'New chat',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Supports the session list endpoint (most-recent first per spec §7.2).
CREATE INDEX chat_sessions_by_user
    ON chat.sessions (user_id, updated_at DESC);

-- chat.messages: one user or assistant turn per row. user_id denormalized
-- for fast tenant filter (spec §4.2). content may carry [N] markers for
-- assistant rows; resolved via chat.message_citations.
CREATE TABLE chat.messages (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID         NOT NULL REFERENCES chat.sessions(id) ON DELETE CASCADE,
    user_id      UUID         NOT NULL,
    role         TEXT         NOT NULL CHECK (role IN ('user', 'assistant')),
    content      TEXT         NOT NULL,
    tokens_in    INT,
    tokens_out   INT,
    retrieval_k  INT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Supports per-session history load + the audit endpoint.
CREATE INDEX chat_messages_by_session
    ON chat.messages (session_id, created_at);

-- chat.message_citations: cited subset (NOT all retrieved) per ADR-14 §10.
-- Orphan rows remain when the cited doc is later deleted; UI renders them as
-- (deleted) per ADR-14 §11.
CREATE TABLE chat.message_citations (
    message_id   UUID  NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
    position     INT   NOT NULL,
    document_id  UUID  NOT NULL,
    chunk_index  INT   NOT NULL,
    PRIMARY KEY (message_id, position)
);

-- Reverse lookup: "which assistant turns cited document X?" (useful for the
-- per-doc analytics in M5 + the post-deletion-citation purge tooling).
CREATE INDEX chat_message_citations_by_document
    ON chat.message_citations (document_id);

COMMENT ON TABLE chat.sessions IS
  'One row per conversation. user_id is app-level FK to identity.users.id (different schema; no DB FK).';
COMMENT ON TABLE chat.messages IS
  'One row per turn (user or assistant). content carries [N] markers for assistant rows; resolved via chat.message_citations.';
COMMENT ON TABLE chat.message_citations IS
  'Cited subset (NOT all retrieved) per ADR-14 §10. Orphaned rows remain when the cited doc is later deleted; UI renders them as (deleted).';

-- Bump chat.sessions.updated_at on every message insert so the top-tab
-- strip's "most-recent" sort matches actual last-activity timestamp
-- (ADR-14 §F + spec §7.2).
CREATE OR REPLACE FUNCTION chat.touch_session_updated_at() RETURNS TRIGGER AS $$
BEGIN
    UPDATE chat.sessions
       SET updated_at = now()
     WHERE id = NEW.session_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER chat_messages_touch_session
AFTER INSERT ON chat.messages
FOR EACH ROW EXECUTE FUNCTION chat.touch_session_updated_at();
