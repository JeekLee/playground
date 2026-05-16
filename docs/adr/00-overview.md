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
              +------------+--+  +--+----------+  +-+-----------+  +------------+
              | identity      |  | docs        |  | rag-chat    |  | metrics    |
              | (18081)       |  | (18082)     |  | (18084)     |  | (18086)    |
              +------------+--+  +--+----------+  +------+------+  +-----+------+
                           |        |                    ^               |
                           |        |                    |               |
                           |        | docs.document.     |               |
                           |        |  uploaded          |               |
                           |        v                    |               |
                           |  +-----+------+         +---+---------+     |
                           |  | rag-       |         |             |     |
                           |  | ingestion  |         |             |     |
                           |  | (18083)    |         |             |     |
                           |  +-----+------+         |             |     |
                           |        |                |             |     |
                           |        | rag.chunk.     |             |     |
                           |        |  embedded      |             |     |
                           |        +---> pgvector --+             |     |
                           |                                        |     |
                           |  identity.user.registered ------------>+ ... |
                           v                                              v
                    +-------------------- Kafka (KRaft, 19092) -----------+

   shared-kernel (compile-time only) <-- imported by all services for event envelope
                                         + common DTOs

   External (host process):
     spark-inference-gateway @ host.docker.internal:10080  (vLLM, OpenAI-compatible)
       used by: rag-ingestion (BGE-M3 embeddings), rag-chat (Qwen3-32B generation)
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
- Positive: New contributor (or new agent) can read 9 short ADRs and know the full
  system shape.
- Negative: Any change to a transverse decision requires an explicit superseding
  ADR — this is intentional friction.

## Diagrams

A FigJam context-map diagram is **not yet generated**. When produced (optional),
link it here with the Figma URL.
