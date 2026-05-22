-- M6 ADR-16 — docs BC PDF support.
--
-- 1) Add the source MIME type column on docs.documents. Default
--    'text/markdown' so existing rows (every M2/M3/M5 row was a Markdown
--    upload) keep their wire shape with no application-level migration.
--    CHECK constraint pins the two literals the application enums to
--    (MimeType.MARKDOWN / MimeType.PDF).
--
-- 2) Widen the body size CHECK from 1 MB to 10 MB. PDF text-layer extraction
--    can produce Markdown bodies larger than 1 MB (large PDFs with dense
--    text); the application VO (DocumentBody.MAX_OCTET_LENGTH) is bumped to
--    match. Drop-then-recreate is necessary because Postgres has no
--    ALTER CONSTRAINT for CHECK.

ALTER TABLE docs.documents
    ADD COLUMN mime_type text NOT NULL DEFAULT 'text/markdown';

ALTER TABLE docs.documents
    ADD CONSTRAINT documents_mime_type_check
    CHECK (mime_type IN ('text/markdown', 'application/pdf'));

ALTER TABLE docs.documents
    DROP CONSTRAINT IF EXISTS documents_body_size_chk;

ALTER TABLE docs.documents
    ADD CONSTRAINT documents_body_size_chk
    CHECK (octet_length(body) <= 10485760);
