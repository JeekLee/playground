# ADR-08: Inter-Service Communication

## Status
Accepted

## Context
We need explicit rules about which service may talk to which, and over what
channel. Without this, services drift into ad-hoc HTTP calls and the "bounded
context" claim becomes paper-thin.

## Decision

### Allowed channels

| Direction | Channel | Notes |
|---|---|---|
| client (browser) -> gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway -> any BC | HTTP (compose-internal) | Per ADR-07 routing |
| BC -> Kafka -> BC | Kafka events | Per ADR-03 envelope |
| BC -> external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC -> Postgres (`playground-postgres`) | JDBC | Per ADR-05 |
| BC -> Redis (`playground-redis`) | Redis protocol | Only the gateway uses Redis today (sessions); BCs may add usage if justified per-milestone |

### Forbidden channels

- **Direct backend-to-backend HTTP across BCs is forbidden.** If `rag-chat`
  needs a piece of `docs` data, it either:
  1. consumes the relevant Kafka event and maintains its own read model, or
  2. (last resort, must be justified in the milestone ADR) calls back through
     the gateway with the user's session — but never directly cross-BC.
- **Cross-schema database access is forbidden.** A BC reads/writes only its
  own Postgres schema (per ADR-05).
- **Backend services must not be host-exposed.** Only the gateway binds a host
  port. ADR-07's trust model breaks otherwise.

### The `shared-kernel` exception

`shared-kernel` is a *compile-time* Java module — not a running service. It is
the only allowed point of cross-BC code coupling, and only for:

- The `EventEnvelope<T>` record (ADR-03).
- Common, immutable value-object types that cross BC boundaries inside event
  payloads (e.g., `UserId`, `DocumentId`).
- Lightweight read DTOs that multiple BCs format identically.
- A small handful of utilities (clock, ID generation) — kept zero-dependency.

`shared-kernel` MUST NOT depend on Spring, JPA, Kafka clients, or any service
module. It is a leaf in the dependency graph.

### Health and observability
- Every backend exposes `/actuator/health` on its own port (compose-internal).
- Compose `healthcheck` for each BC hits its actuator endpoint.
- Gateway does not aggregate health (no `/actuator/health/services` proxy yet) —
  re-evaluate at M5 when `metrics` lands.

### Discovery
- No service registry. DNS via compose service names is sufficient
  (`playground-backend-identity-api:18081`, `playground-backend-docs-api:18082`, etc.).
- Gateway routes are configured statically in `application.yml`. If a BC port
  changes, ADR-01's table is the source of truth — update both.

## Consequences
- Positive: Clear, enforceable rules; the BC boundary is also the network /
  data / code boundary.
- Positive: Adding a new BC requires only a new Kafka topic (per ADR-03) and a
  new gateway route — no cascading changes to siblings.
- Negative: Read-model duplication (each BC keeping its own copy of related
  data) costs storage and consistency reasoning. Acceptable for our scale and
  for the EDD pedagogy this project pursues.
- Negative: No service mesh / no automatic mTLS — backends rely on network
  isolation alone. This is fine for a personal compose stack; it is a hard
  blocker before any public deployment.

## Superseding — M2 amendments (sanctioned exceptions)

This section amends the **"Forbidden channels"** rules above for M2 onward. Two
new sanctioned access paths are introduced; everything else in the original ADR
remains in force. The "BC-to-BC HTTP is forbidden" rule continues to hold as
the default; the entries below are explicit, enumerated exceptions, not a
relaxation of the principle.

### Exception 1: `rag-ingestion` → `docs` (read-only HTTP for MD body)

**Sanctioned route:** `rag-ingestion-infra`'s WebClient may call
`GET http://playground-backend-playground-backend-docs-api:18082/internal/docs/public/{id}/body` on the docs BC.

**Allowed methods and paths (exhaustive — no others):**

| Method | Path | Returns | Purpose |
|---|---|---|---|
| `GET` | `/internal/docs/public/{id}/body` | `200 application/json {"markdown":"..."}` | Fetch the canonical MD body for chunking + embedding during ingestion. |
| `GET` | `/internal/docs/public/{id}` | `200 application/json` document metadata | Re-fetch metadata when the ingestion event is older than the current authoritative state. |

**Why HTTP and not the event payload:**
- MD body size is unbounded (documents can run to tens of KB). Putting the body
  on the `docs.document.uploaded` Kafka event would push payloads past Kafka
  broker defaults (1 MB max message size) and balloon retained log volume for
  every consumer that doesn't need the body.
- The body is **the authoritative artifact** for ingestion. Events should
  carry routing/identity, not large mutable content.
