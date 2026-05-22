-- M6.1 ADR-12 amendment §A12.3 + §A12.4 — async extraction columns + MinIO source-blob columns.
--
-- 1) extraction_status — async extraction lifecycle.
--    Values:
--      'pending'             — no extraction needed (synchronous markdown landed here)
--      'pending_extraction'  — queued; awaits the extraction worker
--      'extracting'          — worker in progress
--      'extracted'           — body materialized
--      'failed'              — worker errored (extraction_reason set)
--    Backfill: every existing row is 'extracted' (its body was materialized
--    by the synchronous M2/M6 paths). New PDF/multipart-MD uploads INSERT
--    with 'pending_extraction' and the worker transitions through
--    'extracting' → 'extracted' or 'failed'. JSON POSTs and pure markdown
--    multipart uploads land as 'extracted' synchronously (no async hop).
--
-- 2) extraction_reason — human-readable failure context (null otherwise).
--
-- 3) source_object_key — MinIO object key (e.g. {id}/source.pdf). Null for
--    pre-M6.1 rows (no MinIO retention prior to this milestone).
--
-- 4) source_size_bytes — stored multipart body size, for the download
--    endpoint's Content-Length header + observability.
--
-- 5) source_mime — stored multipart media type (e.g. 'application/pdf',
--    'text/markdown'). Distinct from the existing mime_type column which is
--    the SOURCE media type of the document (always preserved). For consistency
--    we mirror the same value here on new rows; column is null on backfilled
--    pre-M6.1 rows that have no MinIO object.

SET search_path = docs, public;

ALTER TABLE docs.documents
    ADD COLUMN extraction_status TEXT NOT NULL DEFAULT 'extracted';

ALTER TABLE docs.documents
    ADD CONSTRAINT documents_extraction_status_chk
    CHECK (extraction_status IN ('pending','pending_extraction','extracting','extracted','failed'));

ALTER TABLE docs.documents
    ADD COLUMN extraction_reason TEXT NULL;

ALTER TABLE docs.documents
    ADD COLUMN source_object_key TEXT NULL;

ALTER TABLE docs.documents
    ADD COLUMN source_size_bytes BIGINT NULL;

ALTER TABLE docs.documents
    ADD COLUMN source_mime TEXT NULL;

COMMENT ON COLUMN docs.documents.extraction_status IS
    'Async extraction lifecycle (M6.1): pending|pending_extraction|extracting|extracted|failed.';
COMMENT ON COLUMN docs.documents.extraction_reason IS
    'Failure reason when extraction_status=failed; null otherwise.';
COMMENT ON COLUMN docs.documents.source_object_key IS
    'MinIO object key for the original uploaded blob (e.g. {document_id}/source.pdf); null for pre-M6.1 rows.';
COMMENT ON COLUMN docs.documents.source_size_bytes IS
    'Original uploaded blob size in bytes; null when source_object_key is null.';
COMMENT ON COLUMN docs.documents.source_mime IS
    'Stored multipart media type for the source blob (matches mime_type for new uploads); null for pre-M6.1 rows.';

-- Partial index over the small "extracting" / "pending_extraction" set so the
-- operator's "stuck extractions" health query stays fast even at corpus scale.
CREATE INDEX documents_extraction_in_flight_idx
    ON docs.documents (created_at)
    WHERE extraction_status IN ('pending_extraction','extracting');
