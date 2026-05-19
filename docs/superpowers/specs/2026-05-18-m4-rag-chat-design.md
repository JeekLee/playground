# Spec: M4 — RAG-Chat BC (design)

**Date:** 2026-05-18 (v1)
**Status:** Draft (brainstorming output)
**Audience:** the PM agent who will write `docs/prd/M4-rag-chat.md` when the M4 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/14-m4-rag-chat.md`; the product-designer agent who will produce `docs/design/M4-rag-chat.md` + Figma frames; the human reviewer.

**Relationship to other docs:**
- Supersedes the M4 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- **Will amend** `docs/adr/09-public-route-policy.md` — the existing `POST /api/rag/chat/public` allowlist row is removed; the anon rate-limit + token-cap section becomes auth-only with revised numbers. Amendment lives in the M4 per-milestone ADR.
- **Will amend** `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8 — the 2026-05-18 amendment block in that section already opens with "anonymous caller: WHERE visibility='public'"; that bullet is superseded again (anon has no chat surface at all). Amendment in the M4 PRD/ADR PR.
- **Will amend** `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract (forward invariant — M3 enables, M4 consumes)" — the anon corpus split is dropped. M3's chunk schema invariant (`(user_id, visibility)` non-null per row) is unchanged.
- **Will amend** `docs/roadmap.md` §M4 — already rewritten in 2026-05-18 to the "anonymous or signed-in" model; that revision is itself superseded by the auth-only model in this spec.
- References, does not supersede: ADR-04 (`04-spring-ai-and-llm-backend.md`) — Spring AI + Qwen3-32B + BGE-M3 endpoint pins are inherited. ADR-05 — pgvector + `rag.document_chunks` is the retrieval surface; `chat` schema is a new schema co-located.
- Will be the canonical input for the M4 PRD (Stage-1 PM run, future cycle).

## 0. Terminology

- **Session** = one logical conversation thread. Top-level grouping in the UI; surfaced as a top tab. Owns N messages in chronological order. One row in `chat.sessions`.
- **Message** = one user turn or one assistant turn. Row in `chat.messages` with `role IN ('user', 'assistant')`.
- **Citation** = one (document, chunk_index) pair attached to an assistant message at marker position `[N]`. Row in `chat.message_citations`.
- **Turn** = a user message + the assistant message that responds to it. Two `chat.messages` rows sharing the same `session_id`, sequential `created_at`.
- **Stream** = the SSE response of a single `POST /api/rag/chat` request — composed of one `retrieval` event, N `token` events, one `done` event (or one `error` event).
- "Chat" the noun is the surface (`/chat` route, `/api/rag/chat` endpoint, sidebar Apps row). "Conversation" is interchangeable with "session" in user-facing copy but "session" is the engineering noun.

## 1. Purpose

Pin the bounded context, data model, retrieval+generation pipeline, streaming protocol, UX surfaces, and amendments to upstream ADRs/specs needed to ship M4 — a Perplexity-style chat that lets a signed-in user explore the playground corpus (every author's public docs + the caller's own private docs).

This spec **does not** describe the M4 PRD's user-story list, the architect's port/library pins, the per-milestone ADR's circuit-breaker library choice, or the Stage-2 visual design. Those land in their canonical homes when the M4 cycle opens.

## 2. Scope summary

### In scope (P0)
- **Authenticated-only chat**. `POST /api/rag/chat` requires the gateway-injected `X-User-Id` header; anonymous callers receive 401.
- **Single endpoint**, streaming responses via Server-Sent Events.
- **Stateful conversation**: prior turns in the same session are included in the prompt context (subject to a token budget).
- **Per-turn fresh retrieval**: each user message is embedded via BGE-M3; top-K chunks pulled from pgvector with `WHERE visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`.
- **Inline citations**: assistant message text contains `[N]` markers; the frontend renders an expandable accordion directly below each assistant message. No persistent side panel.
- **Top-tab session navigation** at the top of `/chat`. Sidebar Apps row routes to `/chat`. No sidebar-based session list.
- **Session lifecycle**: hard delete (CASCADE), auto-title from the first user turn via a fire-and-forget Qwen3-32B call, manual rename.
- **Auth-only cost protection**: per-user token-bucket rate limit + Resilience4j circuit breaker on `spark-inference-gateway` 5xx rate. Concrete numbers in the M4 ADR.
- **Stop affordance** during streaming: client-initiated abort closes the SSE on the client. The server pipeline keeps running to completion so the assistant message + citations land in `chat.messages` (revised 2026-05-18 — was "not persisted"; server can't reliably distinguish involuntary navigate-away from a deliberate stop click, so persistence wins and the user can come back to find the answer). A future "discard this turn" feature can layer a separate endpoint on top.
- **Empty / loading / error states** explicitly defined (§7).
- **Desktop only** (≥720 px viewport). Mobile is M4.1.

### Deferred to M4.1 (P1, ship if cycle has slack)
- Mobile layout (≤719 px). Stack vertically, citation accordion collapsed by default, tab strip becomes a dropdown.
- Dynamic empty-state suggestions sourced from the caller's recent docs.
- Hybrid retrieval (BM25 from OpenSearch + cosine from pgvector, RRF fusion). M4 P0 is semantic-only.
- Conversation export (download as Markdown).
- Multi-turn retrieval (embed the last N turns concatenated rather than just the current user message).

### Out of scope (P2 — future milestones)
- Anonymous chat. Removed from the public route allowlist; the home page is the public landing, docs feed is the public corpus surface.
- Public RAG chat against owner's docs only ("ask jeeklee about their notes" mode). Original ADR-09 framing.
- Custom personas / system prompts.
- Tool-using agents (function calling).
- File uploads inside the chat surface ("paste a doc here").
- Multi-modal generation (images, audio).

## 3. Bounded Context: rag-chat

- **책임 (Responsibility):** owns chat sessions, messages, and citation links; performs query embedding, pgvector retrieval, prompt assembly, and LLM streaming. Does **not** own documents (M2), chunks (M3 owns writes, M4 reads), identity (M1), or search projection (M2's OpenSearch).
- **외부 의존성 (External deps):**
  - `identity` (M1): `X-User-Id` / `X-User-Sub` on every authenticated request. Plus a lookup of `display_name + avatar_url` for the caller (renderable in the chat header) — mechanism (cross-schema SELECT vs internal HTTP) deferred to the M4 ADR but the M2 owner-lookup pattern (ADR-08 §"Exception 3") is the obvious template.
  - `rag.document_chunks` (M3-owned): **read-only** for retrieval. M4 issues a parameterized vector similarity SQL via JDBC; no Kafka, no HTTP path. Cross-schema SELECT is sanctioned by ADR-05's amendment block (M4 ADR pins the exact SELECT shape).
  - `docs.documents` (M2-owned): **read-only** for citation enrichment (resolve `title` + `visibility` for each retrieved chunk's `document_id`). Same cross-schema SELECT pattern.
  - `spark-inference-gateway`: BGE-M3 dense embeddings (1024-dim per ADR-04) for query-time vectorization; Qwen3-32B chat completions for generation. Both via Spring AI 1.0 GA's OpenAI-compatible client (ADR-04).
  - Redis (`redis-playground`): token-bucket counters for per-user rate limiting.
- **누가 rag-chat을 호출하나:** Gateway → `rag-chat` for all `/api/rag/chat` traffic. No other BC calls it.
- **이벤트:** **publishes none**, **consumes none**. M4 is request-response over HTTP; conversation history is the only persisted state, and it has no Kafka consumers. (M5 metrics will scrape `/actuator/metrics` like every other BC; no Kafka contract for M4.)

## 4. Data model

### 4.1 Postgres tables (`chat` schema — source of truth)

```sql
CREATE SCHEMA IF NOT EXISTS chat;

