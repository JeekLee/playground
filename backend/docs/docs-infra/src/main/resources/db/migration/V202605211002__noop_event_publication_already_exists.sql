-- M6.1 ADR-12 amendment §A12.13 — migrated from rag-ingestion-infra's
-- V202605200002__create_event_publication.sql. The Spring Modulith outbox
-- table `event_publication` was previously created twice — once in the
-- `rag` schema by the rag-ingestion BC and once in the `docs` schema by
-- the docs BC's V202605180002. With the BC merge (ADR-12 §A12.1) the
-- single outbox lives in `docs.event_publication`; the `rag.event_publication`
-- copy is dropped by the operator procedure (§A12.13 step 3 — `DROP SCHEMA
-- rag CASCADE`).
--
-- This file remains as a no-op migration slot so the Flyway history captures
-- the merge step; the docs.event_publication table is already created by
-- V202605180002 and the Hibernate Modulith entity binding points at it.

DO $$
BEGIN
    -- Defensive: the docs.event_publication table is created by
    -- V202605180002. If for some reason that migration did not run (e.g.,
    -- an out-of-order replay), surface the failure loudly here.
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'docs' AND table_name = 'event_publication'
    ) THEN
        RAISE EXCEPTION
            'docs.event_publication missing — Spring Modulith outbox unusable. '
            'Re-run V202605180002 (create_event_publication) before this migration.';
    END IF;
END$$;
