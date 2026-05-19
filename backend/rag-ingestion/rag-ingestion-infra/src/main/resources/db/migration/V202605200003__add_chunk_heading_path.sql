-- ADR-13 §1 (M3.1 amendment) — heading breadcrumb metadata for each chunk.
-- text[] over jsonb because we only need ordered string elements and
-- Postgres array predicates / sub-arrays are first-class. NOT NULL with
-- DEFAULT '{}' so the existing rows (pre-reembed) read as "no heading
-- breadcrumb"; the reembed CLI overwrites them with real paths.
ALTER TABLE rag.document_chunks
ADD COLUMN heading_path TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN rag.document_chunks.heading_path IS
    'Compact heading breadcrumb: ARRAY[h1, h2, ...] for the section that '
    'owns this chunk. Empty array = pre-heading content or pre-migration row.';
