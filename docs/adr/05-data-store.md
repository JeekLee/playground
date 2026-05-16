# ADR-05: Data Store — Postgres 16 + pgvector, Schema-per-Service

## Status
Accepted

## Context
We need:
- A single OLTP store the personal-scale playground can run for years without
  operational pain.
- A vector store for RAG that does not introduce a second database product.
- Isolation between BCs (no cross-schema joins; each BC owns its tables).
- A port that does not collide with the existing `clic-postgres` (host port
  `10132`) already running on the same machine.

Alternatives considered:
- Separate OLTP + Qdrant for vectors — rejected: extra container, extra ops,
  and pgvector is sufficient for this scale.
- Postgres-per-service (one container each) — rejected: too heavy for a
  personal laptop; schema isolation is enough.

## Decision

### Image and ports
- Image: **`pgvector/pgvector:pg16`** (Postgres 16 + pgvector pre-installed —
  one image, no init script gymnastics).
- Compose service name (and DNS hostname inside the network):
  **`postgres-playground`**.
- Compose-internal port: **`5432`**.
- Host-exposed port: **`10232`** (in the `102xx` block, well clear of
  `clic-postgres:10132`).
- Volume: `postgres-playground-data` (named volume, persistent).

### Cluster + auth
- Single superuser is created by the image (`POSTGRES_USER=playground`,
  `POSTGRES_PASSWORD` from `.env`, `POSTGRES_DB=playground`).
- One database: `playground`. Schema-per-BC inside it.

### Schema-per-service

| Schema | Owned by | Notes |
|---|---|---|
| `identity` | `identity` service | Users, OAuth links |
| `docs` | `docs` service | Document metadata; raw MD stored as TEXT or in object storage TBD |
| `rag` | `rag-ingestion` (writer) + `rag-chat` (reader) | Shared by the RAG bounded context. Contains pgvector-backed `chunk` table. |
| `metrics` | `metrics` service | Snapshot history |
| `flyway_<schema>` | per-service Flyway | Each service's migration history table lives in its own schema. |

Each service connects with the shared superuser but `SET search_path =
<schema>, public` at session start. (We do not bother with per-schema roles for
a single-user personal service; a future ADR can introduce them.)

### Vector store (pgvector)
- Extension `vector` is created once at bootstrap inside the `rag` schema:
  `CREATE EXTENSION IF NOT EXISTS vector SCHEMA rag;`
- Chunk embedding column: `embedding vector(1024)` (dimension fixed by ADR-04 /
  BGE-M3 dense head).
- Default index: **HNSW** (`m=16, ef_construction=64`) on cosine distance —
  `CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)`. IVFFlat is the
  fallback if HNSW build cost becomes prohibitive at corpus scale.

### Migrations — Flyway
- Each service ships its own Flyway migrations under
  `api/<service>/src/main/resources/db/migration/`.
- Flyway is configured per service to:
  - Manage only its own schema (`flyway.schemas=<schema>`).
  - Use a per-schema history table (`flyway.table=flyway_history`).
- Migration naming: `V<yyyyMMddHHmm>__<snake_case_description>.sql`.

### Connection settings (per service)
- JDBC URL template: `jdbc:postgresql://postgres-playground:5432/playground?currentSchema=<schema>`
- HikariCP defaults; max pool size 5 (small per-service pool — many services,
  one DB).

## Consequences
- Positive: One container, one product to learn, vector + relational in one
  transaction when needed.
- Positive: Clear schema ownership documents BC boundaries at the data layer.
- Negative: Schemas share a single failure domain (one Postgres container);
  acceptable for personal scale.
- Negative: Cross-schema queries are *technically* possible — discipline
  (enforced by code review) is the only barrier. ArchUnit cannot help here;
  Flyway script reviews must.
- Negative: pgvector at 1024-dim with HNSW will eventually run into RAM limits;
  revisit when the docs corpus exceeds ~100k chunks.

## Amendment — M2 onward (search projection + object storage reservation)

The M2 docs-BC design spec (`docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md`)
introduces two storage concerns the original ADR did not anticipate. They are
incorporated here as additive decisions; nothing above is superseded.

### OpenSearch — secondary search projection (M2+)

Postgres remains the primary OLTP + vector store. From M2 onward, a second-tier
**search projection** is built in OpenSearch to back full-text / faceted queries
over `docs.documents` that pgvector + Postgres `tsvector` cannot serve
efficiently at the latency/relevance bar M2 sets.

- **Image:** `opensearchproject/opensearch:2.18.0` (latest 2.x at time of
  pinning; bump on per-milestone ADR if needed).
- **Compose service name:** `opensearch-playground`.
- **Compose-internal port:** `9200` (REST), `9600` (perf analyzer).
- **Host-exposed port:** `10292` (in the `102xx` block, clears
  `postgres-playground:10232` and `redis-playground:10279`).
- **Auth:** the security plugin is **disabled** for dev (single-node, internal
  network). Production deployment would re-enable it via a per-milestone ADR.
- **Topology:** **single-node** in dev (`discovery.type=single-node`).
  Multi-node deployment is a production concern that does not block any
  milestone; revisit per-milestone when the playground ships beyond local dev.
- **Volume:** `opensearch-playground-data` (named volume).
- **JVM heap:** `OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m` (dev — small).
- **Index ownership:** indices are owned by the BC whose projector populates
  them. M2 introduces `docs-documents` (read-projected from the docs schema).
  Index-naming pattern: `<bc>-<aggregate>` (lowercase, hyphenated, matches
  ADR-03's BC vocabulary).
- **Projector wiring:** the docs BC's `-infra` module ships an OpenSearch
  client adapter implementing a `*-app` `SearchProjectionPort`. Population is
  triggered by Kafka consumers reacting to the BC's own domain events
  (`docs.document.uploaded`, `docs.document.updated`, `docs.document.deleted`).
  This keeps the projection eventually-consistent with the authoritative
  Postgres rows.
- **No cross-BC index access.** `rag-chat` does not query the docs BC's
  OpenSearch index directly; it consumes events or calls the gateway-sanctioned
  read path (per ADR-08 amendment).

### Object storage — reserved slot (M2.1+, vendor unpinned)

Document attachments (images embedded in documents, file uploads referenced from
MD body) are deferred to **M2.1**. The slot is reserved here so:

- **Compose service name (reserved):** `objectstore-playground`.
- **Host-exposed port (reserved):** `10293` (next in the `102xx` block).
- **Vendor: unpinned.** MinIO is the assumed default for local-compatible S3
  semantics, but no commitment is made until the M2.1 milestone ADR. The slot
  may host MinIO, SeaweedFS, or a thin reverse-proxy in front of an external
  object store — the choice is deferred.
- **Bucket-naming reservation:** `<bc>-<purpose>` (e.g., `docs-attachments`).
  Cross-BC bucket access is forbidden by the same principle as cross-schema
  access (per "Forbidden channels" in ADR-08).
- **MD body storage stays in Postgres for M2.** Only binary attachments are
  considered for object storage. MD source remains TEXT in `docs.documents`
  for the foreseeable future — see the M2 docs-BC design spec for rationale.

The M2.1 per-milestone ADR pins the vendor, the access pattern (presigned URLs
through the gateway vs. direct backend access), and the lifecycle policy.

## Related (added)
- M2 docs-BC design spec — OpenSearch projection contract, MD body storage
- ADR-08 amendment — Redis lock access for `rag-ingestion`
