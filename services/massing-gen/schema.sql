-- M8 ADR-18 §18 + ADR-05 §A05.5 — `arch` schema + arch.outputs table.
-- Implementation language flipped to Python (ADR-18 §A18.1) but the schema
-- shape is unchanged. Hand-rolled migration (single-table P0 doesn't need
-- Alembic; ADR-05 §A05.9 permits hand-rolled for P0).
--
-- Runs once at container startup; idempotent (IF NOT EXISTS guards).

CREATE SCHEMA IF NOT EXISTS arch;

CREATE TABLE IF NOT EXISTS arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK)
    brief_slug      TEXT         NOT NULL,                  -- Content-Disposition filename
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK)
    file_bytes      BYTEA        NOT NULL,                  -- the .3dm binary
    program_json    JSONB        NOT NULL,                  -- extracted room program (rooms + totals)
    total_area_m2   REAL         NOT NULL,
    floor_count     INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Owner-scoped download lookups (GET /api/arch/outputs/{id} verifies user_id).
CREATE INDEX IF NOT EXISTS outputs_user_id_idx ON arch.outputs (user_id);

-- Brief-scoped lookups (M8.1+ may surface "previously generated for this
-- brief" UX) — cheap insurance.
CREATE INDEX IF NOT EXISTS outputs_brief_doc_id_idx ON arch.outputs (brief_doc_id);

-- gen_random_uuid() lives in pgcrypto; ensure available.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
