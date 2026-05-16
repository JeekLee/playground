# ADR-00: Architecture Decision Records — Overview & Index

## Status
Accepted

## Context
The `playground/` repo is a long-lived personal web service grown feature-by-feature
as a Gradle multi-module monorepo of Spring Boot bounded contexts (BCs) behind a
Spring Cloud Gateway. The overall architecture was decided by the human in
`docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`; this ADR set
formalizes each decision with concrete versions, ports, and naming conventions.

These ADRs are **transverse** — they apply to the whole system. Per-milestone ADRs
(`docs/adr/NN-mX-<slug>.md`) layer on top and may supersede individual transverse
decisions when a milestone justifies it.

## Decision

### Index

| ADR | File | Topic |
|---|---|---|
| 00 | `00-overview.md` | This index + module dependency map |
| 01 | `01-msa-gradle-structure.md` | Gradle multi-module monorepo, JDK, ports |
| 02 | `02-ddd-layering.md` | DDD package layout per service |
| 03 | `03-kafka-conventions.md` | Kafka KRaft, topic naming, event envelope |
| 04 | `04-spring-ai-and-llm-backend.md` | Spring AI version + spark-inference-gateway wiring |
| 05 | `05-data-store.md` | Postgres 16 + pgvector, schema-per-service |
| 06 | `06-frontend-stack.md` | Next.js + Feature-Sliced Design |
| 07 | `07-gateway-oauth.md` | Spring Cloud Gateway as OAuth2 Client (Google) |
| 08 | `08-inter-service-comms.md` | Gateway-to-BC HTTP, BC-to-BC Kafka only |
| 09 | `09-public-route-policy.md` | Public route allowlist, anonymous identity, public RAG chat rate limits |
| 10 | `10-m1-identity.md` | **(per-milestone, M1)** Identity implementation — library versions, gateway filter ordering, `POST /users/bootstrap` mechanics, `PLAYGROUND_ANON` cookie attributes, `identity.users` schema, Spring Modulith Events outbox (inherited by M2+) |
| 11 | `11-shared-exception-hierarchy.md` | `shared-kernel` exception hierarchy — `AbstractException` + six HTTP-typed subclasses + reflective `ExceptionCreator` + unified `@RestControllerAdvice` |

### Module count (post-ADR-01 v2, ADR-02 v2)

Each BC ships as a **four-module quadruplet** (`*-api`, `*-app`, `*-domain`,
`*-infra`) per ADR-01 v2. Total at M5: gateway + shared-kernel + (5 BCs × 4
modules) = **22 production modules** + `buildSrc` convention plugins. Only the
six `*-api` modules (plus gateway) bind a JVM port; the rest are Java libraries
linked into the BC's `*-api` fat jar.

### Module dependency graph (compile-time + runtime)

```
                         +---------------------+
                         |   client (Next.js)  |
                         +----------+----------+
                                    | HTTPS (cookie session)
                                    v
                         +----------+----------+
                         |   gateway (18080)   |   <- OAuth2 Client (Google)
                         |   Spring Cloud GW   |       Session: Redis-playground
                         +-+--------+--------+-+
                           |        |        |        injects:
                           |        |        |          X-User-Id
                           |        |        |          X-User-Email
                           |        |        |          X-User-Sub
                           v        v        v
        +------------------+--+  +--+----------------+  +-+----------------+  +------------------+
        | identity-api 18081  |  | docs-api 18082    |  | rag-chat-api     |  | metrics-api      |
        | + identity-app      |  | + docs-app        |  |   18084          |  |   18086          |
        | + identity-domain   |  | + docs-domain     |  | + rag-chat-app   |  | + metrics-app    |
        | + identity-infra    |  | + docs-infra      |  | + rag-chat-domain|  | + metrics-domain |
        +------------------+--+  +--+----------------+  | + rag-chat-infra |  | + metrics-infra  |
                           |        |                   +--------+---------+  +--------+---------+
                           |        |                            ^                     |
                           |        | docs.document.uploaded     |                     |
                           |        |                            |                     |
                           |        +-> rag-ingestion-api 18083  |                     |
                           |            + rag-ingestion-app      |                     |
                           |            + rag-ingestion-domain   |                     |
                           |            + rag-ingestion-infra    |                     |
                           |                |          ^         |                     |
                           |                |          | HTTP    |                     |
                           |                |          | (sanctioned exception,        |
                           |                |          |  ADR-08 amendment:            |
                           |                |          |  GET /internal/documents/...) |
                           |                |          +---------+ docs-api            |
                           |                |                                          |
                           |                | rag.chunk.embedded                       |
                           |                +---> pgvector ---------------+            |
                           |                                              |            |
                           |  identity.user.registered ------------------>+ ...        |
                           v                                                           v
                    +-------------------- Kafka (KRaft, 19092) -----------+------------+

   shared-kernel (compile-time only) <-- imported by all services for:
                                         - EventEnvelope<T>
                                         - AbstractException + 6 HTTP subclasses (ADR-11)
                                         - Unified @RestControllerAdvice (ADR-11)
                                         - Shared value-object types (ADR-08)

   buildSrc (Gradle convention plugins) <-- consumed by every module's build.gradle.kts

   External (host process):
     spark-inference-gateway @ host.docker.internal:10080  (vLLM, OpenAI-compatible)
       used by: rag-ingestion-infra (BGE-M3 embeddings), rag-chat-infra (Qwen3-32B generation)

   Data stores (compose-internal):
     postgres-playground:5432  (Postgres 16 + pgvector, schema-per-BC, ADR-05)
     redis-playground:6379     (Spring Session + rag-ingestion locks, ADR-07, ADR-08 amendment)
     opensearch-playground:9200 (search projection from M2+, ADR-05 amendment)
     objectstore-playground:?  (reserved for M2.1, vendor unpinned, ADR-05 amendment)
```

### Topic-to-BC matrix (initial set, full names per ADR-03)

| Topic | Producer | Consumer(s) |
|---|---|---|
| `identity.user.registered` | identity | (future: notifications) |
| `docs.document.uploaded` | docs | rag-ingestion |
| `docs.document.deleted` | docs | rag-ingestion |
| `rag.chunk.embedded` | rag-ingestion | (future: analytics) |
| `metrics.snapshot.captured` | metrics | (none — internal) |

## Consequences
- Positive: Single source of truth for cross-cutting choices; per-milestone ADRs
  stay short by referencing transverse ones.
- Positive: New contributor (or new agent) can read the ADR set and know the full
  system shape.
- Negative: Any change to a transverse decision requires an explicit superseding
  ADR — this is intentional friction.

## Diagrams

A FigJam context-map diagram is **not yet generated**. When produced (optional),
link it here with the Figma URL.
