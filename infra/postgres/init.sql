-- Bootstrap extensions for the playground Postgres instance.
-- Loaded once on the very first container start (the docker-entrypoint-initdb.d
-- hook is a no-op after the data volume is initialized).
--
-- Per ADR-05: pgvector is the primary vector store (RAG corpus). Each BC
-- creates its own schema later (identity, docs, rag, metrics) — that work is
-- per-milestone, not M0.

CREATE EXTENSION IF NOT EXISTS vector;

-- M1 (identity BC) — schema-per-BC per ADR-05. Table DDL is owned by Flyway
-- (backend/identity/identity-infra/src/main/resources/db/migration/), not here.
CREATE SCHEMA IF NOT EXISTS identity;

-- M2 (docs BC) — schema-per-BC per ADR-05 (amended by ADR-12). Table DDL
-- (docs.documents, docs.document_likes, Modulith event_publication) is owned
-- by Flyway (backend/docs/docs-infra/src/main/resources/db/migration/), not
-- here. Cross-schema FK to identity.users is forbidden per ADR-12 §8 — the
-- relationship is enforced at the application layer.
CREATE SCHEMA IF NOT EXISTS docs;

-- M3 (rag-ingestion BC) — schema-per-BC per ADR-05 (amended by ADR-13).
-- Table DDL (rag.document_chunks with pgvector(1024) + HNSW index, Modulith
-- event_publication for `rag.document.ingested`) is owned by Flyway
-- (backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/),
-- not here. The `vector` extension is created at the top of this file (M2
-- bootstrapped it for the docs corpus); ADR-13 §F's Flyway migration is
-- idempotent against the existing extension via `CREATE EXTENSION IF NOT
-- EXISTS vector SCHEMA rag` when it lands.
CREATE SCHEMA IF NOT EXISTS rag;

-- M4 (rag-chat BC) — schema-per-BC per ADR-05 (amended by ADR-14 §3 + §G.3).
-- Table DDL (chat.sessions, chat.messages, chat.message_citations) is owned
-- by Flyway (backend/rag-chat/rag-chat-infra/src/main/resources/db/migration/),
-- not here. Per ADR-14 §3 + §3.1, the rag-chat-api Hikari connection sets
-- `search_path` to `chat,docs,rag,identity,public` at session start to support
-- the first sanctioned cross-schema SELECT exception: per-turn vector retrieval
-- against `rag.document_chunks`, citation enrichment via `docs.documents`, and
-- the display-name lookup via `identity.users`. Cross-schema writes remain
-- forbidden — rag-chat writes only to the `chat` schema.
CREATE SCHEMA IF NOT EXISTS chat;
