-- ADR-10 §7: identity.users + ADR-05 schema-per-BC.
-- The `pgvector/pgvector:pg16` base image ships pgcrypto, so gen_random_uuid()
-- is available. Defensive CREATE EXTENSION keeps the migration portable.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET search_path = identity, public;

CREATE TABLE IF NOT EXISTS identity.users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub      TEXT         NOT NULL UNIQUE,
    email           TEXT         NOT NULL,
    display_name    TEXT         NOT NULL,
    avatar_url      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_identity_users_email ON identity.users (email);
