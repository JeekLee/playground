# ADR-14: M4 RAG-Chat — Implementation Decisions

## Status
Accepted

## Context

The M4 spec (`docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md` v1)
pins the rag-chat BC's bounded context, data model, streaming protocol, UX
surfaces, and the supersession set that walks back the immediately-prior
"anonymous-or-signed-in" framing to **auth-only**. It deliberately defers
17 implementation-shape questions to this per-milestone ADR (spec §11) plus
six concrete cross-doc amendments enumerated in spec §13.

ADR-13 already did the same job for M3: pin retry curves, the DLQ topology,
the chunk DDL + indexes, the module quadruplet wiring for a Kafka-only BC.
ADR-13 §4 also confirmed that the **module quadruplet** is universal — every
BC ships as `*-api`, `*-app`, `*-domain`, `*-infra`, even when the entry
point isn't an HTTP controller. This ADR inherits that convention and pins
only the M4-specific additions on top — the SSE controller shape, the
`ChatClient` streaming wiring, the cross-schema SELECT exception for
display-name lookup, the Resilience4j configuration, the Redis token-bucket
key, the auto-title prompt verbatim, and the six cross-doc amendments.

The M4 BC has **no Kafka surface** (PRD §"Bounded Context" + spec §3) —
neither produces nor consumes. The gateway routes
`/api/rag/chat/**` to `rag-chat-api`; the BC reads `rag.document_chunks`
(M3-owned) and `docs.documents` (M2-owned) via cross-schema SELECT. This
shape — request-response BC with cross-schema reads, no Kafka — is a first
for the project and is what makes ADR-05 and ADR-08 the amendment targets,
not ADR-03.

Like ADR-12 and ADR-13, none of the decisions below supersede a transverse
ADR's invariants; they fill in implementation details inside the envelopes
ADR-01, ADR-02, ADR-04, ADR-05, ADR-07, ADR-08, and ADR-09 defined. Six
explicit amendments — to ADR-00 (index), ADR-04 (informational), ADR-05
(`chat` schema), ADR-09 (allowlist + cost protection + new badge convention),
plus `docs/roadmap.md` §M4, `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md`
§8, and `docs/prd/M3-rag-ingestion.md` "M4 retrieval contract" — are
captured at the bottom of this ADR and appended inline to each affected
file in the same PR.

## Decision

### 1. Module quadruplet — `-api` hosts WebFlux SSE controller + session CRUD

**Decision (matches ADR-01 v2's quadruplet pattern with one M4-specific
twist — the `-api` module mixes WebFlux for the streaming endpoint with
servlet-style controllers for session CRUD):**

| Module | Type | Port | What it hosts |
|---|---|---|---|
| `rag-chat-api` | runnable Spring Boot app | **18084** (gateway-routable per ADR-07) | Spring Boot bootstrap, `application.yml`, `@SpringBootApplication`, `/actuator/**`, the **SSE controller** (`POST /api/rag/chat` returning `Flux<ServerSentEvent<String>>`), the **session CRUD controllers** (`POST/GET/PATCH/DELETE /api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/{id}/messages`), auth-header binding (`X-User-Id`, `X-User-Sub`). |
| `rag-chat-app` | Java library | n/a | Use-case orchestrators (`StreamChatTurnUseCase`, `CreateSessionUseCase`, `RenameSessionUseCase`, `DeleteSessionUseCase`, `LoadMessagesUseCase`, `AutoTitleUseCase`), port interfaces (`ChatGenerationPort`, `EmbeddingPort`, `ChunkRetrievalPort`, `SessionRepositoryPort`, `MessageRepositoryPort`, `CitationRepositoryPort`, `TokenBucketPort`, `ConcurrentStreamLockPort`, `OwnerDisplayNamePort`), the history truncator, the prompt assembler, the per-turn auth-and-rate-limit guard composed in front of the streaming pipeline. |
| `rag-chat-domain` | Java library | n/a | `Session`, `Message`, `Citation` aggregates, `PromptTemplate` value object (Spring-free), `CitationMarkerParser` (extracts `[N]` markers from streamed text to drive the cite-persistence policy in §10), `TokenCount` value object. **No Spring imports.** |
| `rag-chat-infra` | Java library | n/a | JPA adapter for `chat.sessions` / `chat.messages` / `chat.message_citations`, Spring AI `ChatClient` adapter (Qwen3-32B streaming + auto-title one-shot), Spring AI `EmbeddingModel` adapter (BGE-M3 query embedding), pgvector retrieval JDBC adapter (with `SET LOCAL hnsw.ef_search = 40` per ADR-05 amendment), `docs.documents` citation-enrichment JDBC adapter (cross-schema SELECT — see §3), `identity.users` display-name JDBC adapter (cross-schema SELECT — see §3), Redisson token-bucket adapter, Redisson `RLock` adapter for the per-user concurrent-stream cap, Resilience4j `CircuitBreaker` config wrapping the Spring AI `ChatClient` and `EmbeddingModel` adapters. |

**Why `-api` hosts both the SSE controller and the session CRUD controllers
(no split into a separate streaming submodule):**

The two surfaces share the same auth-header binding, the same exception
mapping (`AbstractException` per ADR-11), and the same OpenAPI surface.
Splitting them into two `-api` modules would double the bootstrap cost
without buying anything. The SSE controller uses Spring WebFlux's
`@RestController` returning `Flux<ServerSentEvent<String>>`; the session
CRUD controllers can sit in the same module because Spring Boot 3.3 lets
WebFlux and traditional servlet stacks coexist within one app — though
**M4 P0 uses WebFlux for the entire `rag-chat-api` module** (no servlet
stack imported) to keep the streaming path reactive end-to-end. The CRUD
controllers therefore also expose `Mono<ResponseEntity<...>>` shapes; the
gateway treats them identically.

**Considered alternative:** add a `rag-chat-streaming` fifth module that
hosts only the SSE controller. Rejected — adds a fifth bootstrap target
for a personal-scale BC, breaks ADR-01 v2's universal quadruplet contract
(every other BC has exactly four modules), and the SSE controller is just
one `@PostMapping` returning a `Flux` — not enough surface to justify a
module.

**Component placement summary (resolves spec §11 Q2):**

| Component | Module | Notes |
|---|---|---|
| `@PostMapping("/api/rag/chat")` returning `Flux<ServerSentEvent<String>>` | `rag-chat-api` | Binds `X-User-Id`, `X-User-Sub`, request body. Delegates to `StreamChatTurnUseCase`. |
| Session CRUD `@RestController`s (`/api/rag/chat/sessions/**`) | `rag-chat-api` | All return `Mono<ResponseEntity<...>>`; auth-bound; ownership enforced at the SQL layer per spec §5.3. |
| `StreamChatTurnUseCase` (the per-turn orchestrator) | `rag-chat-app` | Composes: auth-check → token-bucket-check → session-validate → concurrent-stream-lock → history-load → embed → retrieve → enrich → prompt-assemble → stream → persist. |
| `AutoTitleUseCase` (fire-and-forget post first user turn) | `rag-chat-app` | Invoked async via `Schedulers.boundedElastic()` after first user message persists. Failures land in WARN log; no retry, no SSE error surface (spec §6 + §11 Q6). |
| `PromptTemplate` (system + retrieval block + history block + current turn assembly) | `rag-chat-domain` | Pure text concatenation per spec §6.1 step 10. Takes `List<Citation>`, `List<Message>`, `String` — no Spring. |
| `CitationMarkerParser` (regex-extracts `[N]` from streamed text) | `rag-chat-domain` | Used post-stream to decide which retrieved citations to persist (§10). Pure regex. |
| `ChatClient.prompt().messages(...).stream().chatResponse()` adapter | `rag-chat-infra` (`SparkInferenceChatAdapter` implements `ChatGenerationPort`) | Spring AI 1.0 GA. Resilience4j-wrapped (§4). |
| `EmbeddingModel.embed(query)` adapter | `rag-chat-infra` (`SparkInferenceEmbeddingAdapter` implements `EmbeddingPort`) | BGE-M3, 1024-dim per ADR-04. Resilience4j-wrapped. |
| pgvector retrieval JDBC adapter | `rag-chat-infra` (`PgvectorChunkRetrievalAdapter` implements `ChunkRetrievalPort`) | Parameterized query per §3.2; `SET LOCAL hnsw.ef_search = 40` per ADR-05 amendment. |
| `docs.documents` title-enrichment JDBC adapter | `rag-chat-infra` (`DocsTitleAdapter` implements `ChunkTitleEnrichmentPort`) | Cross-schema SELECT — see §3. |
| `identity.users` display-name JDBC adapter | `rag-chat-infra` (`IdentityDisplayNameAdapter` implements `OwnerDisplayNamePort`) | Cross-schema SELECT — see §3. |
| Token-bucket counter | `rag-chat-infra` (`RedisTokenBucketAdapter` implements `TokenBucketPort`) | Redisson `RRateLimiter`. Key per §5. |
| Per-user concurrent-stream lock | `rag-chat-infra` (`RedissonConcurrentStreamLockAdapter` implements `ConcurrentStreamLockPort`) | Redisson `RLock`. Key per §G. |
| Resilience4j `CircuitBreaker` config | `rag-chat-infra` | One `CircuitBreaker` named `spark-gateway`, shared by `ChatGenerationPort` + `EmbeddingPort` adapters (§4). |
| Session / Message / Citation JPA `@Entity`s | `rag-chat-infra` | Domain `Session` / `Message` / `Citation` POJOs in `-domain`; persistence model is a separate entity in `-infra` with mapper. Standard ADR-02 split. |
| Flyway migrations for `chat.sessions` / `chat.messages` / `chat.message_citations` | `rag-chat-infra` (`src/main/resources/db/migration/`) | Per ADR-05 per-service migration rule. Full DDL in §F. |

#### 1.5. Cross-schema SELECT exception — no event mirroring needed

The M3 ADR §4.5 documented the "mirror docs's event POJOs in
`rag-ingestion-domain` to avoid cross-BC compile-time coupling" pattern.
**M4 has no equivalent need** — it does not consume any Kafka events from
M2 or M3. The cross-BC reads in M4 are SQL-shaped, not event-shaped; the
schema coupling at SQL layer is governed by §3 below and the ADR-08
amendment in §G.4. No event POJO mirroring in `rag-chat-domain`.

> Amendment (2026-06-01, ADR-14): the M8 cycle adds a **third**
> SQL-shaped cross-schema read into `docs.documents` (the document
> manifest for `[YOUR DOCUMENTS]`) — see §3's table and the
> bottom-of-ADR amendment §A14M8. It is still SQL-shaped, not
> event-shaped; no event POJO mirroring is introduced.

### 2. Port assignment — 18084, gateway-routable

**Decision:** `rag-chat-api` binds **port 18084** on
`localhost`/compose-internal. Already reserved by ADR-01 v2's port
table for M4. Unlike `rag-ingestion-api` (port 18083, actuator-only),
`rag-chat-api` **is** gateway-routable — the gateway forwards
`/api/rag/chat/**` to `http://rag-chat-api:18084` per ADR-07.

| Port | Service | Gateway-routable? | Notes |
|---|---|---|---|
| 18080 | gateway | n/a | OAuth ingress per ADR-07 |
| 18081 | identity-api | yes | M1 |
| 18082 | docs-api | yes | M2 |
| 18083 | rag-ingestion-api | **no** | M3 — actuator-only, host-not-exposed |
| **18084** | **rag-chat-api** | **yes** | **M4 — this ADR** |
| 18085 | (reserved) | — | future |
| 18086 | metrics-api | yes (planned) | M5 |

`server.port: 18084` in `application.yml`. **No host port mapping** in
`infra/docker-compose.yml` — `rag-chat-api` is compose-internal per
ADR-08's "backends must not be host-exposed" rule; all browser traffic
flows through the gateway.

**Considered alternative:** assign port 18085 to leave 18084 for a future
"rag-chat-public" anonymous variant. Rejected — spec §13 permanently
removes anonymous chat from the route surface; no future split is planned.
18084 is the canonical rag-chat port.

### 3. Cross-schema SELECT — `chat,docs,rag,identity` search path; no new HTTP exception for display name

**Decision: cross-schema SELECT from `rag-chat-api` into the `rag`, `docs`,
and `identity` schemas. The JDBC connection's `search_path` is set to
`chat,docs,rag,identity` at startup; the application uses fully-qualified
table names regardless (search_path is fallback / debugging aid, not the
correctness mechanism).**

