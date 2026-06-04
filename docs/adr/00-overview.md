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
| 01 | `01-msa-gradle-structure.md` | Gradle multi-module monorepo, JDK, ports. **Amendments:** M6.1 (ADR-12 §A01.1–§A01.5 — rag-ingestion quadruplet retired; port 18083 freed), M8 (ADR-18 §A01.6–§A01.10 — `massing-gen-{api,app,domain,infra}` quadruplet added; port 18083 reclaimed by massing-gen-api; module count 22 → 26), M8 Python flip (§A01.11–§A01.14 — polyglot policy, Java module count back to 22, single Python container per BC), **ADR-19 (§A01.15–§A01.17 — `agent-tools` multi-BC Python host supersedes one-container-per-Python-BC; `massing-gen-api` service retired → `agent-tools` on port 18083; Python LLM-tool BCs default to a directory module inside the host)** |
| 02 | `02-ddd-layering.md` | DDD package layout per service |
| 03 | `03-kafka-conventions.md` | Kafka KRaft, topic naming, event envelope |
| 04 | `04-spring-ai-and-llm-backend.md` | Spring AI version + spark-inference-gateway wiring |
| 05 | `05-data-store.md` | Postgres 16 + pgvector, schema-per-service. **Amendments:** M2 (OpenSearch projection + reserved object-storage slot), ADR-12 (OpenSearch pin), ADR-13 (HNSW + body_checksum), ADR-14 (chat schema + cross-schema SELECT exception), M6.1 (ADR-12 §A05.1–§A05.4 — `rag` schema retired and merged into `docs`; MinIO sidecar `minio-playground`), **M8 (ADR-18 §A05.5–§A05.8 — `arch` schema + `arch.outputs` table with BYTEA file_bytes; no new cross-schema SELECT exception)** |
| 06 | `06-frontend-stack.md` | Next.js + Feature-Sliced Design |
| 07 | `07-gateway-oauth.md` | Spring Cloud Gateway as OAuth2 Client (Google) |
| 08 | `08-inter-service-comms.md` | Gateway-to-BC HTTP, BC-to-BC Kafka only. **Amendments:** M2 (ADR-12, Exception 3 docs→identity), M3 (ADR-13, informational only), M6.1 (ADR-12 §A08.1–§A08.7 — Exception 1 retired, Exception 2 namespace renamed, MinIO direction §A08.3), M7 (ADR-17 §A08.8 — Exception 4 introduced for rag-chat → tool BCs HTTP; template with per-tool-BC sub-row), **M8 (ADR-18 §A08.11–§A08.15 — Exception 4 first sub-row `rag-chat-api → massing-gen-api`; Exception 5 introduced for `massing-gen-api → docs-api` brief body read; rhino3dm-bridge external-sidecar direction)** |
| 09 | `09-public-route-policy.md` | Public route allowlist, anonymous identity, public RAG chat rate limits |
| 10 | `10-m1-identity.md` | **(per-milestone, M1)** Identity implementation — library versions, gateway filter ordering, `POST /users/bootstrap` mechanics, `PLAYGROUND_ANON` cookie attributes, `identity.users` schema, Spring Modulith Events outbox (inherited by M2+) |
| 11 | `11-shared-exception-hierarchy.md` | `shared-kernel` exception hierarchy — `AbstractException` + six HTTP-typed subclasses + reflective `ExceptionCreator` + unified `@RestControllerAdvice` |
| 12 | `12-m2-docs.md` | **(per-milestone, M2)** Docs BC implementation — BlockNote versions + SSR strategy, OpenSearch 2.18 + native client + Nori analyzer, Spring Modulith outbox (inherited from M1), M3 → docs body-fetch HTTP exception, docs → identity owner-lookup HTTP exception, per-IP rate limits for anonymous reads, body size cap 1 MB, view dedup 24h, nightly counter resync via Spring `@Scheduled`. Amends ADR-05, ADR-08, ADR-09. **Amendment 2026-05-22 (M6.1):** rag-ingestion BC dissolved into docs (chunking + embedding + pgvector now in docs-api); async PDF/MD extraction via dedicated `ExecutorService(n=5)` + SSE; MinIO sidecar `minio-playground` for original-blob retention (`playground-docs-originals` bucket); new column `extraction_status` on `docs.documents`; new in-BC topic `docs.document.extraction-requested`; supersedes ADR-13 in full; amends ADR-01, ADR-05, ADR-08, ADR-16. |
| 13 | `13-m3-rag-ingestion.md` | **(per-milestone, M3 — SUPERSEDED by ADR-12 amendment 2026-05-22)** RAG-Ingestion BC implementation — chunking 800-token windows + 120-token overlap (configurable), embedding retry 3 attempts with exponential backoff + jitter, DLQ per source topic (`<topic>.dlq`, 14-day retention), ingestion-complete signal as new Kafka event `rag.document.ingested` via Spring Modulith outbox (inherited from M1/M2), pgvector HNSW (m=16, ef_construction=64) + `(user_id, visibility)` prefilter indexes, `body_checksum` denormalized per chunk row, race resolution via shared Redisson lock, port 18083 (actuator-only, host-not-exposed), no new ADR-08 exception (Exceptions 1+2 reused verbatim). Amends ADR-03 (topic registry + DLQ retention), ADR-05 (chunk DDL + ef_search runtime hint), ADR-08 (informational — no new exception). **As of M6.1 (2026-05-22) the rag-ingestion BC is dissolved into the docs BC; every concrete pin in this ADR carries over to docs but the BC boundary, the module quadruplet, the port number, and the `rag.document.ingested` topic are retired. The current source of truth is `docs/adr/12-m2-docs.md` (amendment 2026-05-22).** |
| 14 | `14-m4-rag-chat.md` | **(per-milestone, M4)** RAG-Chat BC implementation — Spring WebFlux SSE controller (`POST /api/rag/chat`) on port 18084 (gateway-routable), first sanctioned cross-schema SELECT exception (rag-chat reads `rag.document_chunks` + `docs.documents` + `identity.users` via JDBC with `search_path = chat,docs,rag,identity`), Resilience4j 2.2 circuit breaker (`spark-gateway`, 50% / 60s / 30s OPEN), Redisson `RRateLimiter` per-user token bucket (60/hour + 200/day) + `RLock` per-user concurrent-stream cap (latest-wins, 35s TTL), Qwen3-32B streaming via Spring AI `ChatClient.stream()` with K=6 retrieval and 200+2400+24576+4000 token budget, fire-and-forget auto-title (pinned Qwen3-32B prompt, temperature 0.1, max_tokens 24), cite-persistence policy = only `[N]`-cited subset, drop-oldest-pair history truncation, no Kafka surface (BC neither produces nor consumes), `chat` schema added. Amends ADR-04 (informational — ChatClient streaming exercised), ADR-05 (`chat` schema + cross-schema SELECT exception), ADR-09 (allowlist row swap + rate-limit section rewrite + auth-lock badge convention). **Amendment 2026-05-22 (M7, ADR-17 §A14):** SSE grammar reconciled — three new standalone event names (`tool_call` / `tool_result` / `tool_error`) introduced; placeholder `phase.step` values `"tool_call"` / `"tool_result"` from spec §5.2 PR C annotation retired (never shipped — `ChatTurnService` emits only `step: "retrieval"`); `ChatStreamEvent` sealed interface gains three permitted subtype records; `phase.step` discriminator domain closed to progress-class values; `error` event 5-value enum + abort-path persistence semantics unchanged. |
| 15 | `15-m5-metrics.md` | **(per-milestone, M5)** Metrics BC implementation — Spring WebFlux 4-route HTTP surface (`/api/metrics/{dashboard,services,timeseries,logs}`) on port 18085 (gateway-routable), 4-container observability stack added to compose (Prometheus v2.54.1 + Loki 3.2.1 + Alloy v1.3.1 + cAdvisor v0.49.1), PromQL whitelist constants in `metrics-domain` (no raw PromQL exposed; metric-id → template substitution after allowlist check only), Recharts as the dashboard chart library, public dashboard + authenticated logs endpoint (one new row in ADR-09's authenticated section), per-IP rate limit 30/min on `/dashboard` + per-user rate limit 60/min on `/logs` (Redisson `RRateLimiter`), 10s per-PromQL-query budget with per-widget `"degraded": true` partial-response degradation, observability self-monitoring (4 extra service-health cells in P0), HEAD `/v1/models` probe of spark-inference-gateway (15s cached), cAdvisor + Alloy docker sockets mounted **read-only** with the residual privilege-escalation surface documented in `docs/infra-requirements/be.md`, **no Postgres schema** (stateless BC — ADR-05 NOT amended), **no Kafka surface** (BC neither produces nor consumes — ADR-03 NOT amended), **no new ADR-08 HTTP exception** (Prometheus/Loki/spark-gateway are external infra, not sibling BCs). Amends `docs/roadmap.md` §M5 (retire acceptance bullets 4+5, refresh bullet list), ADR-09 (logs auth row), ADR-00 (index + module count bump 22 → 26 + topic matrix `metrics.snapshot.captured` row removal + ASCII art port 18086 → 18085), `docs/infra-requirements/be.md` (new M5 Observability Stack section). |
| 16 | `16-m6-docs-pdf.md` | **(per-milestone, M6)** Docs BC PDF extension — Apache PDFBox 3.0.4 for text extraction + per-page PNG rendering (150 DPI / RGB) for Vision OCR fallback, Spring AI 1.0 GA `ChatClient` + `Media`-attached `UserMessage` for Vision (Qwen3-VL-30B-A3B via `spark-inference-gateway`, env `SPRING_AI_VISION_MODEL`), hybrid per-page algorithm (PDFBox first; OCR fallback when extracted text < 30 chars), three-tier input validation (extension → Content-Type → magic bytes), 25 MB multipart cap + 200-page total + 30-OCR-page two-cap strategy, 6 new `DocsErrorCode` rows mapped to 400 (`INVALID_FILE_TYPE` / `PDF_CORRUPTED` / `PDF_ENCRYPTED` / `PDF_TOO_MANY_PAGES` / `PDF_TOO_MANY_OCR_PAGES` / `FILE_TOO_LARGE`), `mime_type` column added to `docs.documents` via Flyway (`text/markdown` default; PDF rows write `application/pdf`), `DocumentBody.MAX_OCTET_LENGTH` raised from 1 MB to 10 MB uniformly (M3 body-fetch `maxInMemorySize` raised in lockstep to 12 MB), original PDF bytes discarded (no `documents.binary_blob` column; M2.1 may revisit), no new BC + no new module + no new port (docs-api still 18082), **no new ADR-08 exception** (Vision LLM goes via existing `BC → spark-inference-gateway` channel), **no M3 code change** (PDF-derived bodies are Markdown-opaque to ingestion), Vision call adapter (`VisionOcrAdapter`) + PDF extractor (`PdfExtractorAdapter`) placed in `docs-infra` per ADR-02. Amends ADR-04 (Vision modality introduction — informational, no semantic change), ADR-00 (this index row + module count unchanged). **Amendment 2026-05-22 (M6.1):** hybrid algorithm retired (every page goes to Vision OCR — PDFBox text-extraction path dropped, only `PDFRenderer` retained); page cap collapses to single 100-total (200/30 two-cap retired; `PDF_TOO_MANY_OCR_PAGES` enum constant retired); synchronous request-thread extraction retired in favor of async `ExecutorService(n=5)` + SSE (per ADR-12 §A12.5); original PDF bytes now retained in MinIO sidecar (`§13` partially reversed); per-page Vision timeout 30s → 60s. |
| 17 | `17-m7-rag-chat-tool-calling.md` | **(per-milestone, M7)** RAG-Chat tool-calling infrastructure — Spring AI 1.0.0 GA `ChatClient.prompt(...).tools(callbacks).stream()` for LLM function-calling (reused from ADR-04 + ADR-14 pin, no version bump), `ToolCatalog` constants class in `rag-chat-domain` exposing immutable `ToolDescriptor` records (`name`, `description`, `parameterSchema`, `endpoint`, `timeout` — Spring-free, P0 zero entries; M8 lands the first descriptor), `WebClientToolDispatcher` adapter in `rag-chat-infra` with **per-tool Resilience4j circuit breaker** (named `tool-<descriptor>`; thresholds verbatim mirror ADR-14 §4's `spark-gateway` breaker — 50% / 60 s sliding window / minimum 10 calls / OPEN 30 s / half-open 1 probe), 7-value `ToolErrorCode` enum (`TIMEOUT` / `CIRCUIT_OPEN` / `MAX_DEPTH` / `UPSTREAM_4XX` / `UPSTREAM_5XX` / `SCHEMA_INVALID` / `INTERNAL`), three new standalone SSE event names (`tool_call` / `tool_result` / `tool_error`; **not** `phase.step` discriminators — placeholder `step` values retired per ADR-14 §A14), `tool_call.id` correlation IDs (Spring-AI-pass-through when native function-calling is used; ULID `call_<26-char>` server-generated in the manual fallback per §1.1), depth cap default **5** with env `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH` override, tool result max size **16 KiB** with truncate-and-warn over-cap policy + env `PLAYGROUND_RAG_CHAT_TOOL_MAX_RESULT_BYTES` override, `X-User-Id` + `X-User-Sub` headers forwarded to tool BCs (`Authorization` and cookies are NOT forwarded), no new Postgres schema (M7 P0 = in-memory-only tool-call traces — `chat.tool_calls` deferred to M7.1), no new Kafka surface (rag-chat remains non-producer / non-consumer), no new module / no new port / no new external dependency (Resilience4j 2.2.0 and Spring AI 1.0.0 GA both reused). Amends ADR-08 (introduces Exception 4 — `rag-chat-api` → tool-implementer BCs HTTP, template with per-tool-BC sub-row; 0 sub-rows at M7 ship, +1 sub-row M8 with `massing-gen-api`; see §A08.8), ADR-14 §5.2 (SSE grammar reconciliation — placeholder `step: "tool_call"` / `step: "tool_result"` values retired, standalone event names introduced; see §A14), ADR-00 (this row, module count unchanged). |
| 18 | `18-m8-massing-gen.md` | **(per-milestone, M8; Python flip A18.1–A18.9)** `massing-gen` BC — first concrete `ToolCatalog` consumer + first end-to-end domain vertical. New 4-module quadruplet `massing-gen-{api,app,domain,infra}` on port **18083** (reclaimed from M6.1 retirement; spec §6's `18086` was stale — owned by metrics-api per ADR-01 §A01.3). `POST /internal/tools/generate-massing` (Exception 4 first sub-row) + `GET /api/arch/outputs/{id}` owner-only download. New `arch` schema + `arch.outputs` table (BYTEA file_bytes per §12; orphan cleanup = untouched per §13). Brief body fetch over **fresh ADR-08 Exception 5** (`massing-gen-api → docs-api /internal/docs/public/{id}/body` — NOT a revival of M6.1-retired Exception 1; identity-propagation enabled). `BriefProgramExtractor` uses Spring AI 1.0.0 GA `ChatClient` (M4/M7 uniformity) on Qwen3-32B with networknt JSON Schema validator 1.5.3. `MassingAlgorithm` rectangular-first-fit (Spring-free, `-domain`) with default `maxFloors=10` → throws on over-area. `Rhino3dmAdapter` calls new `rhino3dm-bridge` Node 18-alpine sidecar (`rhino3dm@8.4.0`) at compose-internal port 4000; per-sidecar Resilience4j breaker `rhino3dm-bridge` (50%/60s, 30s OPEN — mirrors `spark-gateway`). `MassingErrorCode` enum (7 values) carried in `tool_error.message` via **`<CODE>: <message>`** prefix grammar (no M7 schema extension). `summary` field is Korean-fixed `"%d실 · %d층 · 총 %.0f m²"`. `MassingTool.DESCRIPTOR` registered in `rag-chat-domain.ToolCatalog` (single-line addition; the only rag-chat file the M8 PR touches). Amends ADR-01 (§A01.6–§A01.10 — module count 22 → 26 + port 18083 reclaimed), ADR-05 (§A05.5–§A05.8 — `arch` schema), ADR-08 (§A08.11–§A08.15 — Exception 4 first sub-row + Exception 5 introduced), `docs/roadmap.md` §M8 (PRD + ADR-18 references; stale 18086 corrected to 18083; stale "Exception 1 widened" corrected to "fresh Exception 5"). No M4 / M7 code touched beyond ToolCatalog 1-line addition. **Amendment 2026-06-04 (ADR-19 §A18.10–§A18.11):** single-service `massing-gen` framing superseded — BC renamed `massing-gen` → `architecture`; service `massing-gen-api` → multi-BC Python host `agent-tools` (port 18083 preserved); `/api/arch/**` + `arch` schema + `arch.outputs` contract + `generate_massing` tool name + `/internal/tools/generate-massing` path all preserved; linear `MassingWorkflow` + `ceil(total ÷ full-lot-area)` floor-count formula superseded in direction (LangGraph Phase 2 + 건폐율 algorithm fix + MinIO/audit persistence Phase 3). |
| 19 | `19-agent-tools-host-and-architecture-bc.md` | **(architecture pivot, Phase-1 decision/gate)** `agent-tools` Python multi-BC host + `massing-gen` → `architecture` BC rename + LangGraph adoption policy. **D1** — the Python side stops being one-service-per-BC: a single deployable host `agent-tools` (port 18083, was `massing-gen-api`) hosts multiple small LLM-tool BCs as self-contained directory modules `backend/fastapi/agent-tools/<bc>/`; a deliberate, scoped divergence from ADR-01's one-BC-one-service rule (Python-side, LLM-tools only; shared failure/deploy/scaling domain accepted to avoid duplicating the Python/LLM/LangGraph stack and to bound the polyglot footprint to one service). **D2** — one-shot rename, no compat period: BC `massing-gen` → `architecture`, service/host/image `massing-gen-api` → `agent-tools`, dir `backend/fastapi/massing-gen/` → `backend/fastapi/agent-tools/architecture/`; PRESERVED: port 18083, schema `arch`, route prefix `/api/arch/**`, `arch.outputs` contract, tool name `generate_massing`, `/internal/tools/generate-massing` + `/outputs/{id}` paths; atomic change-set enumerated (rag-chat `MassingTool` host, gateway route `uri`/`id`, compose block + healthcheck). **D3** (hard invariant) — orchestration stays in rag-chat (ADR-17); `agent-tools` BCs are tools, not orchestrators; a BC's internal graph must not become a cross-tool orchestrator. **D4** — LangGraph adopted for tool-internal non-linear flow only (pydantic-graph considered, owner chose LangGraph for graph-mgmt + visualization/observability); guardrail: do not wrap a still-linear pipeline in a graph. **D5** roadmap — Phase 1 = this ADR (rename gate); Phase 2 = install LangGraph + migrate the current linear `architecture` flow to a behavior-identical minimal graph (de-risk/reference, no gold-plating); Phase 3 = real branching/looping graph + algorithm fix (apply 건폐율, separate basement uses — fixes the M8 KFI "3 rooms · 1 floor · 31,000 m²" bug) + persistence (MinIO object key in `arch.outputs` + persist extracted inputs for audit) — Phase-3 DDL deferred to a later amendment. Amends ADR-01 (§A01.15–§A01.17), ADR-17 (§A17.1–§A17.2), ADR-18 (§A18.10–§A18.11), ADR-00 (this row). |
| 20 | `20-tool-artifacts-as-message-attachments.md` | **(tool artifacts → chat message attachments; Phase-3b decision/gate)** Tool-produced files (the M8 `.3dm`, future slide/image) are persisted by **rag-chat** as **message attachments in MinIO**, not by the tool BC. **D1** — new `Attachment` domain concept in rag-chat-domain (Message aggregates it) + table `chat.message_attachments` holding **MinIO storageKey + metadata only (no bytes)**, incl. `contentType` (drives type-aware FE preview) + `sizeBytes`/`filename`/`toolName`. No separate audit table. **D2** — tool→dispatcher contract gains an optional **non-LLM `artifact`** `{filename, contentType, base64}` alongside the LLM-visible `result`; ToolDispatcher feeds only `result` to the LLM (bytes never enter the LLM context) and routes `artifact` to MinIO + an Attachment. **D3** — rag-chat owns storage: new `BlobStoragePort` + `MinioBlobStorageAdapter` (`libs.minio`, `minio-playground`, mirrors docs ADR-12 §A12.4), key `chat/{sessionId}/{messageId}/{attachmentId}-{filename}`. **D4** — download relocates to rag-chat `GET /api/rag/chat/attachments/{id}` (owner-only, 404 on mismatch, RFC-6266); gateway `/api/arch/**` removed; `architecture` BC becomes a **stateless generator** (`{result, artifact}`; `arch.outputs` BYTEA + `/outputs/{id}` retired, `arch` schema dropped). **D5** — FE renders a `contentType`-driven `AttachmentCard` (not a bare link): `.3dm`→metadata+download, `image/*`→inline preview, `application/json`/text→preview, else generic. Amends ADR-17, ADR-14, ADR-18, ADR-19, ADR-00 (this row). |

### Module count (post-ADR-01 v2, ADR-02 v2; bumped by ADR-15 for the M5 metrics quadruplet; reduced by ADR-12 amendment 2026-05-22 M6.1)

Each BC ships as a **four-module quadruplet** (`*-api`, `*-app`, `*-domain`,
`*-infra`) per ADR-01 v2. Total at M5 (post-ADR-15): gateway + shared-kernel
+ (6 BCs × 4 modules) = **26 production modules** + `buildSrc` convention
plugins.

**Post-M6.1 (2026-05-22):** the `rag-ingestion-*` quadruplet is retired
(rag-ingestion BC dissolved into docs per ADR-12 amendment); module count
drops to **5 BCs × 4 modules + gateway + shared-kernel = 22 production
modules** + `buildSrc`. Port 18083 returns to the reservation pool.

**Post-M8 (2026-05-22):** the `massing-gen-*` quadruplet is added (ADR-18
amendment §A01.6); module count climbs back to **6 BCs × 4 modules +
gateway + shared-kernel = 26 production modules** + `buildSrc`. Port
18083 is reclaimed by `massing-gen-api`. The runnable port table:

- gateway 18080
- identity-api 18081
- docs-api 18082 (now also hosts the chunking + embedding + Vision OCR
  pipeline; runs the M6.1 async extraction worker on a dedicated
  `ExecutorService(n=5)` per ADR-12 amendment §A12.6)
- **massing-gen-api 18083** (NEW — ADR-18 §A01.7; reclaims the slot
  the retired `rag-ingestion-api` freed in M6.1)
- rag-chat-api 18084
- (reserved) 18085 — still reserved for next BC
- metrics-api 18086

The rest are Java libraries linked into the BC's `*-api` fat jar.

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

### Topic-to-BC matrix (post-M6.1 — 2026-05-22)

| Topic | Producer | Consumer(s) |
|---|---|---|
| `identity.user.registered` | identity | (future: notifications) |
| `docs.document.extraction-requested` | docs (upload controller via Modulith outbox) | docs (ExtractionWorkflow listener) — **in-BC** |
| `docs.document.uploaded` | docs (extraction worker via Modulith outbox) | docs (IngestionService listener) — **in-BC** (was cross-BC pre-M6.1) |
| `docs.document.visibility-changed` | docs | docs (chunk visibility update listener) — **in-BC** (was cross-BC pre-M6.1) |
| `docs.document.deleted` | docs | docs (chunk cascade delete + MinIO cleanup listeners) — **in-BC** (was cross-BC pre-M6.1) |
| `docs.document.extraction-requested.dlq` | docs (DLQ recoverer) | operator triage |
| `docs.document.uploaded.dlq` | docs (DLQ recoverer; was rag-ingestion pre-M6.1) | operator triage |
| `docs.document.visibility-changed.dlq` | docs (DLQ recoverer; was rag-ingestion pre-M6.1) | operator triage |
| `docs.document.deleted.dlq` | docs (DLQ recoverer; was rag-ingestion pre-M6.1) | operator triage |
| ~~`rag.document.ingested`~~ | ~~rag-ingestion~~ | **Retired by ADR-12 amendment 2026-05-22** — never had a consumer (M4 reads chunks via cross-schema SELECT, not via this event) |

> *(ADR-15 amendment, 2026-05-19): the prior `metrics.snapshot.captured` topic row was a forward-looking placeholder under the retired "polling + Redis cache" framing for M5. ADR-15 confirms the M5 metrics BC publishes no events and consumes none — the row is retired. No M5 Kafka surface exists.)*

> *(ADR-12 amendment, 2026-05-22, M6.1): the three pre-M6.1 cross-BC topics (`docs.document.{uploaded,visibility-changed,deleted}`) reclassify as in-BC topics — the rag-ingestion BC that consumed them is dissolved into docs (ADR-12 §A12.1). They continue to use Spring Modulith externalization → Kafka for operability + DLQ + cross-thread-pool decoupling, but they no longer participate in any cross-BC contract.)*

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
