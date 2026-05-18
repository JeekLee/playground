-- M2 S2 — add denormalized engagement counters per M2 spec §4.1 +
-- ADR-12 §11 ("denormalized columns + nightly resync"). S2 surfaces these
-- columns in every list/detail DTO (default 0 on existing rows); the
-- increment paths (POST /like, POST /view + Redis dedup) and the nightly
-- resync job ship in S3.
--
-- Additive migration — additions to docs.documents that are nullable-with-
-- default-0 so existing S1 rows back-fill without rewriting.

SET search_path = docs, public;

ALTER TABLE docs.documents
    ADD COLUMN IF NOT EXISTS view_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS like_count BIGINT NOT NULL DEFAULT 0;

-- Community feed + per-author public feed indexes per M2 spec §4.1.
-- The community feed (`GET /api/docs`) reads (visibility='public') sorted by
-- published_at DESC; partial index keeps it tight even as private docs grow.
CREATE INDEX IF NOT EXISTS ix_docs_public_published
    ON docs.documents (visibility, published_at DESC)
    WHERE visibility = 'public';

-- The per-author public feed (`GET /api/docs?author={uid}`) reads
-- (user_id, visibility='public') sorted by published_at DESC. Partial index
-- keyed by user_id + published_at avoids a full scan of every user's drafts.
CREATE INDEX IF NOT EXISTS ix_docs_author_public
    ON docs.documents (user_id, visibility, published_at DESC)
    WHERE visibility = 'public';
