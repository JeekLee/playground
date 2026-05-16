# Roadmap

| Milestone | Bounded context | Goal | Status |
|---|---|---|---|
| M0 | Bootstrap | Manual scaffolding: Gradle multi-module + gateway shell + shared-kernel + compose with Kafka/Postgres/Redis. No features. | planned |
| M1 | Identity | Google OAuth handled by gateway; user records in identity service; `/me` endpoint; session cookie | planned |
| M2 | Docs | MD document hosting (replaces user's git blog) — upload, list, render | planned |
| M3 | RAG-Ingestion | Kafka consumer for `document.uploaded` → chunk → embed (BGE-M3 via spark-inference-gateway) → store in pgvector | planned |
| M4 | RAG-Chat | Chatbot UI + retrieval + generation (Qwen3-32B via spark-inference-gateway), conversation history | planned |
| M5 | Metrics | Spark REST polling + Docker container status dashboard | planned |
| M6+ | Agents | TBD (future cycles) | deferred |

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

**Goal:** Give the user a chatbot that answers questions grounded in their own ingested documents.

**Acceptance:**
- [ ] Frontend chat UI lets a logged-in user start a conversation, send a message, and stream back a model response
- [ ] On each user turn, the backend embeds the query (BGE-M3), retrieves top-K chunks from pgvector scoped to that user, and constructs a prompt for Qwen3-32B
- [ ] Generation runs against `spark-inference-gateway` (Qwen3-32B) using Spring AI; responses stream to the client
- [ ] Conversation history is persisted server-side and reloadable across sessions
- [ ] Responses cite which document(s) / chunk(s) backed the answer (id + title at minimum)

**Dependencies:** M0, M1, M2, M3.

**Notes:** Retrieval scope must respect M1's `X-User-Id` (a user can never retrieve from someone else's chunks). This is the first milestone that exercises both LLM endpoints of `spark-inference-gateway` end-to-end.

**Public surface (per ADR-09 / design system spec §11):** This milestone ships **two** chat endpoints: `POST /api/rag/chat/public` (anonymous, retrieves only `visibility = 'public'` chunks, capped at `max_tokens=512`, one retrieved chunk, 10 completions/5min/IP + 30 completions/day/anon-cookie) and `POST /api/rag/chat/private` (authenticated, scoped by `X-User-Id`, full limits). A circuit breaker on `spark-inference-gateway` 5xx rate must be implemented; concrete algorithm and library land in the per-milestone ADR.

---

## M5 — Metrics

**Goal:** Give the operator (the user) a single dashboard for the health of the playground stack and the Spark cluster it depends on.

**Acceptance:**
- [ ] `metrics` service polls the Spark REST API on a fixed cadence and caches results (Redis is fine)
- [ ] `metrics` service reports Docker container status for every playground service (running / stopped / unhealthy)
- [ ] Frontend dashboard renders both Spark cluster state and container state on one page, auto-refreshing
- [ ] Dashboard is only reachable behind login (gateway-enforced)
- [ ] No new external dependency is introduced — polling-only, no Prometheus/Grafana in this milestone

**Dependencies:** M0, M1.

**Notes:** Infra-heavy: needs the docker socket (or an exporter) mounted into the metrics container; that constraint goes into `docs/infra-requirements/be.md` for the M5 cycle. Polling interval is a tunable env var.

**Public surface (per ADR-09):** The metrics dashboard is a **public** page. We accept that this exposes container/cluster health to anyone — it's an intentional "live workshop" signal, not a leak. The endpoint is read-only (no mutation surface).

---

## M6+ — Agents

**Goal:** TBD — reserved for later cycles once M1–M5 are stable and the methodology experiment has produced enough signal to design the agent surface area.

**Acceptance:** to be defined when the milestone is opened for design.

**Dependencies:** M1–M5.

**Notes:** Deferred. No issues are seeded under this milestone yet beyond a single placeholder so the milestone exists in GitHub.
