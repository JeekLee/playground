-- M6.1 ADR-12 amendment §A12.13 — migrated from rag-ingestion-infra's
-- V202605200001__create_rag_schema_and_chunks.sql (now V202605211001 in the
-- docs Flyway history per the renumber required by the BC merge). The
-- table moves from the retired `rag` schema (ADR-05 §A05.1) to the `docs`
-- schema; on prod the operator performs `ALTER TABLE rag.document_chunks
-- SET SCHEMA docs;` ahead of this deploy (ADR-12 §A12.13 step 3), so every
-- DDL statement in this file is `IF NOT EXISTS` to be a no-op on prod.
-- Fresh dev DBs use this file to create the table from scratch.
--
-- The pgvector extension lives in `public` schema. The `vector` TYPE thus
-- belongs to public regardless of search_path, hence `public.vector(1024)`.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS docs.document_chunks (
    document_id    UUID         NOT NULL,
    chunk_index    INTEGER      NOT NULL,
    user_id        UUID         NOT NULL,
    visibility     TEXT         NOT NULL CHECK (visibility IN ('public', 'private')),
    embedding      public.vector(1024) NOT NULL,
    text           TEXT         NOT NULL,
    body_checksum  TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT docs_document_chunks_pkey
        PRIMARY KEY (document_id, chunk_index)
);

-- Supports the idempotency SELECT (`WHERE document_id = ?`) + visibility-
-- changed UPDATE + delete cascade. The PK's leading column already covers
-- this; the dedicated index is cheap insurance when the PK pages get
-- bloated by HNSW metadata co-located on the heap.
CREATE INDEX IF NOT EXISTS docs_document_chunks_document_id_idx
    ON docs.document_chunks (document_id);

-- Supports chat's retrieval scoping. Currently only `visibility = 'public'`
-- is needed (auth-only retrieval per the M2 spec §8 amendment) but both
-- indexes are kept to match ADR-13 §F's prescribed shape and to leave
-- room for future filter shapes without a migration.
CREATE INDEX IF NOT EXISTS docs_document_chunks_visibility_idx
    ON docs.document_chunks (visibility);

CREATE INDEX IF NOT EXISTS docs_document_chunks_user_id_visibility_idx
    ON docs.document_chunks (user_id, visibility);

-- HNSW + cosine ops per ADR-13 §9. m=16 / ef_construction=64 are pgvector
-- defaults; the runtime hint `SET LOCAL hnsw.ef_search = 40;` is applied
-- at chat retrieval time (not here — the index doesn't carry ef_search).
CREATE INDEX IF NOT EXISTS docs_document_chunks_embedding_hnsw_idx
    ON docs.document_chunks
    USING hnsw (embedding public.vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE docs.document_chunks IS
    'M6.1 — chunk corpus per ADR-13 §F (now in docs schema per ADR-05 §A05.1). '
    'docs-api writes via the in-BC ingestion pipeline; chat reads. '
    '(user_id, visibility) carry from docs.documents at ingestion + '
    'visibility-change time; never recomputed from docs schema.';
