-- ADR-13 §F — rag-ingestion BC's persistence root.
-- One Postgres instance hosts every BC's schema (ADR-05); the rag schema
-- is created here and isolated by Flyway's `flyway.schemas=rag` setting.
-- Spring's `spring.jpa.properties.hibernate.default_schema=rag` plus the
-- JPA entity's @Table(schema = "rag") keep the wiring symmetrical.

-- The pgvector extension lives in `public` schema (created globally by
-- infra/postgres/init.sql at first volume bootstrap, or by this CREATE
-- EXTENSION IF NOT EXISTS otherwise). The `vector` TYPE thus belongs to
-- public regardless of which schema CREATE EXTENSION names. With
-- Flyway's `flyway.schemas=rag` the migration runs under
-- `search_path=rag`, so an unqualified `vector(1024)` column type fails
-- to resolve. Reference the type as `public.vector(1024)` so the
-- search_path doesn't matter.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag.document_chunks (
    document_id    UUID         NOT NULL,
    chunk_index    INTEGER      NOT NULL,
    user_id        UUID         NOT NULL,
    visibility     TEXT         NOT NULL CHECK (visibility IN ('public', 'private')),
    embedding      public.vector(1024) NOT NULL,
    text           TEXT         NOT NULL,
    body_checksum  TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT rag_document_chunks_pkey
        PRIMARY KEY (document_id, chunk_index)
);

-- Supports the idempotency SELECT (`WHERE document_id = ?`) + visibility-
-- changed UPDATE + delete cascade. The PK's leading column already covers
-- this; the dedicated index is cheap insurance when the PK pages get
-- bloated by HNSW metadata co-located on the heap.
CREATE INDEX rag_document_chunks_document_id_idx
    ON rag.document_chunks (document_id);

-- Supports M4's retrieval scoping. Currently only `visibility = 'public'`
-- is needed (auth-only retrieval per the M2 spec §8 amendment) but both
-- indexes are kept to match ADR-13 §F's prescribed shape and to leave
-- room for future filter shapes without a migration.
CREATE INDEX rag_document_chunks_visibility_idx
    ON rag.document_chunks (visibility);

CREATE INDEX rag_document_chunks_user_id_visibility_idx
    ON rag.document_chunks (user_id, visibility);

-- HNSW + cosine ops per ADR-13 §9. m=16 / ef_construction=64 are pgvector
-- defaults; the runtime hint `SET LOCAL hnsw.ef_search = 40;` is applied
-- at M4 retrieval time (not here — the index doesn't carry ef_search).
CREATE INDEX rag_document_chunks_embedding_hnsw_idx
    ON rag.document_chunks
    USING hnsw (embedding public.vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE rag.document_chunks IS
    'M3 RAG-Ingestion chunk corpus per ADR-13 §F. M3 writes; M4 reads. '
    '(user_id, visibility) carry from docs.documents at ingestion + '
    'visibility-change time; never recomputed from docs schema.';
