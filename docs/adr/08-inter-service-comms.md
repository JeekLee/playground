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
| BC -> Postgres (`postgres-playground`) | JDBC | Per ADR-05 |
| BC -> Redis (`redis-playground`) | Redis protocol | Only the gateway uses Redis today (sessions); BCs may add usage if justified per-milestone |

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
  (`identity:18081`, `docs:18082`, etc.).
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
`GET http://docs-api:18082/internal/docs/public/{id}/body` on the docs BC.

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

### Exception 2: `rag-ingestion` → `redis-playground` (distributed locks)

**Sanctioned use:** Redisson-backed `@GlobalLock` distributed locks for
ingestion idempotency (preventing duplicate embedding work when the same
`docs.document.uploaded` event is delivered twice).

**Wiring:**
- `rag-ingestion-infra` depends on `org.redisson:redisson-spring-boot-starter`.
- Connects to the same `redis-playground` container that backs the gateway's
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
- Reusing the existing `redis-playground` container keeps the infrastructure
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
| BC -> Postgres (`postgres-playground`) | JDBC | Per ADR-05 |
| BC -> OpenSearch (`opensearch-playground`) | HTTP (REST) | Per ADR-05 amendment; per-BC index ownership |
| gateway -> Redis (`redis-playground`) | Redis protocol | Spring Session (ADR-07) |
| `rag-ingestion` -> Redis (`redis-playground`) | Redis protocol | **Sanctioned (this section)** — distributed locks, namespace `rag-ingestion:lock:*` |
