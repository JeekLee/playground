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
  **`playground-postgres`**.
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
- JDBC URL template: `jdbc:postgresql://playground-postgres:5432/playground?currentSchema=<schema>`
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
- **Compose service name:** `playground-opensearch`.
- **Compose-internal port:** `9200` (REST), `9600` (perf analyzer).
- **Host-exposed port:** `10292` (in the `102xx` block, clears
  `playground-postgres:10232` and `playground-redis:10279`).
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

## Amendment (2026-05-17, ADR-12)

ADR-12 (M2 Docs per-milestone) supersedes parts of the OpenSearch section
above with the concrete client + analyzer + index pins that were left open
when ADR-05's amendment was first authored:

- **OpenSearch is the second-tier search store for the Docs BC.** Postgres
  remains the source of truth; OpenSearch is a projection rebuilt-able from
  Postgres at any time.
- **Image:** `opensearchproject/opensearch:2.18.0` (unchanged from above).
- **Java client:** `org.opensearch.client:opensearch-java:2.10.x` (native
  low-level client, not Spring Data OpenSearch — Spring Data OpenSearch is
  not in the Spring Boot 3.3.x BOM and is not pulled in).
- **Index name:** `docs-v1`. The `-v1` suffix anticipates future blue/green
  reindex via an alias swap (no alias is configured in M2).
- **Korean analyzer:** `nori` (built-in `analysis-nori` plugin in the 2.x
  image — no extra install). Each `title` / `body` field gets a `korean`
  primary analyzer + an `english` multi-field for Latin-only queries.
- **Search projector module placement:** `DocsSearchProjector` lives in
  `docs-app` as a Spring `@Service` bean; Kafka consumer wiring + the
  OpenSearch adapter live in `docs-infra`. Failures to write to OpenSearch
  do not block API writes (events retry via Kafka redelivery).
- **No cross-BC index access.** rag-chat (M4) does not query the docs BC's
  OpenSearch index directly; cross-BC reads continue to go through Kafka or
  the explicit `/internal/**` HTTP exception (per ADR-08 amendment).

See `docs/adr/12-m2-docs.md` §5 + §6 for the full specification (compose
service block, JVM heap, security plugin posture, Nori filter chain).

## Amendment (2026-05-18, ADR-13)

ADR-13 (M3 RAG-Ingestion per-milestone) **confirms** the original ADR-05
pgvector pin (`HNSW (m=16, ef_construction=64)` on cosine distance) for
the M3 `rag.document_chunks` table — **no change** to the index defaults.
It adds three complementary commitments:

- **Runtime hint:** M4's retrieval query will set
  `SET LOCAL hnsw.ef_search = 40;` per query — wide enough for K=10
  retrieval at >95% recall on the M3 P0 corpus (M4-owned, documented here
  as part of the M3-enabled retrieval contract).
- **Chunk DDL adds `body_checksum TEXT NOT NULL`** (SHA-256 hex, carried
  per row) for ingestion idempotency, plus three secondary B-tree indexes
  — `(document_id)`, `(visibility)`, `(user_id, visibility)` — that
  support M3's event handlers (idempotency SELECT, visibility UPDATE,
  delete cascade) and M4's retrieval predicates (the M2 spec §8 amendment
  pins `WHERE visibility='public' OR (user_id=? AND visibility='private')`
  as the single retrieval filter; the composite index covers it).
- **Corpus-size assumption made explicit:** HNSW is the M3 P0 default
  for up to ~50k chunks. **IVFFlat remains the documented fallback** per
  ADR-05's original "IVFFlat is the fallback if HNSW build cost becomes
  prohibitive at corpus scale" — revisit when total chunks cross ~100k.

The full `rag.document_chunks` DDL (with the HNSW index, the three
secondary indexes, and the `body_checksum` column) lives in
`docs/adr/13-m3-rag-ingestion.md` §F. The Flyway migration that creates
the table lives in
`backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/`.

See `docs/adr/13-m3-rag-ingestion.md` §9 + §F + amendment block §G.2 for
the full specification.

## Amendment (2026-05-18, ADR-14) — `chat` schema + cross-schema SELECT exception

ADR-14 (M4 RAG-Chat per-milestone) adds the **`chat` schema** — the fifth
top-level schema, after `identity`, `docs`, `rag`, and `metrics`. The
schema-per-BC invariant is preserved; `chat` is owned exclusively by
`rag-chat-api`.

