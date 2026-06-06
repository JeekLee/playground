-- Bootstrap extensions for the playground Postgres instance.
-- Loaded once on the very first container start (the docker-entrypoint-initdb.d
-- hook is a no-op after the data volume is initialized).
--
-- Per ADR-05: pgvector is the primary vector store (RAG corpus). Each BC
-- creates its own schema later (identity, docs, chat) — that work is
-- per-milestone, not M0.

CREATE EXTENSION IF NOT EXISTS vector;

-- M1 (identity BC) — schema-per-BC per ADR-05. Table DDL is owned by Flyway
-- (backend/identity/identity-infra/src/main/resources/db/migration/), not here.
CREATE SCHEMA IF NOT EXISTS identity;

-- M2 (docs BC) — schema-per-BC per ADR-05 (amended by ADR-12). Table DDL
-- (docs.documents, docs.document_likes, docs.document_chunks, Modulith
-- event_publication) is owned by Flyway
-- (backend/docs/docs-infra/src/main/resources/db/migration/), not here.
-- Cross-schema FK to identity.users is forbidden per ADR-12 §8 — the
-- relationship is enforced at the application layer.
--
-- M6.1 ADR-12 §A12.1 — the M3 `rag-ingestion` BC was absorbed into `docs` and
-- its `rag.document_chunks` table moved to `docs.document_chunks` via a
-- Flyway migration. The retired `rag` schema is NOT created by this cold-boot
-- script; if you are migrating an existing dev DB rather than starting fresh,
-- `ALTER SCHEMA rag RENAME TO …` is a one-time prod step (see ADR-12 §A12.1)
-- and is not the responsibility of init.sql.
CREATE SCHEMA IF NOT EXISTS docs;

-- M4 (chat BC) — schema-per-BC per ADR-05 (amended by ADR-14 §3 + §G.3,
-- further amended by ADR-05 §A05.4 in M6.1). Table DDL (chat.sessions,
-- chat.messages, chat.message_citations) is owned by Flyway
-- (backend/springboot/chat/chat-infra/src/main/resources/db/migration/), not
-- here. Per ADR-14 §3 + §3.1, the chat-api Hikari connection sets
-- `search_path` to `chat,docs,identity,public` (post-M6.1; the prior `rag`
-- entry was dropped when `document_chunks` moved into `docs`) at session
-- start to support the sanctioned cross-schema SELECT exception: per-turn
-- vector retrieval against `docs.document_chunks`, citation enrichment via
-- `docs.documents`, and the display-name lookup via `identity.users`.
-- Cross-schema writes remain forbidden — chat writes only to `chat`.
CREATE SCHEMA IF NOT EXISTS chat;
