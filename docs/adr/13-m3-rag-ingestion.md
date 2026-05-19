# ADR-13: M3 RAG-Ingestion — Implementation Decisions

## Status
Accepted

## Context

The M3 PRD (`docs/prd/M3-rag-ingestion.md`) pins the bounded context, the three
consumed Kafka topics (`docs.document.uploaded`, `docs.document.visibility-changed`,
`docs.document.deleted`), the pgvector chunk schema invariants, and the M4
retrieval contract M3 enables. It deliberately defers 13 implementation-shape
questions to this per-milestone ADR (PRD §"Open questions for the implementer").

ADR-12 already did the same job for M2 Docs: pinned BlockNote versions, the
OpenSearch projector, the body-fetch internal HTTP route, and the body size
cap. ADR-12 §1 also confirmed that **Spring Modulith Events JPA** (the outbox
chosen in ADR-10 §8 for M1) is **inherited by M2-M5 unless explicitly
superseded**. This ADR honors that inheritance and pins only the M3-specific
additions on top — chunking parameters, retry curves, the DLQ topic, the
ingestion-complete signal mechanism, the pgvector index choice and parameters,
and the module quadruplet wiring for a UI-less BC.

The M3 BC has **no public HTTP surface** (per PRD §"UX surfaces" + ADR-09).
The gateway has no route to M3; the only inbound traffic is Kafka. This
shapes some of the decisions below — most notably the module quadruplet's
`-api` role, the port assignment (actuator only), and the absence of any
ADR-09 amendment.

Like ADR-10 and ADR-12, none of the decisions below supersede a transverse
ADR; they fill in implementation details inside the envelopes ADR-01, ADR-02,
ADR-03, ADR-04, ADR-05, ADR-07, ADR-08, and ADR-09 defined. Three explicit
amendments to transverse ADRs are captured at the bottom of this ADR and
appended inline to the affected ADR files (ADR-03 topic registry, ADR-05
pgvector index pin, ADR-08 cross-reference note).

## Decision

### 1. Chunking — markdown-aware (M3.1 amendment, 2026-05-19), 800-token windows with 120-token overlap

> **M3.1 amendment (2026-05-19):** §1 was originally drafted with the fixed
> token-window `MarkdownChunker` as the default and alternative (c) "semantic
> chunking" deferred to M3.1. The deferred work has now shipped:
> `MarkdownAwareChunker` is the default chunker; the token-window algorithm
> is preserved as a **parse-fallback** path only (fires when commonmark-java
> throws — extremely rare).

**Decision:**

| Parameter | Default | Rationale |
|---|---|---|
| `playground.rag-ingestion.chunk.size-tokens` | **800** | BGE-M3 accepts up to 8192 input tokens, but retrieval quality plateaus and recall drops once chunks exceed ~1k tokens for typical Korean prose. 800 is a recall/precision sweet spot for the M2 corpus (long-form essays, ~10-50 KB MD bodies; rare tail of large `.md` uploads up to 1 MB). |
| `playground.rag-ingestion.chunk.overlap-tokens` | **120** | Dual meaning per the M3.1 amendment. **Normal path:** caps the heading-prefix budget injected at chunk start (`> Context: A > B > C` breadcrumb). **Parse-fallback path:** sliding-window stride = `size - overlap`. Same numeric value, different role per path. |
| `playground.rag-ingestion.chunk.tokenizer` | **`cl100k-base`** (via JTokkit) | Approximation tokenizer for the chunker — BGE-M3's actual tokenizer is XLM-RoBERTa-based but we don't need exact parity here; we only use this to bound chunk sizes pre-embedding. JTokkit is JVM-native, zero-dep beyond a small jar. |
| `playground.rag-ingestion.chunk.min-chunk-tokens` | **64** | A trailing chunk smaller than 64 tokens is merged back into the previous chunk; avoids degenerate one-sentence chunks at document tails. |
| `playground.rag-ingestion.chunk.max-oversize-fence-tokens` (M3.1) | **800** | Fenced code blocks larger than this get line-split inside the fence (each split chunk re-opens with the original language tag). Fences ≤ this stay atomic even if they exceed `size-tokens` (the atomicity beats the size cap for citation rendering correctness). |
| `playground.rag-ingestion.chunk.preserve-heading-path` (M3.1) | **true** | Toggle for the heading-aware prefix injection on chunks 2..N of a section. Disable to fall back to plain block packing without breadcrumb. |

**Location:** values live in `rag-ingestion-api/src/main/resources/application.yml`
under the `playground.rag-ingestion.chunk` namespace. A `ChunkingProperties`
`@ConfigurationProperties` POJO in `rag-ingestion-infra` (Spring Boot type
forbidden in `-app` per ADR-02) exposes them as a typed bean; the chunker
(`MarkdownAwareChunker` in `rag-ingestion-domain`, pure-Java) takes the
derived `ChunkingPolicy` as a constructor arg (no Spring import in
`-domain`, per ADR-02).

**Algorithm (M3.1):** `MarkdownAwareChunker` composes `SectionBuilder` +
`WindowNormalizer`:

1. `SectionBuilder` parses the body with commonmark-java (GFM tables +
   strikethrough extensions enabled), walks the top level grouping blocks
   into `Section`s separated by `Heading` nodes. Each section carries its
   `headingPath` (h1..hN breadcrumb).
2. `WindowNormalizer` packs blocks within each section greedily up to
   `size-tokens`. Cross-section pack is forbidden — each section is ≥ 1
   chunk. Oversize blocks are handled per type:
   - Fenced code: atomic if ≤ `max-oversize-fence-tokens`, else line-split
     with the original language tag re-emitted on each chunk.
   - GFM table: same threshold, with the header row + separator repeated on
     each split chunk.
   - Paragraph: sentence-split via `SentenceSplitter` (default
     `JdkBreakIteratorSentenceSplitter`, JDK-only); single sentences
     exceeding `size-tokens` fall back to token-window slicing with WARN log.
   - Lists / blockquotes: recurse into items / children.
3. Chunks 2..N of a section get a `> Context: A > B > C` breadcrumb prefix
   (budget ≤ `overlap-tokens`); top-level headings drop first when the
   breadcrumb overflows budget.
4. Trailing chunks shorter than `min-chunk-tokens` merge back into the
   previous chunk in the same section.

**Why `application.yml` and not constants:** PRD §"Acceptance criteria"
says chunk size + overlap are "configurable". A `@ConfigurationProperties`
binding lets an operator override defaults via environment variable
(`PLAYGROUND_RAG_INGESTION_CHUNK_SIZE_TOKENS=…`) without recompilation —
useful for the M3.1 re-embedding job experiments. Constants would lock the
values at build time.

