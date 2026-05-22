-- ADR-18 §18 + ADR-05 §A05.5–§A05.7 — fresh `arch` schema owned by
-- massing-gen-api. Single table arch.outputs holds the generated .3dm
-- binaries inline (BYTEA per §12) keyed by user_id (FK to identity.users.id
-- at the application layer only — no cross-schema FK constraint per
-- schema-per-BC invariant).

CREATE SCHEMA IF NOT EXISTS arch;

CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK, no constraint — §13 dangling-FK semantic)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK, no constraint)
    file_bytes      BYTEA        NOT NULL,                  -- the .3dm binary (per ADR-18 §12 — BYTEA inline)
    program_json    JSONB        NOT NULL,                  -- the LLM-extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    summary         TEXT         NOT NULL,                  -- Korean-fixed format string per ADR-18 §5
    brief_slug      TEXT         NOT NULL,                  -- derived from docs.documents.title at create time; drives Content-Disposition filename per ADR-18 §21
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_arch_outputs_user
    ON arch.outputs (user_id, created_at DESC);

CREATE INDEX idx_arch_outputs_brief
    ON arch.outputs (brief_doc_id);

COMMENT ON TABLE arch.outputs IS
    'Generated .3dm massing outputs from massing-gen BC. Owner-tagged; .3dm bytes stored inline as BYTEA per ADR-18 §12.';
COMMENT ON COLUMN arch.outputs.brief_doc_id IS
    'App-level FK to docs.documents.id. May dangle after brief deletion (ADR-18 §13 — untouched-orphan policy).';
COMMENT ON COLUMN arch.outputs.user_id IS
    'App-level FK to identity.users.id. Forwarded from X-User-Id header (ADR-08 §A08.11 Exception 4).';
COMMENT ON COLUMN arch.outputs.summary IS
    'Korean-fixed format: "{rooms}실 · {floors}층 · 총 {area} m²" (ADR-18 §5).';
COMMENT ON COLUMN arch.outputs.brief_slug IS
    'Slug of the source brief title at creation time; used in Content-Disposition filename (ADR-18 §21).';
