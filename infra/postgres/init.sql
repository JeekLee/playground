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