### `chat` schema DDL (excerpt — full DDL in ADR-14 §F)

```sql
CREATE SCHEMA IF NOT EXISTS chat;

CREATE TABLE chat.sessions (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL,            -- app-level FK to identity.users.id
  title       TEXT         NOT NULL DEFAULT 'New chat',
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chat_sessions_by_user
  ON chat.sessions (user_id, updated_at DESC);

CREATE TABLE chat.messages (
  id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id   UUID         NOT NULL REFERENCES chat.sessions(id) ON DELETE CASCADE,
  user_id      UUID         NOT NULL,                   -- denormalized tenant key
  role         TEXT         NOT NULL CHECK (role IN ('user','assistant')),
  content      TEXT         NOT NULL,
  tokens_in    INT,
  tokens_out   INT,
  retrieval_k  INT,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_by_session
  ON chat.messages (session_id, created_at);

CREATE TABLE chat.message_citations (
  message_id   UUID  NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
  position     INT   NOT NULL,
  document_id  UUID  NOT NULL,             -- app-level FK to docs.documents.id
  chunk_index  INT   NOT NULL,             -- app-level FK to rag.document_chunks.chunk_index
  PRIMARY KEY (message_id, position)
);
CREATE INDEX chat_message_citations_by_document
  ON chat.message_citations (document_id);
```

A trigger bumps `chat.sessions.updated_at` on every `chat.messages`
insert (so the top-tab strip's "most-recent" sort matches the actual
last-activity timestamp). Full trigger DDL in ADR-14 §F.

### Cross-schema SELECT exception — first of its kind

ADR-05's original "Cross-schema queries are *technically* possible —
discipline (enforced by code review) is the only barrier" framing is
**relaxed for the rag-chat BC** to allow three specific read predicates:

| Caller | Schema | Predicate | Why cross-schema, not HTTP |
|---|---|---|---|
| `rag-chat-api` | `rag.document_chunks` | `ORDER BY embedding <=> :q LIMIT :k` with `WHERE visibility='public' OR (user_id=? AND visibility='private')` | Per-turn vector retrieval. An HTTP RPC would defeat pgvector's HNSW sub-millisecond primitive. Already framed as M4-owned by ADR-13 §G.4. |
| `rag-chat-api` | `docs.documents` | `SELECT id, title, visibility FROM docs.documents WHERE id IN (...)` | Citation enrichment, inside the TTFT P95 ≤ 2.0s budget. Batched single-query SELECT is sub-millisecond vs ~30ms HTTP fan-out. |
| `rag-chat-api` | `identity.users` | `SELECT display_name, avatar_url FROM identity.users WHERE id = ?` | Chat header display. Identity-api does not currently expose a `/internal/users/by-id/{id}` HTTP route (only `by-google-sub`); SELECT is the lower-coupling choice. |

**Cross-schema writes remain forbidden.** rag-chat writes only to the
`chat` schema. The two existing HTTP exceptions in ADR-08 (M3→docs
body-fetch, docs→identity owner-lookup) are NOT extended — M4's
cross-schema reads stay at the SQL layer.

### Hikari connection — search_path covers four schemas

```yaml
spring:
  datasource:
    hikari:
      connection-init-sql: "SET search_path TO chat,docs,rag,identity,public"
  jpa:
    properties:
      hibernate:
        default_schema: chat
```

`default_schema: chat` ensures Hibernate `@Entity` writes land in `chat`
(no accidental writes to `rag` / `docs` / `identity`); the cross-schema
read adapters use fully-qualified table names regardless (`rag.document_chunks`,
`docs.documents`, `identity.users`). The search_path is belt-and-suspenders.

### Forward note

A future BC that wants to reach into `chat.*` via cross-schema SELECT
needs another ADR-05 amendment row. The exception is bounded to the three
predicates above; the discipline does not relax beyond what is enumerated.

See `docs/adr/14-m4-rag-chat.md` §3 + §F for the full specification.

## Amendment (2026-05-22, ADR-12 amendment M6.1) — `rag` schema retired + MinIO sidecar

The M6.1 master amendment in **ADR-12 (2026-05-22)** collapses the
`rag-ingestion` BC into `docs`. The data-store implications:

### A05.1. `rag` schema — retired

