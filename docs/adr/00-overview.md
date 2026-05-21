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
| 12 | `12-m2-docs.md` | **(per-milestone, M2)** Docs BC implementation — BlockNote versions + SSR strategy, OpenSearch 2.18 + native client + Nori analyzer, Spring Modulith outbox (inherited from M1), M3 → docs body-fetch HTTP exception, docs → identity owner-lookup HTTP exception, per-IP rate limits for anonymous reads, body size cap 1 MB, view dedup 24h, nightly counter resync via Spring `@Scheduled`. Amends ADR-05, ADR-08, ADR-09. |
| 13 | `13-m3-rag-ingestion.md` | **(per-milestone, M3)** RAG-Ingestion BC implementation — chunking 800-token windows + 120-token overlap (configurable), embedding retry 3 attempts with exponential backoff + jitter, DLQ per source topic (`<topic>.dlq`, 14-day retention), ingestion-complete signal as new Kafka event `rag.document.ingested` via Spring Modulith outbox (inherited from M1/M2), pgvector HNSW (m=16, ef_construction=64) + `(user_id, visibility)` prefilter indexes, `body_checksum` denormalized per chunk row, race resolution via shared Redisson lock, port 18083 (actuator-only, host-not-exposed), no new ADR-08 exception (Exceptions 1+2 reused verbatim). Amends ADR-03 (topic registry + DLQ retention), ADR-05 (chunk DDL + ef_search runtime hint), ADR-08 (informational — no new exception). |
| 14 | `14-m4-rag-chat.md` | **(per-milestone, M4)** RAG-Chat BC implementation — Spring WebFlux SSE controller (`POST /api/rag/chat`) on port 18084 (gateway-routable), first sanctioned cross-schema SELECT exception (rag-chat reads `rag.document_chunks` + `docs.documents` + `identity.users` via JDBC with `search_path = chat,docs,rag,identity`), Resilience4j 2.2 circuit breaker (`spark-gateway`, 50% / 60s / 30s OPEN), Redisson `RRateLimiter` per-user token bucket (60/hour + 200/day) + `RLock` per-user concurrent-stream cap (latest-wins, 35s TTL), Qwen3-32B streaming via Spring AI `ChatClient.stream()` with K=6 retrieval and 200+2400+24576+4000 token budget, fire-and-forget auto-title (pinned Qwen3-32B prompt, temperature 0.1, max_tokens 24), cite-persistence policy = only `[N]`-cited subset, drop-oldest-pair history truncation, no Kafka surface (BC neither produces nor consumes), `chat` schema added. Amends ADR-04 (informational — ChatClient streaming exercised), ADR-05 (`chat` schema + cross-schema SELECT exception), ADR-09 (allowlist row swap + rate-limit section rewrite + auth-lock badge convention). |
| 15 | `15-m5-metrics.md` | **(per-milestone, M5)** Metrics BC implementation — Spring WebFlux 4-route HTTP surface (`/api/metrics/{dashboard,services,timeseries,logs}`) on port 18085 (gateway-routable), 4-container observability stack added to compose (Prometheus v2.54.1 + Loki 3.2.1 + Alloy v1.3.1 + cAdvisor v0.49.1), PromQL whitelist constants in `metrics-domain` (no raw PromQL exposed; metric-id → template substitution after allowlist check only), Recharts as the dashboard chart library, public dashboard + authenticated logs endpoint (one new row in ADR-09's authenticated section), per-IP rate limit 30/min on `/dashboard` + per-user rate limit 60/min on `/logs` (Redisson `RRateLimiter`), 10s per-PromQL-query budget with per-widget `"degraded": true` partial-response degradation, observability self-monitoring (4 extra service-health cells in P0), HEAD `/v1/models` probe of spark-inference-gateway (15s cached), cAdvisor + Alloy docker sockets mounted **read-only** with the residual privilege-escalation surface documented in `docs/infra-requirements/be.md`, **no Postgres schema** (stateless BC — ADR-05 NOT amended), **no Kafka surface** (BC neither produces nor consumes — ADR-03 NOT amended), **no new ADR-08 HTTP exception** (Prometheus/Loki/spark-gateway are external infra, not sibling BCs). Amends `docs/roadmap.md` §M5 (retire acceptance bullets 4+5, refresh bullet list), ADR-09 (logs auth row), ADR-00 (index + module count bump 22 → 26 + topic matrix `metrics.snapshot.captured` row removal + ASCII art port 18086 → 18085), `docs/infra-requirements/be.md` (new M5 Observability Stack section). |
| 16 | `16-m6-docs-pdf.md` | **(per-milestone, M6)** Docs BC PDF extension — Apache PDFBox 3.0.4 for text extraction + per-page PNG rendering (150 DPI / RGB) for Vision OCR fallback, Spring AI 1.0 GA `ChatClient` + `Media`-attached `UserMessage` for Vision (Qwen3-VL-30B-A3B via `spark-inference-gateway`, env `SPRING_AI_VISION_MODEL`), hybrid per-page algorithm (PDFBox first; OCR fallback when extracted text < 30 chars), three-tier input validation (extension → Content-Type → magic bytes), 25 MB multipart cap + 200-page total + 30-OCR-page two-cap strategy, 6 new `DocsErrorCode` rows mapped to 400 (`INVALID_FILE_TYPE` / `PDF_CORRUPTED` / `PDF_ENCRYPTED` / `PDF_TOO_MANY_PAGES` / `PDF_TOO_MANY_OCR_PAGES` / `FILE_TOO_LARGE`), `mime_type` column added to `docs.documents` via Flyway (`text/markdown` default; PDF rows write `application/pdf`), `DocumentBody.MAX_OCTET_LENGTH` raised from 1 MB to 10 MB uniformly (M3 body-fetch `maxInMemorySize` raised in lockstep to 12 MB), original PDF bytes discarded (no `documents.binary_blob` column; M2.1 may revisit), no new BC + no new module + no new port (docs-api still 18082), **no new ADR-08 exception** (Vision LLM goes via existing `BC → spark-inference-gateway` channel), **no M3 code change** (PDF-derived bodies are Markdown-opaque to ingestion), Vision call adapter (`VisionOcrAdapter`) + PDF extractor (`PdfExtractorAdapter`) placed in `docs-infra` per ADR-02. Amends ADR-04 (Vision modality introduction — informational, no semantic change), ADR-00 (this index row + module count unchanged). |

### Module count (post-ADR-01 v2, ADR-02 v2; bumped by ADR-15 for the M5 metrics quadruplet)

Each BC ships as a **four-module quadruplet** (`*-api`, `*-app`, `*-domain`,
`*-infra`) per ADR-01 v2. Total at M5 (post-ADR-15): gateway + shared-kernel
+ (6 BCs × 4 modules) = **26 production modules** + `buildSrc` convention
plugins. Only the **six** `*-api` modules (plus gateway) bind a JVM port
(gateway 18080; identity-api 18081; docs-api 18082; rag-ingestion-api 18083 —
actuator-only; rag-chat-api 18084; metrics-api 18085); the rest are Java
libraries linked into the BC's `*-api` fat jar.

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
        | + identity-app      |  | + docs-app        |  |   18084          |  |   18085          |
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
| `docs.document.visibility-changed` | docs | rag-ingestion |
| `docs.document.deleted` | docs | rag-ingestion |
| `docs.document.uploaded.dlq` | rag-ingestion (DLQ recoverer) | operator triage |
| `docs.document.visibility-changed.dlq` | rag-ingestion (DLQ recoverer) | operator triage |
| `docs.document.deleted.dlq` | rag-ingestion (DLQ recoverer) | operator triage |
| `rag.document.ingested` | rag-ingestion | (future: M4 readiness) |

> *(ADR-15 amendment, 2026-05-19): the prior `metrics.snapshot.captured` topic row was a forward-looking placeholder under the retired "polling + Redis cache" framing for M5. ADR-15 confirms the M5 metrics BC publishes no events and consumes none — the row is retired. No M5 Kafka surface exists.)*

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
