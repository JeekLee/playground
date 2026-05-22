-- M6.1 ADR-12 amendment §A12.13 — migrated from rag-ingestion-infra's
-- V202605200003__add_chunk_heading_path.sql. The table moved from `rag`
-- schema to `docs` schema per ADR-05 §A05.1, so the column add now targets
-- docs.document_chunks. Idempotent via the `IF NOT EXISTS` clause so prod
-- (where the operator's `ALTER TABLE rag.document_chunks SET SCHEMA docs`
-- carried the column over already) is a no-op.
ALTER TABLE docs.document_chunks
    ADD COLUMN IF NOT EXISTS heading_path TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN docs.document_chunks.heading_path IS
    'Compact heading breadcrumb: ARRAY[h1, h2, ...] for the section that '
    'owns this chunk. Empty array = pre-heading content or pre-migration row.';