**Decision:** the `rag` schema is dropped. Its only table —
`rag.document_chunks` (introduced by ADR-13 §F and amended into ADR-05
via the 2026-05-18 amendment) — moves into the `docs` schema:

- **Before M6.1:** `rag.document_chunks` (HNSW index, body_checksum
  column, three secondary B-tree indexes per ADR-05 amendment 2026-05-18).
- **After M6.1:** `docs.document_chunks` (every column, every index,
  every constraint preserved verbatim — only the schema prefix changes).

**Migration mechanic** (operational, not architectural — full
procedure in ADR-12 amendment A12.13):

```sql
ALTER TABLE rag.document_chunks SET SCHEMA docs;
```

The HNSW index, the secondary indexes, the `body_checksum` column, and
the runtime hint (`SET LOCAL hnsw.ef_search = 40` per query — owned by
rag-chat) are all preserved. No DDL change beyond the schema move.

The `rag.event_publication` table (Spring Modulith outbox) merges with
the existing `docs.event_publication` — single outbox per BC per ADR-10
§8. The merge procedure (copy unfinished rows, drop `rag.event_publication`)
lives in ADR-12 amendment A12.13.

After the schema drop, the **schema-per-BC** invariant (the load-bearing
rule of this ADR) is **strengthened**: M6.1 has one fewer schema, and
the cross-schema SELECT exception (ADR-14 amendment 2026-05-18) reduces
its surface from three schemas (`rag,docs,identity`) to two
(`docs,identity`). The schema list at the top of this ADR becomes:

| Schema | Owned by | Notes |
|---|---|---|
| `identity` | `identity` service | Users, OAuth links |
| `docs` | `docs` service | Document metadata; raw MD body as TEXT; chunks + pgvector embeddings; outbox table |
| ~~`rag`~~ | ~~`rag-ingestion` + `rag-chat`~~ | **Retired by this amendment** — table moved to `docs` schema |
| `chat` | `rag-chat` service | Chat sessions, messages, citations |
| `metrics` | `metrics` service | Snapshot history (stateless in M5 per ADR-15 — no `metrics` schema is provisioned in P0; the slot is reserved) |
| `flyway_<schema>` | per-service Flyway | Each service's migration history table |

The total schema count drops from 4 to 3 (excluding the
`flyway_<schema>` per-service histories).

### A05.2. MinIO sidecar — original-blob storage for the docs BC

**Decision:** a new compose-internal MinIO instance
(`playground-minio`) is provisioned as a sidecar for the docs BC.
**This is the activation of the "object storage — reserved slot" that
ADR-05's 2026-05-17 amendment penciled in for M2.1**, materialized one
milestone early because M6.1's async extraction shape requires the
original PDF/MD bytes to survive the upload request-response cycle.

| Concern | Pin |
|---|---|
| Compose service name | `playground-minio` (replaces the reserved `objectstore-playground` slot from the 2026-05-17 amendment — the slot was unpinned; M6.1 pins it; volume name remains `minio-playground-data` to preserve existing local data) |
| Image | `minio/minio:RELEASE.2025-04-08T15-41-24Z` (latest stable as of the M6.1 PR; infra-implementer may bump to current latest) |
| Compose-internal port | `9000` (S3 API), `9001` (console) |
| Host-exposed port | `10294` (S3 API), `10295` (console) — both in the `102xx` block, after `playground-opensearch:10292/10293-reserved` |
| Volume | `minio-playground-data` (named volume) |
| Bucket | `playground-docs-originals` (only one bucket in M6.1; reserved naming pattern `<bc>-<purpose>` per ADR-05's 2026-05-17 amendment is honored — `<bc>=docs`, `<purpose>=originals`) |
| Auth | Single root user, `MINIO_ROOT_USER` + `MINIO_ROOT_PASSWORD` from `.env`; compose-internal network only; security plugin sufficient for personal scale (no per-service role split in M6.1) |
| Bucket ownership | `docs-api` only. Future BCs adding object-store usage either declare their own bucket on `playground-minio` (and add a per-milestone ADR row enumerating the bucket name) or provision a separate sidecar. Cross-bucket access is forbidden by the same principle as cross-schema DB access. |
| Java client | `io.minio:minio:8.5.x` on docs-infra's classpath. `BlobStoragePort` interface in `docs-app`; `MinioBlobStorageAdapter` in `docs-infra` (per ADR-02 layering). |
| Multipart-upload streaming | `MinioClient.putObject(InputStream, size, ...)` — never materializes the full blob into a `byte[]`. Spring Multipart's `file-size-threshold` stays at 1 MB (ADR-16 §8); larger uploads spill to a temp file and the file-backed `getInputStream()` re-streams to MinIO. |
| Download streaming | `MinioClient.getObject(...)` → response output stream; never buffers full blob. |
| Cascade-delete | On `DELETE /api/docs/{id}`, the `MinioBlobStorageAdapter.delete(documentId)` call runs inside the same Spring transaction boundary as the row delete and the Kafka outbox publish. If MinIO is unreachable, the SQL delete + event publish still commit; orphan cleanup runs nightly (`@Scheduled` — same pattern as ADR-12 §11 counter resync). |
| Health check | `curl -sf http://localhost:9000/minio/health/live` (built into the MinIO image) |

The full compose service block, the bucket-key convention
(`{document_id}.{ext}`), and the download endpoint (`GET /api/docs/{id}/source`,
visibility-aware auth) live in ADR-12 amendment A12.4. ADR-05 carries
only the data-store-level pins.

### A05.3. Updated reservation table

The 2026-05-17 amendment's "object storage — reserved slot (M2.1+,
vendor unpinned)" section is **superseded by A05.2 above** — vendor is
pinned (MinIO), port is pinned (10294/10295), bucket naming is honored.
The "MD body storage stays in Postgres for M2" guidance also continues
to hold for M6.1 — MD body in `docs.documents.body` (TEXT, 10 MB cap
per ADR-16 §11) is unaffected. **Only the original uploaded blob (the
PDF or the source MD file) lives in MinIO; the extracted Markdown lives
in Postgres as before.**