This is the **first sanctioned cross-schema SELECT exception** in the
project. ADR-05 originally forbade cross-schema joins ("Cross-schema
queries are *technically* possible — discipline is the only barrier").
ADR-08 § amendment block then opened two sanctioned **HTTP** exceptions
(M3→docs body-fetch, docs→identity owner-lookup). M4 takes a different
fork: read-only cross-schema SELECT, no HTTP hop, for three predicates.

| SELECT direction | Predicate | Why cross-schema, not HTTP |
|---|---|---|
| `rag-chat-api` → `rag.document_chunks` | `WHERE visibility='public' OR (user_id=? AND visibility='private') ORDER BY embedding <=> :q LIMIT :k` per spec §6.1 step 6 | Already sanctioned by ADR-05's amendment: "rag-chat reads `rag.document_chunks`" is the canonical retrieval primitive per ADR-13 §G.4. An HTTP retrieval RPC would force every per-turn vector search through a service hop — defeats the purpose of pgvector's HNSW being a sub-millisecond primitive. |
| `rag-chat-api` → `docs.documents` | `SELECT id, title, visibility FROM docs.documents WHERE id IN (:retrieved_doc_ids)` for citation enrichment (spec §6.1 step 7) | Tight latency budget — citation enrichment runs inside the TTFT P95 ≤ 2.0 s envelope per spec §10. Batched single-query JOIN is sub-millisecond; the alternative is a per-citation HTTP fan-out via docs-api's `/internal/docs/public/{id}` route (ADR-12 §2), which would burn ~6 × ~5 ms RTT = 30 ms per turn for no schema-coupling benefit (the SELECT is `(id, title, visibility)` — three stable columns; M2 has not shipped a column rename in the docs BC). |
| `rag-chat-api` → `identity.users` | `SELECT display_name, avatar_url FROM identity.users WHERE id = :user_id` for chat-header rendering (spec §3) | Single-row, single-column-set, called once per session-page-load (not per turn). Could equally well go through identity-api's `/internal/users/by-id/{id}` HTTP route — but that route **does not exist yet** (the existing one is `/internal/users/by-google-sub/{sub}` per ADR-12 §8, lookup by Google sub not by UUID). Adding a new `/internal/**` route to identity-api just for the display-name lookup would be a second amendment to ADR-08; SELECT is the lower-coupling choice given M4 already reads `docs.documents` cross-schema. |
| `rag-chat-api` → `docs.documents` (manifest) | `SELECT id, title, mime_type, extraction_status FROM docs.documents WHERE user_id = ? ORDER BY created_at ASC LIMIT 30` for the `[YOUR DOCUMENTS]` prompt section — **added by the 2026-06-01 (M8) amendment below.** | Third sanctioned SELECT; same SQL-layer posture as the citation-enrichment row above (rag-chat already reads `docs.documents` cross-schema). See the bottom-of-ADR amendment §A14M8 for the full rationale + trade-offs. |

**Considered alternative for display name (spec §11 Q3):** add a new
identity-api `GET /internal/users/by-id/{id}` route and reuse the M2
`docs-api` → `identity-api` HTTP exception pattern (ADR-08 Exception 3).
Rejected because:
- It would add a third sanctioned BC-to-BC HTTP route purely for one
  read; the SELECT path is already opened for `rag.document_chunks`
  and `docs.documents` (the retrieval + citation enrichment are
  load-bearing reasons; display-name follows the same channel as a
  cheap rider).
- The display-name read is **non-critical** (the chat header degrades
  gracefully to the email prefix if the lookup returns no row); a 5 ms
  cross-schema SELECT is preferable to a 5–50 ms HTTP round-trip + retry
  + circuit-breaker plumbing for the same one-row read.
- The four-schema search path (`chat,docs,rag,identity`) is a single
  Hikari datasource configuration line, not a new adapter.

**Cross-schema FK discipline (unchanged from ADR-05):**

- `chat.sessions.user_id` → `identity.users.id`: **app-level FK only.**
  No database-enforced constraint (different schema). M4's
  `CreateSessionUseCase` does not validate the user exists — the
  `X-User-Id` header is trusted per ADR-07 / ADR-09's "backends trust the
  header" rule.
- `chat.message_citations.document_id` → `docs.documents.id`: **app-level
  FK only.** Stale citation rendering per §11 below handles the deleted-doc
  case.
- `chat.message_citations.(document_id, chunk_index)` →
  `rag.document_chunks.(document_id, chunk_index)`: **app-level FK only.**
  Same stale-citation handling.

#### 3.1. Hikari connection configuration

```yaml
# rag-chat-api/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:postgresql://postgres-playground:5432/playground
    username: ${POSTGRES_USER:playground}
    password: ${POSTGRES_PASSWORD:playground}
    hikari:
      maximum-pool-size: 5    # ADR-05 per-service default
      pool-name: rag-chat-pool
      connection-init-sql: "SET search_path TO chat,docs,rag,identity,public"
  jpa:
    properties:
      hibernate:
        default_schema: chat
```

The `connection-init-sql` sets the search path on every checked-out
connection. Hibernate's `default_schema` ensures all `@Entity` writes
land in `chat` (no accidental writes to other schemas). The JDBC adapters
for cross-schema reads (`PgvectorChunkRetrievalAdapter`,
`DocsTitleAdapter`, `IdentityDisplayNameAdapter`) use fully-qualified
table names regardless (`rag.document_chunks`, `docs.documents`,
`identity.users`) — search-path is belt-and-suspenders.

#### 3.2. The pgvector retrieval SQL (verbatim)

```sql
SET LOCAL hnsw.ef_search = 40;                                 -- ADR-05 amendment §G.2
SELECT
  document_id,
  chunk_index,
  text,
  user_id,
  visibility
FROM rag.document_chunks
WHERE visibility = 'public'
   OR (user_id = :x_user_id AND visibility = 'private')
ORDER BY embedding <=> :query_embedding
LIMIT :k;                                                      -- :k = 6 (§7)
```

The `SET LOCAL` runs in the same transaction as the SELECT; no global
mutation. Parameterized — `:x_user_id`, `:query_embedding`, `:k` are
bound via JDBC `?` placeholders. **No string concatenation** of `user_id`
into the WHERE clause; tenant isolation is enforced by parameter binding,
matching the M3 ADR §F invariant.

### 4. Circuit breaker — Resilience4j 2.2.x wrapping a shared `spark-gateway` breaker

**Decision: Resilience4j 2.2.x. The library is added as a new dependency
for M4; not used before. One `CircuitBreaker` named `spark-gateway` is
shared by both adapters (`ChatGenerationPort` + `EmbeddingPort`) — they
hit the same upstream (`spark-inference-gateway` at `host.docker.internal:10080`
per ADR-04) and a sustained 5xx burst on either path means the same
upstream is sick.**

| Concern | Pin |
|---|---|
| Library | `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` + `resilience4j-reactor:2.2.0` (reactive support for `ChatClient.stream()`'s `Flux<ChatResponse>`) |
| Breaker name | `spark-gateway` (single shared instance) |
| Failure-rate threshold | **50 %** over a **sliding window of 60 seconds** (spec §6.4 working number, confirmed) |
| Minimum number of calls | **10** (avoid tripping on 0/0 = 0 % in the first second) |
| OPEN duration | **30 s** (spec §6.4 confirmed) |
| Permitted calls in HALF_OPEN | **1** (single probe per spec §6.4) |
| Exception classification | `WebClientResponseException.5xx`, `IOException`, `TimeoutException` count as failure. `WebClientResponseException.4xx` does NOT count as failure (per ADR-13 §2.5: 4xx is a code/data bug, not a gateway-health signal). |
| Metric exposure | Micrometer auto-binding via `resilience4j-micrometer`. Metrics: `resilience4j_circuitbreaker_state{name="spark-gateway"}`, `resilience4j_circuitbreaker_calls{name="spark-gateway",kind="successful|failed|not_permitted|ignored"}`. |

**Considered alternatives (spec §11 Q4):**

- **Spring Cloud Circuit Breaker** abstraction. Rejected: adds a layer
  between the application and Resilience4j without buying anything;
  Spring AI 1.0 GA is already on Spring Boot 3.3 and Resilience4j 2.x is
  the native fit. Spring Cloud Circuit Breaker is useful when juggling
  Hystrix / Sentinel / Resilience4j swaps; M4 has no such requirement.
- **Hand-rolled `AtomicReference<State>` with manual transitions.**
  Rejected: half-open probe + sliding-window failure-rate is non-trivial
  to get right; Resilience4j has been doing it correctly for years; the
  cost of one starter dependency is far below the maintenance cost of a
  hand roll.

**Reactor wiring** (the streaming path):

```java
// rag-chat-infra
@Bean
CircuitBreaker sparkGatewayBreaker(CircuitBreakerRegistry registry) {
  return registry.circuitBreaker("spark-gateway", CircuitBreakerConfig.custom()
      .failureRateThreshold(50)
      .slidingWindowType(SlidingWindowType.TIME_BASED)
      .slidingWindowSize(60)
      .minimumNumberOfCalls(10)
      .waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(1)
      .recordExceptions(WebClientResponseException.class, IOException.class, TimeoutException.class)
      .ignoreExceptions(WebClientResponseException.BadRequest.class,
                        WebClientResponseException.NotFound.class,
                        WebClientResponseException.UnprocessableEntity.class)
      .build());
}

// SparkInferenceChatAdapter
Flux<String> streamTokens(Prompt prompt) {
  return chatClient.prompt(prompt).stream().content()
      .transformDeferred(CircuitBreakerOperator.of(sparkGatewayBreaker));
}
```

Breaker OPEN → the `Flux` errors with `CallNotPermittedException`, which
the SSE controller maps to an `error` event with code `GATEWAY_5XX` and a
`retryAfter` hint of 30 seconds (the OPEN duration).

### 5. Per-user token bucket — Redis-only via Redisson `RRateLimiter`

**Decision: Redis-only via Redisson `RRateLimiter`. No Postgres rollup
table at M4 P0.**

| Concern | Pin |
|---|---|
| Library | `org.redisson:redisson-spring-boot-starter:3.34.x` (already on the classpath from M3 per ADR-13 §C — confirmed reused, no new dependency) |
| Primitive | `RRateLimiter` (Redisson's distributed token bucket) |
| Bucket key | `rag-chat:bucket:user:{userId}` |
| Hourly limit | **60 completions / hour / user** (spec §8.2 working number, confirmed) |
| Daily limit | **200 completions / day / user** (spec §8.2 working number, confirmed) |
| Two-tier enforcement | Two `RRateLimiter` instances with the same userId key but different rate windows. Hourly bucket: `rag-chat:bucket:user:{userId}:hourly` (60 tokens, 1-hour window). Daily bucket: `rag-chat:bucket:user:{userId}:daily` (200 tokens, 1-day window). Both checked per turn; whichever depletes first → 429. |
| Bucket-key identifier scope (spec §11 Q17) | **`userId` only.** Not `userId + X-User-Sub`. Justification below. |
| TTL | Buckets self-expire after their refill window; no explicit TTL needed. Redisson manages key lifetime. |
| Retry-After header | The smaller of (hourly bucket refill ETA, daily bucket refill ETA), in seconds, returned as `Retry-After: <n>` on the 429 response per spec §5.1. |

**Considered alternatives (spec §11 Q5):**

- **Postgres rollup table `chat.usage_daily`** (PK `(user_id, day)`,
  columns `(completions_count, tokens_in_sum, tokens_out_sum)`). Rejected
  for P0: writes one row per user per day, and a per-turn UPDATE on each
  `done` event. The Redis bucket already gives us at-least-once accuracy
  for rate-limit decisions; Postgres would be a redundant audit copy.
  **Reconsider in M5** when the metrics dashboard wants a historical
  "tokens-by-user-by-day" view; at that point the rollup table is a
  better fit than Micrometer counters (which lose per-day granularity).
- **Hand-rolled token bucket** with a Lua script in Redis. Rejected:
  Redisson already provides `RRateLimiter` with sliding window semantics;
  the Lua-script path is what you reach for if Redisson is unsuitable,
  not the first answer.

**Why `userId` only as the bucket key (spec §11 Q17):**

`X-User-Sub` is the Google `sub` claim — stable per Google account,
1:1 with `identity.users.id` (per ADR-10's mapping rule). A user signing
in from two browsers / two devices uses the same `identity.users.id`
across both sessions; the same Google account → same `sub` → same `userId`.
Bucket key `userId` already collapses all of a user's surfaces into one
quota. Adding `sub` to the key would either (a) be a no-op (same `sub`
across browsers, same key — adds a string concatenation for nothing)
or (b) split the bucket when `sub` is somehow absent (gateway bug);
neither helps the user nor the operator. Stick with `userId`.

### 6. Auto-title — Qwen3-32B one-shot, pinned system prompt, fire-and-forget

**Decision: Qwen3-32B (same model as generation, per spec §11 Q6's "obvious
answer"). One-shot non-streaming call via Spring AI's `ChatClient.call()`
(not `stream()`). Fire-and-forget — failure path leaves `title='New chat'`,
no retry, no SSE error surface.**

The auto-title call is invoked from `AutoTitleUseCase` in `rag-chat-app`
on a `Schedulers.boundedElastic()` thread immediately after the first
user message persists. The triggering event is the user's first message
on a session (i.e., the session has exactly one row in `chat.messages`
with `role='user'` after the insert commits) — checked inside the same
transaction or as an after-commit hook.

**Auto-title system prompt (pinned verbatim):**

```
You generate a concise 2 to 6 word title for a chat conversation.
The user just sent their first message in a new chat session.
Read the message and produce a title that captures the topic.

Rules:
- Output ONLY the title. No quotes, no punctuation at the end, no explanation.
- 2 to 6 words. Use Title Case for English, plain spacing for Korean.
- Match the language of the user message (English in -> English out; Korean in -> Korean out).
- If the message is too short or vague to title (one-word, "hi", "test"), output: New chat
- Do not invent specifics not present in the message.
```

**Auto-title user prompt:** the literal text of the user message,
trimmed, capped at 1 KB (sentence the message is the system prompt's
input; longer than 1 KB is a low-signal long ramble — truncate to first
1 KB, no ellipsis appended, the auto-titler gets enough).

**Auto-title generation options:**

| Option | Value |
|---|---|
| `model` | `Qwen3-32B` (inherited from ADR-04; no override) |
| `temperature` | **0.1** (lower than the chat default of 0.2 — titles should be deterministic) |
| `max_tokens` | **24** (2–6 words × ~4 tokens each, with headroom) |
| `top_p` | default |

**Result handling:**

- Trim whitespace, drop trailing punctuation, cap to 60 chars (defensive
  — the prompt should keep it short, but a misbehaving model could blow
  past 6 words).
- If the trimmed result is empty or contains only the literal `New chat`,
  no update (the column already defaults to `'New chat'`).
- Otherwise, `UPDATE chat.sessions SET title = ? WHERE id = ? AND title = 'New chat'` —
  the `title = 'New chat'` predicate prevents the auto-titler from
  overwriting a manual rename that races the call.

**Failure path:**

- Resilience4j breaker OPEN → auto-title skipped silently, WARN-logged.
- Spring AI 4xx / 5xx exhausted → WARN-logged, `title` stays `'New chat'`.
- Auto-title MUST NOT block or fail the user's first turn — the streaming
  response keeps flowing regardless.

**Considered alternative (spec §11 Q6):** use a smaller/cheaper model
for titles. Rejected — `spark-inference-gateway` exposes Qwen3-32B and
BGE-M3 only (per ADR-04); there is no separately-served smaller chat
model. Adding one would require a separate vLLM instance, a new ADR-04
amendment, and infra work disproportionate to the 24-token title cost.
Qwen3-32B at 24 max tokens costs ~50–100 ms — invisibly cheap.

### 7. Retrieval K — 6, configurable via `application.yml`

**Decision: K = 6, exposed as `playground.rag-chat.retrieval.k` in
`application.yml`.**

| Option | Trade-off | Choice |
|---|---|---|
| K = 4 | Smaller prompt, tighter precision, but recall drops for multi-aspect questions ("compare ADR-13 and ADR-12"). | Rejected |
| **K = 6** | Working sweet spot per spec §6.3. ~2400 tokens of retrieval context (6 × ~400 tokens/chunk) — comfortably inside the 32k context window's retrieval slice. Recall on the M3 P0 corpus (≤50k chunks per ADR-13 §9) is >95 % at this K against the same HNSW ef_search=40 used in M3's index. | **Chosen** |
| K = 10 | More context per turn, more cost (~4000 retrieval tokens), and the history budget shrinks correspondingly. Marginally better for compound questions. | Rejected for P0; revisit if user feedback shows compound-question recall is the bottleneck. |

**Why configurable, not constant:** the same reasoning as ADR-13 §1's
chunking parameters — operator can override via env var
(`PLAYGROUND_RAG_CHAT_RETRIEVAL_K=8`) for experiments without recompile.

```yaml
# rag-chat-api/src/main/resources/application.yml
playground:
  rag-chat:
    retrieval:
      k: 6
      ef-search: 40             # set as SET LOCAL per query, ADR-05 amendment §G.2
    prompt:
      max-history-tokens: 24576
      max-completion-tokens: 4000
      system-prompt-budget-tokens: 200
      retrieval-block-token-budget: 2400
```

A `RagChatRetrievalProperties` `@ConfigurationProperties` POJO binds
these in `rag-chat-app` (Spring-free imports — only
`@ConfigurationProperties` from `spring-boot-starter`, which is acceptable
in `-app` per ADR-02; the `-domain` `MarkdownChunker`-style strictness
does not extend to `-app`).

### 8. Token budget split — 200 + 2400 + 24576 + 4000 ≈ 31176 (under 32k)

**Decision: confirmed verbatim per spec §6.3.**

| Slot | Tokens | Notes |
|---|---|---|
| System prompt | **200** | The grounding instruction per spec §6.1 step 10. Counted with `cl100k-base` (JTokkit, same tokenizer as ADR-13 §1; not BGE-M3's tokenizer — we use it for budget bookkeeping, not for prompt construction). |
| Retrieval block | **2400** | K=6 × 400 tokens/chunk. Each chunk's text is truncated to 400 tokens via the same JTokkit cl100k tokenizer before insertion into the `[RETRIEVED CONTEXT]` block. If the chunk is shorter than 400 tokens it goes in whole; longer is head-truncated (keep the first 400 tokens of the chunk). Justification: M3's chunker emits 800-token chunks (ADR-13 §1); halving each chunk for the prompt is a budget choice — full 800-token chunks at K=6 = 4800 tokens, leaves less history room. The 400-token truncation is the K=6 design's intentional trade. |
| History | **24576** | Working budget per spec §6.3. Truncation policy: §9 below. |
| Assistant completion (`max_tokens`) | **4000** | Spring AI option `max_tokens=4000` per spec §8.2. |
| **Sum** | **31176** | Under Qwen3-32B's 32 768 context window by 1592 tokens of safety margin (covers `[SYSTEM]`/`[RETRIEVED CONTEXT]`/`[CONVERSATION SO FAR]`/`[CURRENT TURN]` literal headers + the `[N]` markers in retrieved chunks). |

**Headroom note:** if the user's first message itself is near 1024 tokens
(rare — spec §5.1 caps the user message at 4 KB raw which is ~1024
tokens), the history budget is taken from the working 24576 by the
current-turn-message tokens before truncation. Mathematically:
`available_history = 24576 − tokens(current_user_message)`.

**Considered alternative (spec §11 Q8):** revise the split to give more
to history (e.g., 200 + 1800 [K=6 × 300] + 25776 + 4000 = 31776). Rejected
— shorter retrieval-block-per-chunk means lower retrieval recall per
chunk, which is precisely the variable K=6 is meant to optimize. Better
to keep chunks at 400 tokens and drop oldest turns when the history is
long, than to shrink chunks and keep more history.

### 9. Conversation truncation policy — drop oldest turn pairs

**Decision: drop oldest turn pairs (user + assistant) until the history
fits. Never truncate mid-turn. P0 default per spec §6.3 confirmed.**

| Option | Trade-off | Choice |
|---|---|---|
| **Drop oldest turn pairs** | Simplest. Each call computes total history token count; drops `(user_i, assistant_i)` pairs from the head until the remaining is ≤ `max-history-tokens - current-user-message-tokens`. | **Chosen** |
| Summarize-and-replace | Better long-conversation continuity, but requires another LLM call per turn — doubles `spark-inference-gateway` round-trips on every dropped turn. Cost and latency cost unjustified for personal-scale P0. | Rejected; revisit if real users hit the truncation horizon often. |
| Sliding window of last N turns regardless of token count | Cheap, but ignores the actual budget — short turns and long turns get the same treatment. | Rejected. |

**Algorithm:**

```text
function truncateHistory(turns, currentUserMessageTokens):
  budget = max-history-tokens - currentUserMessageTokens
  total = sum(tokens(turn.content) for turn in turns)
  while total > budget AND turns.length >= 2:
    drop turns[0]                       # user
    drop turns[0] (now the assistant)   # assistant of same turn pair
    total = sum(tokens(turn.content) for turn in turns)
  return turns
```

**Edge case:** if even the empty history (`turns = []`) plus the current
user message exceeds the budget — i.e., the user typed a 30k-token
message — the request is rejected with **413 Payload Too Large** before
the prompt assembly even begins. Spec §5.1 already caps the user message
at 4 KB raw (≤1024 tokens); the 413 is defensive against a future cap
relaxation.

**The truncated turns are gone from this prompt only.** They stay in
`chat.messages` forever (or until the session is deleted); the page
reload renders them; the next turn re-computes truncation from scratch.
This guarantees the database is the source of truth and prompts are
budget-managed views.

### 10. Cite-persistence policy — only citations whose `[N]` marker actually appeared

**Decision: persist only the citations whose `[N]` marker actually appeared
in the streamed assistant text. Retrieved-but-not-cited chunks are NOT
written to `chat.message_citations`.**

| Option | Trade-off | Choice |
|---|---|---|
| Persist all K retrieved citations | Simple: just dump the `retrieval` event's payload into `chat.message_citations` on `done`. But stores 6 rows when the LLM only cited 2 — fills the table with rows that the UI accordion will render but the assistant text never references. | Rejected |
| **Persist only the cited subset** | Semantic — the persisted citations exactly match the visible `[N]` superscripts in the rendered assistant text. Frontend reload renders the same accordion as the live stream. | **Chosen** |
| Persist all, but mark a `was_cited` boolean | Adds a column to `chat.message_citations` for no UX benefit (the frontend only ever shows the cited subset). | Rejected |

**Mechanism:**

1. Stream completes (last `token` event flushed, before `done`).
2. `CitationMarkerParser.extractMarkers(assistantText)` returns
   `Set<Integer>` — the 1-indexed `[N]` values that appeared. Regex:
   `\[(\d+)\]` (greedy, no nesting concerns since `[N]` is a flat marker).
3. Filter the in-memory `retrieval` event's citation list to only those
   whose `n ∈ extracted`.
4. Bulk insert filtered rows into `chat.message_citations`
   (`(message_id, position, document_id, chunk_index)` — `position`
   is the `n` from the retrieval event, preserved).
5. Emit `done` SSE event with the persisted `messageId`.

**Edge case:** the LLM cites a `[N]` that does not exist in the retrieval
set (e.g., model hallucinated `[9]` when K=6 returned 6 chunks). The
extraction includes `9` but the filter step finds no matching citation;
the row is silently dropped. The frontend's `CitationMarker` component
renders the orphan marker as plain text (no pill) — the UX degrades to
"LLM said `[9]` but no document is linked", which is acceptable.

**Considered alternative (spec §11 Q10):** persist all retrieved citations
but render only the cited subset. Rejected — the frontend has to filter
anyway, and the unused rows are dead weight on the row count per session
(at K=6, ~4× growth on `chat.message_citations` for the same UX).

### 11. Stale-citation rendering — `(deleted)` placeholder, no chunk_index exposed

**Decision: when the citation's `document_id` no longer resolves (the
docs row was deleted post-cite), render the accordion entry as
`[N] (deleted) — 이 문서는 더 이상 사용할 수 없습니다`. No `↗ open` link.
The `chunk_index` is NOT displayed in the deleted state.**

| Concern | Pin |
|---|---|
| Trigger | `GET /api/rag/chat/sessions/{id}/messages` join to `docs.documents` returns null `title` for the cited `document_id` (the row is gone). |
| Visible string (Korean) | `이 문서는 더 이상 사용할 수 없습니다` (matches spec §4.3's existing copy) |
| Visible string (English) | `(deleted — this document is no longer available)` (used when the rendered locale is English; the frontend i18n decides) |
| Display of `chunk_index` | **hidden in deleted state.** The chunk_index has no actionable value for the user once the document is gone, and showing it leaks the implementation detail (M3's chunking shape) for no benefit. |
| `↗ open` link | **hidden in deleted state.** No target to link to. |
| Database row | **NOT deleted.** The `chat.message_citations` row stays (orphan FK by design). A future re-upload of the same `document_id` (theoretically possible but unlikely under M2's UUID-PK generation) would un-orphan the row; M4 P0 does not engineer for that. |

**Considered alternative (spec §11 Q11):** delete the citation row when
the docs row is deleted (subscribe to `docs.document.deleted` Kafka
event from rag-chat). Rejected — adds a Kafka consumer to a BC that
otherwise has zero Kafka surface (spec §3, PRD §"Bounded Context"); the
graceful-degrade-at-read-time path is strictly simpler and the audit
log of "this conversation cited a now-deleted doc" is arguably more
useful preserved than scrubbed.

### 12. Empty-state suggestions — three pinned strings (Korean primary, English fallback)

**Decision: pin the three static suggestion strings below. The frontend
picks the locale based on the user's `Accept-Language` (or the
playground's i18n provider, when M2.1 introduces one).**

| # | Korean (primary) | English (fallback) |
|---|---|---|
| 1 | `최근에 올린 문서를 요약해 줘` | `Summarize my recent uploads` |
| 2 | `이 주제에 대해 내 공개 문서에 뭐가 있어?` | `What do my public docs say about this topic?` |
| 3 | `ADR-13의 chunking 정책이 어떻게 되지?` | `What is the chunking policy in ADR-13?` |

**Rationale for the three:**

- (1) is a low-friction starter that exercises retrieval against the
  caller's own corpus (private + public) — gives the user immediate
  signal that retrieval is working.
- (2) is a meta-prompt that the user fills in by editing — the chip
  doesn't auto-send (per spec §7.5), so the user gets to substitute
  their own topic.
- (3) is an example of citing a known artifact in the playground itself
  — encourages the "ask about my own ADRs / specs" use case which is
  the playground's distinctive surface.

**Considered alternative (spec §11 Q12):** dynamic suggestions sourced
from the caller's recent docs. Deferred to M4.1 per spec §2 — the
backend would need a new endpoint `GET /api/rag/chat/suggestions` that
queries `docs.documents WHERE user_id = ? ORDER BY updated_at DESC LIMIT 3`
and turns titles into prompts; out of scope for P0.

### 13. Stop button → assistant message NOT persisted

**Decision: confirmed per spec §6.1 step 12. The partial assistant message
text accumulated up to the abort is NOT inserted into `chat.messages`.
The user message persisted in step 9 stays.**

| Concern | Pin |
|---|---|
| User message row (`role='user'`) | **persisted.** Inserted in §6.1 step 9 (before the stream starts). Page reload after abort shows the user turn. |
| Assistant message row (`role='assistant'`) | **NOT persisted.** No `INSERT` is issued on the abort path. Page reload after abort shows the user turn alone with no assistant reply. |
| Citation rows | **NOT persisted.** Conditional on the assistant row existing per the FK / data-flow chain. |
| `tokens_in` / `tokens_out` | **NOT recorded** (no assistant row to record on). The Micrometer counter `playground.rag_chat.tokens` does NOT increment for aborted turns. |
| Telemetry | The abort itself is logged at INFO with `sessionId`, `userId`, `bytesEmitted`, `tokensEmittedEstimate`. Used for cost-analytics but does not count against the user's rate limit's daily total. |

**Considered alternative (spec §11 Q13):** persist the partial text with a
flag `aborted=true` so the user sees "you stopped me halfway" on reload.
Rejected — adds a column to `chat.messages` (`aborted BOOLEAN NOT NULL DEFAULT FALSE`)
for a UX that the user already saw (the greyed-out partial text + "Generation
stopped" footer) and immediately discarded by clicking Stop. The page-reload-
shows-no-assistant behavior matches user intent ("I stopped that, it didn't
help"); a persisted partial is information the user actively rejected.

### 14. Streaming abort signaling — Reactor cancellation propagates to the upstream HTTP request

**Decision: rely on Reactor's `Subscription.cancel()` propagating through
Spring AI's `WebClient` to the underlying HTTP request, which closes the
upstream connection to `spark-inference-gateway`. vLLM (the OpenAI-compatible
server backing spark-inference-gateway) does observe client disconnects on
its `/v1/chat/completions` streaming endpoint and stops generation when the
connection closes — pinning the assumption here with the verification steps
to confirm during implementation.**

| Concern | Pin |
|---|---|
| Server-side trigger | The SSE controller's returned `Flux<ServerSentEvent<String>>` subscribes downstream into `ChatClient.stream().chatResponse()`. When the client disconnects (browser close, tab switch, Stop button → `EventSource.close()`), Spring WebFlux signals `cancel()` on the upstream subscription. |
| Spring AI behavior | `ChatClient.stream()` uses `WebClient` internally to call `spark-inference-gateway`. `cancel()` propagates → `WebClient` disposes the response → the underlying Netty connection sends FIN → vLLM detects the disconnect (vLLM emits `Client disconnected from /v1/chat/completions` in its logs). |
| Verification (implementer task at backend-implementation Stage 3) | Pre-flight test: start a chat turn that requests `max_tokens=4000`, abort 500 ms in, observe `spark-inference-gateway` logs to confirm the request enters its "client disconnected" branch and frees its GPU slot. If observation shows vLLM keeps generating to completion despite disconnect, file an upstream issue and add a watchdog (a 30s timeout on the `Flux` that explicitly `dispose()`s the upstream). |
| Watchdog (defensive) | The `Flux<ChatResponse>` is wrapped with `.timeout(Duration.ofSeconds(35))` — covers spec §10's "End-to-end stream P95 ≤ 20 s" with 75 % headroom. Beyond 35 s the breaker forcibly cancels regardless of whether the client is still connected. |
| Effect on the user's quota | Aborted turns do NOT consume the rate-limit bucket. The bucket is decremented on `done` event success only — abort path does not call the `RRateLimiter.tryAcquire()` debit. (Note: the bucket's permit IS taken at the start of the turn — if abort, the permit is refunded via Redisson's `RRateLimiter.consume(-1)` defensive call. The refund is best-effort; the worst case is a small "ghost permit" leak that the hourly refill window self-heals.) |

**Considered alternative (spec §11 Q14):** assume vLLM keeps generating
post-disconnect and bill the tokens regardless. Rejected as the working
assumption for cost reasons; the verification step is a real risk-mitigation,
not a rubber stamp. If verification fails, this ADR is amended with the
watchdog dispatch path as the fallback.

### 15. `X-User-Sub` audit logging — included on every state transition

**Decision: log `X-User-Sub` (Google `sub` claim) on every chat audit log
record alongside `X-User-Id`.**

| Field | Mandatory? | Why |
|---|---|---|
| `userId` (= `X-User-Id`, UUID) | yes | Primary correlation key. Joins to `identity.users.id` and `chat.sessions.user_id`. |
| `userSub` (= `X-User-Sub`, Google `sub` string) | **yes** | Secondary correlation key. Lets the operator answer "which Google account?" without joining to `identity.users`. Useful for cross-system debugging (e.g., a Google account misconfiguration that's making OAuth flap). |
| `sessionId` | yes (per state transition) | Per spec §10. |
| `messageId` | when applicable (turn-level events) | Per spec §10. |
| `eventType` | yes | `turn_start`, `retrieval_done`, `stream_start`, `stream_end`, `stream_abort`, `stream_error`, `auto_title_success`, `auto_title_failed`. |
| `tokensIn` / `tokensOut` | turn-level, on `stream_end` | Cost telemetry. |
| `latencyMs` | yes | TTFT or per-event latency. |
| `errorCode` | on `stream_error` only | Per spec §6.5. |

**PII consideration:** `userSub` is a Google-provided opaque identifier
(numeric string, not a user-typed email or name). It is **not** PII in
the GDPR sense — it is a pseudonymous account locator. Logging it
alongside `userId` adds a debugging axis without a privacy regression.
ADR-09's "Anonymous identity contract" is unaffected — for the public
routes (M2 surfaces) the headers remain absent; chat is auth-only so the
audit log always carries both.

**Log shape (Logback JSON encoder, M1 ADR-10 §11 inheritance):**

```json
{
  "timestamp": "2026-05-18T12:34:56.789Z",
  "level": "INFO",
  "logger": "rag-chat.StreamChatTurnUseCase",
  "userId": "11111111-2222-3333-4444-555555555555",
  "userSub": "108234567890123456789",
  "sessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "messageId": "ffffffff-1111-2222-3333-444444444444",
  "eventType": "stream_end",
  "tokensIn": 1873,
  "tokensOut": 421,
  "latencyMs": 14210,
  "message": "Chat turn completed successfully"
}
```

**Considered alternative (spec §11 Q15):** log `userId` only, on the
principle of "least privilege in logs". Rejected because `userSub` is
not sensitive and its absence in logs makes the M5 dashboard's "this
Google account is hammering chat" investigation needlessly join-heavy.

### 16. Contract test fixture strategy — WireMock + Testcontainers (mirrors ADR-13 §13)

**Decision: same shape as ADR-13 §13.**

| Test layer | Stack | What it covers |
|---|---|---|
| `rag-chat-domain` unit tests | Pure JUnit 5 | `PromptTemplate` assembly invariants (exact string output for known inputs), `CitationMarkerParser` regex correctness on `[N]` markers (single, multi, nested-bracket false positives, etc.), `TokenCount` arithmetic. No Spring, no I/O. |
| `rag-chat-app` slice tests | `@SpringBootTest(classes = {...})` + Mockito for all ports | Use-case behavior — given a mocked `EmbeddingPort` returning `[1024 floats]`, mocked `ChunkRetrievalPort` returning 6 chunks, mocked `ChatGenerationPort` emitting a controlled `Flux<String>`, asserts the use-case persists exactly one user message + one assistant message + the cited subset of citations. Includes the cite-persistence invariant test from §10. |
| `rag-chat-infra` integration tests | **Testcontainers** for: `pgvector/pgvector:pg16` (Postgres + pgvector, ADR-05), `redis:7-alpine` (Redisson rate-limit + concurrent-stream lock). **WireMock** for `spark-inference-gateway` (BGE-M3 `/v1/embeddings` + Qwen3-32B `/v1/chat/completions` streaming). | Real JPA against real Postgres; real Redisson against real Redis; real WebClient against WireMock-served streaming endpoints. The streaming WireMock stub uses `org.wiremock.extensions:streaming` or `Transfer-Encoding: chunked` to emulate SSE-ish chunks. |
| `rag-chat-api` end-to-end tests | Full `@SpringBootTest` with all Testcontainers above + WireMock | Hits the actual `POST /api/rag/chat` endpoint via `WebTestClient.post().retrieve().bodyToFlux(ServerSentEvent.class)`, asserts the SSE event order (`retrieval` → `token` × N → `done`), asserts DB state, asserts rate-limit / circuit-breaker code paths (force WireMock to return 5xx, verify breaker OPEN → 503). |

**WireMock stub files:**
- `rag-chat-infra/src/test/resources/wiremock/spark-inference-gateway/chat-completions-stream-happy.json` — golden 4-chunk streaming response.
- `rag-chat-infra/src/test/resources/wiremock/spark-inference-gateway/embeddings-1024d.json` — fixed 1024-dim response for the query-embedding call.
- `rag-chat-infra/src/test/resources/wiremock/spark-inference-gateway/chat-completions-500.json` — 5xx response for circuit-breaker tests.

**Why not Spring Cloud Contract:** same rationale as ADR-13 §13 —
`spark-inference-gateway` is external; no producer-side contract artifact
exists. WireMock with response bodies copied from real vLLM samples is
the proportionate fixture strategy.

**Concurrency tests** (the per-user concurrent-stream cap from §G below)
use two Reactor `WebTestClient` instances pointing at the same `userId`,
post the second turn while the first is still emitting tokens, and assert
the first stream emits `error: ABORTED` (or closes) while the second
proceeds.

### 17. Streaming response Reactor schedulers — Spring AI defaults plus bounded-elastic for auto-title

**Decision: use Spring AI's built-in scheduling for the streaming `Flux<ChatResponse>`
(it manages its own boundary between WebClient I/O and the application thread).
The application code does NOT impose additional `.publishOn()` / `.subscribeOn()`
wrappers around the chat stream. The one exception is the fire-and-forget
auto-title call (§6), which is dispatched on `Schedulers.boundedElastic()`
so its blocking-by-default Spring AI `call()` path doesn't pin a request
thread.**

| Concern | Pin |
|---|---|
| Chat streaming (`ChatClient.stream()`) | Spring AI default. Spring WebFlux handles the SSE emission on the WebFlux event-loop. No custom scheduler. |
| Query embedding (`EmbeddingModel.embed()`) | Spring AI default — the `EmbeddingModel` is non-blocking (returns `Mono`), so the embedding call inlines on the request's reactive pipeline. |
| pgvector retrieval JDBC call | Hibernate / JDBC is blocking; the adapter wraps the SELECT in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Same pattern as `DocsTitleAdapter` and `IdentityDisplayNameAdapter`. |
| Auto-title call (§6) | `Schedulers.boundedElastic()` — the fire-and-forget call is launched from the request thread but allowed to run to completion on a worker thread; the request thread returns immediately. |
| Citation-marker extraction | Pure CPU work, runs inline on whichever thread is finalizing the stream. No scheduler. |

**Considered alternative (the brief's option F suggestion):** explicit
`Schedulers.parallel()` for retrieval and `Schedulers.single()` for SSE
emission. Rejected — Spring WebFlux already manages SSE emission on its
event loop; layering a `.publishOn(Schedulers.single())` would funnel all
emissions through a single thread and serialize across all users — a
contention regression. The default behavior is correct.

**Why not switch to `Mono`/non-blocking JDBC (R2DBC):** R2DBC is not on
the project's stack (M3 + M2 use JPA blocking access per ADR-05); switching
M4 alone would split the data-access pattern. The boundedElastic-wrapped
JDBC call adds ~50 µs of scheduler overhead per retrieval — negligible
inside the 2 s TTFT budget.

## Additional decisions (not in spec's question list but architect-owned)

### A. Compose service container name — `rag-chat-api`

**Decision:** the compose service is named **`rag-chat-api`** (matches
the runnable module name from ADR-01 v2, mirrors M1's `identity-api`,
M2's `docs-api`, and M3's `rag-ingestion-api`). The brief's specified
name `rag-chat-playground` is NOT used — the `-playground` suffix is
reserved for shared infrastructure services (`postgres-playground`,
`kafka-playground`, `redis-playground`, `opensearch-playground`); BC
services keep their module name as the container name. This matches
ADR-13 §B's discipline verbatim.

**Compose service block specification** (for infra-implementer to
transcribe verbatim into `infra/docker-compose.yml` in Stage 3):

```yaml
  # --- M4: rag-chat-api (RAG-Chat BC quadruplet runnable per ADR-01 v2 + ADR-14) ---
  rag-chat-api:
    build:
      context: ../backend
      dockerfile: rag-chat/rag-chat-api/Dockerfile
    container_name: rag-chat-api
    depends_on:
      postgres-playground:
        condition: service_healthy
      redis-playground:
        condition: service_healthy
      gateway:
        condition: service_started     # gateway must be up for /api/rag/chat/** routing
    extra_hosts:
      - "host.docker.internal:host-gateway"   # per ADR-04 — reach spark-inference-gateway
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-default}
      POSTGRES_HOST: ${POSTGRES_HOST:-postgres-playground}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:-playground}
      POSTGRES_USER: ${POSTGRES_USER:-playground}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-playground}
      REDIS_HOST: ${REDIS_HOST:-redis-playground}
      REDIS_PORT: ${REDIS_PORT:-6379}
      SPRING_AI_OPENAI_BASE_URL: ${SPRING_AI_OPENAI_BASE_URL:-http://host.docker.internal:10080}
      SPRING_AI_OPENAI_API_KEY: ${SPRING_AI_OPENAI_API_KEY:-dummy-not-used}
      PLAYGROUND_RAG_CHAT_RETRIEVAL_K: ${PLAYGROUND_RAG_CHAT_RETRIEVAL_K:-6}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:18084/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
    # NO `ports:` block — rag-chat-api is compose-internal only (ADR-08).
    # Browser traffic reaches it via gateway:18080 → /api/rag/chat/** → http://rag-chat-api:18084.
```

**Notable absences:** no `depends_on: kafka-playground` (rag-chat has no
Kafka surface per spec §3), no `depends_on: opensearch-playground` (M4
uses pgvector only for retrieval; OpenSearch is M2-owned), no
`depends_on: docs-api` / `rag-ingestion-api` (cross-schema SELECT is JDBC
to `postgres-playground`, not HTTP). The dependency graph is the
shallowest of any BC at M4 — only Postgres, Redis, gateway, and the host
inference gateway.

### B. Library versions

Inherited from M1 (ADR-10), M2 (ADR-12), M3 (ADR-13) where applicable;
M4-specific additions listed:

| Coordinate | Version source / pin | Why |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-webflux` | Spring Boot **3.3.x BOM** (inherited) | Reactive controllers for SSE streaming + non-blocking `Mono`/`Flux` request handling |
| `org.springframework.boot:spring-boot-starter-data-jpa` | Spring Boot 3.3.x BOM | `chat.*` persistence + cross-schema JDBC reads (via blocking adapter wrapped in `Schedulers.boundedElastic`) |
| `org.postgresql:postgresql` | Spring Boot 3.3.x BOM | JDBC driver |
| `com.pgvector:pgvector` | **0.1.6** (same as ADR-13) | pgvector Hibernate dialect + `Vector` type for the retrieval adapter's parameter binding |
| `org.flywaydb:flyway-core` | Spring Boot 3.3.x BOM | Per-service migrations for the `chat` schema |
| `org.springframework.ai:spring-ai-openai-spring-boot-starter` | Spring AI **1.0.0** GA (per ADR-04) | Qwen3-32B chat streaming + BGE-M3 query embedding |
| `io.github.resilience4j:resilience4j-spring-boot3` | **2.2.0** | New for M4 — circuit breaker (§4) |
| `io.github.resilience4j:resilience4j-reactor` | **2.2.0** | Reactive `Flux`-aware operators (`CircuitBreakerOperator`) |
| `io.github.resilience4j:resilience4j-micrometer` | **2.2.0** | Micrometer metrics export for the breaker |
| `org.redisson:redisson-spring-boot-starter` | **3.34.x** (same as M3 per ADR-13 §C) | Token bucket (`RRateLimiter`) + per-user concurrent-stream lock (`RLock`) |
| `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot 3.3.x BOM | `/actuator/health` + `/actuator/prometheus` |
| `com.knuddels:jtokkit` | **1.1.x** (same as M3) | Tokenizer for budget bookkeeping (§8). Counts tokens against the working budget; not for prompt construction. |
| `com.github.tomakehurst:wiremock-standalone` | **3.x** | Test scope — stub `spark-inference-gateway` |
| **NOT used:** `org.springframework.modulith:spring-modulith-events-*` | n/a | M4 publishes/consumes no Kafka events (spec §3) — no outbox needed |
| **NOT used:** `org.springframework.kafka:spring-kafka` | n/a | Same — no Kafka surface |
| **NOT used:** `org.opensearch.client:opensearch-java` | n/a | OpenSearch is M2's projector; M4 has no read or write |

**Why Spring AI is pinned to the same 1.0.0 GA that ADR-04 + ADR-13 use:**
M3's `EmbeddingModel` adapter and M4's `ChatClient` adapter must share the
same Spring AI line so the auto-config beans interoperate. A future
ADR-04 amendment bumping Spring AI to 1.0.x is implementer-led; ADR-14
locks the 1.0.0 floor for M4.

### C. HTTP error response shape

Per spec §5.1 — non-stream errors (401, 404, 413, 415, 429) return a
standard JSON body shape via the unified `@RestControllerAdvice` from
ADR-11. Stream-time errors (post-200-OK SSE handshake) emit an `error`
SSE event per spec §5.2 and close the connection.

| HTTP status | When | Body (ADR-11 unified shape) | SSE? |
|---|---|---|---|
| 401 | `X-User-Id` absent on any `/api/rag/chat/**` route | `{"errorCode":"AUTH-401-001","message":"Authentication required","timestamp":"...","path":"...","traceId":"..."}` | No (gateway returns this before reaching the SSE controller; per ADR-09 amendment) |
| 404 | `sessionId` not owned by caller, or session row absent | `{"errorCode":"CHAT-NOT-FOUND-001","message":"Session not found","timestamp":"...","path":"...","traceId":"..."}` (existence-leak-neutral wording per spec §5.1) | No |
| 413 | User message > 4 KB raw | `{"errorCode":"CHAT-VALIDATION-001","message":"Message exceeds 4 KB","timestamp":"...","path":"...","traceId":"..."}` | No |
| 415 | `Accept` header doesn't include `text/event-stream` | `{"errorCode":"CHAT-VALIDATION-002","message":"Accept must include text/event-stream","timestamp":"...","path":"...","traceId":"..."}` | No |
| 429 | Token bucket empty (hourly or daily) | `{"errorCode":"CHAT-RATE-LIMIT-001","message":"Rate limit exceeded","retryAfterSeconds":<n>,"timestamp":"...","path":"...","traceId":"..."}` + `Retry-After: <n>` header | No |
| 503 | Circuit breaker OPEN before stream starts | `{"errorCode":"CHAT-GATEWAY-DOWN-001","message":"AI service unavailable","retryAfterSeconds":30,"timestamp":"...","path":"...","traceId":"..."}` | No (pre-stream); switches to SSE `error: GATEWAY_5XX` if the breaker opens **during** the stream |
| 200 + SSE `error` event | Mid-stream failure (LLM 5xx, timeout, circuit breaker trip mid-stream) | `data: {"code":"GATEWAY_5XX|RATE_LIMIT|RETRIEVAL_EMPTY|ABORTED|INTERNAL","message":"<human-readable>"}` per spec §5.2 | Yes |

The split between "HTTP error before the 200 OK headers" and "SSE error
event mid-stream" is bound by **when the SSE handshake commits**: the
controller does the auth/rate-limit/session-validate/embedding/retrieval
synchronously **before** writing the response headers, so any failure in
that pre-stream phase surfaces as an HTTP error. Once the `retrieval`
SSE event is emitted, the response is committed; subsequent failures
become SSE `error` events.

`Retry-After` is included on 429 and pre-stream 503. The SSE `error`
event includes a `retryAfter` field in the data payload (seconds).

### D. Concurrent stream cap — Redisson `RLock` per user, 35s TTL

**Decision: Redisson `RLock` (same primitive M3 uses for the
ingestion-document lock per ADR-08 Exception 2 + ADR-13 §5). Lock key:
`rag-chat:lock:user:{userId}`. TTL: 35 seconds (matches the streaming
watchdog from §14). Acquire-on-turn-start, release-on-stream-end /
on-error / on-abort.**

| Concern | Pin |
|---|---|
| Lock key | `rag-chat:lock:user:{userId}` |
| Acquire policy | `tryLock(0, 35, TimeUnit.SECONDS)` — non-blocking acquire. If `false` returned, the user has an in-flight turn; per spec §10's "latest-wins" rule, the in-flight turn is aborted (the `Flux` of the in-flight turn is `dispose()`d via a global registry of `userId → Disposable`) and the new turn acquires fresh. |
| Release | In all terminal Reactor signals (`doFinally(signal -> lock.unlock())`). |
| TTL fallback | 35 s — if the application crashes between acquire and release, Redisson auto-releases. Matches the watchdog timeout from §14 so a stale lock from a hung turn does not outlive the turn it's protecting. |
| Latest-wins implementation | The `StreamChatTurnUseCase` keeps a `ConcurrentHashMap<UserId, Disposable>` of in-flight Reactor subscriptions; on a new turn, `dispose()` is called on the previous entry (if any) before acquiring the lock. The aborted turn's SSE goes through the §13 abort path. |

**Considered alternative (the brief's option D suggestion: `@GlobalLock`
annotation):** an AOP aspect that wraps the use-case method with a
distributed lock. Rejected for M4 — the use-case method returns
`Flux<ServerSentEvent<String>>`, not a synchronous value; AOP-driven
locking would have to unwrap the `Flux` to release on completion, which
is exactly the `doFinally` plumbing the use-case can do directly with
less indirection. M3's `@GlobalLock` works because the use-case is
synchronous (`void ingest(...)`); M4's reactive shape calls for explicit
`doOnSubscribe` / `doFinally` lock management.

### E. Database connection pool — single HikariCP, search_path covers four schemas

Per §3.1 above. Single Hikari datasource, max pool size 5 (ADR-05
default), search path `chat,docs,rag,identity`. No second datasource for
cross-schema reads — the same connection reads `rag.document_chunks`,
`docs.documents`, and `identity.users` via fully-qualified table names.

### F. Schema DDL — `chat.sessions`, `chat.messages`, `chat.message_citations`

**Schema name:** `chat` (new schema, per ADR-05's "schema-per-BC"
invariant). No pgvector extension (M4 reads from `rag.document_chunks`
but does not create vector columns of its own).

**Table DDL** (the Flyway V1 migration sketch):

```sql
-- backend/rag-chat/rag-chat-infra/src/main/resources/db/migration/V202605180001__create_chat_tables.sql

CREATE SCHEMA IF NOT EXISTS chat;

CREATE TABLE chat.sessions (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL,                          -- app-level FK to identity.users.id
  title       TEXT         NOT NULL DEFAULT 'New chat',
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chat_sessions_by_user
  ON chat.sessions (user_id, updated_at DESC);

CREATE TABLE chat.messages (
  id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id   UUID         NOT NULL REFERENCES chat.sessions(id) ON DELETE CASCADE,
  user_id      UUID         NOT NULL,                                -- denormalized tenant key
  role         TEXT         NOT NULL CHECK (role IN ('user','assistant')),
  content      TEXT         NOT NULL,                                -- raw text; assistant rows may contain [N]
  tokens_in    INT,                                                  -- assistant only
  tokens_out   INT,                                                  -- assistant only
  retrieval_k  INT,                                                  -- assistant only
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_by_session
  ON chat.messages (session_id, created_at);

CREATE TABLE chat.message_citations (
  message_id   UUID  NOT NULL REFERENCES chat.messages(id) ON DELETE CASCADE,
  position     INT   NOT NULL,                            -- the [N] in the assistant body, 1-indexed
  document_id  UUID  NOT NULL,                            -- app-level FK to docs.documents.id
  chunk_index  INT   NOT NULL,                            -- app-level FK to rag.document_chunks.chunk_index
  PRIMARY KEY (message_id, position)
);
CREATE INDEX chat_message_citations_by_document
  ON chat.message_citations (document_id);

COMMENT ON TABLE chat.sessions IS
  'One row per conversation. user_id is app-level FK to identity.users.id (different schema; no DB FK).';
COMMENT ON TABLE chat.messages IS
  'One row per turn (user or assistant). content carries [N] markers for assistant rows; resolved via chat.message_citations.';
COMMENT ON TABLE chat.message_citations IS
  'Cited subset (NOT all retrieved) per ADR-14 §10. Orphaned rows remain when the cited doc is later deleted; UI renders them as (deleted).';
```

**Trigger to bump `updated_at` on message insert** (so the top-tab strip's
"most-recent" sort matches the actual last-activity timestamp):

```sql
CREATE OR REPLACE FUNCTION chat.touch_session_updated_at() RETURNS TRIGGER AS $$
BEGIN
  UPDATE chat.sessions
     SET updated_at = now()
   WHERE id = NEW.session_id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER chat_messages_touch_session
AFTER INSERT ON chat.messages
FOR EACH ROW EXECUTE FUNCTION chat.touch_session_updated_at();
```

**Why two `user_id` columns (denormalized on `messages`):** spec §4.2
("denormalized for fast tenant filter"). Every `chat.messages` SELECT in
the read paths filters by `user_id = ?`; joining to `chat.sessions` for
every read just to recover the user is overhead the denormalization
pays for once at insert time.

### G. Amendments to transverse ADRs + sibling docs

Six amendments. Inline notes appended to each affected file so readers
see the M4 change in context.

#### G.1. Amendment to ADR-00 (Overview)

Add a new row to the index table:

> | 14 | `14-m4-rag-chat.md` | **(per-milestone, M4)** RAG-Chat BC implementation — Spring WebFlux SSE controller (`POST /api/rag/chat`) on port 18084, cross-schema SELECT exception into `rag`, `docs`, `identity` schemas (no new HTTP exception), Resilience4j 2.2 circuit breaker (`spark-gateway`), Redisson `RRateLimiter` per-user token bucket (60/hr + 200/day) + `RLock` per-user concurrent-stream cap, Qwen3-32B streaming via `ChatClient.stream()` with K=6 retrieval and 200+2400+24576+4000 token budget, Spring-AI-driven auto-title (pinned prompt), cite-persistence policy = only `[N]`-cited subset, no Kafka surface (BC neither produces nor consumes). Amends ADR-04, ADR-05, ADR-09. |

The "Topic-to-BC matrix" row count is unchanged — M4 has no Kafka surface.

#### G.2. Amendment to ADR-04 (Spring AI + LLM Backend)

> Amendment (2026-05-18, ADR-14): ADR-14 **confirms** ADR-04's Spring AI
> 1.0 GA + Qwen3-32B + BGE-M3 endpoint pins for the M4 RAG-Chat BC
> with **no semantic change**.
>
> M4 exercises a streaming path that M3 did not: `ChatClient.prompt().stream().chatResponse()`
> returning `Flux<ChatResponse>`, mapped one-to-one to SSE `token` events
> on the gateway-routed `POST /api/rag/chat` endpoint (per ADR-14 §1).
> The BGE-M3 embedding path is the same `EmbeddingModel.embed(query)`
> shape M3 uses (per ADR-13 §10) — confirmed for query-time use in M4.
>
> The base URL (`http://host.docker.internal:10080`), the model names
> (`Qwen3-32B` for chat, `BGE-M3` for embeddings), the dimension (1024
> dense), and the `chat.options.temperature=0.2` default are unchanged.
> ADR-14 §6 adds a separate (and lower) `temperature=0.1` for the
> auto-title call only — applied as a per-call override via Spring AI's
> `ChatOptions`, not as an `application.yml` change.
>
> ADR-14 §4 wraps both `ChatClient` and `EmbeddingModel` invocations
> with a Resilience4j `CircuitBreaker` named `spark-gateway`; ADR-04's
> "If `spark-inference-gateway` is down, `rag-chat` fails" consequence
> remains (the breaker doesn't introduce a fallback model — it just
> short-circuits faster).
>
> See `docs/adr/14-m4-rag-chat.md` §1 + §4 + §6 + §17 for the full
> specification.

#### G.3. Amendment to ADR-05 (Data Store)

> Amendment (2026-05-18, ADR-14): the **`chat` schema** is added for the
> M4 RAG-Chat BC. The schema-per-BC invariant is preserved — `chat` is
> the fifth top-level schema after `identity`, `docs`, `rag`, `metrics`.
> Full DDL in `docs/adr/14-m4-rag-chat.md` §F.
>
> Tables:
> - `chat.sessions` — one conversation per row, `user_id` is an
>   app-level FK to `identity.users.id` (no cross-schema DB FK).
> - `chat.messages` — one user or assistant turn per row, `session_id`
>   FK CASCADE to `chat.sessions(id)`, `user_id` denormalized for fast
>   tenant filter.
> - `chat.message_citations` — only the `[N]`-cited subset of retrieved
>   chunks (per ADR-14 §10), `message_id` FK CASCADE to
>   `chat.messages(id)`, `document_id` + `chunk_index` are app-level
>   FKs to `docs.documents.id` + `rag.document_chunks.chunk_index`
>   (stale citations gracefully degrade per ADR-14 §11).
>
> **First sanctioned cross-schema SELECT exception:** rag-chat reads
> three foreign schemas at runtime:
> - `rag.document_chunks` — per-turn vector retrieval, ORDER BY
>   `embedding <=> :q` LIMIT K (K=6 default). Already framed by ADR-13
>   §G.4 as M4-owned; this amendment promotes the framing to a
>   sanctioned exception.
> - `docs.documents` — citation enrichment (`title` + `visibility` by
>   `id IN (:retrieved_doc_ids)`).
> - `identity.users` — display-name / avatar for the chat header
>   (`SELECT display_name, avatar_url FROM identity.users WHERE id = ?`).
>
> The `rag-chat-api` Hikari connection sets `search_path` to
> `chat,docs,rag,identity,public` at session start; all cross-schema
> reads use fully-qualified table names regardless. **Cross-schema
> writes remain forbidden** — rag-chat writes only to the `chat`
> schema. The two existing HTTP exceptions in ADR-08 (M3→docs body-fetch,
> docs→identity owner-lookup) are NOT extended; M4's cross-schema reads
> stay at the SQL layer.
>
> The pgvector retrieval SQL pinned in ADR-14 §3.2 is the canonical M4
> retrieval expression — implements the spec §8 amendment's "WHERE
> visibility='public' OR (user_id=? AND visibility='private')" clause
> against the `rag.document_chunks` table M3 owns (ADR-13 §F).
>
> See `docs/adr/14-m4-rag-chat.md` §3 + §F for the full specification.

#### G.4. Amendment to ADR-09 (Public Route Policy)

This is the largest amendment. ADR-09's route classification table is
revised (one row removed, one row added), the "Rate-limit and cost
protection (public RAG chat)" section is fully replaced, and a new
"Auth-lock vs milestone-lock badge convention" section is added.

> Amendment (2026-05-18, ADR-14): the public route allowlist is
> **narrowed** for the RAG-chat surface. The earlier `POST /api/rag/chat/public`
> row (intended for anonymous chat) is **removed permanently**; the
> single endpoint `POST /api/rag/chat` is **authenticated-only**.
> Anonymous callers receive 401 at the gateway. This supersedes both
> the original ADR-09 framing and the 2026-05-18 spec-cycle interim
> framing ("any visitor — anonymous or signed-in").
>
> ### Route classification table — revised rows (post-ADR-14)
>
> The row `| POST /api/rag/chat/public | public | Anonymous RAG chat against the public corpus only. |` is **removed**.
>
> The authenticated section gains:
>
> | Pattern | Class | Reason |
> |---|---|---|
> | `POST /api/rag/chat`, `/api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/*/messages` | **authenticated** | RAG chat (single streaming endpoint + session CRUD). Anonymous callers receive 401. Per spec §5.1 + §5.3. |
>
> ### Rate-limit and cost protection (RAG chat — authenticated)
>
> ADR-09's original §"Rate-limit and cost protection (public RAG chat)"
> is renamed to **"Rate-limit and cost protection (RAG chat —
> authenticated)"** and the content is fully replaced:
>
> - **Per-user token bucket:** **60** chat completions / hour / user
>   AND **200** chat completions / day / user. Both buckets must have
>   capacity for the turn to proceed; whichever depletes first → 429
>   with `Retry-After` set to the smaller refill ETA. Backing:
>   Redisson `RRateLimiter`, key
>   `rag-chat:bucket:user:{userId}:{hourly|daily}` (per ADR-14 §5).
> - **Per-completion token cap:** Spring AI option `max_tokens=4000`
>   (per ADR-14 §8). The retrieval slice is K=6 chunks (per ADR-14 §7);
>   the prompt assembler enforces the 200 + 2400 + 24576 + 4000 token
>   split (per ADR-14 §8).
> - **Global circuit breaker:** Resilience4j 2.2.x `CircuitBreaker`
>   named `spark-gateway`, shared by the `ChatClient` and
>   `EmbeddingModel` adapters. Failure rate threshold 50 % over a
>   60-second sliding window, minimum 10 calls, OPEN duration 30 s,
>   1 HALF_OPEN probe (per ADR-14 §4).
> - **Per-user concurrent stream cap:** 1. A second concurrent
>   `POST /api/rag/chat` from the same user aborts the first
>   (latest-wins) via a Redisson `RLock` keyed
>   `rag-chat:lock:user:{userId}` with 35 s TTL (per ADR-14 §G).
> - **No per-IP rate limit** for authenticated chat — the per-user
>   bucket is the relevant denominator. The M2 per-IP rate limits
>   (ADR-12 §7's anonymous-read caps) remain in force for their own
>   public surfaces (`GET /api/docs`, `GET /api/docs/search`, etc.).
>
> ### Anonymous identity contract — narrowed, not removed
>
> ADR-09's original §"Anonymous identity contract" still governs the
> remaining public routes (`/`, `GET /api/docs`, `GET /api/docs/{id}`,
> `POST /api/docs/{id}/view`, `GET /api/docs/search?scope=public`,
> `GET /api/metrics/**` when shipped). The `PLAYGROUND_ANON` cookie
> attributes and per-IP fallback are unchanged. **Chat is the only
> route where anonymous = 401**; everything else still treats
> `X-User-Id` as optionally absent.
>
> ### New section: Auth-lock vs milestone-lock badge convention
>
> The sidebar `Apps` rows render with three lock states. ADR-14
> introduces a third badge variant; the existing two are documented
> here for completeness.
>
> | Badge | When | Click behavior | Visual |
> |---|---|---|---|
> | (none) | The corresponding milestone has shipped AND the route is reachable for the current session | Navigates to the route | Active text, no badge |
> | `🔒 Mx` | The milestone has NOT yet shipped (e.g., `🔒 M5` for Metrics before M5 ships) | No-op (no route exists yet) — click does nothing, hover tooltip "Coming in Mx" | Muted text |
> | **`🔒 Sign in`** | The milestone has shipped BUT the current session is anonymous AND the route is auth-only | Navigates to `/login?return=<target>` | Muted text + sign-in badge |
>
> The **auth-lock** state (`🔒 Sign in`) is introduced by M4 for the
> `Chat` sidebar row. It is distinct from the **milestone-lock** state
> (`🔒 Mx`):
>
> - Milestone-lock = "no route exists" (the BC has not deployed yet).
> - Auth-lock = "route exists but you're anonymous" (the BC has
>   deployed but only serves authenticated callers).
>
> The two locks are mutually exclusive per row at any moment — a
> shipped + anon-required route shows `🔒 Sign in`; an unshipped route
> shows `🔒 Mx`; a fully-accessible route shows no badge. The
> frontend's sidebar component derives the state from `(milestone
> shipped?, session authenticated?, route auth-required?)`. M4 ships
> the auth-lock visual; future auth-only routes (e.g., M6+ Agents)
> reuse the same badge.
>
> ### Forward note — M5 Metrics is unaffected
>
> M5's `GET /api/metrics/**` remains in the public allowlist per ADR-09
> §"Route classification". The auth-lock badge convention does not
> apply to it.
>
> See `docs/adr/14-m4-rag-chat.md` §C + §G + spec §7.6 + §8 for the
> full specification.

(Note: ADR-09's existing 2026-05-17 amendment block from ADR-12
remains in force — the `/api/docs/**` allowlist rows and the per-IP
read caps are untouched. ADR-14's amendment composes additively with
ADR-12's amendment.)

#### G.5. Amendment to `docs/roadmap.md` §M4

The §M4 block is rewritten to remove the "anonymous or signed-in"
framing. Specifically:

> **Goal:** Give an authenticated visitor a chatbot whose answers are
> grounded in the playground corpus the caller is allowed to read (all
> community-wide public documents from every author, plus the caller's
> own private documents).
>
> **Acceptance:**
> - [ ] Frontend chat UI lets an authenticated visitor start a
>   conversation, send a message, and stream back a model response.
> - [ ] On each user turn, the backend embeds the query (BGE-M3),
>   retrieves top-K chunks from pgvector per the M4 retrieval contract
>   below, and constructs a prompt for Qwen3-32B.
> - [ ] Generation runs against `spark-inference-gateway` (Qwen3-32B)
>   using Spring AI; responses stream to the client as Server-Sent
>   Events.
> - [ ] Conversation history is persisted server-side and reloadable
>   across browser sessions for the authenticated caller.
> - [ ] Responses cite which document(s) / chunk(s) backed the answer
>   (id + title at minimum) with inline `[N]` markers and an
>   expandable accordion.
>
> **Dependencies:** M0, M1, M2, M3.
>
> **Notes:** Retrieval scope is governed by the M4 retrieval contract
> (see `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8 +
> `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract" + ADR-14):
> every public chunk is in scope for every authenticated caller;
> private chunks are in scope only for their author when that author
> is the caller (matched by `X-User-Id`). No caller can ever retrieve
> another user's private chunks. This is the first milestone that
> exercises both LLM endpoints of `spark-inference-gateway`
> end-to-end.
>
> **Public surface (per ADR-09 amendment in ADR-14):** This milestone
> ships **one** chat endpoint: `POST /api/rag/chat`,
> **authenticated-only**. Gateway returns 401 on missing `X-User-Id`.
> The retrieval corpus is fixed per-caller:
> - Authenticated (`X-User-Id` present): `WHERE visibility = 'public'
>   OR (user_id = X-User-Id AND visibility = 'private')`.
>
> Per-user token bucket (60/hour, 200/day), `max_tokens=4000`, K=6
> retrieved chunks, Resilience4j circuit breaker on
> `spark-inference-gateway` 5xx > 50 % in 60 s. Concrete numbers and
> the ADR-09 amendment land in ADR-14.

The interim "any visitor — anonymous or signed-in" framing is
**superseded twice** — once by spec v1 (2026-05-18), and once again
here by the auth-only ADR-14 PR.

#### G.6. Amendment to `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8

The §8 RAG-handoff trace amendment block (the one dated 2026-05-18 in
the spec) is **re-revised** to drop the anonymous-caller bullet and
the diagram caption.

> The diagram caption `anyone starts chat in M4 (anon or auth)` is
> revised to **`an authenticated user starts chat in M4`** and the
> `(X-User-Id present iff signed in)` annotation is replaced with
> `(X-User-Id always present — gateway 401 on absence)`.
>
> The "M4 retrieval contract (canonical)" sub-list drops the
> **Anonymous caller** bullet entirely. The remaining bullets:
> - **Single endpoint**: `/api/rag/chat`, authenticated-only.
>   Anonymous callers receive 401 at the gateway.
> - **Authenticated caller** (`X-User-Id` present): retrieval corpus =
>   `WHERE visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`.
>   All public docs from every author, plus the caller's own private docs.
> - **Never visible**: other users' `private` docs, AND the entire
>   chat surface is closed to unauthenticated callers (no anonymous
>   retrieval corpus exists at all).
> - M3's job is to keep the `(user_id, visibility)` pair on every
>   chunk row accurate — unchanged.

#### G.7. Amendment to `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract"

The two-row corpus table is replaced with one row:

> **M4 retrieval contract (forward invariant — M3 enables, M4
> consumes):**
> - [ ] M4가 다음 단일 SQL 형태로 corpus를 분리할 수 있다 (M3가 만들어주는 invariant):
>   - 인증: `SELECT ... FROM rag.document_chunks WHERE visibility = 'public' OR (user_id = :uid AND visibility = 'private') ORDER BY embedding <=> :q LIMIT :k`
> - [ ] M3 측에서 retrieval API / RPC를 노출하지 않는다 — M4가 pgvector를 직접 SELECT한다.
> - [ ] 모든 chunk 행이 `(user_id, visibility)`를 non-null로 가짐 — 통합 테스트로 invariant 단언.
>
> **Anon row dropped (2026-05-18, ADR-14):** the earlier draft listed
> an anonymous corpus case (`WHERE visibility = 'public'` only). M4
> is auth-only per ADR-09's ADR-14 amendment; the anonymous SQL form
> is no longer a target. M3's chunk schema invariant
> `(user_id, visibility)` non-null is unchanged.

## Open questions deferred beyond M4

These are explicitly out of scope for M4 P0 and noted here so the next
milestone's architect doesn't re-litigate them:

- **Mobile layout (M4.1)** — spec §2 deferred.
- **Hybrid retrieval (M4.1)** — BM25 from OpenSearch fused with cosine
  from pgvector via RRF. M4 P0 is semantic-only.
- **Dynamic empty-state suggestions (M4.1)** — sourced from the caller's
  recent docs.
- **Conversation export (M4.1)** — Markdown download.
- **Multi-turn retrieval (M4.1)** — embed the last N turns concatenated
  rather than just the current user message.
- **Chunk-anchor citation links** — `/docs/{documentId}#chunk-{chunkIndex}`
  fragment resolution; M4 P0 links to `/docs/{documentId}` only.
- **Postgres rollup table `chat.usage_daily`** — added in M5 if the
  metrics dashboard wants per-user-per-day token history; M4 P0 uses
  Redis-only buckets (§5).
- **Anonymous chat (P2 — permanently removed)** — explicitly out per
  spec §2 + this ADR's ADR-09 amendment.
- **Regenerate / copy assistant message** — P1 candidates leveraging the
  `done` event's `messageId`; M4 P0 ships without them.
- **Multi-instance rag-chat-api** — single-instance assumption is baked
  into the Resilience4j breaker state (in-memory) and the in-flight
  `Disposable` registry for latest-wins. Multi-instance fan-out moves
  the breaker to a shared Redis backing and the registry to a Redisson
  `RMap`; P2+ concern.
- **Conversation summarization (in-line)** — when the context fills up,
  summarize old turns into one synthetic message. Spec §6.3 working
  default is drop-oldest (§9 confirmed); revisit if user feedback shows
  the truncation horizon is hit often.

## Diagrams

### M4 per-turn flow (HTTP + JDBC cross-schema reads + Spring AI streaming)

```
Browser (authenticated, /chat top tab)
   │
   │  POST /api/rag/chat  (Accept: text/event-stream)
   │   Body: { sessionId, message }
   ▼
gateway (18080)  ──── injects X-User-Id, X-User-Sub
   │
   ▼
rag-chat-api (18084)  WebFlux SSE controller
   │
   ▼
┌────────────────────────────────────────────────────┐
│  StreamChatTurnUseCase (rag-chat-app)              │
│                                                    │
│   1. auth check    (header present?)               │
│   2. rate-limit    (RRateLimiter hourly + daily)   │
│   3. session-validate (SELECT ... WHERE id=?       │
│                       AND user_id=?)               │
│   4. concurrent-stream lock (RLock per userId)     │
│   5. history-load  (SELECT ... FROM chat.messages  │
│                    WHERE session_id=?)             │
│   6. truncate to budget (drop oldest pairs)        │
│   7. embed query   (Spring AI EmbeddingModel       │
│                    BGE-M3, 1024d)                  │
│   8. retrieve K=6  (pgvector cross-schema SELECT)  │
│   9. enrich titles (docs.documents cross-schema)   │
│  10. emit SSE retrieval event                      │
│  11. persist user message (chat.messages INSERT)   │
│  12. assemble prompt                               │
│  13. ChatClient.stream() Qwen3-32B                 │
│      Flux<ChatResponse> ──► SSE token events       │
│  14. parse [N] markers from accumulated text       │
│  15. persist assistant message + cited subset      │
│  16. emit SSE done event                           │
│  17. release lock, debit bucket                    │
└────────────────────────────────────────────────────┘
   │             │             │             │
   ▼             ▼             ▼             ▼
postgres        redis        spark-inference-gateway
(playground)    (lock +      (host.docker.internal:10080)
   │            bucket)        │
   │                           │  BGE-M3 /v1/embeddings
   │                           │  Qwen3-32B /v1/chat/completions
   │                                   (streaming, Resilience4j-wrapped)
   │
   │  4 schemas (search_path = chat,docs,rag,identity):
   │    chat.sessions       (write)
   │    chat.messages       (write)
   │    chat.message_citations (write)
   │    rag.document_chunks (read — retrieval)
   │    docs.documents      (read — title enrichment)
   │    identity.users      (read — display_name at /chat load)

Failure paths:
  - Pre-stream:  401 / 404 / 413 / 415 / 429 / 503 → standard ADR-11 JSON body
  - Mid-stream:  SSE error event → connection closes; no assistant row persisted
  - Client abort: Reactor cancel → upstream WebClient disposes →
                  vLLM sees client disconnect → assistant row NOT persisted
```

### M4 auto-title (fire-and-forget, post first user turn)

```
StreamChatTurnUseCase
   │ (after user message INSERT commits, only if this is the first user msg)
   │
   ▼
AutoTitleUseCase  (Schedulers.boundedElastic)
   │
   ▼
ChatClient.call()  Qwen3-32B
   │  system prompt + first user message
   │  temperature=0.1, max_tokens=24
   ▼
UPDATE chat.sessions
   SET title = ?
   WHERE id = ? AND title = 'New chat'

Failures (4xx / 5xx / breaker OPEN) → WARN log, title stays 'New chat'.
Streaming response continues unaffected.
```

## Consequences

- Positive: M4 ships with the same module-quadruplet shape every prior
  BC uses. backend-implementer has one mental model across BCs; code-
  reviewer has one shape to check. The cross-schema SELECT — the one
  novel element — is bounded to three specific predicates in a single
  amendment to ADR-05 + ADR-08, easy to audit.
- Positive: Single endpoint + single port + single connection pool keeps
  the operational surface minimal. The four-schema search path is a
  one-line Hikari config; the auth-only invariant collapses the public
  surface to one row in ADR-09 to manage.
- Positive: Reactive end-to-end (WebFlux SSE controller + Spring AI
  `Flux<ChatResponse>` + `Mono`-wrapped JDBC adapters on bounded-elastic)
  means the request thread is never blocked during streaming — a single
  rag-chat-api instance can sustain many concurrent streams without a
  per-stream thread pool.
- Positive: Resilience4j 2.2 + Redisson 3.34 are both mature, both
  Spring-Boot-3-native, both Micrometer-integrated out of the box — no
  bespoke metrics wiring needed for the M5 dashboard.
- Positive: The cite-persistence-only-cited policy keeps the
  `chat.message_citations` table as small as the visible UX surface —
  no orphan "we retrieved this but the model ignored it" rows polluting
  the audit trail.
- Positive: The auto-title pinned prompt + `temperature=0.1` + 24-token
  cap is deterministic enough that titles are stable for repeat first
  messages; users get a recognizable "this is my retry of that
  conversation" hint at a tiny cost.
- Negative: The first cross-schema SELECT in the project — ADR-05's
  "Cross-schema queries are *technically* possible — discipline is the
  only barrier" line is now sanctioned to look the other way for three
  specific predicates. A future BC that wants to reach into `chat.*`
  needs a new amendment; the discipline does not relax beyond the
  enumerated three.
- Negative: The streaming abort path's "vLLM observes disconnect"
  assumption is a runtime-verifiable assumption, not a pinned-by-contract
  one. Mitigation: the 35 s watchdog (§14) caps the worst case at a
  bounded number of wasted tokens regardless.
- Negative: Redis becomes a load-bearing dependency for chat (token
  bucket + concurrent-stream lock); an `redis-playground` outage now
  takes chat down where M2 and M3 keep working (M2 uses Redis for view
  dedup which degrades gracefully; M3 uses it for ingestion locks which
  retry via Kafka). ADR-08's existing "Redis is a sanctioned dependency"
  framing covers this, but the failure mode is more user-visible for
  M4 than for upstream BCs.
- Negative: WebFlux end-to-end means the cross-schema JDBC reads have to
  be bounded-elastic-wrapped (§17); a typo there silently blocks the
  request thread on a large pool, with no compile-time check. The
  integration-test layer (§16) catches it via the latency-assertion
  tests.
- Negative: The per-user concurrent-stream cap of 1 + latest-wins is
  user-visible (open two `/chat` tabs and the first stops streaming).
  Acceptable per spec §10 — operator visibility into "user opened 10
  tabs and submitted at once" is more important than per-tab simultaneity.

## Related

- ADR-00 (amended below by this ADR — index row)
- ADR-01 v2 — quadruplet module layout, `rag-chat-api` port 18084
- ADR-02 — DDD layering rules the rag-chat quadruplet inherits
- ADR-03 — Kafka envelope / topic conventions (M4 makes NO topic
  changes; no amendment)
- ADR-04 (amended below by this ADR — informational only) — Spring AI
  1.0 GA, Qwen3-32B chat streaming + BGE-M3 query embedding
- ADR-05 (amended below by this ADR) — Postgres + pgvector; the `chat`
  schema is added; cross-schema SELECT into `rag`, `docs`, `identity`
  becomes a sanctioned exception
- ADR-07 — gateway routing for `/api/rag/chat/**` → `rag-chat-api:18084`
- ADR-08 — inter-service comms (NO new HTTP exception; M4's cross-BC
  reads are SQL, not HTTP). ADR-08 is **not** amended; its existing
  exceptions are not extended.
- ADR-09 (amended below by this ADR — largest amendment) — public route
  policy: `/api/rag/chat/public` row removed, auth-only chat row added,
  rate-limit section rewritten, auth-lock badge convention introduced
- ADR-10 §8 — Spring Modulith Events outbox (M4 does NOT use it — no
  Kafka surface)
- ADR-11 — shared exception hierarchy used in all chat error responses
- ADR-12 §1 + §2 + §8 — outbox inheritance pattern + cross-BC HTTP
  exceptions docs-api relies on (not used by M4)
- ADR-13 §F — `rag.document_chunks` DDL (M4 SELECTs against it)
- ADR-13 §G.2 — `SET LOCAL hnsw.ef_search = 40` runtime hint (M4 owns
  the SET; ADR-13 documents the contract)
- ADR-17 (amended above — A14.1–A14.7) — M7 tool-calling SSE grammar
  reconciliation
- ADR-18 §Exception 5 — `massing-gen-api` → `docs-api` HTTP brief-body
  read. Contrasted in the 2026-06-01 amendment §A14M8.1: that is a
  fresh ADR-08 HTTP exception because massing-gen has no cross-schema
  posture; rag-chat's manifest read is a SELECT because rag-chat
  already holds the `chat,docs,rag,identity` search path. Not amended
  by this ADR.
- `docs/prd/M4-rag-chat.md` — M4 PRD (the user-stories surface this
  ADR resolves §11 against)
- `docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md` — M4 spec
  (the 17 open questions this ADR closes; the 6 supersession amendments
  this ADR carries)
- `docs/roadmap.md` §M4 (amended below by this ADR — auth-only)
- `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8 (amended
  below by this ADR — anon row dropped)
- `docs/prd/M3-rag-ingestion.md` "M4 retrieval contract" (amended below
  by this ADR — anon row dropped)

## Amendment 2026-05-22 (ADR-17, M7) — SSE grammar reconciliation for tool-calling

The M7 PR set (ADR-17 — `docs/adr/17-m7-rag-chat-tool-calling.md`)
adds three new SSE event types — `tool_call` / `tool_result` /
`tool_error` — to the rag-chat streaming surface. This amendment
reconciles the new events with the original §5.2 grammar table
without changing any M4 behavior. Every shipped M4 invariant is
preserved.

### A14.1. SSE wire shape — standalone event names supersede placeholder phase steps

The M4 spec §5.2 (revised 2026-05-19, PR B) example block carried
two forward-looking placeholder rows annotated as "PR C onward —
extra phase events from tool-calling":

```
event: phase
data: {"step":"tool_call","label":"공개 문서 검색 중","data":{"tool":"…","args":{…}}}
```

with an implicit symmetric `step: "tool_result"` row.

**Status flip:** these placeholder `phase.step` values for
`tool_call` and `tool_result` are **retired** by M7. M7 ships
function-calling SSE events as **standalone `event:` names**
(`event: tool_call`, `event: tool_result`, `event: tool_error`),
not as values of the `phase` event's `step` field.

The `phase` event continues to exist for non-tool progress signaling
(currently `step: "retrieval"`; spec §5.2's open-vocabulary
`generating` / `thinking` examples remain valid). The `step`
discriminator domain is now closed to progress-class values —
tool-class values do not appear there.

### A14.2. Why standalone event names

- **The shipped M4 backend never emitted `step: "tool_call"` or
  `step: "tool_result"`.** The
  `rag-chat-app.ChatTurnService.stream(...)` method on `main` at the
  M7 cycle start (commit `f540789`) emits exactly one `Phase` event
  per turn — `step: "retrieval"`. The placeholder rows in spec §5.2
  were forward-looking annotations, not shipped wire shape. Inspect
  `backend/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/service/ChatTurnService.java`
  ~line 204 — only `new ChatStreamEvent.Phase("retrieval", …)` is
  constructed.
- **The shipped M4 frontend `chat.sse.ts` already supports standalone
  event-name dispatch** via a `switch (eventName)` block in
  `parseFrame(...)`. Unknown event names return `null` and are
  silently dropped — the frontend has been forward-compatible with
  new `event:` names since M4 shipped. Adding `tool_call` /
  `tool_result` / `tool_error` is purely additive; the existing FE
  code does not break. Inspect
  `frontend/src/shared/api/chat.sse.ts` — the comment block at line
  ~103 explicitly notes that a future server "might add `usage` or
  `tool_call` events" and that unknown event types are dropped.
- **Standalone event names match FE dispatch ergonomics.** A
  frontend that wants to render tool-call cards distinctly from
  retrieval progress benefits from a dedicated event name (matches
  React reducer / `EventSource.addEventListener('tool_call', …)`
  patterns). Conflating tool calls with progress phases would force
  the FE to disambiguate the same `phase` event into two different
  rendering paths via the `step` field — unnecessary indirection
  given the wire format can carry the distinction directly.
- **Forward compatibility for parallel tool calls** (M7.1 scope).
  When Spring AI's parallel function-calling lands and is adopted,
  the `tool_call.id` correlation ID (ADR-17 §3.2) is the FE's
  mechanism for pairing multiple in-flight tool calls — a dedicated
  event name per tool action keeps the pairing JSON-shape compact.

### A14.3. Updated §5.2 grammar example (replaces the PR C placeholder block)

The revised §5.2 grammar example reads, post-M7:

```
event: phase
data: {"step":"retrieval","label":"참고 문서 확인 중","data":{"count":6}}

event: phase
data: {"step":"generating"}

# (M7 onward — tool-calling events emitted as standalone event names)
event: tool_call
data: {"id":"call_01HZ…","name":"generate_massing","args":{"briefDocId":"…","siteWidth":30.0}}

event: tool_result
data: {"id":"call_01HZ…","name":"generate_massing","result":{"fileUrl":"/api/arch/outputs/…","summary":"12 rooms, 3 floors, 480 m² total"}}

# OR (tool failure alternative — NOT terminal unless code is MAX_DEPTH or CIRCUIT_OPEN)
event: tool_error
data: {"id":"call_01HZ…","name":"generate_massing","code":"TIMEOUT","message":"Tool 'generate_massing' did not respond within 30s"}

event: token
data: {"delta":"800-token windows "}

event: token
data: {"delta":"with 120-token overlap [1][2]…"}

event: done
data: {"messageId":"<uuid>","tokensIn":1234,"tokensOut":567,"citations":[…]}

# OR (terminal alternative — chat-level fatal; tool-level errors do NOT fall back to this)
event: error
data: {"code":"GATEWAY_5XX|RATE_LIMIT|RETRIEVAL_EMPTY|ABORTED|INTERNAL","message":"<human-readable>"}
```

### A14.4. Invariants preserved from M4

- `phase` events remain progress-class only. The `step`
  discriminator domain after M7 ship is: `retrieval`, `generating`,
  `thinking` (open vocabulary for progress only — tool-class values
  retired).
- `done` is the success terminal; `error` is the chat-level fatal
  terminal. Neither changes. The `error` event continues to carry
  the M4 §6.5 5-value enum: `GATEWAY_5XX` / `RATE_LIMIT` /
  `RETRIEVAL_EMPTY` / `ABORTED` / `INTERNAL`.
- `tool_error` does **not** fall back to `error` — tool failures are
  distinct from chat-level fatals.
- `tool_error` with `code: "CIRCUIT_OPEN"` or `code: "MAX_DEPTH"`
  terminates the turn without a further `done` / `error` (the
  `tool_error` event is itself terminal in those two cases; partial
  assistant content accumulated so far is not persisted, matching
  ADR-14 §13 abort-path semantics).
- Stream-time `error` event termination shape per ADR-14 §C is
  unchanged.

### A14.5. `ChatStreamEvent` sealed-interface extension (in `shared-kernel`)

The M4 `ChatStreamEvent` sealed interface in
`backend/shared-kernel/src/main/java/com/playground/shared/chat/ChatStreamEvent.java`
gains three new permitted subtype records — `ToolCall`,
`ToolResult`, `ToolError`. The existing `Phase` / `Token` / `Done` /
`Error` records are unchanged.

The wire-name convention (lowercase snake_case of record name) is
preserved — `ToolCall` → `tool_call`, `ToolResult` → `tool_result`,
`ToolError` → `tool_error`. The existing `Phase` → `phase` mapping
is unchanged.

The pre-PR-B compat note in the existing `ChatStreamEvent` javadoc
("Today rag-chat (M4) still emits the wire event `retrieval` as a
`Phase` with `step = "retrieval"` …") remains accurate as
historical context — the M7 PR set updates the javadoc to reflect
the new permitted subtypes but does not rewrite the historical note.

### A14.6. §1 component-placement table — additive only

ADR-14 §1's table gains three new component rows (the M7 components
from ADR-17 §8 — `ToolCatalog` / `ToolDescriptor` / `ToolDispatcher`
/ etc.). The existing 18 rows are unchanged. The M4 implementation
in `rag-chat-{api,app,domain,infra}` ships unmodified in the M7 PR
set — the M7 commits are purely additive.

### A14.7. No change to ADR-14 §4 (`spark-gateway` breaker)

The shared `spark-gateway` Resilience4j circuit breaker introduced
in ADR-14 §4 is **not** modified. M7 introduces a **new** set of
breakers — one per tool descriptor, named `tool-<descriptor-name>`
— with the same configuration template as `spark-gateway`. The two
breaker registries (single `spark-gateway` vs per-descriptor
`tool-*`) are independent; a degraded `spark-gateway` does not
prevent the tool breakers from operating, and vice versa. Cross-cut
operations (e.g., a tool BC that itself calls
`spark-inference-gateway`) are subject to **both** breakers — the
tool BC's outbound call is governed by its own breaker (in its own
JVM), and rag-chat's call to the tool BC is governed by
`tool-<descriptor>` in rag-chat's JVM. There is no shared state
between them.

See `docs/adr/17-m7-rag-chat-tool-calling.md` §1 + §2 + §3 + §5 +
§8 + §10 for the full M7 specification.

## Amendment 2026-06-01 (ADR-14, M8) — document manifest injection for multi-turn tool-arg resolution

The M8 PR set (PR #207, merged `fc0a7b7`) makes natural-language
document references resolve to a tool's `briefDocId`. Before this,
the model was never shown any document UUID, so `generate_massing`'s
required `briefDocId: uuid` argument was model-invented on a turn like
"두 번째 문서로 매스 만들어줘" (an ordinal reference with no UUID) →
hallucinated UUID → `massing-gen` 404. The fix injects the caller's
document manifest into the prompt; E2E-proven to emit the real UUID of
the second-uploaded document. This amendment is **additive** — every
shipped M4 invariant (§3, §10, §12) and the M7 SSE grammar (A14.1–A14.7)
are preserved; the M4 no-tool prompt stays byte-identical.

### A14M8.1. A third sanctioned cross-schema SELECT into `docs.documents`

**Decision: `rag-chat-api` reads `docs.documents` a second way — a
per-user document manifest — via cross-schema SELECT, extending §3's
table.** This is the **third** sanctioned SELECT (after `rag.document_chunks`
retrieval and `docs.documents` citation enrichment), and the **second**
predicate into `docs.documents`.

| Concern | Pin |
|---|---|
| Port (in `rag-chat-app`) | `UserDocumentManifestPort.recentForUser(UserId, int limit)` |
| Domain record (in `rag-chat-domain`) | `UserDocumentRef(int ordinal, UUID documentId, String title, String mimeType, String extractionStatus)` — `ordinal` 1-indexed, Spring-free |
| Adapter (in `rag-chat-infra`) | `CrossSchemaUserDocumentManifestAdapter` — `SELECT id, title, mime_type, extraction_status FROM docs.documents WHERE user_id = ? ORDER BY created_at ASC LIMIT ?`, `JdbcTemplate`, fully-qualified table name |
| Posture | Identical to `CrossSchemaCitationResolverAdapter` (citation enrichment) and `IdentityDisplayNameAdapter` (display name) — same `chat,docs,rag,identity` search path (§3.1), same `JdbcTemplate` blocking-read wrapped on bounded-elastic per §17 |

**Why SELECT, not HTTP — and why this is NOT an ADR-08 exception:** the
same lower-coupling rationale §3 already gives for the citation-enrichment
read. rag-chat already opens a connection to `docs.documents`; the manifest
is a second predicate on a table the BC already reads cross-schema, not a
new BC boundary crossing. ADR-08's two HTTP exceptions are not extended and
no new one is added — the read stays at the SQL layer.

**Contrast with ADR-18 Exception 5 (massing-gen → docs HTTP).** ADR-18
introduced a *fresh ADR-08 HTTP exception* for `massing-gen-api → docs-api`
brief-body read precisely because `massing-gen` is a **different BC with no
cross-schema posture** — it owns no schema-search-path into `docs`, so the
proportionate channel there is HTTP. rag-chat is the opposite case: it
*already* holds the `chat,docs,rag,identity` search path (§3.1) and already
SELECTs `docs.documents` for citations, so a SELECT is strictly lower-coupling
than minting an HTTP route. The two reads touch the same source table from
two different BCs by two different channels for sound reasons — this is not
an inconsistency.

### A14M8.2. Prompt contract — the optional `[YOUR DOCUMENTS]` section

**Decision: `PromptTemplate` gains a 5-arg `assemble(retrieved, history,
currentUserMessage, perChunkTokenBudget, documents)` overload that renders a
`[YOUR DOCUMENTS]` section between `[RETRIEVED CONTEXT]` and `[CONVERSATION
SO FAR]`. The original 4-arg overload delegates to it with `List.of()`.**

Section shape (verbatim per the shipped template):

```
[YOUR DOCUMENTS]
The caller's uploaded documents, in upload order. When you call a tool that
needs a document id (e.g. briefDocId), pick the exact id from this list that
matches the document the user refers to — by ordinal ("두 번째"/"second"), by
title, or by type. Never invent an id; if none matches, ask the user.
1. "<title>" [<mimeType>, <extractionStatus>] id=<uuid>
2. "<title>" [<mimeType>, <extractionStatus>] id=<uuid>
...
```

**Invariant (preserves §10/§12 and the PromptTemplate fixtures): an empty or
null `documents` list renders NOTHING — no header, no blank line — so the M4
no-tool prompt is byte-identical to the pre-M8 output.** The existing
`rag-chat-domain` `PromptTemplate` fixtures (§16) that assert exact string
output for the 4-arg path remain green unchanged; the new section is covered
by separate fixtures driven through the 5-arg overload.

`MassingTool`'s `briefDocId` JSON-schema `description` is updated to instruct
the model to "Copy the exact id from the `[YOUR DOCUMENTS]` list … Never
invent a uuid." — the prompt section and the tool-arg schema reinforce each
other.

### A14M8.3. Ordering = `created_at ASC` (upload order), deliberately

**Decision: the manifest is ordered `created_at ASC` (upload order), NOT the
`updated_at DESC` that `GET /api/docs?scope=mine` uses.** The `ordinal` field
is the 1-indexed position in that ASC order, so "두 번째 문서" resolves to the
document the user *uploaded second* — a stable, user-intuitive anchor that
does not shuffle when a document is later edited (which would bump
`updated_at`). The two orderings are intentionally different and serve
different surfaces: the docs list is "what did I touch most recently", the
chat manifest is "the Nth thing I uploaded".

**Limit: 30** (`DOCUMENT_MANIFEST_LIMIT` in `ChatTurnService`). Bounds the
token cost of the section and the per-turn read.

**Cost:** one extra indexed SELECT in the prepare phase (`guardAndPrepare`).
The query filters `user_id = ?` and orders `created_at ASC`; the existing
docs index `ix_docs_user_updated` is on `(user_id, updated_at)`, so this
query is a per-user scan + sort on `created_at` rather than an index-ordered
read. At personal scale (≤30 rows per user after the `LIMIT`, small per-user
partition) this is a sub-millisecond scan well inside the §8 TTFT budget. A
covering `(user_id, created_at)` index on `docs.documents` would make it an
index-ordered read, but **this ADR does not change DDL** — `docs.documents`
is M2-owned (ADR-12 / ADR-05) and the current cost does not warrant a
migration. Flagged here for the M2/docs owner to consider only if a future
profile shows it material; M4/M8 does not require it.

### A14M8.4. Injection gating — manifest enters the prompt only when tools are registered

**Decision: `ChatTurnService` fetches the manifest in `guardAndPrepare` and
carries it in `Preparation`, but passes it to `assemble(...)` ONLY when the
tool-descriptor list is non-empty (the ADR-17 §1 tool branch).** When no
tools are registered, `promptDocuments = List.of()` and the §A14M8.2 empty
invariant keeps the M4 path inert and byte-identical.

```java
List<ToolDescriptor> descriptors = toolDescriptorSupplier.get();
List<UserDocumentRef> promptDocuments = descriptors.isEmpty() ? List.of() : prep.documents();
```

This bounds the token cost to turns that can actually use a `briefDocId` and
keeps the no-tool path's prompt unchanged.

**Graceful degradation:** the manifest fetch is wrapped in try/catch in
`guardAndPrepare`; a lookup failure logs `document_manifest_lookup_failed` at
WARN and substitutes an empty list. An empty manifest (lookup failed, or the
user has uploaded nothing) omits the `[YOUR DOCUMENTS]` section and the turn
proceeds — the model simply cannot resolve an ordinal/title reference and (per
the prompt instruction) asks the user. The manifest is non-critical, mirroring
the display-name read's graceful-degrade posture in §3.

### A14M8.5. Security / tenant — caller-scoped, own ids only

**Decision: the SELECT is scoped `WHERE user_id = ?` (the `X-User-Id`
caller), so the manifest exposes only the caller's own documents** — the same
tenant invariant as M4 retrieval (§3.2) and consistent with ADR-09's
"backends trust the header" rule. Raw document UUIDs now appear in the model's
context, which is acceptable: they are the caller's own ids, and tool dispatch
downstream (ADR-17 / ADR-18) re-enforces `X-User-Id` ownership when the brief
is fetched — a model that copied an id it was shown cannot reach another
user's document because the downstream owner check still gates the read.

### A14M8.6. Files + identifiers

| Concern | Identifier |
|---|---|
| Domain record | `UserDocumentRef` (`rag-chat-domain`) |
| App port | `UserDocumentManifestPort.recentForUser(UserId, int)` (`rag-chat-app`) |
| Infra adapter | `CrossSchemaUserDocumentManifestAdapter` (`rag-chat-infra`) |
| Prompt assembler | `PromptTemplate.assemble(...)` 5-arg overload → `[YOUR DOCUMENTS]` (`rag-chat-domain`) |
| Orchestration | `ChatTurnService.guardAndPrepare` / `Preparation.documents()` / `DOCUMENT_MANIFEST_LIMIT = 30` (`rag-chat-app`) |
| Tool-arg reinforcement | `MassingTool` `briefDocId` schema `description` (`rag-chat-domain`) |

This amendment supersedes nothing; ADR-08 is **not** amended (no new HTTP
exception), and ADR-18's Exception 5 is untouched (the contrast in §A14M8.1 is
explanatory, not a change). The §3 cross-schema table and §1.5 note carry
inline pointers here.