- Pulling the body on demand also lets `rag-ingestion` re-process older
  documents (re-embedding under a new model) by replaying historical events
  and fetching the current body — without keeping body history in every
  event's payload.

**Constraints on this exception:**
- **Read-only.** `rag-ingestion` MUST NOT mutate any `docs` state.
- **Internal route prefix (`/internal/**`).** docs BC routes prefixed
  `/internal/` are explicitly **not** exposed through the gateway (gateway's
  route table per ADR-07 does not forward `/internal/**`). The docs BC trusts
  the compose-internal network for this prefix.
- **No user identity propagation.** This is service-to-service traffic for
  ingestion bookkeeping; no `X-User-*` headers are forwarded. The docs BC's
  internal handler does NOT consult visibility rules — `rag-ingestion` needs
  the body for both public and private documents (visibility filtering
  happens at retrieval time per ADR-09).
- **Reliability discipline.** WebClient timeout 5s, up to 3 retries with
  exponential backoff. Permanent failure routes the in-flight ingestion event
  to `docs.document.uploaded.dlq` (per ADR-03).
- **No other BC may add internal routes without a superseding ADR.** This
  exception is enumerated here; the next sanctioned BC-to-BC HTTP path
  requires another amendment row.

This is the **first justified exception** to the cross-BC HTTP ban; it does
not implicitly authorize others. M3+ milestones referencing this section must
do so explicitly.

### Exception 2: `rag-ingestion` → `playground-redis` (distributed locks)

**Sanctioned use:** Redisson-backed `@GlobalLock` distributed locks for
ingestion idempotency (preventing duplicate embedding work when the same
`docs.document.uploaded` event is delivered twice).

**Wiring:**
- `rag-ingestion-infra` depends on `org.redisson:redisson-spring-boot-starter`.
- Connects to the same `playground-redis` container that backs the gateway's
  Spring Session (ADR-07).
- Lock keys are namespaced: `rag-ingestion:lock:document:{id}`.
- Lock TTLs cap at 5 minutes — exceeding the cap is a bug, not a config
  knob.
- Lock-port abstraction: `rag-ingestion-app/application/port/DistributedLockPort`
  with `RedissonDistributedLockAdapter` in `-infra` (per ADR-02 layering).

**Why Redis and not Postgres advisory locks:**
- Redis lock semantics (Redlock / Redisson `RLock`) are well-documented and
  testable; Postgres advisory locks tie the lock lifetime to a JDBC connection
  which complicates the async ingestion worker.
- Reusing the existing `playground-redis` container keeps the infrastructure
  footprint flat.

**Constraints:**
- The Redis instance is **shared** with Spring Session — namespace prefixes
  prevent collision. Sessions use Spring Session's default `spring:session:*`
  prefix; locks use `rag-ingestion:lock:*`. No other BC may write to the
  `rag-ingestion:lock:*` namespace.
- Other BCs adding Redis usage (locks, caches, rate-limit buckets) must add a
  per-milestone ADR row enumerating the namespace they own. Cross-namespace
  reads are forbidden by the same principle as cross-schema DB access.

