-- ADR-12 §15 + M2 spec §4.1 — docs.documents table (S1 subset).
-- Schema-per-BC per ADR-05; cross-schema FK to identity.users is deliberately
-- absent per ADR-12 §8 (app-level reference only).
--
-- S1 ships the columns the single-author CRUD slice needs. Per M2 spec §4.1
-- the canonical M2 schema also carries `view_count` and `like_count` plus a
-- `docs.document_likes` table; those land in M2 S2 (engagement) and S3
-- (search/community feed). Adding them later is an additive migration, so the
-- S1 schema does not pre-create unused columns.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS docs;

SET search_path = docs, public;

CREATE TABLE IF NOT EXISTS docs.documents (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    title         TEXT         NOT NULL,
    body          TEXT         NOT NULL,
    visibility    TEXT         NOT NULL DEFAULT 'private'
                  CHECK (visibility IN ('private', 'public')),
    path          TEXT         NOT NULL DEFAULT '/'
                  CHECK (path ~ '^(/|(/[a-z0-9][a-z0-9-]*)+/)$'),
    published_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ADR-12 §4: 1 MB raw-Markdown body cap, enforced both at API and DB.
    CONSTRAINT documents_body_size_chk CHECK (octet_length(body) <= 1048576)
);

-- Per-user mine-list index. M2 spec §4.1 names ix_docs_user_updated; S1 keeps
-- it on (user_id, updated_at DESC) so the mine-listing query has an index
-- that covers the secondary sort key (published_at DESC NULLS FIRST,
-- updated_at DESC is satisfied by a sequential scan with re-sort for now —
-- mine-listing returns ≤ a few hundred rows per author, which fits the
-- personal-scale budget).
CREATE INDEX IF NOT EXISTS ix_docs_user_updated
    ON docs.documents (user_id, updated_at DESC);