A 6th reservation slot opens for any future BC's object store needs:

- **Compose service name (reserved):** (TBD by next-milestone ADR)
- **Host-exposed port (reserved):** `10296` (next in the `102xx` block)

### A05.4. Updated schema-per-service connection settings

The `docs-api` Hikari connection-init-sql is unchanged by M6.1:

```yaml
spring:
  datasource:
    hikari:
      connection-init-sql: "SET search_path TO docs, public"
  jpa:
    properties:
      hibernate:
        default_schema: docs
```

The `rag-chat-api` Hikari connection-init-sql changes:

```yaml
# Before M6.1:
# connection-init-sql: "SET search_path TO chat,docs,rag,identity,public"

# After M6.1:
connection-init-sql: "SET search_path TO chat,docs,identity,public"
```

`default_schema: chat` is preserved (Hibernate writes still land only in
`chat`); only the read path's search_path narrows.

See `docs/adr/12-m2-docs.md` amendment 2026-05-22 §A12.1 + §A12.4 +
§A12.13 for the full M6.1 specification.

## Amendment 2026-05-22 (ADR-18, M8) — `arch` schema introduced

The M8 PR set (ADR-18 — `docs/adr/18-m8-massing-gen.md`) introduces
the `massing-gen` BC, whose persistent storage is a new
`arch.outputs` table inside a new `arch` schema. The data-store
implications:

### A05.5. New schema — `arch` (owned by `massing-gen` BC)

The `arch` schema is **added** to the schema-per-BC list:

| Schema | Owned by | Notes |
|---|---|---|
| `identity` | `identity` service | Users, OAuth links |
| `docs` | `docs` service | Document metadata; raw MD body as TEXT; chunks + pgvector embeddings; outbox table |
| `chat` | `rag-chat` service | Chat sessions, messages, citations |
| **`arch`** | **`massing-gen` service** | **Generated `.3dm` outputs (`arch.outputs`). Owner-tagged; `.3dm` bytes inline as BYTEA per ADR-18 §12.** |
| `metrics` | `metrics` service | Snapshot history (stateless in M5 per ADR-15 — no `metrics` schema is provisioned in P0; the slot remains reserved) |
| `flyway_<schema>` | per-service Flyway | Each service's migration history table lives in its own schema |

Total schema count rises from 3 (post-M6.1) to **4**. The
schema-per-BC invariant (the load-bearing rule of this ADR) is
preserved — `arch` is owned exclusively by `massing-gen`.

### A05.6. `arch.outputs` table — DDL pinned

The full DDL lives in ADR-18 §18 (the Flyway migration
`V202605230001__arch_outputs.sql`, owned by `massing-gen-infra`). For
ADR-05's schema-overview audit, the table shape is:

```sql
CREATE SCHEMA IF NOT EXISTS arch;

CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK)
    file_bytes      BYTEA        NOT NULL,                  -- .3dm binary (ADR-18 §12)
    program_json    JSONB        NOT NULL,                  -- extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    summary         TEXT         NOT NULL,                  -- Korean-fixed (ADR-18 §5)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_arch_outputs_user  ON arch.outputs (user_id, created_at DESC);
CREATE INDEX idx_arch_outputs_brief ON arch.outputs (brief_doc_id);
```

| Concern | Pin |
|---|---|
| Schema | `arch` (NEW) |
| Tables in M8 P0 | one — `arch.outputs` |
| pgvector usage in `arch` | **None.** The `arch` schema does NOT create the `vector` extension or any embedding columns. M8 has no retrieval surface; vector search stays in `docs.document_chunks` (M3-derived, M6.1-relocated). |
| File storage media | **BYTEA inline** (per ADR-18 §12) — chosen over MinIO for M8 P0 storage simplicity at the expected file size (5–50 KiB per row). Migration to MinIO documented but deferred (M8.1 trigger = >10 K rows in `arch.outputs`). |
| Cross-schema FK to `docs.documents` | **App-level only** (no DB FK; cross-schema FK forbidden by ADR-05 invariant). Dangling-FK semantic documented in ADR-18 §13 — `arch.outputs` rows persist even when the source brief doc is deleted (untouched cleanup policy). |
| Cross-schema FK to `identity.users` | **App-level only**, mirroring the M4 `chat.sessions.user_id` pattern (ADR-14 §3). |
| Hikari `search_path` (`massing-gen-api`) | **`SET search_path TO arch, public`** (no cross-schema reads — M8 reads docs metadata over HTTP via ADR-08 Exception 5, not via SQL). |
| Hibernate `default_schema` (`massing-gen-api`) | `arch` |
| Connection pool | `maximum-pool-size: 5` (ADR-05 default; M8 is a low-frequency BC). |

### A05.7. No new cross-schema SELECT exception

M8's brief-body fetch goes over HTTP (per ADR-18 §5 + ADR-08 §A08.12 —
fresh Exception 5). The M4 cross-schema SELECT exception (introduced
in the 2026-05-18 amendment, narrowed in M6.1 §A05.4 to
`chat,docs,identity`) is **not** extended by M8. The cross-schema
SELECT surface stays at 2 schemas (`docs.*`, `identity.*`).

This is intentional — ADR-18 §5 evaluated (a) revive Exception 1
(rejected — M6.1 retirement), (b) cross-schema SELECT (rejected —
latency budget allows HTTP and HTTP preserves BC isolation), (c) fresh
ADR-08 Exception 5 (chosen). The schema-per-BC invariant strengthens
here: the `arch` schema is fully isolated from sibling schemas; the
only cross-schema interaction is at the app-level FK UUID columns,
which carry no SQL-level coupling.

### A05.8. Object storage — slot 6 still reserved

The 6th object-storage reservation slot opened by M6.1 amendment
§A05.3 (host port 10296, compose service name TBD) remains
**reserved**. M8 does **not** claim it (per ADR-18 §12 — `.3dm` files
stay in BYTEA, not MinIO). The slot remains open for M9+ or for a
future migration of `arch.outputs.file_bytes` → MinIO if corpus
growth makes it necessary.

The existing `playground-minio` sidecar (introduced by M6.1
§A05.2 for docs-originals) is **not shared** with M8 — its bucket
(`playground-docs-originals`) is owned exclusively by docs-api per
the single-tenant invariant. Any future MinIO-backed M8 storage
would either declare a new bucket on `playground-minio`
(`playground-arch-outputs`, with an updated bucket-ownership
amendment) or provision a separate sidecar at slot 6.

See `docs/adr/18-m8-massing-gen.md` §12 + §18 for the full M8
storage specification.

## Amendment 2026-05-22 (M8 Python flip — Alembic / hand-rolled SQL)

> This amendment is appended to ADR-05 as a new amendment block
> following the M8 block (§A05.5–§A05.8) above. The M8 BC's
> implementation language is flipped from Java/Spring Boot to
> Python/FastAPI (per ADR-18 §A18.1). The `arch` schema DDL and the
> `arch.outputs` table shape are **unchanged**; only the migration
> tool moves from Flyway to Alembic (or hand-rolled SQL). The
> §A05.5–§A05.8 block above is **not rewritten**.