### Allowed-channels table (post-amendment)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) -> gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway -> any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC -> Kafka -> BC | Kafka events | Per ADR-03 envelope |
| `rag-ingestion` -> `docs-api` `/internal/**` | HTTP (compose-internal) | **Sanctioned exception (this section)** — read-only |
| BC -> external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC -> Postgres (`playground-postgres`) | JDBC | Per ADR-05 |
| BC -> OpenSearch (`playground-opensearch`) | HTTP (REST) | Per ADR-05 amendment; per-BC index ownership |
| gateway -> Redis (`playground-redis`) | Redis protocol | Spring Session (ADR-07) |
| `rag-ingestion` -> Redis (`playground-redis`) | Redis protocol | **Sanctioned (this section)** — distributed locks, namespace `rag-ingestion:lock:*` |
| `docs-api` -> Redis (`playground-redis`) | Redis protocol | **Sanctioned (ADR-12 amendment)** — view-counter dedup, namespace `view:*` (24h TTL keys) |
| `docs-api` -> `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | **Sanctioned (ADR-12 amendment)** — boot-time owner resolution, read-only, cached for JVM lifetime |

## Amendment (2026-05-17, ADR-12)

ADR-12 (M2 Docs per-milestone) extends the **"Sanctioned exceptions"**
section above with two additions; the original "BC-to-BC HTTP is forbidden"
default and the "cross-schema database access is forbidden" default remain
in force.

### Exception 3 (additive): `docs-api` → `identity-api` (boot-time owner lookup)

The M3 → docs body-fetch exception (Exception 1 above) is the **first**
justified BC-to-BC HTTP path; ADR-12 §2 confirms its concrete route pin
(`GET /internal/docs/public/{id}/body` + `GET /internal/docs/public/{id}`).

ADR-12 §8 introduces a **second** sanctioned BC-to-BC HTTP route, distinct
from Exception 1:

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `GET` | `/internal/users/by-google-sub/{sub}` | `docs-api` | `identity-api` | Resolve the owner user id from the `PLAYGROUND_OWNER_GOOGLE_SUB` env var at docs-api boot. |

Constraints (mirror Exception 1):
- **Read-only.** docs-api MUST NOT mutate any identity state via this route.
- **Internal route prefix (`/internal/**`).** Not forwarded by the gateway
  (ADR-07's route table excludes the prefix).
- **No user identity propagation** — this is service-to-service traffic.
- **Reliability:** 2s timeout, 3 retries with backoff, fail-closed (public
  feed returns empty list if identity is unreachable; lazy retry on first
  request).
- **JVM-lifetime cache** — docs-api caches the resolved owner UUID for the
  process lifetime; operator restarts docs-api after changing the owner.

### Choice rationale (recap from ADR-12 §2 + §8)

For both exceptions, the alternative was a **read-only DB role** on the
callee BC's schema. That alternative was rejected because it breaks ADR-05's
schema-per-BC invariant and silently couples the two BCs to the callee's
SQL layout. HTTP-with-`/internal/`-prefix is the only sanctioned
cross-BC read mechanism in M2.

### Future-exception discipline

Every future BC-to-BC HTTP path requires a fresh ADR amendment row. ADR-12
adds Exception 3; the next BC that needs cross-BC HTTP writes its own
per-milestone ADR amendment to ADR-08.

See `docs/adr/12-m2-docs.md` §2 (M3 body-fetch) and §8 (owner resolution)
for the full specification.

## Amendment (2026-05-18, ADR-13) — informational, no new exception

ADR-13 (M3 RAG-Ingestion per-milestone) explicitly **does not** add a new
BC-to-BC HTTP exception. M3 P0 ships using the two exceptions ADR-12
already pinned, with no shape change:

- **Exception 1 reuse** (`rag-ingestion` → `docs-api` `/internal/**`):
  rag-ingestion's WebClient calls `GET /internal/docs/public/{id}/body`
  for the canonical body and (defensively, on visibility-change) `GET
  /internal/docs/public/{id}` for current metadata. Reliability discipline
  pinned by ADR-13 §2 mirrors ADR-12 §2 verbatim — 5 s timeout, 3 attempts,
  exponential backoff (200 ms, 400 ms, 800 ms base, jitter 0.5),
  permanent failure → `docs.document.uploaded.dlq` (per ADR-13 §8).
- **Exception 2 reuse** (`rag-ingestion` → `playground-redis`): Redisson
  `RLock` on `rag-ingestion:lock:document:{id}`, TTL ≤ 5 minutes.
  ADR-13 §5 reuses the same lock as the **serialization primitive for the
  visibility-change-before-uploaded race** — no new namespace, no new
  TTL cap, no new redis usage shape.

M3's published event `rag.document.ingested` (per ADR-13 §3) goes through
Kafka — the default sanctioned cross-BC channel. No exception required;
the topic is added to ADR-03's registry (per ADR-03's 2026-05-18
amendment).

**Future M3.1 work** (backfill `GET /internal/docs/scan?since=…` on
docs-api) will require an amendment to this ADR when it lands; it is
**not** in scope for M3 P0.

See `docs/adr/13-m3-rag-ingestion.md` §2 (retry policies), §5 (race
resolution via shared lock), §8 (DLQ topology), and amendment block §G.3
for the full M3 specification.

## Amendment 2026-05-22 (ADR-12 amendment, M6.1) — BC consolidation, exception retirement, topic reclassification

The M6.1 master amendment in **ADR-12 (2026-05-22)** dissolves the
rag-ingestion BC into docs. That collapse rewrites several rows of
ADR-08's allowed-channels table and retires one sanctioned exception
entirely. This block enumerates the changes; the original "Allowed
channels" + "Forbidden channels" defaults remain in force for every BC
pair this amendment does not touch.

### A08.1. Exception 1 (rag-ingestion → docs-api `/internal/**`) — retired

**Status flip:** Exception 1 (the `rag-ingestion` → `docs-api`
`/internal/docs/public/{id}/body` + `/internal/docs/public/{id}` HTTP
sanctioned route, originally introduced by ADR-12 §2 + ADR-13 §2) is
**retired** as of M6.1. With the rag-ingestion BC dissolved into docs,
the body fetch is an in-process JPA SELECT on `docs.documents` — no
cross-BC HTTP, no `/internal/**` round trip, no WebClient.

The `/internal/docs/public/{id}/body` and `/internal/docs/public/{id}`
routes on `docs-api` are **kept defensively** (in case a future BC needs
to revive a cross-BC body-fetch under a new ADR-08 exception), but
their only M6 caller — `rag-ingestion-infra`'s WebClient — no longer
exists. The controller file (`InternalDocumentController` in docs-api)
stays in the codebase; the route stays compose-internal (gateway does
not forward `/internal/**`); no caller exercises it in M6.1.

If a future M6.x or M7+ BC wants to call these routes, that milestone's
ADR adds a fresh ADR-08 exception row — the same discipline ADR-12
established. The route's existence today is **not** an implicit standing
authorization.

### A08.2. Exception 2 (Redis lock) — survives, namespace renamed

**Status:** Exception 2 (Redisson `RLock` for ingestion idempotency,
originally `rag-ingestion:lock:document:{id}`) **survives semantically**
— the chunking + embedding pipeline still races on duplicate Kafka
event delivery and still needs a distributed lock. The lock key
namespace renames:

- Before M6.1: `rag-ingestion:lock:document:{id}`
- After M6.1: `docs:lock:document:{id}`

The TTL cap (5 minutes), the abstraction (`DistributedLockPort` in
`docs-app` — moved from `rag-ingestion-app`; `RedissonDistributedLockAdapter`
in `docs-infra` — moved from `rag-ingestion-infra`), and the Redisson
starter coordinate are all preserved verbatim. Only the namespace name
changes.

The Redis container (`playground-redis`) is shared with Spring Session
(gateway) and the M2 view-counter dedup (`view:*` namespace, owned by
docs-api per ADR-12 amendment 2026-05-17). The new `docs:lock:*`
namespace and the existing `view:*` namespace are both owned by the
same docs-api JVM; no cross-namespace reads, same as before.

### A08.3. New direction — `docs-api` → `playground-minio` (S3 protocol)

**Sanctioned use:** the docs BC writes uploaded blobs to MinIO and
streams them back to the user on `GET /api/docs/{id}/source` + to the
extraction worker on the in-BC `docs.document.extraction-requested`
listener (ADR-12 amendment A12.4 + A12.5).

| Direction | Channel | Notes |
|---|---|---|
| `docs-api` → `playground-minio:9000` | HTTP / S3 protocol via MinIO Java SDK 8.5.x | **Sanctioned (this amendment)** — single-bucket access (`playground-docs-originals`); object key = `{document_id}.{ext}`; auth via shared `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` env vars; cascade-delete in the same transaction as the row delete (orphan cleanup via nightly `@Scheduled` job). |

**Why MinIO and not Postgres BYTEA or `text` body alone:** ADR-12
amendment A12.4 covers the full rationale. Summary: the multipart
upload bytes need to survive the request-response cycle so the async
worker can read them after the controller returns, and the user-visible
"download original" affordance needs streamable access. Postgres BYTEA
in `docs.documents` would push the 1 GiB-per-row TOAST ceiling into
view at corpus scale; a sidecar object store is the standard answer.

**No other BC may access this MinIO instance.** The same single-tenant
invariant as the Redis namespace rule (A08.2) applies — future BCs
needing object storage either (a) declare a new MinIO bucket they own
exclusively, or (b) provision a separate object-store sidecar. The
M6.1 amendment claims `playground-docs-originals` for docs and no
other.

### A08.4. Allowed-channels table (post-A08.1, A08.2, A08.3)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) -> gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway -> any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC -> Kafka -> BC | Kafka events | Per ADR-03 envelope |
| ~~`rag-ingestion` -> `docs-api` `/internal/**`~~ | ~~HTTP (compose-internal)~~ | **Retired by A08.1** — BC consolidated into docs |
| BC -> external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC -> Postgres (`playground-postgres`) | JDBC | Per ADR-05 |
| BC -> OpenSearch (`playground-opensearch`) | HTTP (REST) | Per ADR-05 amendment; per-BC index ownership |
| gateway -> Redis (`playground-redis`) | Redis protocol | Spring Session (ADR-07) |
| `docs-api` -> Redis (`playground-redis`) — `view:*` namespace | Redis protocol | Sanctioned (ADR-12 amendment 2026-05-17) — view-counter dedup |
| `docs-api` -> Redis (`playground-redis`) — `docs:lock:*` namespace | Redis protocol | **Sanctioned (this amendment §A08.2)** — distributed lock for ingestion idempotency (renamed from `rag-ingestion:lock:*`) |
| `docs-api` -> `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | Sanctioned (ADR-12 amendment 2026-05-17) — boot-time owner resolution |
| `docs-api` -> `playground-minio:9000` (S3) | HTTP / S3 protocol | **Sanctioned (this amendment §A08.3)** — single bucket `playground-docs-originals` |
| `rag-chat-api` -> cross-schema SELECT into `docs.*` + `identity.*` | JDBC (cross-schema) | Sanctioned (ADR-14 amendment) — narrowed in M6.1 (search_path drops `rag`); see A08.5 below |

### A08.5. Cross-schema SELECT exception (ADR-14) — narrowed scope

ADR-14's cross-schema SELECT exception (Exception 2 in informal
notation — the rag-chat per-turn retrieval reads `rag.document_chunks` +
`docs.documents` + `identity.users` via JDBC) **narrows** as part of M6.1:

- `rag.document_chunks` → `docs.document_chunks` (per ADR-05 amendment
  2026-05-22 — the `rag` schema is dropped).
- rag-chat's Hikari `connection-init-sql` changes from
  `SET search_path TO chat,docs,rag,identity,public` → `SET search_path
  TO chat,docs,identity,public`.
- The three cross-schema predicates in ADR-14's table reduce to **two
  schemas** (`docs.*` + `identity.*`) instead of three (`rag.*` +
  `docs.*` + `identity.*`). The retrieval predicate now hits
  `docs.document_chunks` instead of `rag.document_chunks` — the JOIN
  with `docs.documents` becomes intra-schema.

This is a tightening, not a relaxation: the exception's surface drops
from three schemas to two. No new exception is created; the
chunks-table move is a SQL detail that flows through ADR-14's
existing sanction.

### A08.6. Kafka topic registry — informational cross-reference

The `docs.document.uploaded`, `docs.document.visibility-changed`, and
`docs.document.deleted` topics shift from **cross-BC** (docs →
rag-ingestion in M3) to **in-BC** (docs producer, docs consumer) in
M6.1. They continue to use Spring Modulith externalization → Kafka
(operability + DLQ + cross-thread-pool decoupling), but they no longer
participate in any cross-BC contract.

A new in-BC topic `docs.document.extraction-requested` is added per
ADR-12 amendment A12.8.

The `rag.document.ingested` topic (ADR-13 §3) is **retired** —
no consumer (M4 reads chunks via cross-schema SELECT, not via this event).

ADR-03's topic registry receives these updates as part of the M6.1 PR
set (the ADR-03 amendment block is added separately if material; the
shape changes are descriptive and don't supersede ADR-03's naming +
envelope invariants).

### A08.7. Future-exception discipline (unchanged)

Every future BC-to-BC HTTP path still requires a fresh ADR amendment
row. M6.1's net effect on the cross-BC HTTP exception count is **-1**
(Exception 1 retired; no new HTTP exception added). The MinIO direction
(A08.3) is a new sanctioned external-service direction, not a BC-to-BC
HTTP path; it does not affect the BC-to-BC HTTP exception count.

See `docs/adr/12-m2-docs.md` amendment 2026-05-22 §A12.1 + §A12.4 +
§A12.8 for the full M6.1 specification.

## Amendment 2026-05-22 (ADR-17, M7) — Exception 4 introduced for LLM function-calling

The M7 PR set (ADR-17 — `docs/adr/17-m7-rag-chat-tool-calling.md`)
introduces a new sanctioned BC-to-BC HTTP exception — **Exception 4**
— for the rag-chat tool-calling infrastructure. The existing M6.1
amendments above (§A08.1–§A08.7) remain in force; this block layers
Exception 4 on top.

### A08.8. Exception 4 — `rag-chat-api` → tool-implementer BCs (HTTP)

**Sanctioned route:** `rag-chat-infra`'s `WebClientToolDispatcher` may
call any `POST /internal/tools/<tool-name>` endpoint on any
tool-implementer BC registered in `rag-chat-domain.ToolCatalog`.

**Justification:** synchronous, user-facing chat requires the tool
result to be folded back into the next LLM turn within the same SSE
connection; Kafka's async semantics break the LLM context flow. Tool
dispatch IS the chat synchronous critical path — it cannot be moved
off-thread without breaking the chat surface's M4 invariants.

**Allowed methods and paths (per-tool-BC sub-row table):**

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/<tool-name>` | `rag-chat-api` | tool BC (`-api`) | LLM-driven tool invocation; one sub-row per registered tool BC. |

**Sub-rows at M7 ship:** none (`ToolCatalog.descriptors()` returns
empty list). M8 adds the first sub-row:

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/generate-massing` | `rag-chat-api` | `massing-gen-api` | Brief PDF → `.3dm` massing (M8). |

Subsequent tool BCs add their own sub-rows when their per-milestone
ADR lands (e.g., ADR-19 for the second tool BC, etc.). The pattern
mirrors how Exception 3 lists its docs→identity row as a single
named endpoint — Exception 4 is a template that grows by one sub-row
per tool BC.

**Constraints on Exception 4:**

- **One direction only.** `rag-chat-api` → tool BC. Tool BCs MUST NOT
  call back into `rag-chat-api` over HTTP. (They may publish Kafka
  events on their own topics if needed; that's outside Exception 4's
  scope.)
- **Internal route prefix (`/internal/**`).** Tool BC routes prefixed
  `/internal/` are explicitly **not** exposed through the gateway
  (gateway's route table per ADR-07 does not forward `/internal/**`,
  matching Exception 3's shape).
- **User identity propagation IS performed.** `X-User-Id` and
  `X-User-Sub` headers are forwarded (the originating chat user's
  identity, sourced from the SSE request). This is **different from
  Exception 1's "no user identity propagation" rule** (Exception 1
  was ingestion bookkeeping; Exception 4 is user-facing tool
  invocation — the tool BC needs the user identity to do
  tenant-scoped reads, e.g., reading the brief doc).
- **Reliability discipline.** WebClient timeout = the descriptor's
  `timeout` value (per-descriptor, not a single global value);
  Resilience4j circuit breaker per descriptor (thresholds verbatim
  mirror ADR-14 §4's `spark-gateway` breaker — 50% / 60s window /
  30s OPEN / 1 half-open probe). No WebClient-level retries — the
  per-tool breaker handles burst isolation, and the LLM handles
  retry-with-correction via the depth-bounded multi-turn loop (max
  depth 5, env-overridable per ADR-17 §6).
- **Each new tool BC requires a sub-row.** Exception 4 is a template,
  not a wildcard. Adding a new tool BC requires the new BC's
  per-milestone ADR to add its specific sub-row to the table above.

### A08.9. Allowed-channels table (post-A08.8)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) → gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway → any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC → Kafka → BC | Kafka events | Per ADR-03 envelope |
| BC → external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC → Postgres (`playground-postgres`) | JDBC | Per ADR-05 |
| BC → OpenSearch (`playground-opensearch`) | HTTP (REST) | Per ADR-05 amendment |
| gateway → Redis (`playground-redis`) | Redis protocol | Spring Session (ADR-07) |
| `docs-api` → Redis (`playground-redis`) — `view:*` + `docs:lock:*` namespaces | Redis protocol | Sanctioned (ADR-12 amendment + M6.1 amendment A08.2) |
| `docs-api` → `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | Sanctioned (ADR-12 amendment 2026-05-17) — Exception 3 |
| `docs-api` → `playground-minio:9000` | HTTP / S3 protocol | Sanctioned (M6.1 amendment A08.3) |
| `rag-chat-api` → cross-schema SELECT into `docs.*` + `identity.*` | JDBC (cross-schema) | Sanctioned (ADR-14 amendment; M6.1-narrowed to 2 schemas — A08.5) |
| **`rag-chat-api` → tool BC `-api` `/internal/tools/<name>`** | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.8) — Exception 4.** Per-tool-BC sub-row required when the tool BC ships. |

### A08.10. Future-exception discipline (unchanged)

Every future BC-to-BC HTTP path still requires a fresh ADR amendment
row. M7's net effect on the cross-BC HTTP exception count is **+1**
(Exception 4 introduced as a template; 0 sub-rows at M7 ship; 1
sub-row added by M8). The exception **type** count is now 2
(Exception 3 surviving; Exception 4 introduced). Exception 1 was
retired in M6.1; Exception 2 (Redis lock) was retired in M6.1 and
folded into the docs-owned Redis namespace surface.

See `docs/adr/17-m7-rag-chat-tool-calling.md` §9 and §A08.x for the
full Exception 4 wire-shape specification.

## Amendment 2026-05-22 (ADR-18, M8) — Exception 4 first sub-row + Exception 5 introduced

The M8 PR set (ADR-18 — `docs/adr/18-m8-massing-gen.md`) introduces
the `massing-gen` BC, the first concrete `ToolCatalog` consumer. Two
distinct amendments to this ADR land in the M8 PR set:

1. **Exception 4** gains its **first sub-row** —
   `rag-chat-api` → `massing-gen-api` `POST /internal/tools/generate-massing`.
   The template ADR-17 §A08.8 established is now materialized.
2. **Exception 5** is **introduced** — `massing-gen-api` → `docs-api`
   for brief body read. ADR-18 §5 rejected (a) reviving the retired
   Exception 1, (b) cross-schema SELECT (M4 ADR-14 §3 pattern) — in
   favor of (c) a fresh exception with identity-propagation enabled.

The existing M7 amendment block (§A08.8–§A08.10) remains in force;
this block layers two changes on top.

### §A08.11. Exception 4 — first sub-row added

The M7 amendment §A08.8 introduced Exception 4 as a template with 0
sub-rows ("Sub-rows at M7 ship: none. M8 adds the first sub-row").
M8 adds that first sub-row:

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/generate-massing` | `rag-chat-api` | `massing-gen-api` | Brief PDF → `.3dm` massing (M8). |

The Exception 4 template constraints (§A08.8) apply verbatim:

- One direction only (`rag-chat-api` → tool BC; never the reverse).
- Internal `/internal/**` route prefix; gateway does not forward.
- `X-User-Id` + `X-User-Sub` headers forwarded (the originating chat
  user's identity, sourced from the SSE request).
- WebClient timeout = descriptor's `timeout` (60 s for
  `generate_massing` per ADR-18 §20).
- Per-tool Resilience4j breaker `tool-generate_massing` auto-registered
  by the M7 dispatcher (ADR-17 §5).
- No WebClient-level retries — the breaker handles burst isolation,
  the LLM handles retry-with-correction via the depth-bounded
  multi-turn loop.

### §A08.12. Exception 5 — `massing-gen-api` → `docs-api` (brief body read)

**Sanctioned route:** `massing-gen-infra`'s `HttpBriefBodyAdapter`
WebClient may call **two routes** on the docs BC:

| Method | Path | Returns | Purpose |
|---|---|---|---|
| `GET` | `/internal/docs/public/{id}` | `200 application/json` document metadata (`id`, `title`, `extraction_status`, `visibility`, `owner_user_id`) | Pre-check that the brief is `extraction_status='completed'` and visible to the caller; surface the brief title for the `Content-Disposition` slug. |
| `GET` | `/internal/docs/public/{id}/body` | `200 application/json {"markdown":"..."}` | Fetch the brief body (Markdown — PDF-extracted via M6.1's Vision OCR pipeline, written by docs-api's async extraction worker per ADR-12 §A12.5). |

**Justification (matching ADR-18 §5):**

- M8's brief-body fetch needs the PDF-extracted Markdown body, which
  is authoritatively held by the docs BC (`docs.documents.body`).
- Cross-schema SELECT was considered (M4 ADR-14 §3 pattern reused)
  and **rejected**: the M8 body-fetch runs once per tool call (not
  per token), so the ~5 ms HTTP hop is invisible against the LLM
  extraction call (10–30 s). HTTP preserves BC isolation —
  `massing-gen-api` never learns the `docs.documents` column layout.
- M6.1 retired Exception 1 (the rag-ingestion → docs body-fetch
  exception) but **defensively preserved** the `/internal/docs/public/{id}`
  + `/internal/docs/public/{id}/body` routes in `docs-api` "in
  expectation of a future BC reviving cross-BC body-fetch under a new
  ADR-08 exception" (verbatim from §A08.1). Exception 5 is that
  future revival; zero docs-api code change is required.

**Why this is NOT a revival of Exception 1:**

- **Exception 1 explicitly forbade user-identity propagation**
  ("No user identity propagation" — §82). Its semantic was
  service-to-service ingestion bookkeeping. Exception 5 **requires**
  user-identity propagation (Exception 4 forwards `X-User-Id` /
  `X-User-Sub` from the originating chat user; massing-gen
  forwards those same headers to docs-api for ownership/visibility
  checks).
- **Exception 1's caller (rag-ingestion-api) was retired** in M6.1.
  Reviving an exception slot whose original caller no longer exists
  would muddy the amendment history; a fresh Exception 5 keeps the
  audit trail clean.
- **The two exceptions sit at different layers** of the call chain.
  Exception 1 was a Kafka-consumer-driven background path; Exception
  5 is a synchronous tool-dispatch-driven user-facing path. Bundling
  them under one exception number would conflate distinct trust
  postures.

**Constraints on Exception 5:**

- **Read-only.** `massing-gen` MUST NOT mutate any docs state via
  these routes.
- **Internal route prefix (`/internal/**`).** Not forwarded by the
  gateway (ADR-07's route table excludes the prefix).
- **User identity propagation IS performed.** `X-User-Id` and
  `X-User-Sub` are forwarded; the docs-api body controller does not
  consult them today (M6.1's visibility check lives on the public
  list/read endpoints, not on the internal route — by ADR-12 §2's
  framing, the internal route returns the body regardless of
  visibility, because the original Exception 1 caller needed both
  public and private bodies for ingestion). The authoritative
  tenant-isolation lives in the **caller** (`massing-gen-app`'s
  metadata-then-body two-call sequence): if the metadata response
  shows `visibility='private' AND owner_user_id != X-User-Id`, the
  caller rejects the brief with `BRIEF_NOT_ACCESSIBLE` and never
  invokes the body call.
- **Reliability discipline.** WebClient timeout **5 s**, up to **3
  retries** with exponential backoff (200/400/800 ms base, jitter
  0.5) — mirrors Exception 1's ADR-12 §2 + ADR-13 §2 discipline.
  Permanent failure → ADR-18 §7's `BRIEF_FETCH_FAILED` (HTTP 502).
- **No dedicated circuit breaker for docs-api.** docs-api is a
  healthy compose-network BC. If availability becomes a concern,
  M8.1 may add a `docs-api` breaker.
- **Sub-row growth.** Unlike Exception 4's per-tool-BC template
  grammar, Exception 5 is a per-caller-BC exception. Future BCs
  needing docs-api body access would either reuse this exception
  (adding a row to the caller table below) **or** open Exception 6
  with a different access shape — depending on whether
  identity-propagation semantics match. M8 ships with one caller
  (`massing-gen-api`); the table is single-row.

| Caller | Callee | Endpoint(s) | Purpose |
|---|---|---|---|
| `massing-gen-api` | `docs-api` | `GET /internal/docs/public/{id}` + `GET /internal/docs/public/{id}/body` | Brief body read for `generate_massing` (ADR-18). |

### §A08.13. Allowed-channels table (post-A08.11 + A08.12)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) → gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway → any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC → Kafka → BC | Kafka events | Per ADR-03 envelope |
| BC → external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC → Postgres (`playground-postgres`) | JDBC | Per ADR-05 |
| BC → OpenSearch (`playground-opensearch`) | HTTP (REST) | Per ADR-05 amendment |
| gateway → Redis (`playground-redis`) | Redis protocol | Spring Session (ADR-07) |
| `docs-api` → Redis (`playground-redis`) — `view:*` + `docs:lock:*` namespaces | Redis protocol | Sanctioned (ADR-12 amendment + M6.1 amendment A08.2) |
| `docs-api` → `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | Sanctioned (ADR-12 amendment 2026-05-17) — Exception 3 |
| `docs-api` → `playground-minio:9000` | HTTP / S3 protocol | Sanctioned (M6.1 amendment A08.3) |
| `rag-chat-api` → cross-schema SELECT into `docs.*` + `identity.*` | JDBC (cross-schema) | Sanctioned (ADR-14 amendment; M6.1-narrowed to 2 schemas — A08.5) |
| `rag-chat-api` → tool BC `-api` `/internal/tools/<name>` | HTTP (compose-internal) | Sanctioned (M7 amendment §A08.8) — Exception 4. Sub-row table — `generate_massing` added by this amendment §A08.11. |
| **`massing-gen-api`** → `docs-api` `/internal/docs/public/{id}` + `/internal/docs/public/{id}/body` | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.12) — Exception 5.** Brief body read; identity-propagation enabled (distinct from retired Exception 1). |
| **`massing-gen-api`** → `rhino3dm-bridge:4000` | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.14, informational) — external-sidecar direction, not BC-to-BC.** Resilience4j `rhino3dm-bridge` breaker per ADR-18 §17. |

### §A08.14. New external-sidecar direction (informational)

The `rhino3dm-bridge` sidecar is a compose-internal external service
(like `spark-inference-gateway` and `playground-minio`), not a sibling
BC. The `massing-gen-api → rhino3dm-bridge` HTTP direction is
classified the same way the M6.1 `docs-api → playground-minio`
direction was — a sanctioned external-service direction, not a
BC-to-BC HTTP exception. It does not count against the BC-to-BC
exception count.

### §A08.15. Future-exception discipline + cumulative count

Every future BC-to-BC HTTP path still requires a fresh ADR amendment
row. The cumulative BC-to-BC exception count after M8:

- ~~Exception 1~~ — **retired in M6.1.**
- ~~Exception 2~~ — **retired in M6.1** (folded into docs-owned
  Redis namespace).
- Exception 3 (docs-api → identity-api): **active**, 1 sub-row.
- Exception 4 (rag-chat-api → tool BCs): **active**, **1 sub-row**
  (`generate_massing` — added by §A08.11).
- Exception 5 (massing-gen-api → docs-api): **active**, NEW by
  §A08.12, 1 sub-row.

Net M8 effect: **+1** new BC-to-BC exception type (Exception 5),
**+1** sub-row under Exception 4. M9+ tool BCs add their own
Exception 4 sub-rows; any future BC needing docs-api body access
adds a row to Exception 5's caller table (or opens Exception 6 if
the semantics differ).

See `docs/adr/18-m8-massing-gen.md` §3 + §5 + §17 for the full M8
inter-service specification.