CREATE TABLE chat.sessions (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL,                                -- identity.users.id (app-level FK)
  title       TEXT         NOT NULL DEFAULT 'New chat',
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()                   -- bumped on every message insert
);
CREATE INDEX chat_sessions_by_user ON chat.sessions (user_id, updated_at DESC);

CREATE TABLE chat.messages (
  id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id   UUID         NOT NULL REFERENCES chat.sessions(id) ON DELETE CASCADE,
  user_id      UUID         NOT NULL,                               -- denormalized for fast tenant filter
  role         TEXT         NOT NULL CHECK (role IN ('user','assistant')),
  content      TEXT         NOT NULL,                               -- raw text; assistant turns may contain [N] markers
  tokens_in    INT,                                                 -- assistant only (prompt tokens billed)
  tokens_out   INT,                                                 -- assistant only (completion tokens billed)
  retrieval_k  INT,                                                 -- assistant only (how many chunks pulled this turn)
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_by_session ON chat.messages (session_id, created_at);

CREATE TABLE chat.message_citations (
  message_id   UUID  NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
  position     INT   NOT NULL,                                       -- the [N] in the assistant message body, 1-indexed
  document_id  UUID  NOT NULL,                                       -- rag.document_chunks.document_id (app-level FK)
  chunk_index  INT   NOT NULL,                                       -- rag.document_chunks.chunk_index
  PRIMARY KEY (message_id, position)
);
CREATE INDEX chat_message_citations_by_document ON chat.message_citations (document_id);  -- reverse lookup
```

### 4.2 Field rules

- `user_id` on both `sessions` and `messages` (denormalized on the latter) is the tenant key. Every retrieval / authorization check uses it as the WHERE predicate.
- `title` defaults to `'New chat'`; the auto-titler updates it to a 2–6 word summary after the first user message commits. Manual rename via `PATCH /api/rag/chat/sessions/{id}` (route shape in §5) replaces it.
- `content` of an assistant message may include `[N]` markers; the marker → citation mapping lives in `message_citations`. The frontend renders markers as superscript pills linked to the accordion entry; absent markers render as plain text.
- `tokens_in` / `tokens_out` come from the LLM response; they are NULL on user rows. `retrieval_k` records the number of chunks actually retrieved this turn (may be < the requested K if the corpus is sparse). All three are advisory — used for cost metrics, not invariants.
- `message_citations.position` is a positive integer; gaps allowed (an LLM might cite `[1] [3]` skipping `[2]` if the prompt only had 2 useful chunks).

### 4.3 Cross-schema references (NOT FKs)

- `sessions.user_id` → `identity.users.id`: app-level. No database-enforced FK (different schema, no cross-schema constraints per ADR-05).
- `message_citations.document_id` → `docs.documents.id`: app-level. **Stale citations** (when M3 has purged the chunks for a deleted document) are accepted; the citation row stays in `chat.message_citations` but the accordion expand-on-click resolves to `{"title": null, "deleted": true, "excerpt": "(이 문서는 더 이상 사용할 수 없습니다)"}`. The M4 ADR may add a `chat.message_citations.document_id` FK to a future `docs.documents.id` if cross-schema FKs become available.
- `message_citations.(document_id, chunk_index)` → `rag.document_chunks.(document_id, chunk_index)`: app-level. Resolution at read time joins to the live row to fetch `excerpt` / `text` for the accordion.

### 4.4 Cascade behavior

- Session hard-delete (CASCADE) removes all messages and (cascading via messages) all citations of that session. A single `DELETE FROM chat.sessions WHERE id = ? AND user_id = ?` cleans the entire conversation atomically.
- Document delete in M2 fires `docs.document.deleted` → M3 purges `rag.document_chunks`. M4 does not subscribe to this event; stale citation rows remain in `chat.message_citations` and are resolved as deleted at read time.

### 4.5 Token / cost accounting

`tokens_in` + `tokens_out` per assistant message are the canonical billing record. The M4 ADR may decide to add a `chat.usage_daily` rollup table for "tokens this user has used today" if the rate limiter needs a Postgres backing instead of (or in addition to) Redis. Spec leaves the choice open; Redis-only is the working assumption.

## 5. HTTP surface

### 5.1 Main streaming endpoint

```
POST /api/rag/chat
Headers
  X-User-Id: <uuid>          required (gateway-injected; absence = 401)
  X-User-Sub: <google sub>   optional (passthrough; logged for audit)
  Accept: text/event-stream  required (415 otherwise)
Content-Type: application/json
Body
  {
    "sessionId": "uuid",     // existing session, MUST belong to X-User-Id (else 404)
    "message": "string"      // user turn, ≤ 4 KB raw text
  }

Response (success)
  HTTP/1.1 200 OK
  Content-Type: text/event-stream
  Cache-Control: no-cache
  X-Accel-Buffering: no       // disable nginx-style buffering

  [SSE events — see §5.2]

Response (errors)
  401 — no X-User-Id
  404 — session not found OR session.user_id != X-User-Id
  413 — message > 4 KB
  415 — Accept does not include text/event-stream
  429 — rate-limit bucket empty (with Retry-After header)
  503 — circuit breaker open OR gateway 5xx exhausted retries
```

### 5.2 SSE event grammar

> Revised 2026-05-19 (PR B): the legacy `retrieval` event is gone. Progress info now ships as `phase` events (≥0 of them), and citation cards arrive on the terminal `done` (cited subset only — `[N]` markers that survived in the assistant text, mapped back to the originally-retrieved chunks). The whole grammar is BC-agnostic and lives in `shared-kernel/chat/ChatStreamEvent` so future chat surfaces (tool-calling agent, second model line) emit identical events.

Three streaming event types plus one terminal pair: ≥0 `phase`, ≥0 `token`, exactly one terminal `done` or `error`.

```
event: phase
data: {"step":"retrieval","label":"참고 문서 확인 중","data":{"count":6}}

# (PR C onward — extra phase events from tool-calling)
event: phase
data: {"step":"tool_call","label":"공개 문서 검색 중","data":{"tool":"searchPublicDocs","args":{"query":"…"}}}

event: phase
data: {"step":"generating"}

event: token
data: {"delta":"800-token windows "}

event: token
data: {"delta":"with 120-token overlap [1][2]…"}

event: done
data: {"messageId":"<uuid>","tokensIn":1234,"tokensOut":567,"citations":[
        {"n":1,"documentId":"<uuid>","chunkIndex":3,"title":"ADR-13: M3 RAG-Ingestion","excerpt":"…","visibility":"public"},
        {"n":2,"documentId":"<uuid>","chunkIndex":7,"title":"M3 PRD","excerpt":"…","visibility":"public"}
      ]}

# OR (terminal alternative)
event: error
data: {"code":"GATEWAY_5XX|RATE_LIMIT|RETRIEVAL_EMPTY|ABORTED|INTERNAL","message":"<human-readable>"}
```

- `phase` events are progress / status updates: `step` is the discriminator (`retrieval`, `tool_call`, `tool_result`, `thinking`, `generating`, …), `label` is a human-readable hint the UI may render verbatim, and `data` is a BC-specific payload (count, tool + args, etc.). 0+ allowed in any order; the UI uses the latest label to drive the "Thinking…" affordance line.
- `token` events deliver `delta` strings as they arrive from Spring AI's `Flux<ChatResponse>`. No transformation server-side — the LLM's `[N]` markers reach the client as-is and are matched against the `done.citations` list by `n`.
- `done` is emitted after the last `token`; carries the assistant message's persisted ID, token counts, and the **cited subset** of retrieved chunks (the `[N]`s that actually appeared in the final text). The frontend uses `messageId` to enable post-stream actions (regenerate, copy, etc. — those are PRD features, P1 candidates).
- `error` is terminal. Once emitted, the SSE connection closes. The assistant message is **not** persisted when the stream ended in `error` (upstream LLM failure). Client disconnects without an `error` event (navigate-away, Stop button) do **not** abort the server pipeline — see §6.1 step 12 for the disconnect-tolerant persistence rule (revised 2026-05-18).

### 5.3 Session management endpoints (non-streaming + resume)

```
POST   /api/rag/chat/sessions                 Create empty session. Response 201 { "sessionId", "title": "New chat" }
GET    /api/rag/chat/sessions                 List caller's sessions. Response 200 { "sessions":[{ "id","title","updatedAt","messageCount"}] }
PATCH  /api/rag/chat/sessions/{id}            Rename session. Body { "title": "..." }. 404 if not owned.
DELETE /api/rag/chat/sessions/{id}            Hard delete + cascade. 404 if not owned. Idempotent.
GET    /api/rag/chat/sessions/{id}/messages   Load message + citation history. Response 200 { "messages":[{...,"citations":[...]}] }
GET    /api/rag/chat/sessions/{id}/stream     Attach to an in-flight chat turn for this session as SSE. Returns the same
                                              event grammar as POST /api/rag/chat (replay of retrieval + every token emitted
                                              so far + the live tail or final done). 404 if no in-flight turn OR session
                                              not owned. Added 2026-05-18 — see §6.3.
```

All session endpoints require `X-User-Id` and enforce ownership at the SQL level (`WHERE user_id = ?`). The resume endpoint enforces ownership against the in-memory turn registry. No internal `/internal/**` routes; M4 has no peer BCs.

### 5.4 DTO sketches (final shapes in OpenAPI / per-milestone ADR)

```typescript
// Backend → Frontend (SSE retrieval payload)
type CitationEvent = {
  citations: Array<{
    n: number;            // 1-indexed; matches [N] markers in token text
    documentId: string;   // UUID
    chunkIndex: number;
    title: string;        // resolved from docs.documents
    excerpt: string;      // first ~160 chars of the chunk's text
    visibility: 'public' | 'private';
  }>;
};
```

## 6. LLM + retrieval pipeline

### 6.1 Per-turn flow

1. **Auth + tenant check** at the gateway. `X-User-Id` extracted from session. No X-User-Id → 401.
2. **Rate-limit check** via Redis token bucket (one bucket per `user_id`). On miss, return 429 with `Retry-After`.
3. **Session validation**: `SELECT user_id FROM chat.sessions WHERE id = $1`. If row missing or `user_id != X-User-Id`, return 404 (do not distinguish "not found" from "not yours" — same response).
4. **History assembly**:
   - Load `messages WHERE session_id = ? ORDER BY created_at` (the existing turns).
   - Compute remaining budget: `qwen3_32b_context_window = 32_768`; reserve `8_192` tokens (4k for retrieval block + 4k for assistant completion). Working budget for history = `24_576` tokens.
   - Truncate history from the oldest turn until `tokens(history_text) ≤ 24_576 - tokens(current_user_message)`. The system prompt is added on top of this budget but is small (~200 tokens).
5. **Query embedding**: BGE-M3 dense embedding of `message` (the **current** user turn only — prior turns are not re-embedded). Spring AI `EmbeddingModel`. 1024-dim output per ADR-04.
6. **pgvector top-K retrieval**:
   ```sql
   SELECT document_id, chunk_index, text, user_id, visibility
   FROM rag.document_chunks
   WHERE visibility = 'public' OR (user_id = $X_USER_ID AND visibility = 'private')
   ORDER BY embedding <=> $QUERY_EMBEDDING
   LIMIT $K;
   ```
   Working default **K=6**; configurable via `application.yml`. Runtime hint `SET LOCAL hnsw.ef_search = 40` on the same connection (ADR-13 §9 default).
7. **Citation enrichment**: for each retrieved chunk, JOIN `docs.documents` to resolve `title` (~one extra round-trip per turn, batched). Build the `CitationEvent` payload.
8. **Emit `retrieval` SSE event** with the enriched citation list.
9. **Persist the user message** to `chat.messages` (role=`user`) so it shows up if the user reloads the page mid-stream.
10. **Prompt assembly** (text concatenation, no DSL):

    ```
    [SYSTEM]
    You are a helpful assistant grounded in the user's playground corpus.
    Cite every factual claim with [N] markers where N is the 1-indexed
    position from the RETRIEVED CONTEXT block. If no chunk supports a
    claim, say so plainly — do not fabricate citations.

    [RETRIEVED CONTEXT]
    [1] <title> (visibility=<v>) — <chunk text, truncated to ~400 tokens>
    [2] <title> — <chunk text>
    ... up to [K]

    [CONVERSATION SO FAR]
    user: <prior turn 1>
    assistant: <prior turn 1 reply text>           ← only the text, NOT citations
    user: <prior turn 2>
    assistant: <prior turn 2 reply text>
    ...

    [CURRENT TURN]
    user: <current message>
    assistant:
    ```

    Notes:
    - Prior assistant turns are stripped of their `[N]` markers before being included (each turn has its own retrieval; old `[N]` indices would collide with new ones).
    - Retrieval context is fresh per turn — prior turn's retrieved chunks are NOT carried forward.

11. **Spring AI streaming call**: `chatClient.prompt().messages(...).stream().chatResponse()` returns `Flux<ChatResponse>`. Map `.getResult().getOutput().getText()` to `token` SSE events, forwarding as they arrive.
12. **Persist assistant message** when the stream completes successfully: insert `chat.messages` (role=`assistant`, with `content` = accumulated text, `tokens_in/out/retrieval_k` from the final `ChatResponse`), then insert `chat.message_citations` rows. **The server pipeline is decoupled from client subscription state** (revised 2026-05-18) — the chat-turn pipeline runs to completion on a hot replay-shared flux regardless of whether the SSE client is still attached, so navigate-away / Stop-button / tab-close all still produce a persisted assistant message that shows up on the user's next visit via `GET /sessions/{id}/messages`. Whether to persist **only the citations whose `[N]` marker appeared in the final text** or **all retrieved chunks regardless** is open-question #10 (§11) — the working assumption is "only those that appeared", but the ADR may revise.
13. **Emit `done` SSE event** with `messageId` + token counts. Close the stream.
14. **On any failure** between step 11 and step 12: emit `error` event with `code`, do **not** persist the assistant message. The user message persisted in step 9 stays.

### 6.2 Streaming protocol choice

SSE chosen over WebSocket / HTTP/2 push for:
- **One-way semantics fit**: tokens flow server → client only. WebSocket's bidirectional is unused.
- **Browser-native API**: `new EventSource(url)` works without a library.
- **Spring AI integration**: `Flux<ChatResponse>` → `Flux<ServerSentEvent>` is a one-liner with `flux.map(ServerSentEvent::builder).asFlux()`.
- **Proxy / reverse-proxy compatibility**: HTTP/1.1 chunked transfer; works through standard reverse proxies as long as buffering is disabled (`X-Accel-Buffering: no`).

### 6.3 Context window strategy

Token budget per turn (Qwen3-32B context = 32k):
- System prompt: ~200 tokens (fixed).
- Retrieved context: K=6 × ~400 tokens/chunk = 2,400 tokens.
- Reserved for assistant completion: 4,000 tokens (`max_tokens=4000`).
- Remaining for conversation history: ~25,000 tokens.

Truncation policy: drop oldest turn pairs until the history fits. Never truncate mid-turn (a user message and its assistant response are dropped together). Working default; the ADR may revise the budget split.

**Open question for ADR-14**: should we summarize truncated turns into a single "Earlier in this conversation: ..." paragraph instead of dropping? P0 default = drop.

### 6.4 Mid-stream re-join (resume) — added 2026-05-18

Decoupling the server pipeline from the SSE subscription (§6.1 step 12) means an in-flight chat turn keeps running even when the client disconnects, but the live token stream itself is still in memory on the hot replay-shared flux. The resume endpoint lets a returning client attach to that flux:

- **`GET /api/rag/chat/sessions/{id}/stream`** with `Accept: text/event-stream` and `X-User-Id` — see §5.3 row.
- Server consults an in-memory `ActiveTurnRegistry` keyed by `SessionId`. Entry value carries `(owner UserId, shared Flux<ChatStreamEvent>, startedAt Instant)`.
- Ownership: the entry's `owner` must equal `X-User-Id`. Mismatch + missing entry both map to 404 (same shape, do not distinguish — matches §6.1 step 3's "not found vs not yours" rule).
- Wire grammar: identical to the POST stream — `retrieval` (replayed) → `token` (replayed past + live tail) → terminal `done` / `error`. The frontend SSE consumer is the same parser, just bound to GET.
- Lifecycle: `ChatTurnService` registers the entry immediately after building the shared flux; `Flux.doFinally` on the source unregisters it as the pipeline terminates (success, error, or both). The replay buffer is GC'd once the registry no longer references it.
- Scope: JVM-local. ADR-07 §"Hosting model" keeps playground single-instance through M5; multi-instance scale-out would add a Redis `sessionId → instanceId` routing index on top (the flux itself stays in-process — it owns the live Spring AI subscription and cannot be transported).
- Multi-tab UX: opening a second tab on the same session attaches another subscriber to the same shared flux — both tabs see the same tokens at the same time, for free.

### 6.4 Circuit breaker

Resilience4j-style circuit breaker on `spark-inference-gateway` calls (both BGE-M3 and Qwen3-32B). Working numbers (ADR-NN pins):
- **Threshold**: > 50 % failures over the last 60 seconds → OPEN.
- **Open duration**: 30 seconds. Subsequent requests return `503 GATEWAY_DOWN` immediately.
- **Half-open probe**: one request after the open duration; success → CLOSED, failure → another 30s OPEN.

The breaker is shared across all rag-chat instances if M4 ever scales horizontally (we'd back it with Redis); P0 is single-instance so in-memory is fine.

### 6.5 Error classification

| Source | Retry? | SSE error code |
|---|---|---|
| BGE-M3 embedding 5xx / timeout | yes, up to 3× per turn | `GATEWAY_5XX` if exhausted |
| BGE-M3 embedding 4xx | no | `INTERNAL` (likely a code bug, not user input) |
| pgvector SQL timeout | yes, once | `INTERNAL` |
| pgvector returns 0 rows | no — not an error | not emitted; `retrieval` event ships empty `citations`, generation proceeds |
| Qwen3-32B 5xx / timeout mid-stream | no (already streaming, partial output exists) | `GATEWAY_5XX`; partial text discarded, no assistant message persisted |
| Qwen3-32B 4xx | no | `INTERNAL` |
| Circuit breaker OPEN | no | `GATEWAY_5XX` with retry-after suggestion |
| Rate limit bucket empty | no | `RATE_LIMIT` with `retryAfter` seconds |
| Client disconnect (SSE close) | n/a | `ABORTED` (logged only; not emitted since client is gone). Server pipeline keeps running to completion — assistant message persists (revised 2026-05-18). |

## 7. UX surfaces

### 7.1 `/chat` page (desktop, ≥720 px)

```
┌───┬────────────────────────────────────────────────────┐
│ ▣ │ Home / Chat / <session title>            [⌘K] [👤] │  topbar (M1 chrome, breadcrumb updated)
│   ├────────────────────────────────────────────────────┤
│ H │ ▣ <s1> │ <s2> │ <s3> │ <s4> │ <s5> │ <s6> │ <s7> │ + │ ▾ N more │  session tabs
│ D ├────────────────────────────────────────────────────┤
│ ▣ │   (message list — scrollable)                      │
│ S │                                                    │
│   │   user: how does the chunker handle long md?       │
│   │                                                    │
│   │   asst: 800-token windows with 120 overlap [1][2]. │
│   │         Spring AI's MarkdownChunker uses cl100k…[3]│
│   │         ▾ Citations · 3                            │
│   │         ┌──────────────────────────────────────────┐
│   │         │ [1] ADR-13 §1 Chunking      ↗ open       │
│   │         │     excerpt: "800-token windows with…"   │
│   │         │ [2] M3 PRD §4 Schema        ↗ open       │
│   │         │ [3] roadmap.md §M3          ↗ open       │
│   │         └──────────────────────────────────────────┘
│   │                                                    │
│   │   user: retry policy?                              │
│   │   asst: 3× exponential backoff…  ▍   [Stop]        │  streaming
│   │                                                    │
│   ├────────────────────────────────────────────────────┤
│   │  Ask anything…                            ⏎ Send   │  composer
│   └────────────────────────────────────────────────────┘
```

Sidebar `Apps` row "Chat" unlocks on M4 ship. Locked row pattern stays for unauth (see §7.6 below).

### 7.2 Session tab strip behavior

- Active tab = filled background, title in primary text color.
- Inactive tabs = muted background, secondary text color, hover state highlights the tab outline.
- `+` button at the end → POST `/api/rag/chat/sessions` → new tab inserted at the left of the strip, title = "New chat", page scrolls to the composer.
- **Overflow rule**: max 7 visible tabs (most recent by `updated_at DESC`). The 8th and beyond appear in the `▾ N more` dropdown; selecting one from the dropdown pulls it back to the head of the visible strip.
- **Tab hover affordances**: a `⋯` button reveals on hover → Rename / Delete. Rename inlines (the tab becomes a text input). Delete prompts a confirm dialog ("Delete this conversation? This cannot be undone.") → DELETE `/api/rag/chat/sessions/{id}` → tab removed, page navigates to the next-most-recent session or to an empty `/chat` if none remain.
- Switching tabs while a stream is in flight: client aborts the current SSE (no `ABORTED` notification needed — same user, same authority). The server pipeline keeps running and persists the assistant message; switching back loads it from `GET /sessions/{id}/messages` (revised 2026-05-18 — was "partial assistant message is not persisted").

### 7.3 Citation accordion (inline, per assistant message)

- Collapsed by default: `▾ Citations · N` (N from the retrieval event's citation count). If N = 0, render `▾ Citations · none` (collapsed, click expands to "(no citations — answer was unsupported)").
- Expanded: a card per citation, ordered by `n`. Each card shows `[n] Document title`, the first ~160 chars of the chunk excerpt, and an `↗ open` link to `/docs/{documentId}#chunk-{chunkIndex}` (the chunk anchor is M4.1 — for P0 the link goes to `/docs/{documentId}` and the doc page handles or ignores the fragment).
- Stale citation (doc deleted, JOIN returns null title): render `[n] (deleted) — 이 문서는 더 이상 사용할 수 없습니다`. No `↗ open`. The citation row stays in the database; the UI degrades gracefully.

### 7.4 Composer

- Single-line input that grows to multi-line on `Shift+Enter` (max 8 visible lines, scroll beyond). `Enter` submits.
- `Send` button activates when input is non-empty and no stream is in flight.
- During a stream: composer is disabled with placeholder "응답이 생성 중입니다…"; the Send button becomes a Stop button (the same Stop affordance as the in-message Stop, kept duplicated for discoverability).
- After successful stream end: composer re-enables, input clears, scroll snaps to the bottom of the message list.

### 7.5 Empty / loading / error states

| State | Trigger | Render |
|---|---|---|
| Empty session (no messages yet) | Newly-created session or all messages deleted | Centered card: "What do you want to know about your corpus?" + 3 **static** suggestion chips (P0). Clicking a chip pre-fills the composer; does **not** auto-send. |
| Thinking | Between user submit and the first `retrieval` SSE event | Small spinner + "Thinking…" placed where the assistant message will appear. |
| Streaming | `token` events flowing | Pulsing block cursor `▍` at the end of the rendered assistant text. Stop button visible. |
| 503 GATEWAY_DOWN | SSE `error: GATEWAY_5XX` | Red banner above composer: "AI service is currently unavailable." + `Retry last message` button (resubmits the same user message). |
| 429 RATE_LIMIT | SSE `error: RATE_LIMIT` | Yellow banner: "You've hit your hourly limit. Try again in M minutes." Countdown updates from `retryAfter`. Composer disabled until countdown reaches 0. |
| RETRIEVAL_EMPTY | `retrieval` event with `citations: []` | Stream proceeds normally; the assistant message will be guided by the system prompt to say "I couldn't find relevant chunks for that". Citation accordion shows "(no citations)". |
| Aborted | User clicks Stop OR switches tab mid-stream OR closes browser | Partial assistant text greyed out + "Generation stopped" footer. Message **not** persisted (refreshing the page = the message is gone). |
| Session 404 | Stale tab from another browser session | Toast: "This conversation no longer exists." Tab removed from the strip; page redirects to most-recent session or `/chat`. |

### 7.6 Anon visiting `/chat`

- Sidebar Apps row "Chat" renders with the **auth-lock** badge `🔒 Sign in` (distinct from milestone-lock `🔒 Mx` used for un-shipped milestones). Click → `/login?return=/chat`.
- Direct navigation to `/chat` while unauth: the Next.js middleware redirects to `/login?return=/chat`. Server-side check; no flicker of the chat UI.
- POST to `/api/rag/chat` without `X-User-Id` returns 401 from the gateway (allowlist amendment in M4 ADR).

### 7.7 Sidebar Apps row state matrix (post-M4)

| User state | "Chat" row | Click behavior |
|---|---|---|
| Signed-in | active, no badge, primary text | `/chat` (last session restored or new) |
| Signed-out | muted, badge `🔒 Sign in`, secondary text | `/login?return=/chat` |
| Future un-shipped milestones (e.g., Metrics M5) | muted, badge `🔒 M5`, secondary text | no-op (no route exists yet) |

ADR-09 amendment will add `🔒 Sign in` as a third badge state alongside the existing milestone-lock convention.

## 8. Auth + cost protection (amends ADR-09)

### 8.1 Public route allowlist amendment

ADR-09 §"Route classification" table — **remove** the row:

> | `POST /api/rag/chat/public` | public | Anonymous RAG chat against the public corpus only. |

— and **revise** the authenticated row to mention the single endpoint:

> | `POST /api/rag/chat`, `/api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/*/messages` | **authenticated** | RAG chat (authoring + retrieval). Anon callers receive 401. |

### 8.2 Rate-limit + cost protection amendment

ADR-09 §"Rate-limit and cost protection (public RAG chat)" → renamed to **"Rate-limit and cost protection (RAG chat — authenticated)"**, content fully replaced. Working numbers (M4 ADR pins; spec proposes):

- **Per-user token bucket**: 60 chat completions / hour / user. On miss → 429 + `Retry-After`.
- **Daily ceiling**: 200 chat completions / day / user.
- **Per-completion token cap**: `max_tokens=4000` (vs the old anon cap of 512). K = 6 retrieved chunks.
- **Global circuit breaker**: as in §6.4.
- **Token budget for free-tier-ish controls**: none beyond the daily ceiling for P0; revisit if a single user starts paying real GPU.

### 8.3 Anonymous identity contract — unchanged, narrowed

ADR-09 §"Anonymous identity contract" still applies to **other** public routes (`/`, `/docs/public/**`, `/docs/search`, `/metrics/**` when shipped). For chat specifically the contract is moot — chat is auth-only.

## 9. Streaming + rendering feature scope (M4)

- **Markdown rendering**: assistant messages render Markdown via the same renderer M2 uses (`unified` + `remark-gfm` + `rehype-sanitize` + `shiki`). GFM tables, fenced code with syntax highlighting, task lists, autolinks. Inline `[N]` markers are picked off by a remark plugin and replaced with citation pills before rendering — this keeps citation interactivity orthogonal to the Markdown pipeline.
- **Math / Mermaid / HTML in MD**: out of scope, M2 stance applies.
- **Streaming cursor**: a CSS-animated block element rendered as the last node while `token` events are flowing. Removed on `done` or `error`.
- **Auto-scroll**: anchored to bottom while streaming; if the user scrolls up mid-stream, the auto-scroll pauses and a "Jump to latest" button appears at the bottom-right. Clicking it resumes auto-scroll.

## 10. Non-functional requirements

- **Time-to-first-token (TTFT) P95 ≤ 2.0 s** for the median query (defined: K=6 retrieval against a 50k-chunk corpus + 1024-dim embedding + Qwen3-32B first token). WARN log when exceeded.
- **End-to-end stream P95 ≤ 20 s** for a `max_tokens=4000` completion.
- **Session list load P95 ≤ 200 ms** (GET `/api/rag/chat/sessions`).
- **Concurrent stream cap per user**: 1. A second concurrent `POST /api/rag/chat` from the same user aborts the first (latest-wins). Backend enforces via per-user lock in Redis (Redisson, same pattern as M3's `@GlobalLock`).
- **Cost telemetry**: every `done` event records `tokens_in + tokens_out` to a Micrometer counter `playground.rag_chat.tokens` tagged by `userId`. Polled by M5 metrics.
- **Observability**: every state transition (turn start, retrieval done, stream start, stream end / abort / error) → INFO structured log with `userId`, `sessionId`, `messageId`, `eventType`, `tokensIn`, `tokensOut`, `latencyMs`.
- **Resilience4j metrics**: `circuit_breaker_state{name=spark-gateway}`, `circuit_breaker_calls{kind=successful|failed|not_permitted}`.

## 11. Open questions for the per-milestone ADR (M4)

The M4 ADR (`docs/adr/14-m4-rag-chat.md`) must close at least these:

1. **Port assignment** for `rag-chat-api` (M0–M3 used 18080 / 18081 / 18082 / 18083; M4 likely 18084 — confirm).
2. **Module quadruplet wiring**: `rag-chat-{api,app,domain,infra}`. `-api` hosts the SSE controller + session CRUD controllers. `-app` hosts the per-turn use case (auth + rate-limit + history assembly + retrieval orchestration + LLM call). `-domain` hosts `Session`, `Message`, `Citation` aggregates and the prompt-template DSL (Spring-free). `-infra` hosts JPA, Spring AI `ChatClient` adapter, BGE-M3 `EmbeddingModel` adapter, Redis Redisson rate-limit adapter, the pgvector retrieval SQL adapter.
3. **Cross-schema SELECT pattern** vs internal HTTP for the `display_name` lookup (M2 owner-lookup precedent vs new SELECT exception). Pin one.
4. **Circuit breaker library**: Resilience4j vs Spring Cloud Circuit Breaker vs hand-rolled. Resilience4j is the working assumption.
5. **Per-user token bucket backing**: Redis-only vs Postgres-rollup-table-as-fallback (`chat.usage_daily`).
6. **Auto-title prompt**: exact wording + which model (Qwen3-32B is the obvious answer but a smaller/cheaper one would be nicer if available).
7. **Retrieval K**: confirm 6 or revise. Range: 4–10.
8. **Token budget split**: confirm 200 (system) + 2400 (retrieval, K=6 × 400) + 24576 (history) + 4000 (response) = 31176 ≈ 32k, or revise.
9. **Conversation truncation policy**: drop oldest turns vs summarize-and-replace. P0 default = drop.
10. **Cite-persistence policy**: persist all retrieved citations in `chat.message_citations`, OR only the ones whose `[N]` actually appeared in the LLM output. PRD/ADR call.
11. **Stale-citation rendering on the frontend**: exact copy + whether to show the chunk_index in the "deleted" state.
12. **Empty-state suggestions for P0**: pin the three static suggestion strings.
13. ~~**Stop button → assistant message persistence**: confirm "not persisted" vs "persist partial up to abort point" (P0 default = not persisted).~~ **Resolved 2026-05-18**: assistant message is persisted regardless of client disconnect (Stop button, navigate-away, tab-close all count as disconnects). Server pipeline is decoupled from SSE subscription state via `Flux.replay(N).autoConnect(1)` + background drain, so the chat turn always runs to completion server-side. A future "discard this turn" endpoint can lay on top if needed.
14. **Streaming abort signaling to the LLM**: does Spring AI's `Flux<ChatResponse>` cancellation actually stop the gateway-side generation, or does the gateway keep generating tokens that get dropped? If the latter, we're paying for tokens we don't use — needs investigation.
15. **`X-User-Sub` audit logging**: which fields are mandatory in the chat audit log (PII concerns vs operator's ability to debug).
16. **Contract test fixture strategy**: WireMock for spark-inference-gateway (BGE-M3 + Qwen3-32B), Testcontainers for pgvector + Redis. Confirm pattern (matches M3 ADR-13 §13).
17. **Rate limit identifier**: `userId` only, or `userId + sub` (some users could share a userId across browsers — unlikely for OAuth-Google, but worth checking).

## 12. Acceptance criteria (refinement of `roadmap.md` M4)

### Auth + tenant isolation
- [ ] `POST /api/rag/chat` without `X-User-Id` returns 401. ADR-09 allowlist updated; gateway integration test covers it.
- [ ] `POST /api/rag/chat` with `sessionId` whose `chat.sessions.user_id != X-User-Id` returns 404.
- [ ] `GET /api/rag/chat/sessions` returns only sessions where `user_id = X-User-Id`.
- [ ] Retrieval SQL contains the WHERE clause `(visibility='public') OR (user_id = $X_USER_ID AND visibility='private')` — verified by parameterized integration test that confirms private chunks from other users are never returned.

### Streaming protocol
- [ ] Response is `Content-Type: text/event-stream`, exactly one `retrieval` event ordered before any `token` event.
- [ ] On normal completion, exactly one `done` event terminates the stream.
- [ ] On any failure, exactly one `error` event terminates the stream and no `done` is emitted.
- [ ] Client disconnect (SSE `close`, Stop button, navigate-away) → server pipeline continues to completion; assistant message + cited rows persist and show up on the user's next `GET /sessions/{id}/messages` (revised 2026-05-18).
- [ ] Mid-stream re-join: while a turn is in flight, `GET /api/rag/chat/sessions/{id}/stream` returns the live SSE stream — same wire grammar, replayed `retrieval` + every token so far + live tail (added 2026-05-18, §6.4).
- [ ] Re-join 404 conditions: no in-flight turn for the session OR session not owned by `X-User-Id` — both return 404 (indistinguishable to the client, matches §6.1 step 3).

### Conversation persistence
- [ ] `chat.messages` insert order matches the `created_at` clock; reload renders identical history.
- [ ] `chat.message_citations` rows persist with `(message_id, position)` PK + `(document_id)` reverse index.
- [ ] Session DELETE cascades to messages → citations atomically.
- [ ] Auto-title fires on the first user turn commit; failure path leaves `title = 'New chat'`.

### UX
- [ ] Sidebar Apps "Chat" row unlocks on M4 deploy (M1 design doc + frontend implementer update).
- [ ] `/chat` is server-side auth-gated; anon GET → 302 to `/login?return=/chat`.
- [ ] Citation accordion expands inline below the assistant message; no side panel.
- [ ] Stop button during stream aborts SSE within 200 ms (P95) of click.
- [ ] Tab switch during stream aborts the in-flight stream and switches to the target session.

### Cost protection
- [ ] Rate-limit bucket empty → 429 with `Retry-After` header.
- [ ] Circuit breaker OPEN → 503 with body `{"code":"GATEWAY_DOWN"}`; transitions to HALF_OPEN after the configured duration; one probe call closes or re-opens.

### Cross-milestone (traceability — non-blocking for M4 close)
- [ ] ADR-09 amendments listed in §8 are applied in the M4 ADR PR.
- [ ] Spec v5 §8 amendment block re-revised to point to this spec as canonical.
- [ ] M3 PRD §"M4 retrieval contract" re-revised: anon row dropped.
- [ ] `roadmap.md` §M4 re-revised: anon row dropped.

## 13. Supersession + downstream amendments

The M4 PRD / ADR PR set must include the following amendments. Listed here so no one (PM, architect, frontend-implementer) has to rediscover them:

| File | Section | Change |
|---|---|---|
| `docs/roadmap.md` | §M4 | Revert "any visitor" → "signed-in user only". Single endpoint, no anon limits. |
| `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` | §8 "RAG handoff trace" + §8 amendment block | Drop the anonymous-caller bullet. Single endpoint, auth-only. |
| `docs/prd/M3-rag-ingestion.md` | "M4 retrieval contract (forward invariant)" | Drop anonymous case from the WHERE clause description. Auth-only retrieval expression remains as-is. |
| `docs/adr/09-public-route-policy.md` | "Route classification" table | Remove `POST /api/rag/chat/public` row; revise auth row to list `POST /api/rag/chat` + session CRUD endpoints. |
| `docs/adr/09-public-route-policy.md` | "Rate-limit and cost protection (public RAG chat)" | Replace with "(RAG chat — authenticated)" + new auth-only numbers per §8.2 above. Anon rate-limit block deleted. |
| `docs/adr/09-public-route-policy.md` | (new section) | Add "Auth-lock vs milestone-lock" badge convention for sidebar Apps rows (see §7.6). |
| `docs/adr/00-overview.md` | Index table | Add row for ADR-14. |
| `docs/adr/03-kafka-conventions.md` | (no change) | M4 has no Kafka topics. |
| `docs/adr/04-spring-ai-and-llm-backend.md` | (informational note only) | No semantic change. A short amendment block confirms ChatClient streaming (`Flux<ChatResponse>`) is exercised by `rag-chat` and the BGE-M3 + Qwen3-32B pins are unchanged. |
| `docs/adr/05-data-store.md` | (new amendment section) | Add `chat` schema definition + cross-schema-SELECT-from-rag-chat-to-rag+docs note. |

All amendments land in a single PR (the M4 ADR PR) so the doc set advances atomically with the implementation.
