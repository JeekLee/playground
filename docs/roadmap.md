# Roadmap

| Milestone | Bounded context | Goal | Status |
|---|---|---|---|
| M0 | Bootstrap | Manual scaffolding: Gradle multi-module + gateway shell + shared-kernel + compose with Kafka/Postgres/Redis. No features. | planned |
| M1 | Identity | Google OAuth handled by gateway; user records in identity service; `/me` endpoint; session cookie | planned |
| M2 | Docs | MD document hosting (replaces user's git blog) — upload, list, render | planned |
| M3 | RAG-Ingestion | Kafka consumer for `document.uploaded` → chunk → embed (BGE-M3 via spark-inference-gateway) → store in pgvector | planned |
| M4 | RAG-Chat | Chatbot UI + retrieval + generation (Qwen3-32B via spark-inference-gateway), conversation history | planned |
| M5 | Metrics | Spark REST polling + Docker container status dashboard | planned |
| M6 | Docs (PDF support) | M2 docs BC accepts PDF; Apache PDFBox text extraction feeds M3 unchanged | planned |
| M7 | RAG-Chat (tool-calling) | rag-chat invokes external tool BCs via Spring AI 1.0 function-calling; generic infra | planned |
| M8 | massing-gen | New BC: brief PDF → room program → basic massing → .3dm via rhino3dm sidecar | planned |

---

## M0 — Bootstrap

**Goal:** Stand up an empty but runnable platform skeleton so every later milestone can ship by adding a single bounded-context module.

**Acceptance:**
- [ ] Gradle multi-module monorepo exists at `api/` with `gateway` + `shared-kernel` subprojects compiling
- [ ] `infra/docker-compose.yml` boots gateway + Kafka (KRaft) + Postgres (with pgvector extension enabled) + Redis cleanly
- [ ] `curl http://localhost:<gateway-port>/actuator/health` returns `UP` on a fresh `docker compose up`
- [ ] `extra_hosts: ["host.docker.internal:host-gateway"]` is wired in compose so services can reach `spark-inference-gateway` at `host.docker.internal:10080`
- [ ] `.env.example` enumerates every env var consumed by compose; image tags are pinned (no `latest`)

**Dependencies:** none.

**Notes:** Executed manually by the human, not by agents. Bootstrap guide will live at `docs/bootstrap.md`. Agent teams come online only after M0 verification.

---

## M1 — Identity

**Goal:** Sign users in with Google through the gateway, persist their identity, and expose the current user to every downstream service.

**Acceptance:**
- [ ] User clicks "Login with Google" → completes OAuth → lands back on the app authenticated
- [ ] `GET /me` returns the current user's id / email / displayName / avatarUrl behind auth
- [ ] First-time login creates a row in `identity.users`; subsequent logins reuse it and update `lastLoginAt`
- [ ] Backend services receive `X-User-Id` and `X-User-Email` headers on every authenticated request (verified via a probe endpoint)
- [ ] Unauthenticated request to a protected route returns 401 (or redirects to login flow at the frontend)

**Dependencies:** M0.

**Notes:** Full PRD lives at `docs/prd/M1-identity.md`. OAuth dance is owned by the gateway (Spring Cloud Gateway + Spring Security OAuth2 Client). The identity service itself is a thin user-record CRUD + `/me` resolver and does not see Google tokens directly.

---

## M2 — Docs

**Goal:** Replace the user's existing git-based blog with a hosted Markdown document service inside playground.

**Acceptance:**
- [ ] Authenticated user can upload a `.md` file (or paste MD) and receive a stable document id
- [ ] `GET /docs` returns the calling user's document list (id, title, createdAt, updatedAt)
- [ ] `GET /docs/{id}` returns rendered HTML (or raw MD for the frontend to render) of a single document
- [ ] On successful upload, the docs service publishes a `document.uploaded` Kafka event using the shared-kernel envelope (consumed later by M3)
- [ ] Document storage is scoped per user — one user cannot read another user's documents

**Dependencies:** M0, M1.

**Notes:** The `document.uploaded` event contract is the hard interface between M2 and M3 and must be frozen in the shared-kernel before M3 starts. Schema lands in architect's per-milestone ADR for M2.

**Public surface (per ADR-09 / design system spec §11):** M2 introduces the `visibility` column on `docs.documents` (default `private`). Public list/read endpoints (`GET /api/docs/public/**`) serve only documents where `visibility = 'public'`. A `docs.document.visibility-changed` Kafka event is published when an author toggles visibility, so the RAG pipeline can re-tag chunks (consumed in M3).

---

## M3 — RAG-Ingestion

**Goal:** Turn every uploaded document into searchable vector chunks automatically, with no user-facing UI.

**Acceptance:**
- [ ] `rag-ingestion` service consumes `document.uploaded` from Kafka and acks idempotently (re-delivery does not double-insert)
- [ ] Documents are split into chunks (size + overlap configurable) before embedding
- [ ] Each chunk is embedded via `spark-inference-gateway` using BGE-M3 at `host.docker.internal:10080`
- [ ] Chunks + vectors are stored in pgvector with `(document_id, chunk_index, user_id, embedding, text)` and a vector index suitable for cosine similarity
- [ ] An ingestion-complete signal (event or DB flag) is emitted so M4 can know which documents are queryable

**Dependencies:** M0, M2.

**Notes:** Depends on M2's `document.uploaded` event envelope from the shared kernel. No direct HTTP from M3 to M2 — Kafka only. Embedding model + endpoint are external (spark-inference-gateway); failure modes (gateway down, timeout) need explicit retry/backoff policy in the per-milestone ADR.

**Public surface (per ADR-09):** Chunks inherit the parent document's `visibility` at ingestion time. On `docs.document.visibility-changed` (public ↔ private), the consumer re-tags every chunk of that document so the visibility filter on the retrieval side stays correct.

---

## M4 — RAG-Chat

**Goal:** Give an authenticated visitor a chatbot whose answers are grounded in the playground corpus the caller is allowed to read (all community-wide public documents from every author, plus the caller's own private documents).

**Acceptance:**
- [ ] Frontend chat UI lets an authenticated visitor start a conversation, send a message, and stream back a model response
- [ ] On each user turn, the backend embeds the query (BGE-M3), retrieves top-K chunks from pgvector per the M4 retrieval contract below, and constructs a prompt for Qwen3-32B
- [ ] Generation runs against `spark-inference-gateway` (Qwen3-32B) using Spring AI; responses stream to the client as Server-Sent Events
- [ ] Conversation history is persisted server-side and reloadable across browser sessions for the authenticated caller
- [ ] Responses cite which document(s) / chunk(s) backed the answer (id + title at minimum) with inline `[N]` markers and an expandable accordion

**Dependencies:** M0, M1, M2, M3.

**Notes:** Retrieval scope is governed by the M4 retrieval contract (see `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8 + `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract" + ADR-14): every public chunk is in scope for every authenticated caller; private chunks are in scope only for their author when that author is the caller (matched by `X-User-Id`). No caller can ever retrieve another user's private chunks. This is the first milestone that exercises both LLM endpoints of `spark-inference-gateway` end-to-end. The auth-only invariant is the controlling supersession of the previous "anonymous or signed-in" framing — anonymous chat is a permanent non-goal (P2).

**Public surface (per ADR-09 amendment in ADR-14):** This milestone ships **one** chat endpoint: `POST /api/rag/chat`, **authenticated-only**. Gateway returns 401 on missing `X-User-Id`. The retrieval corpus is fixed per-caller:
- Authenticated (`X-User-Id` present): `WHERE visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`.

Per-user token bucket (60/hour, 200/day), `max_tokens=4000`, K=6 retrieved chunks, Resilience4j circuit breaker on `spark-inference-gateway` 5xx > 50% in 60s. Concrete numbers and the ADR-09 amendment land in ADR-14.

---

## M5 — Metrics

**Goal:** Give the operator (the user) a single public dashboard for the health of the playground stack, container resources, JVM state, HTTP traffic, host metrics, and the spark-inference-gateway.

**Acceptance (revised 2026-05-19 by ADR-15 — bullets 4 + 5 of the prior list retired):**
- [ ] `metrics` BC queries Prometheus + Loki for stack health (PromQL via HTTP API; LogQL via HTTP API)
- [ ] Frontend dashboard at `/metrics` renders ~19 widgets covering service health, host metrics, JVM heap, HTTP request rate, and spark-inference-gateway state — 15s polling
- [ ] Dashboard is **public** (per ADR-09); logs API endpoint (`/api/metrics/logs/**`) is authenticated
- [ ] **Observability stack added** — Prometheus + Loki + Alloy + cAdvisor (4 new compose containers); concrete image pins in ADR-15 §3–§6
- [ ] Sidebar "System status" row unlocks on M5 ship

**Dependencies:** M0, M1, M2, M3, M4.

**Notes:** Infra-heavy: needs the docker socket mounted **read-only** into both `cadvisor-playground` and `alloy-playground`; that constraint goes into `docs/infra-requirements/be.md` for the M5 cycle (per ADR-15 §G.4). Polling interval, retention periods, and per-IP / per-user rate limits are tunable env vars (per ADR-15 §18).

**Public surface (per ADR-09 + ADR-15 amendment):** The metrics dashboard is a **public** page. We accept that this exposes container/cluster health to anyone — it's an intentional "live workshop" signal, not a leak. `/api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries` are public read endpoints; `/api/metrics/logs/**` is authenticated (logs may carry PII / error context not appropriate for anon viewers — per ADR-09 + ADR-15 §G.2). Per-IP rate limit 30/min on `/api/metrics/dashboard`; per-user rate limit 60/min on `/api/metrics/logs`.

---

## M6 — Docs (PDF support)

**Goal:** Extend M2's docs BC to accept `.pdf` uploads. Server extracts text via Apache PDFBox and stores it in the existing `body` column; M3 RAG ingestion (Kafka consumer + chunking + embedding) processes the extracted text unchanged.

**Acceptance:**
- [ ] `POST /api/docs/upload` accepts `application/pdf` MIME type
- [ ] `docs.documents.mime_type` column added (default `text/markdown`); PDF uploads set `application/pdf`
- [ ] Extracted text appears in `body` and M3 ingests it identically to MD content
- [ ] Frontend `/docs/new` file picker accepts `.pdf`; doc detail page shows `(PDF)` indicator
- [ ] Apache PDFBox runs in-process (no new container needed)

**Dependencies:** M0, M2.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §4. Per-milestone ADR-16 closes open questions (PDFBox version pin, Korean PDF test corpus, corrupted-PDF error semantic, original-binary storage decision).

---

## M7 — RAG-Chat (tool-calling)

**Goal:** Give rag-chat (M4) the ability to invoke external tool BCs via LLM function-calling. Generic infrastructure — domain-neutral — so any future tool BC plugs in via the same pattern.

**Acceptance:**
- [ ] `ToolCatalog` constants class in `rag-chat-domain` lists registered tools (name, description, parameter schema, endpoint URL, timeout)
- [ ] `ToolDispatcher` adapter in `rag-chat-infra` invokes tool BCs via Spring WebClient with Resilience4j circuit breaker per tool
- [ ] `ChatTurnUseCase` extended with Spring AI 1.0 `ChatClient.tools(...)` — handles multi-turn tool_call → tool_result → token flow
- [ ] SSE event grammar gains `tool_call` / `tool_result` / `tool_error` event types (amends ADR-14 §5.2)
- [ ] ADR-08 Exception 4 added (rag-chat → tool BCs HTTP for LLM function-calling)
- [ ] Maximum tool-call depth (default 5) enforced; exceeded depth emits `tool_error` with code `MAX_DEPTH_EXCEEDED`
- [ ] WireMock-stubbed end-to-end test validates the full flow without a real tool BC

**Dependencies:** M0, M1, M2, M3, M4.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §5. Per-milestone ADR-17 closes open questions (Spring AI 1.0 API stability, tool error retry policy, max-depth default, tool result max size, SSE event ordering during multi-turn). M7 itself adds no user-visible feature — it enables M8.

---

## M8 — massing-gen

**Goal:** Ship the first domain-specific tool BC — given a brief PDF document ID, extract the room program via LLM, run a basic massing algorithm, and return a Rhino `.3dm` file URL.

**Acceptance:**
- [ ] New BC `massing-gen-{api,app,domain,infra}` quadruplet (port 18086 candidate, ADR-18 confirms)
- [ ] `POST /internal/tools/generate-massing` accepts `{ briefDocId, siteWidth?, siteDepth?, floorHeight? }` and returns `{ fileUrl, programJson, totalAreaM2, floorCount, summary }`
- [ ] Brief reading via M2's existing `/internal/docs/{id}/body` (ADR-08 Exception 1 widened — see ADR-18)
- [ ] LLM call (Qwen3-32B via spark-inference-gateway) extracts structured room program JSON from brief text
- [ ] `MassingAlgorithm` in `-domain` (Spring-free) implements rectangular first-fit + area balance
- [ ] `rhino3dm-bridge` Node sidecar container serializes box list → `.3dm` binary
- [ ] New Postgres schema `arch` + table `arch.outputs` stores file_bytes (BYTEA), program_json (JSONB), brief_doc_id, user_id, total_area_m2, floor_count, created_at
- [ ] `GET /api/arch/outputs/{id}` authenticated owner-only download endpoint
- [ ] Tool descriptor registered in `rag-chat-domain.ToolCatalog`
- [ ] Generated `.3dm` opens in Rhino without errors; total box area ≥ sum of required room areas

**Dependencies:** M0, M1, M2 (extended by M6), M3, M4, M7.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §6. Per-milestone ADR-18 closes open questions (`.3dm` library pin, Korean brief extraction prompt, output JSON Schema, over-area handling, file storage BYTEA vs object storage, orphan cleanup, BC name finalize, per-user rate limit).
