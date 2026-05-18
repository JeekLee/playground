-- M2 S3 — per-user document like table per M2 spec §4.1 + ADR-12 §11.
--
-- Composite PK (document_id, user_id) gives idempotent toggle at the DB layer
-- (INSERT ... ON CONFLICT DO NOTHING). user_id has NO foreign key to
-- identity.users — per ADR-12 §8 / spec §4.1 we keep cross-BC references as
-- "app-level FKs" rather than physical constraints (schema isolation).
--
-- like_count on docs.documents is the denormalized read counter; this table
-- is the source of truth. The nightly resync job (ADR-12 §11) recomputes
-- like_count from COUNT(*) here in case a partial failure drifts the
-- denormalized value.

SET search_path = docs, public;

CREATE TABLE IF NOT EXISTS docs.document_likes (
    document_id UUID        NOT NULL REFERENCES docs.documents(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL,
    liked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);

-- Per-user index: "which docs did I like?" lookup for the likedByMe batch
-- join in DocumentFeedService.
CREATE INDEX IF NOT EXISTS ix_docs_document_likes_user
    ON docs.document_likes (user_id);