### §A05.9. `arch` schema ownership — language flipped, DDL unchanged

**Decision: the `arch` schema and the `arch.outputs` table DDL pinned
in §A05.6 (and ratified in ADR-18 §18) are preserved verbatim. The
implementation language of the owning BC (`massing-gen`) flips from
Java to Python, but the DDL the Postgres cluster receives is
identical — same column names, same types (UUID / BYTEA / JSONB /
REAL / INT / TEXT / TIMESTAMPTZ), same indexes
(`idx_arch_outputs_user`, `idx_arch_outputs_brief`), same comments.**

**Migration tool — Flyway out, Alembic in (or hand-rolled SQL):**

| Concern | Pre-flip (Java) | Post-flip (Python) |
|---|---|---|
| Migration tool | **Flyway** — `flyway_arch` history table; `V202605230001__arch_outputs.sql` under `massing-gen-infra/src/main/resources/db/migration/` | **Alembic** (recommended) OR hand-rolled SQL — see ADR-18 §A18.7. Alembic history lives in `alembic_version` table (Alembic's default); hand-rolled SQL has no history table. |
| Migration file location | `backend/massing-gen/massing-gen-infra/src/main/resources/db/migration/V202605230001__arch_outputs.sql` | `backend/fastapi/massing-gen/alembic/versions/202605230001_arch_outputs.py` (Alembic) OR `backend/fastapi/massing-gen/schema.sql` (hand-rolled idempotent SQL with `CREATE SCHEMA IF NOT EXISTS arch; CREATE TABLE IF NOT EXISTS arch.outputs ...`) |
| DDL bytes | identical | identical |
| Bootstrap mechanic | Flyway auto-runs on Spring Boot startup | Alembic CLI invoked from container entrypoint OR `psycopg`-driven SQL execution in FastAPI's startup event (`@app.on_event("startup")`) |
| Connection settings (`search_path`, `default_schema`) | `SET search_path TO arch, public` via Hikari `connection-init-sql`; Hibernate `default_schema: arch` | Same `SET search_path TO arch, public` configured via SQLAlchemy `connect_args={"options": "-csearch_path=arch,public"}` (or equivalent). SQLAlchemy has no `default_schema` equivalent; the implementer either fully-qualifies table names (`arch.outputs`) in SQLAlchemy `__table_args__ = {"schema": "arch"}` or relies on the search_path. Both achieve the same effect. |

### §A05.10. Cross-BC migration uniformity — Flyway (Java BCs) + Alembic (Python BCs)

**Decision: Java BCs continue to use Flyway (the ADR-05 default). The
Python BC (massing-gen at M8) uses Alembic (or hand-rolled SQL). Both
tools target the same `playground-postgres` Postgres instance, the
same `playground` database, but distinct history tables
(`flyway_arch` would not have existed under Java post-M6.1 anyway —
ADR-05 §"Migrations" baseline uses per-schema `flyway_history`; for
the Python BC the equivalent is `alembic_version`, scoped per
schema via Alembic's `version_table_schema='arch'` config).**

**No conflict in practice:** each BC owns its schema (`identity`,
`docs`, `chat`, `arch`, `metrics`) and runs its own migrations on
its own schema. The two migration tools never touch the same DDL.
The polyglot migration toolchain is a direct consequence of the
polyglot BC policy from ADR-01 §A01.11.

| Schema | Owner BC | Implementation language | Migration tool |
|---|---|---|---|
| `identity` | `identity` | Java | Flyway |
| `docs` | `docs` | Java | Flyway |
| `chat` | `rag-chat` | Java | Flyway |
| **`arch`** | **`massing-gen`** | **Python** (per ADR-18 §A18.1) | **Alembic** (preferred) or hand-rolled SQL |
| `metrics` | `metrics` | Java | Flyway (reserved — no schema in P0 per ADR-15) |

**Forward note:** if a future Python BC is introduced (per
ADR-01 §A01.14), it MAY use Alembic; the migration tool choice is
per-BC, not project-wide. Java BCs MAY NOT switch to Alembic — Flyway
remains the Java default to keep the Java toolchain uniform.

See `docs/adr/18-m8-massing-gen.md` §A18.7 + ADR-01 §A01.11–§A01.14
for the full Python flip specification.