**Considered alternatives (kept for posterity):**
- (a) **512 + 64 overlap** (industry default for OpenAI `text-embedding-ada-002`-era pipelines). Rejected: BGE-M3 handles longer contexts well, and Korean documents need more semantic context per chunk than a 512-token window provides.
- (b) **1024 + 128 overlap** (BGE-M3's commonly-quoted "preferred" size). Rejected: 1024 over-mixes topics in M2's long-form essays; recall on per-paragraph questions drops in informal benchmarking.
- (c) ~~**Semantic chunking** (split on headings + paragraph boundaries with a max-size cap). Rejected for M3 P0~~ — **adopted in M3.1.** The markdown-aware path is now default; the simple token-window chunker is retained as the parse-fallback safety net.

**Chunker location (per the quadruplet rules in §4 below):**
`MarkdownAwareChunker` lives in `rag-ingestion-domain` — pure-Java
algorithm with no Spring/JPA/Kafka coupling. commonmark-java + JTokkit jars
are on `-domain`'s classpath (leaf libraries, not frameworks).
`heading_path text[]` column added to `rag.document_chunks` via Flyway
migration `V202605200003__add_chunk_heading_path.sql`; populated for every
new ingest, backfilled by the operator-triggered `reembed` profile (§7).

### 2. Embedding retry policy — 3 retries, exponential backoff with jitter, classified errors

**Decision (mirrors ADR-12 §2's WebClient discipline, with embedding-specific
classification):**

| Concern | Pin |
|---|---|
| Initial timeout per embedding call | **15 seconds** (BGE-M3 batch of up to 32 chunks; gateway is on `host.docker.internal` so RTT is local but inference can take seconds for a full batch) |
| Max attempts | **3** (one initial + 2 retries) |
| Backoff base delays | **400 ms, 1.6 s** (multiplier 4, jitter 0.5) — `Retry.backoff(2, Duration.ofMillis(400)).multiplier(4).jitter(0.5)` |
| Max delay cap | **5 seconds** between retries |
| Retryable classifications | **5xx** from spark-inference-gateway, `TimeoutException`, `ConnectException`, `IOException` |
| Non-retryable classifications | **4xx** (esp. 400 invalid input, 413 payload too large, 422 unprocessable) — go straight to DLQ |
| Retry exhaustion → DLQ | Yes (see §8) |

**Embedding batch size:** **32 chunks per HTTP call** to spark-inference-gateway.
The vLLM-backed gateway accepts batched OpenAI-compatible embedding requests;
batching amortizes RTT and HTTP overhead. Documents producing fewer than 32
chunks ship in a single call.

**Why these numbers:**
- 15 s timeout: a single batch of 32 chunks × 800 tokens = ~25k tokens of
  embedding work, well within typical BGE-M3 inference budgets even on
  modest GPUs. 15 s gives wide headroom; gateway down or queue-saturated
  scenarios surface as timeouts before retry exhaustion.
- 3 attempts: tight enough that a permanently-down gateway doesn't keep
  the consumer hung; wide enough that transient hiccups self-heal without
  paging. Total worst-case time per event = 3 × 15 s + 0.4 s + 1.6 s ≈
  47 s before DLQ — under the latency SLO's WARN threshold (§6).
- Jitter 0.5: industry default; prevents thundering-herd when a single
  gateway blip releases multiple paused events at once (rare in
  single-instance dev but cheap insurance).

**Classification source:** the WebClient adapter inspects
`WebClientResponseException.getStatusCode()` and the cause class. Anything
not in the retryable set short-circuits to the DLQ router without consuming
retry budget.

**Body-fetch retry policy (the call to docs-api `/internal/docs/public/{id}/body`)
inherits ADR-12 §2 + ADR-08 Exception 1 verbatim:**

| Concern | Pin |
|---|---|
| Timeout | **5 seconds** |
| Max attempts | **3** |
| Backoff | 200 ms, 400 ms, 800 ms base, jitter 0.5 |
| Non-retryable | 404 (doc deleted between event and fetch — DLQ-but-quiet), 413 (body too large — DLQ), 400 (bad request — DLQ) |
| Retryable | 5xx, connect timeout, read timeout |

Two distinct retry contexts: docs-api body-fetch is short-RTT, cheap;
embedding is long-RTT, expensive. The numbers reflect that.

### 3. Ingestion-complete signal — new Kafka event `rag.document.ingested`

**Decision: Option (a) — publish a new domain event `rag.document.ingested`
when ingestion finishes successfully.**

| Option | Trade-off | Choice |
|---|---|---|
| (a) New Kafka event `rag.document.ingested` | Clean event-driven contract; M4 can subscribe; signal travels through the same envelope + outbox machinery as every other BC's events. One new topic, one new event class. | **Chosen** |
| (b) DB flag column (`ingestion_complete_at` on `rag.document_chunks` or a separate `rag.document_ingestion_state` table) | M4 reads pgvector directly anyway, so checking a flag is "for free" with the retrieval query. But couples M4 to M3's table layout; harder to evolve. | Rejected |
| (c) No signal — M4 treats "0 chunks" as "not ready yet" | Zero new infrastructure. But indistinguishable from "this document genuinely has no embeddable content" or "the document was deleted just now". M4 cannot render an "indexing…" hint without polling. | Rejected |

**Why (a):**
- Consistent with the project's event-driven posture (ADR-03, ADR-08 default).
  M3 is already a Kafka publisher of nothing yet; adding one outbound topic
  with the same Spring Modulith Events JPA wiring M1 and M2 use costs one
  domain event class + one `application.yml` topic mapping line.
- M4's "indexing…" UX hint (PRD Story 10) is implementable without polling.
- The event is also a natural audit log entry: "this document is now
  retrievable from M4". Operators reading Kafka topics see a clear lifecycle.

**Topic name:** **`rag.document.ingested`** (per ADR-03 topic naming
`<bc>.<aggregate>.<event-past-tense>`).

**Event payload (envelope per ADR-03):**

```java
// rag-ingestion-domain
package dev.jeeklee.playground.ragingestion.domain.event;

public record DocumentIngested(
    UUID    documentId,    // logical FK to docs.documents.id
    UUID    userId,        // chunk owner
    String  visibility,    // "public" | "private", current at ingestion
    int     chunkCount,    // number of rows written to rag.document_chunks
    String  bodyChecksum,  // SHA-256 of the body that produced these chunks
    Instant embeddedAt     // when the last chunk was written
) {}
```

**Key:** `documentId` (per ADR-03's "key = aggregateId" rule).

**Publisher placement:** `DocumentIngested` is published via Spring's
`ApplicationEventPublisher` inside the same `@Transactional` boundary as the
final chunk-upsert SQL. The Spring Modulith Events JPA outbox bridges to
Kafka asynchronously. This is the same wiring M1 (ADR-10 §8) and M2 (ADR-12
§1) use — no new outbox library.

**Outbox `event_publication` table** lives in the `rag` schema, populated by
Modulith's `spring.modulith.events.jdbc.schema-initialization.enabled=true`.
Mirrors M2's placement of `event_publication` in the `docs` schema.

**Effect on ADR-08:** publishing `rag.document.ingested` is **not a new
exception** — it goes through Kafka, which is the default sanctioned
cross-BC channel. ADR-08 is unchanged for M3's publish side. The two
existing exceptions (M3 → docs body-fetch, M3 → Redis lock) are reused
verbatim; no new HTTP path is added.

**Effect on ADR-03:** the topic registry needs a new row. See the amendment
block at the bottom of this ADR.

### 4. Module quadruplet — full four-module layout, `-api` hosts the Kafka entry point

**Decision (matches ADR-01 v2's quadruplet pattern with one M3-specific twist):**

| Module | Type | Port | What it hosts |
|---|---|---|---|
| `rag-ingestion-api` | runnable Spring Boot app | **18083** (actuator only, host-not-exposed) | Spring Boot bootstrap, `application.yml`, `@SpringBootApplication`, `/actuator/**` endpoints, the **Kafka `@KafkaListener` container factory wiring**, the Spring Modulith Kafka externalization config. **No HTTP controllers.** |
| `rag-ingestion-app` | Java library | n/a | Use-case orchestrators (`IngestDocumentUseCase`, `RetagVisibilityUseCase`, `PurgeDocumentUseCase`), application services, port interfaces (`EmbeddingPort`, `ChunkRepositoryPort`, `BodyFetchPort`, `DistributedLockPort`), the `@GlobalLock`-style aspect that wraps use-case methods with Redisson locks. |
| `rag-ingestion-domain` | Java library | n/a | `DocumentChunk` aggregate, `MarkdownChunker` algorithm, `BodyChecksum` value object, the three consumed-event POJOs (mirrored from docs-domain — see §4.5 below), the `DocumentIngested` published-event POJO. **No Spring imports.** |
| `rag-ingestion-infra` | Java library | n/a | JPA adapter for `rag.document_chunks` (pgvector dialect), Spring Kafka consumer beans, Spring Modulith Kafka bridge, WebClient adapter for spark-inference-gateway embeddings, WebClient adapter for docs-api `/internal/**` body fetch, Redisson distributed-lock adapter. |

**Why `-api` hosts the Kafka listener and not `-infra`:**

The Kafka `@KafkaListener` methods are the BC's **outermost adapter** — the
equivalent of an HTTP controller for an HTTP-fronted BC. ADR-02 places
controllers in `-api`; by symmetry, Kafka listener beans (the entry point
of the event-driven pipeline) live in `-api`. They are thin: each listener
deserializes the envelope, calls a use-case in `-app`, and acks.

`-infra` hosts:
- The Spring Kafka **infrastructure beans** (consumer factory, container
  factory, deserializer, error handler) — the plumbing under the listener.
- The Spring Modulith Kafka externalization config that bridges the
  outbox to the producer side.
- All outbound adapters (HTTP clients, JPA adapter, Redisson adapter).

The split: `-api` = "Kafka listener method bodies" (entry point adapters);
`-infra` = "everything Kafka needs to deliver and consume" (transport plumbing).
Same pattern M2 uses for its `DocsSearchProjector`: the projector bean lives
in `docs-app`, but its Kafka subscription wiring lives in `docs-infra`.
M3's `-api` houses the entry point because there is no HTTP surface to
otherwise justify a runnable `-api` module.

**Considered alternative:** collapse `-api` into `-infra` (make `-infra`
the runnable Spring Boot app). Rejected because (a) it breaks ADR-01 v2's
universal quadruplet contract — every other BC has a `-api` and tooling
(bootRun targets, Dockerfile, port assignment) assumes it; (b) the
"Kafka listener as entry point" mapping is clean and lets a future HTTP
admin endpoint (e.g., M3.1 backfill CLI exposed as HTTP) land in `-api`
without restructuring.

**Component placement summary (resolves PRD Q4 sub-questions):**

| Component | Module | Notes |
|---|---|---|
| `@KafkaListener` methods | `rag-ingestion-api` | One listener per consumed topic, each deserializes envelope, calls use-case. |
| Spring Kafka container factory, error handler, deserializer | `rag-ingestion-infra` | Includes the DLQ-routing `DefaultErrorHandler` configured per §8. |
| Redisson `RLock` lock acquisition (the `@GlobalLock` semantic) | `rag-ingestion-app` (aspect) + `rag-ingestion-infra` (Redisson adapter) | The aspect uses the `DistributedLockPort` from `-app`; the Redisson adapter implementing it lives in `-infra`. Mirrors ADR-08 Exception 2's port-and-adapter sketch. |
| BGE-M3 client (WebClient to spark-inference-gateway) | `rag-ingestion-infra` (`SparkInferenceEmbeddingAdapter`) | Implements `EmbeddingPort` from `-app`. |
| docs-api `/internal/**` body-fetch client | `rag-ingestion-infra` (`DocsApiBodyFetchAdapter`) | Implements `BodyFetchPort` from `-app`. |
| `MarkdownChunker` | `rag-ingestion-domain` | Pure algorithm. Constructor takes `ChunkingProperties` values. |
| `DocumentChunk` JPA `@Entity` | `rag-ingestion-infra` (entity class) | Domain `DocumentChunk` lives in `-domain` (pure POJO). The JPA `@Entity` is a separate persistence model in `-infra` (mapper between them in the adapter). Standard ADR-02 split. |
| pgvector type binding (`Vector` Hibernate UserType / `com.pgvector:pgvector` jar) | `rag-ingestion-infra` | Hibernate dialect configured in `-infra`'s `application-rag-ingestion.yml` fragment. |
| Spring Modulith Events JPA outbox + Kafka bridge | `rag-ingestion-infra` + `rag-ingestion-api` config | Same wiring as M1, M2. |
| `DocumentIngested` event POJO | `rag-ingestion-domain` | Pure record, no Spring. |
| Publishing the event via `ApplicationEventPublisher` | `rag-ingestion-app` | Inside `IngestDocumentUseCase`, after the chunk-upsert succeeds. |

#### 4.5. Consumed-event POJOs — mirrored, not imported

M3 receives three events authored by the docs BC. The PRD §"Bounded Context"
and ADR-12 §1 place the original event POJOs in `docs-domain`. M3 **does
not** depend on `docs-domain` (ADR-08 forbids cross-BC compile-time coupling
outside `shared-kernel`).

**Decision:** `rag-ingestion-domain` declares its **own** event records
(`DocumentUploadedEvent`, `DocumentVisibilityChangedEvent`,
`DocumentDeletedEvent`) with the exact payload shape M2 emits (per M2 spec
§5 + the envelope from ADR-03). The Kafka deserializer in `rag-ingestion-infra`
maps the JSON payload into these local records. If M2's payload shape
changes, M3 follows via `schemaVersion` (per ADR-03) and updates its mirror
records — typed at compile time on the consumer side, decoupled at runtime.

**Why mirroring and not `shared-kernel`:**
- ADR-08's `shared-kernel` rule allows lightweight DTOs that **multiple BCs
  format identically**. The consumed-event POJOs are docs-BC-authored —
  they conceptually belong to docs, not the shared kernel.
- Mirroring imposes one small cost (keep two records in sync) but preserves
  the invariant that adding a field to docs's `DocumentUploaded` doesn't
  ripple into M3's compile graph until M3 actively wants to consume it.

### 5. Race condition: `visibility-changed` arrives before `uploaded` finishes — option (a) serialize via the same Redisson lock

**Decision: option (a) — `visibility-changed` and `deleted` use the same
Redisson lock key as `uploaded` (`rag-ingestion:lock:document:{id}`).
The lock holder semantics guarantee that the second-arriving event blocks
until the first releases, then re-fetches state and proceeds.**

| Option | Trade-off | Choice |
|---|---|---|
| (a) Serialize via the shared per-document Redisson lock | Simplest invariant. The lock TTL (5 min, per ADR-08 Exception 2) bounds worst-case stall. `visibility-changed` waits until `uploaded` finishes, then runs the UPDATE against the freshly-written chunks. | **Chosen** |
| (b) Process `visibility-changed` immediately; if no chunks exist for the document, no-op and rely on `uploaded` (when it eventually finishes) to call docs-api's auxiliary `/internal/docs/public/{id}` route to read the current visibility | Faster on the hot path; one more docs-api round-trip per `uploaded`. Harder to reason about — the "current visibility" can flip again between the read and the chunk write. | Rejected |
| (c) Drop — eventually consistent, M4 retrieval lag absorbs the staleness | M4 retrieval could surface a chunk with stale `visibility` for up to several seconds. ADR-09's "Public retrieval scoping" invariant says chunks must be re-tagged "within a bounded latency"; "drop" doesn't bound anything. | Rejected |

**Why (a):**
- The Redisson lock per `documentId` is already the idempotency primitive
  (PRD §"Idempotency invariant"). Reusing it for ordering costs nothing —
  every event handler on a given `documentId` already acquires the lock.
- Lock acquisition order is approximately Kafka delivery order (single
  consumer instance, single partition per key). The lock guarantees
  serialization even if Kafka were to deliver out-of-order (rare but
  possible across rebalances).
- The auxiliary `GET /internal/docs/public/{id}` metadata route exists
  (per ADR-08 Exception 1 + ADR-12 §2) and is used inside the
  `visibility-changed` handler **only when** the event payload's
  `newVisibility` was already incorporated by an in-flight `uploaded`
  (defensive re-read; cheap one-row fetch).

**Encodable test invariant** (per PRD §"Open questions" Q5 requirement —
"the policy must be encodable as a test invariant"):

> Given a `documentId` with no existing chunks, when `docs.document.uploaded`
> (visibility=private) and `docs.document.visibility-changed` (newVisibility=public)
> are both published at the same Kafka offset boundary in arbitrary order,
> after both events drain the lock, **`rag.document_chunks` rows for that
> documentId all have `visibility = 'public'`** (the most recent
> `visibility-changed` wins) **regardless of arrival order**.

The integration test publishes the two events in both orders and asserts
the same final state.

**Lock TTL cap of 5 minutes** (per ADR-08 Exception 2) is the hard
upper bound on serialization stall. If `uploaded` legitimately exceeds
5 min (it shouldn't — embedding budget §2 caps at ~47 s worst case),
the lock auto-releases and the late event proceeds; this is a bug
worth WARN-logging, not a hung consumer.

### 6. Latency SLOs — 30 s upload-to-queryable, 5 s flip-to-retag, WARN at 2× target

**Decision (confirms PRD working defaults with explicit WARN thresholds):**

| Metric | Target (P95) | WARN threshold |
|---|---|---|
| `uploaded` event received → all chunks visible in pgvector | **≤ 30 seconds** | Single event exceeds 60 s |
| `visibility-changed` event received → all chunk rows updated | **≤ 5 seconds** | Single event exceeds 15 s |
| `deleted` event received → all chunk rows purged | **≤ 5 seconds** | Single event exceeds 15 s |
| End-to-end queryability after publish (`docs.document.uploaded` published → `rag.document.ingested` published) | **≤ 30 seconds P95** | — (same as the first row, observed via `rag.document.ingested` emission timestamp) |

**Rationale:**
- 30 s assumes the typical body is 10-50 KB → ~5-25 chunks → 1-2 embedding
  batches at ~5-15 s each + body fetch (sub-second) + DB writes (sub-second).
  The retry budget (§2) is 47 s worst case for a single retry-exhausted event;
  P95 stays well under 30 s when the gateway is healthy.
- 5 s for visibility/deletion: pure SQL UPDATE/DELETE against the
  `(document_id)` index. The chunk count per document is bounded (1 MB body
  → at most ~250 chunks); the UPDATE is single-digit milliseconds in
  practice. The 5 s budget covers Kafka delivery lag + lock acquisition.
- 2× WARN threshold: distinguishes "system is slow" from "system is broken".
  Operators only page on broken; "slow" generates structured-log breadcrumbs
  for trend analysis (M5 dashboard).

**Metric surface** (per PRD Story 5):

| Micrometer metric | Type | Labels |
|---|---|---|
| `playground.rag_ingestion.embed.duration` | Timer | `outcome` ∈ {success, retry, failed} |
| `playground.rag_ingestion.body_fetch.duration` | Timer | `outcome` |
| `playground.rag_ingestion.chunk.count` | DistributionSummary | (no labels) |
| `playground.rag_ingestion.ingestion.duration` | Timer | `event_type` ∈ {uploaded, visibility_changed, deleted}; `outcome` |
| `playground.rag_ingestion.dlq.routed` | Counter | `topic`, `reason` |
| `playground.rag_ingestion.lock.wait_duration` | Timer | (Redisson `RLock` wait time) |
| `playground.rag_ingestion.chunker.duration` (M3.1) | Timer | `outcome` ∈ {success, parse_fallback} |
| `playground.rag_ingestion.chunker.oversize_fence_split` (M3.1) | Counter | — |
| `playground.rag_ingestion.chunker.oversize_sentence_fallback` (M3.1) | Counter | — |
| `playground.rag_ingestion.chunker.parse_fallback` (M3.1) | Counter | — |
| `playground.rag_ingestion.reembed.documents` (M3.1) | Counter | `outcome` ∈ {success, skipped, failed} |
| `playground.rag_ingestion.reembed.duration` (M3.1) | Timer | — |

Names follow the Micrometer `<app>.<bc>.<subsystem>.<measurement>` shape;
M5's dashboard pulls them via `/actuator/prometheus`.

### 7. Backfill — forward-only at M3 P0; one-shot CLI via Spring Boot `CommandLineRunner` profile at M3.1

**Decision (confirms PRD's working direction):**

| Phase | Behavior |
|---|---|
| **M3 P0** | Forward-only consumption. Kafka consumer group `rag-ingestion` starts at `latest` offset on first deploy. Pre-existing `docs.documents` rows (created in M2 before M3 shipped) are **not** auto-backfilled. |
| **M3.1** | One-shot CLI via a dedicated Spring profile (`backfill`). Invocation: `./gradlew :rag-ingestion:rag-ingestion-api:bootRun --args="--spring.profiles.active=backfill --since=<ISO-8601>"`. Activates a `BackfillCommandLineRunner` bean (only present under the `backfill` profile) that scans `docs-api`'s `GET /internal/docs/public/{id}` for the candidate documentId list (from a Postgres query against `docs.documents` filtered by `created_at >= --since`) and replays them through the normal `IngestDocumentUseCase` (same lock, same idempotency). |
| **M3.1 (re-embed, 2026-05-19)** | Sibling profile `reembed` for migrating the existing corpus to new chunker boundaries / new metadata columns. Invocation: `./gradlew :rag-ingestion:rag-ingestion-api:bootRun --args="--spring.profiles.active=reembed --playground.rag-ingestion.reembed.scope=all"`. Activates a `ReembedCommandLineRunner` bean (only under the `reembed` profile) that scans `rag.document_chunks` for distinct `document_id` values (scope-filtered: `all` / `user:<uid>` / `document:<docId>`) and re-runs `ReembedService.reembedOne` per document — same Redisson lock as live `uploaded` events, same `replaceAll` transaction, same `rag.document.ingested` emission. Per-document failures are isolated (skipped on 404, failed on permanent embed errors) and the run continues; exit code 2 if any document failed. |

**Why `CommandLineRunner` + profile, not a separate executable:**
- Reuses the entire wiring (Kafka factory excepted — the `backfill` profile
  disables Kafka consumers via `spring.kafka.listener.auto-startup=false`).
- One Spring Boot jar to ship; no separate CLI binary, no separate Dockerfile.
- The `--since` arg is parsed via `org.springframework.boot.ApplicationArguments`.

**The candidate list source:** the CLI calls a new `docs-api`
`GET /internal/docs/scan?since=<ts>` route (added to docs-api at M3.1, NOT M2)
that returns `[{ "id": "...", "userId": "...", "visibility": "..." }, ...]`
for documents whose `updated_at >= since`. The route is `/internal/**`
(unforwarded by the gateway, per ADR-07) and is the natural home for
backfill bookkeeping. **This route is in M3.1's scope, not M3 P0.**

**Why not `kafka-console-producer` replay or `kafka-consumer-groups
--reset-offsets`:**
- Resetting the consumer-group offset re-delivers historical events, which
  would only help if M2 has been emitting `docs.document.uploaded` since
  before M3's deploy *and* Kafka's retention (7 days per ADR-03) hasn't
  aged them out. Both conditions are unreliable; the CLI scan is the
  predictable path.

**Backfill is explicitly out of scope for M3 P0.** The roadmap deliverable
is a deployable, forward-only ingestion pipeline.

### 8. DLQ topic + reprocessing — `<topic>.dlq` per ADR-03; manual replay via Kafka UI for M3 P0

**Decision:**

| Concern | Pin |
|---|---|
| DLQ for `docs.document.uploaded` failures | **`docs.document.uploaded.dlq`** (per ADR-03 §"Dead-letter topics" convention) |
| DLQ for `docs.document.visibility-changed` failures | **`docs.document.visibility-changed.dlq`** |
| DLQ for `docs.document.deleted` failures | **`docs.document.deleted.dlq`** |
| DLQ partitions / retention | **3 partitions, 14 days retention** (per ADR-03 default for DLQs; doubled retention vs business events because DLQ messages await operator triage) |
| DLQ key | Same as source topic key (`documentId`) — preserves ordering per document on replay |

**Why a DLQ per source topic, not a unified `rag-ingestion.dlq`:**
- ADR-03's naming convention pins `<topic>.dlq` per topic. Following the
  convention means topic UIs (Kafka UI, Conduktor) auto-pair source and DLQ
  for inspection.
- Each source topic has different consumer semantics
  (`uploaded` triggers expensive embedding work; `visibility-changed` is
  cheap SQL; `deleted` is cheap SQL). A unified DLQ would mix three
  failure shapes; per-topic DLQs preserve the symmetry.

**DLQ envelope:**

Spring Kafka's `DeadLetterPublishingRecoverer` (default) re-publishes the
original record with three additional headers:
- `kafka_dlt-exception-class-name`
- `kafka_dlt-exception-message`
- `kafka_dlt-original-topic`

The record body is the original envelope (per ADR-03), unchanged. Replay
tools can move the record back to the source topic by stripping the `dlt-`
headers (Spring Kafka's idiom).

**Reprocessing tooling at M3 P0:**

- **No operator CLI in M3 P0.** Manual replay via Kafka UI / `kafkactl` is
  acceptable for the personal-scale operations cadence. The DLQ entry's
  `documentId` key + `kafka_dlt-exception-class-name` header is enough to
  triage.
- **Structured WARN log** on every DLQ-route emits the `documentId`,
  `userId`, `eventType`, `bodyChecksum`, exception class, and exception
  message. Operator decision tree from the log is: 4xx body-fetch → check
  if doc still exists; 5xx embedding → wait for gateway recovery + replay;
  413 body-too-large → operator data issue, no replay until fixed upstream.

**Reprocessing tooling at M3.1:** an operator CLI
(`./gradlew :rag-ingestion:rag-ingestion-api:bootRun --args="--spring.profiles.active=dlq-replay --topic=docs.document.uploaded.dlq"`)
drains the DLQ back to the source topic. Same `CommandLineRunner` mechanism
as §7. Out of scope for M3 P0.

**Error handler wiring** (in `rag-ingestion-infra`'s Kafka configuration):

```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template) {
  var recoverer = new DeadLetterPublishingRecoverer(template,
      (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition() % 3));
  return new DefaultErrorHandler(
      recoverer,
      new ExponentialBackOffWithMaxRetries(2)
          .setInitialInterval(400L)
          .setMultiplier(4.0)
          .setMaxInterval(5_000L));
}
```

This is a single shared error handler for all three `@KafkaListener`s. Per-listener
overrides only if the retry policy diverges from §2 (it does not for M3 P0).

### 9. pgvector index — HNSW (m=16, ef_construction=64), cosine ops

**Decision: HNSW.**

| Option | Trade-off | Choice |
|---|---|---|
| **HNSW** (`hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)`) | Best query latency for personal-scale corpora; build cost grows linearly with rows but is fine up to ~100k chunks. Default-recommended by pgvector docs for "production-style" workloads. ADR-05 already pins HNSW as the default. | **Chosen** |
| IVFFlat (`ivfflat (embedding vector_cosine_ops) WITH (lists=100)`) | Lower build cost (faster to add new chunks at scale); query quality requires tuning `lists` to ~sqrt(rows) — coupled to corpus size. | Rejected for M3 P0; fallback only if HNSW build time becomes a problem (ADR-05 amendment "IVFFlat is the fallback if HNSW build cost becomes prohibitive at corpus scale" stands). |
| No vector index (sequential scan) | Always-correct, never tuned. Query time O(N) in the corpus size. | Rejected — even at 1k chunks this dominates retrieval latency. |

**Parameters chosen:**

| Parameter | Value | Why |
|---|---|---|
| `m` | **16** | pgvector default; balances build memory with recall. |
| `ef_construction` | **64** | pgvector default; quality at build time. |
| `ef_search` (runtime) | **40** | Set via `SET LOCAL hnsw.ef_search = 40;` at query time in M4's retrieval. Sets the candidate pool size for the search — 40 is wide enough for K=10 retrieval at >95% recall on the M2 corpus. |
| Distance op | **`vector_cosine_ops`** | Matches BGE-M3's intended similarity (cosine, not L2 or inner-product). |

**Corpus-size assumption (made explicit):** M3 P0 targets up to **~50k
chunks total** (roughly 500-2000 documents at the M2 personal-scale rate).
HNSW build cost per insert at this size is sub-millisecond. **Revisit
when total chunks cross ~100k** — that's the threshold where HNSW build
on the hot path starts costing tens of milliseconds per chunk and IVFFlat's
batch-build characteristics become attractive.

**Maintenance note:** HNSW index recall degrades slightly as rows churn
(insert/delete cycles). An optional `REINDEX INDEX CONCURRENTLY` job is
M3.1's concern; M3 P0 ships without periodic reindexing because at the
P0 corpus size the degradation is invisible.

**Effect on ADR-05:** ADR-05's "Default index: HNSW (m=16, ef_construction=64)"
line already pins this. ADR-13 confirms it explicitly and adds the
`ef_search=40` runtime hint (the runtime hint is M4-owned but documented
here because it's part of the M3-enabled retrieval contract). See the
ADR-05 amendment note at the bottom.

### 10. Embedding dimension + spark-inference-gateway endpoint sketch

**Decision (confirms and aligns with ADR-04):**

| Concern | Pin |
|---|---|
| Model name | **BGE-M3** (dense head only; sparse + ColBERT heads unused for M3 P0) |
| Embedding dimension | **1024** (BGE-M3's dense head; matches ADR-04 + ADR-05's `vector(1024)` column) |
| Gateway base URL | **`http://host.docker.internal:10080`** (per ADR-04) |
| Endpoint | **`POST /v1/embeddings`** (OpenAI-compatible; vLLM exposes this for any embedding model) |
| Request shape | OpenAI-compatible — `{"model": "BGE-M3", "input": ["chunk 1 text", "chunk 2 text", ...]}` |
| Response shape | OpenAI-compatible — `{"data": [{"embedding": [...1024 floats...], "index": 0}, ...]}` |
| Client | **`spring-ai-openai-spring-boot-starter`** (per ADR-04) via the `EmbeddingModel` bean |
| Spring AI property | `spring.ai.openai.embedding.options.model=BGE-M3` (per ADR-04) |

**No new amendment to ADR-04 is needed** — the model name, dimension, and
base URL are already pinned there. This ADR confirms them and locks the
batch size (32 chunks per call, §2) which ADR-04 left to the consuming
milestone.

**Operational note:** at first call, the implementer verifies that the
gateway's served model id is exactly `BGE-M3`. If it differs (e.g., the
vLLM launch script used `bge-m3` lowercase), the `application.yml`
property is overridden — **the ADR is not changed**, consistent with
ADR-04's "If the served names differ, override the property; do not
change this ADR".

### 11. Body streaming vs buffered read — buffered (full body in memory)

**Decision: buffered read.** The body-fetch WebClient call reads the full
response body into memory before passing it to the chunker.

| Option | Trade-off | Choice |
|---|---|---|
| Buffered (`.bodyToMono(String.class)`) | Simplest. The 1 MB M2 body cap (ADR-12 §4) bounds memory per in-flight ingestion to 1 MB. With the consumer concurrency at 1 (one event at a time per partition, single instance), peak transient memory is one body buffer. The chunker is a synchronous token-window pass over the entire string — naturally buffered-friendly. | **Chosen** |
| Streaming (`.bodyToFlux(DataBuffer.class)` + incremental chunking) | Lower peak memory; supports larger-than-RAM bodies. But the chunker would need a token-streaming variant, and the M2 cap makes the memory benefit cosmetic. Adds complexity for a non-problem. | Rejected |

**Why buffered:**
- 1 MB worst-case body × consumer concurrency 1 = 1 MB peak. The Spring
  Kafka container default is one in-flight record per partition; the
  Redisson lock per `documentId` further serializes. No realistic scenario
  exceeds a handful of MB transient.
- The `MarkdownChunker` (§1) is a single-pass token-window algorithm; it
  needs the full string to know when to emit the last chunk. Streaming
  variants would add buffering anyway.
- 413 (body too large) from the body-fetch route is classified as
  non-retryable (§2) and routes to DLQ — no risk of an oversized body
  blowing the heap because the docs-api `CHECK` constraint (ADR-12 §4)
  prevents writes > 1 MB at the source.

**Memory safety:** the WebClient `maxInMemorySize` is explicitly set to
**2 MB** (2× the body cap, a small headroom for envelope overhead). Any
response exceeding 2 MB throws `DataBufferLimitException`, classified as
non-retryable, routed to DLQ. The same WebClient also sets
`responseTimeout(Duration.ofSeconds(5))` per §2's body-fetch policy.

### 12. `bodyChecksum` carrier — column on `rag.document_chunks`, denormalized

**Decision: store `body_checksum` as a column on every chunk row.**

| Option | Trade-off | Choice |
|---|---|---|
| (a) Column on `rag.document_chunks` (one value per chunk row, all rows for a `documentId` share the same checksum) | Denormalized but trivially simple. Idempotency check is a single `SELECT body_checksum FROM rag.document_chunks WHERE document_id = ? LIMIT 1` — cheap. No new table. | **Chosen** |
| (b) Separate `rag.document_ingestion_state` table (PK `documentId`, one row per document) | Normalized; natural home for additional bookkeeping (e.g., `last_ingested_at`, `chunk_count`). But introduces a second table for a single denormalized scalar; cross-table transactions for chunk-replace must include both. | Rejected |
| (c) No checksum on the row; recompute on every event | Idempotency check would require re-fetching the body and re-hashing — defeats the purpose. | Rejected |

**Why (a):**
- The PRD's idempotency invariant is `(documentId, bodyChecksum)`. Storing
  the checksum next to the chunks puts the invariant's two halves in one
  place; the use-case's first SQL is a one-shot SELECT.
- Storage cost is negligible — one 64-character hex string per chunk
  (~64 bytes). For 50k chunks: ~3 MB total. Pgvector's embedding column
  dwarfs it.
- Since the ingestion-complete signal is a Kafka event (§3), not a flag
  column, there's no second piece of bookkeeping to normalize alongside
  the checksum — option (b)'s "natural home for more state" benefit
  doesn't materialize.

**Effect on the schema** (full DDL in §F):
```sql
CREATE TABLE rag.document_chunks (
  ...
  body_checksum  TEXT NOT NULL,   -- SHA-256 hex, 64 chars
  ...
);
CREATE INDEX rag_document_chunks_document_id_idx
  ON rag.document_chunks (document_id);  -- supports the idempotency SELECT
```

**Idempotency check flow:**
1. Acquire Redisson lock on `rag-ingestion:lock:document:{id}`.
2. `SELECT body_checksum FROM rag.document_chunks WHERE document_id = ? LIMIT 1`.
3. If equal to event's `bodyChecksum` → no-op, ack, release lock, emit
   `rag.document.ingested` (defensive: a downstream consumer may have
   missed the earlier emission).
4. If different (or null/empty) → fetch body, chunk, embed, atomically
   `DELETE FROM rag.document_chunks WHERE document_id = ?` then bulk
   `INSERT` new chunk rows, all in one transaction. Emit
   `rag.document.ingested`.
5. Release lock.

The full `DELETE` + `INSERT` is the simplest correctness path. A "compute
diff and update in place" optimization is M3.1's concern; correctness here
matters more than write amplification.

### 13. Test fixture + contract test strategy — WireMock for HTTP, Testcontainers for the data path

**Decision:**

| Test layer | Stack | What it covers |
|---|---|---|
| **`rag-ingestion-domain` unit tests** | Pure JUnit 5 | `MarkdownChunker` invariants (chunk count, overlap, deterministic output for a given input), `BodyChecksum` SHA-256 correctness, event POJO shape. No Spring, no I/O. |
| **`rag-ingestion-app` slice tests** | Spring Boot slice test (`@SpringBootTest(classes = {...App.class})`) + Mockito for all ports (`EmbeddingPort`, `BodyFetchPort`, `ChunkRepositoryPort`, `DistributedLockPort`) | Use-case behavior — given a mocked body fetcher returning "...", embedding port returning N×1024 floats, asserts the chunk repository receives the expected upsert. Includes the lock-ordering invariant test from §5. |
| **`rag-ingestion-infra` integration tests** | **Testcontainers** for: `pgvector/pgvector:pg16` (Postgres + pgvector, per ADR-05), `redis:7-alpine` (per ADR-08 Exception 2), `apache/kafka:3.8.0` (per ADR-03). **WireMock** for the docs-api `/internal/**` route + the spark-inference-gateway `/v1/embeddings` endpoint. | Real JPA + pgvector adapter against a real Postgres; real Redisson against a real Redis; real Kafka consumer wiring. HTTP collaborators are stubbed because docs-api and the inference gateway are external to M3's repo. |
| **`rag-ingestion-api` end-to-end tests** | Full `@SpringBootTest` with all Testcontainers above + WireMock + Spring Kafka's `EmbeddedKafkaBroker` fallback for fast iteration | Publishes an envelope JSON to the source topic via a test producer, drains the listener, asserts pgvector rows + asserts `rag.document.ingested` is emitted. End-to-end for the happy path; per-event-type smoke tests; the §5 race-condition invariant. |

**Why WireMock and not Spring Cloud Contract:**
- Spring Cloud Contract requires a published contract artifact from the
  producer (docs-api). M2 ships an OpenAPI spec but no Spring Cloud Contract
  contracts. Authoring contracts on the M3 side would mean writing them
  from scratch against an already-stable API — WireMock stubs (with response
  bodies copied from docs-api's OpenAPI examples) gives the same coverage
  with less moving-target ceremony.
- The docs-api `/internal/**` route surface is small (two `GET`s) and stable
  per ADR-12 §2 — a WireMock stub file pinned in the M3 test resources is
  proportionate.
- The spark-inference-gateway is an external project not in this repo;
  Spring Cloud Contract isn't even reachable as a producer.

**WireMock stub locations:**
- `rag-ingestion-infra/src/test/resources/wiremock/docs-api/body-fetch.json`
- `rag-ingestion-infra/src/test/resources/wiremock/spark-inference-gateway/embedding-32-chunks.json`

**Contract drift watch:** when docs-api's `/internal/docs/public/{id}/body`
response shape changes (e.g., M2.1 adds an `etag` field), the M2 PR that
changes it must also update the WireMock stub in M3's test resources.
This is the same cross-PR coupling the M2 PR already has for the OpenAPI
spec; one more file to touch.

**Concurrency tests** (the §5 race invariant) use Awaitility + Testcontainers
Kafka + the embedded-broker producer to publish two events at known offsets,
then poll the DB until both processings settle, then assert the final state.

## Additional decisions (not in PRD's question list but architect-owned)

### A. Port assignment — 18083 (actuator only, host-not-exposed)

**Decision:** `rag-ingestion-api` binds **port 18083** on
`localhost`/compose-internal only. Already reserved by ADR-01 v2's port
table for M3. Used exclusively by `/actuator/**` (health for
`docker-compose healthcheck`, prometheus scrape from M5). No host port
mapping in `infra/docker-compose.yml` — the BC has no public HTTP surface
(ADR-08's "Backend services must not be host-exposed" rule).

The Spring Boot application **does** listen on 18083 even though it serves
no controllers; the actuator endpoints need a port. `server.port: 18083`
in `application.yml`.

### B. Compose service container name — `rag-ingestion-api`

**Decision:** the compose service is named **`rag-ingestion-api`** (matches
the runnable module name from ADR-01 v2, mirrors the M1 `identity-api`
and M2 `docs-api` convention). Some other infra containers use the
`-playground` suffix (`postgres-playground`, `kafka-playground`,
`redis-playground`, `opensearch-playground`) because they are
shared-infrastructure services, not BC-owned services. BC services keep
their module name as the container name.

**Compose service block specification** (for infra-implementer to
transcribe verbatim into `infra/docker-compose.yml` in Stage 3):

```yaml
  # --- M3: rag-ingestion-api (RAG-Ingestion BC quadruplet runnable per ADR-01 v2 + ADR-13) ---
  rag-ingestion-api:
    build:
      context: ../backend
      dockerfile: rag-ingestion/rag-ingestion-api/Dockerfile
    container_name: rag-ingestion-api
    depends_on:
      postgres-playground:
        condition: service_healthy
      kafka-playground:
        condition: service_healthy
      redis-playground:
        condition: service_healthy
      docs-api:
        condition: service_healthy   # body-fetch dependency per ADR-08 Exception 1
    extra_hosts:
      - "host.docker.internal:host-gateway"   # per ADR-04 — reach spark-inference-gateway
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-default}
      POSTGRES_HOST: ${POSTGRES_HOST:-postgres-playground}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:-playground}
      POSTGRES_USER: ${POSTGRES_USER:-playground}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-playground}
      KAFKA_BOOTSTRAP_SERVERS: ${KAFKA_BOOTSTRAP_SERVERS:-kafka-playground:9092}
      REDIS_HOST: ${REDIS_HOST:-redis-playground}
      REDIS_PORT: ${REDIS_PORT:-6379}
      DOCS_API_BASE_URL: ${DOCS_API_BASE_URL:-http://docs-api:18082}
      SPRING_AI_OPENAI_BASE_URL: ${SPRING_AI_OPENAI_BASE_URL:-http://host.docker.internal:10080}
      SPRING_AI_OPENAI_API_KEY: ${SPRING_AI_OPENAI_API_KEY:-dummy-not-used}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:18083/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
    # NO `ports:` block — rag-ingestion-api is compose-internal only (ADR-08).
```

### C. Library versions

Inherited from M1 (ADR-10) + M2 (ADR-12) where applicable; M3-specific
additions listed:

| Coordinate | Version source / pin | Why |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-web` | Spring Boot 3.3.x BOM | Actuator endpoint exposure (no controllers, but actuator uses servlet stack) |
| `org.springframework.boot:spring-boot-starter-data-jpa` | Spring Boot 3.3.x BOM | `rag.document_chunks` persistence |
| `org.postgresql:postgresql` | Spring Boot 3.3.x BOM | JDBC driver |
| `org.flywaydb:flyway-core` | Spring Boot 3.3.x BOM | Per-service migrations (ADR-05) |
| `com.pgvector:pgvector` | **0.1.6** | pgvector Hibernate dialect + `Vector` type binding |
| `org.springframework.kafka:spring-kafka` | Spring Boot 3.3.x BOM | Kafka consumer + DLQ error handler |
| `org.springframework.modulith:spring-modulith-events-jpa` | Spring Modulith **1.2.x** (same line as M1/M2 per ADR-10 §8 / ADR-12 §1) | Outbox for `rag.document.ingested` |
| `org.springframework.modulith:spring-modulith-events-kafka` | same line | Kafka bridge |
| `org.redisson:redisson-spring-boot-starter` | **3.34.x** (3.x line aligned with Spring Boot 3.3) | Distributed lock per ADR-08 Exception 2; same coordinate ADR-08 sketched |
| `org.springframework.ai:spring-ai-openai-spring-boot-starter` | Spring AI **1.0.0** GA (per ADR-04) | BGE-M3 embeddings via the OpenAI-compatible vLLM endpoint |
| `com.knuddels:jtokkit` | **1.1.x** | Tokenizer for chunk sizing (§1). Zero-dep lightweight tokenizer. |
| `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot 3.3.x BOM | `/actuator/health` + `/actuator/prometheus` |
| **NOT used:** `org.opensearch.client:opensearch-java` | n/a | M3 does not write to OpenSearch (that's M2's projector). Confirmed for clarity per the brief. |
| **NOT used:** `org.springframework.boot:spring-boot-starter-security` | n/a | No HTTP surface to secure |

Spring Cloud Gateway, Spring Security, OAuth2 client — all **absent** from
the M3 module: rag-ingestion has no gateway ingress, no OAuth role.

### D. Spring Modulith outbox — yes, for `rag.document.ingested`

**Decision:** M3 publishes `rag.document.ingested` (§3) via Spring Modulith
Events JPA + Spring Modulith Events Kafka — same wiring as M1 (ADR-10 §8)
and M2 (ADR-12 §1). The outbox `event_publication` table is provisioned in
the `rag` schema by Modulith's
`spring.modulith.events.jdbc.schema-initialization.enabled=true`.

`application.yml` snippet (rag-ingestion-api):

```yaml
spring:
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      externalization:
        enabled: true
      kafka:
        enabled: true
```

The publishing call lives in `rag-ingestion-app`
(`IngestDocumentUseCase`), via Spring's `ApplicationEventPublisher`,
inside the same `@Transactional` boundary as the chunk upsert. The Kafka
bridge config lives in `rag-ingestion-infra`. Mirrors M2 exactly.

### E. Kafka topic registry — additions for M3

Add four rows to ADR-03's topic registry (see the ADR-03 amendment block
at the bottom). Summary here:

| Topic | Producer | Consumer(s) | Partitions | Retention | Key |
|---|---|---|---|---|---|
| `rag.document.ingested` | rag-ingestion | (future: M4, M5) | **3** | 7 days | `documentId` |
| `docs.document.uploaded.dlq` | rag-ingestion (DLQ recoverer) | (operator triage) | **3** | 14 days | `documentId` |
| `docs.document.visibility-changed.dlq` | rag-ingestion | (operator triage) | **3** | 14 days | `documentId` |
| `docs.document.deleted.dlq` | rag-ingestion | (operator triage) | **3** | 14 days | `documentId` |

The DLQs are technically produced by rag-ingestion (the error handler
re-publishes the failed record), so they are "owned" by rag-ingestion
for routing purposes even though they carry docs-BC payloads. Operationally
this is fine — the DLQ is for failure triage, not a long-lived data product.

### F. pgvector schema — `rag.document_chunks` DDL

**Schema name:** `rag` (per ADR-05). pgvector extension installed in this
schema (`CREATE EXTENSION IF NOT EXISTS vector SCHEMA rag;`).

**Table DDL** (the Flyway V1 migration sketch):

```sql
-- backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/V202605180001__create_document_chunks.sql

CREATE EXTENSION IF NOT EXISTS vector SCHEMA rag;

CREATE TABLE rag.document_chunks (
  document_id    UUID         NOT NULL,
  chunk_index    INTEGER      NOT NULL,
  user_id        UUID         NOT NULL,
  visibility     TEXT         NOT NULL CHECK (visibility IN ('public', 'private')),
  embedding      vector(1024) NOT NULL,
  text           TEXT         NOT NULL,
  body_checksum  TEXT         NOT NULL,   -- SHA-256 hex
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT rag_document_chunks_pkey
    PRIMARY KEY (document_id, chunk_index)
);

-- supports the idempotency SELECT + the visibility-change UPDATE + the delete cascade
CREATE INDEX rag_document_chunks_document_id_idx
  ON rag.document_chunks (document_id);

-- supports M4's retrieval scoping (anonymous: visibility='public')
CREATE INDEX rag_document_chunks_visibility_idx
  ON rag.document_chunks (visibility);

-- supports M4's retrieval scoping (authenticated: user_id + visibility)
CREATE INDEX rag_document_chunks_user_id_visibility_idx
  ON rag.document_chunks (user_id, visibility);

-- vector similarity index — HNSW + cosine ops, parameters per §9
CREATE INDEX rag_document_chunks_embedding_hnsw_idx
  ON rag.document_chunks
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE rag.document_chunks IS
  'Per-chunk embeddings + metadata. M3 writes; M4 reads. (user_id, visibility) carry from docs.documents at ingestion + visibility-change time; never recomputed from docs schema.';
```

**Confirms PRD §"Bounded Context / Core entity"** columns + adds
`body_checksum` and `updated_at` (the latter touched on `visibility-changed`
to surface stale rows in diagnostics).

**Why three secondary indexes** in addition to the PK:
- `(document_id)` — every event handler starts with a `WHERE document_id = ?`
  scan; the PK's leading column already covers this but a dedicated index
  helps when the PK is bloated by HNSW pages. (Postgres makes this
  no-op if the planner prefers the PK; cheap insurance.)
- `(visibility)` and `(user_id, visibility)` — M4 retrieval's primary
  predicates. These are **prefilter** indexes that the HNSW search uses
  via pgvector's filter-aware extensions. Without them, M4 falls back to
  filter-after-vector-search which over-fetches.

### G. Amendments to transverse ADRs

Three amendments. Inline notes appended to each affected ADR's file so
readers see the M3 change in context.

#### G.1. Amendment to ADR-03 (Kafka conventions)

> Amendment (2026-05-18, ADR-13): the **topic registry** is extended with
> the four M3 topics — one published business event and three DLQs.
>
> | Topic | Producer | Consumer(s) | Partitions | Retention | Key |
> |---|---|---|---|---|---|
> | `rag.document.ingested` | rag-ingestion | future (M4, M5) | 3 | 7 days | `documentId` |
> | `docs.document.uploaded.dlq` | rag-ingestion (DLQ recoverer) | operator triage | 3 | 14 days | `documentId` |
> | `docs.document.visibility-changed.dlq` | rag-ingestion | operator triage | 3 | 14 days | `documentId` |
> | `docs.document.deleted.dlq` | rag-ingestion | operator triage | 3 | 14 days | `documentId` |
>
> DLQ retention doubles the business-event retention (per ADR-03's
> "Retention: 7 days for business events, 1 day for DLQ" — the "1 day"
> default is **superseded to 14 days for DLQs** in ADR-13 because operator
> triage cadence is not daily on a personal-scale system; 14 days gives a
> safe inspection window). See ADR-13 §8 for the rationale.
>
> The `rag.document.ingested` envelope's `payload` shape (per ADR-13 §3):
>
> ```json
> {
>   "documentId": "...",
>   "userId": "...",
>   "visibility": "public",
>   "chunkCount": 12,
>   "bodyChecksum": "<sha-256>",
>   "embeddedAt": "2026-05-18T00:00:00Z"
> }
> ```
>
> See ADR-13 §3 + §8 for the full specification.

#### G.2. Amendment to ADR-05 (Data Store)

> Amendment (2026-05-18, ADR-13): ADR-05's "Default index: HNSW
> (`m=16, ef_construction=64`) on cosine distance" is **confirmed verbatim**
> for the M3 `rag.document_chunks` table, plus a runtime hint:
> `SET LOCAL hnsw.ef_search = 40;` at M4's retrieval query time (M4-owned
> but documented here as part of the M3-enabled contract).
>
> The `rag.document_chunks` schema adds `body_checksum TEXT NOT NULL`
> (SHA-256 hex carried per row for idempotency, per ADR-13 §12) and three
> secondary indexes — `(document_id)`, `(visibility)`, `(user_id, visibility)` —
> that support M3's event handlers + M4's retrieval predicates. The HNSW
> index uses pgvector's `vector_cosine_ops` op class. Full DDL in ADR-13 §F.
>
> Corpus-size assumption made explicit: HNSW is the M3 P0 default for up
> to ~50k chunks. IVFFlat remains the fallback per ADR-05's original text
> if the corpus crosses ~100k chunks and build cost becomes a problem.
>
> See ADR-13 §9 + §F for the full specification.

#### G.3. Amendment to ADR-08 (Inter-Service Communication) — no new exception

> Amendment (2026-05-18, ADR-13): ADR-13 **does not** add a new BC-to-BC
> HTTP exception. The two existing exceptions are reused verbatim:
>
> - **Exception 1** (`rag-ingestion` → `docs-api` `/internal/**`) —
>   confirmed for M3 P0. Reliability discipline: 5 s timeout, 3 attempts,
>   exponential backoff (200 ms, 400 ms, 800 ms base, jitter 0.5),
>   permanent failure → `docs.document.uploaded.dlq` (per ADR-13 §8).
> - **Exception 2** (`rag-ingestion` → `redis-playground` for Redisson
>   locks) — confirmed for M3 P0. Lock TTL cap 5 minutes; reused as the
>   serialization primitive for the race condition resolved in ADR-13 §5.
>
> M3's published event (`rag.document.ingested`, per ADR-13 §3) goes
> through Kafka — the default sanctioned cross-BC channel. No exception
> needed; the topic is added to ADR-03's registry (per amendment §G.1
> above).
>
> **M3 P0 itself adds no new HTTP routes** to either docs-api or
> identity-api beyond what ADR-12 already pinned. (M3.1's backfill scan
> route `GET /internal/docs/scan?since=…` on docs-api is M3.1's amendment,
> not M3 P0's.)
>
> See ADR-13 §A-G for the full M3 specification; see ADR-08's existing
> "Superseding — M2 amendments" section for the two reused exceptions.

#### G.4. Amendment to ADR-09 (Public Route Policy) — none needed

> Amendment (2026-05-18, ADR-13): ADR-13 makes **no changes** to ADR-09's
> allowlist. M3 has no public HTTP surface (PRD §"UX surfaces"); the
> gateway does not route to `rag-ingestion-api`. ADR-09's "Public
> retrieval scoping" invariant ("public RAG chat retrieves only against
> `visibility='public'` chunks") is **enabled** by M3 via the
> `(user_id, visibility)` chunk-row contract (ADR-13 §F + §3 + §5) but is
> **enforced** at M4's retrieval-query layer, not at M3's write side.
> The forward retrieval contract per M2 spec §8 (amended 2026-05-18) is
> the canonical statement of M4's predicate; M3 makes it expressible as a
> single SQL `WHERE` clause.

(No inline edit to ADR-09 needed — this note is informational and lives
only in ADR-13.)

#### G.5. Module count update in ADR-00 / ADR-01

ADR-01 v2's module count (22 production modules) already accounts for the
M3 quadruplet (4 modules: api, app, domain, infra). No bump needed.

ADR-00's index gains one row for ADR-13. The "Module count" line in ADR-00
"(post-ADR-01 v2, ADR-02 v2)" already reflects the 5-BC × 4-module = 20
plus gateway + shared-kernel = 22 figure; M3 lands inside that envelope.
**No further increment.**

## Open questions deferred beyond M3

These are explicitly out of scope for M3 P0 and noted here so the next
milestone's architect doesn't re-litigate them:

- **Backfill mechanism (M3.1)** — Spring profile + `CommandLineRunner` per
  §7. M3.1 will pin the `GET /internal/docs/scan` route on docs-api,
  the CLI's invocation surface, and the rate-limiting strategy (so a
  backfill burst doesn't saturate spark-inference-gateway).
- **DLQ replay tooling (M3.1)** — operator CLI for draining DLQs back to
  source topics. M3 P0 ships with manual Kafka UI replay only.
- **Re-embedding job (M3.1)** — full-corpus re-embed when chunking
  parameters or the embedding model change. M3 P0 has no automatic
  trigger.
- **Path metadata on chunks (M3.1)** — when M2.1 introduces
  `docs.document.moved`, M3 will pick up `path` as a chunk column and
  consume the new event. M3 P0 does not carry `path` because M4's
  retrieval doesn't filter on it.
- **HNSW reindex schedule** — at >100k chunks, recall degradation
  warrants a periodic `REINDEX INDEX CONCURRENTLY`. Out of scope for M3
  P0 corpus sizes.
- **Multi-instance rag-ingestion** — single-instance assumption is baked
  into the consumer-group config and the Redisson lock's TTL cap.
  Multi-instance fan-out is a P2+ concern (and pgvector's HNSW write
  performance is the more likely scaling bottleneck before the consumer
  side becomes one).
- **Multi-modal embedding** — text-only via BGE-M3 dense head for M3 P0.
  Image / PDF / multi-vector heads are P2.

## Diagrams

### M3 ingestion data flow (Kafka + HTTP + pgvector)

```
M2 docs-api                                       M3 rag-ingestion-api (18083)
   │                                                       │
   │  outbox commit (Modulith)                             │  @KafkaListener("docs.document.uploaded")
   │                                                       │
   └─── docs.document.uploaded ──────────────────────────▶ │
        (envelope {documentId, userId, visibility,         │
         title, path, bodyChecksum})                       ▼
                                                  ┌────────────────────────────┐
                                                  │  IngestDocumentUseCase     │
                                                  │  (rag-ingestion-app)       │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  acquire Redisson lock     │
                                                  │  rag-ingestion:lock:       │
                                                  │     document:{id} (TTL 5m) │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  SELECT body_checksum      │
                                                  │  WHERE document_id = ?     │
                                                  │  LIMIT 1                   │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                  same checksum?  ├── yes ──▶ emit rag.document.ingested,
                                                                 │            release lock, ack
                                                                 │ no
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  GET docs-api/internal/    │  (ADR-08 Exception 1
                                                  │  docs/public/{id}/body     │   + ADR-13 §2)
                                                  │  5s timeout, 3 retries     │
                                                  │  WebClient buffered read   │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  MarkdownChunker           │  (ADR-13 §1)
                                                  │  (rag-ingestion-domain)    │
                                                  │  800-token windows,        │
                                                  │  120-token overlap         │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  POST /v1/embeddings       │  (ADR-04 + ADR-13 §10)
                                                  │  to host.docker.internal:  │
                                                  │  10080, model=BGE-M3       │
                                                  │  batch 32, 15s timeout,    │
                                                  │  3 retries (ADR-13 §2)     │
                                                  └──────────────┬─────────────┘
                                                                 │ 1024-dim vectors
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  TX: DELETE then bulk      │
                                                  │  INSERT rag.document_chunks│
                                                  │  + publish DocumentIngested│
                                                  │     via Modulith outbox    │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                                 ▼
                                                  ┌────────────────────────────┐
                                                  │  release Redisson lock,    │
                                                  │  ack Kafka                 │
                                                  └──────────────┬─────────────┘
                                                                 │
                                                  ┌──────────────▼─────────────┐
                                                  │ Modulith → Kafka bridge    │
                                                  │ publishes                  │
                                                  │ rag.document.ingested      │
                                                  └────────────────────────────┘

  Failure paths (any retry-exhausted error from body fetch or embedding):
     └── DefaultErrorHandler.recover() → publish to docs.document.uploaded.dlq,
         WARN log, increment playground.rag_ingestion.dlq.routed counter,
         next event proceeds (consumer group not stalled).
```

### M3 visibility re-tag (no body fetch, no embedding)

```
M2 docs-api                                       M3 rag-ingestion-api
   │                                                       │
   └─── docs.document.visibility-changed ────────────────▶ │  @KafkaListener
        (envelope {documentId, userId,                     │
         oldVisibility, newVisibility, publishedAt})       ▼
                                                  ┌────────────────────────────┐
                                                  │  RetagVisibilityUseCase    │
                                                  │  acquire Redisson lock     │
                                                  │  UPDATE rag.document_chunks│
                                                  │    SET visibility=?        │
                                                  │    WHERE document_id = ?   │
                                                  │  release lock, ack         │
                                                  └────────────────────────────┘

  Invariant: NO body fetch, NO embedding call. Integration test asserts
  both counts == 0 across the event lifecycle.
```

## Consequences

- Positive: the M3 BC ships with the same outbox pattern M1 and M2 use —
  backend-implementer has one mental model across BCs, code-reviewer has
  one shape to check. The "M3 publishes events" path is identical to
  identity's `identity.user.registered` and docs's `docs.document.uploaded`.
- Positive: the ingestion-complete signal is a first-class Kafka event,
  not a polled flag — M4 (the eventual consumer) can subscribe with the
  same envelope machinery every other BC uses.
- Positive: chunk-row metadata `(user_id, visibility, body_checksum)` is
  denormalized on every row. Idempotency check and M4 retrieval are both
  single-SELECT operations against well-indexed columns; no joins, no
  cross-schema reads.
- Positive: HNSW + cosine ops + per-predicate prefilter indexes give M4
  a sub-millisecond retrieval primitive at M3 P0 corpus sizes. The
  IVFFlat fallback path is explicitly documented for the future.
- Positive: WireMock + Testcontainers gives the implementer an integration
  test stack that doesn't require a running docs-api or spark-inference-gateway,
  which is critical for CI on a personal-scale project where neither
  external dependency runs in CI today.
- Positive: the race condition (visibility-changed-before-uploaded) is
  resolved via the existing Redisson lock — no new primitive, no new
  Kafka semantics, encodable as a single-document invariant test.
- Negative: the buffered body read assumes the 1 MB cap holds. If M2.1
  ever lifts the cap to 5 MB (anticipated per ADR-12 §4), the WebClient
  `maxInMemorySize` setting (§11) must rise in lockstep — an inter-ADR
  coupling worth documenting (this consequence does that).
- Negative: the M3 quadruplet's `-api` module is a runnable Spring Boot
  app whose only HTTP surface is `/actuator/**`. The 22-modules count
  feels heavier for a UI-less BC than a UI-having one, but the symmetry
  with M1/M2/M4/M5 keeps the convention plugins (per ADR-01) one shape.
- Negative: three DLQ topics (one per source) instead of a unified
  `rag-ingestion.dlq` increases topic count by 3. Acceptable; ADR-03's
  convention prescribes the per-topic-DLQ pattern explicitly and the
  cost is negligible at single-broker dev scale.
- Negative: the `rag.document.ingested` event publication adds one more
  outbox row per ingestion success. Outbox table growth is M2-already-paid
  operational cost; M3 adds linear growth in the same order of magnitude
  as M2's three docs events. Acceptable for personal scale.
- Negative: WireMock contract stubs drift if M2 mutates the
  `/internal/docs/public/{id}/body` response without updating the M3
  test resources. Mitigated by ADR-12 §2's "the next sanctioned BC-to-BC
  HTTP path requires another amendment row" discipline — any change
  to the route shape is an ADR-level event, hard to forget.

## Related

- ADR-01 v2 — quadruplet module layout, port 18083 for `rag-ingestion-api`
- ADR-02 — DDD layering rules the rag-ingestion quadruplet inherits
- ADR-03 (amended below by this ADR) — Kafka envelope, topic naming, DLQ
  convention + M3 topic registry rows
- ADR-04 — Spring AI 1.0 GA, BGE-M3 1024-dim, spark-inference-gateway
  wiring (M3 confirms; no amendment)
- ADR-05 (amended below by this ADR) — Postgres + pgvector HNSW index,
  `rag.document_chunks` DDL with `body_checksum`
- ADR-07 — gateway routing (M3 not in route table; no amendment)
- ADR-08 (amended below by this ADR — informational note) — inter-service
  comms; M3 reuses Exceptions 1 + 2 verbatim, adds no new exception
- ADR-09 — public route policy (M3 makes no changes; `(user_id,
  visibility)` chunk invariant enables M4's single-WHERE retrieval per
  M2 spec §8 amendment)
- ADR-10 §8 — Spring Modulith Events outbox (inherited by M3 for
  `rag.document.ingested`)
- ADR-11 — shared exception hierarchy (M3 has no HTTP surface but
  internal exceptions still throw `AbstractException` subclasses for
  structured-log correlation)
- ADR-12 §1 + §2 — outbox inheritance + docs body-fetch route shape
- `docs/prd/M3-rag-ingestion.md` — M3 PRD (the 13 open questions this
  ADR resolves)
- `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §5 + §8 — M2
  event payloads + canonical M4 retrieval contract
