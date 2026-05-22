# ADR-17: M7 RAG-Chat Tool-Calling Infrastructure — Implementation Decisions

## Status
Accepted (2026-05-22)

## Context

The M7 PRD (`docs/prd/M7-rag-chat-tool-calling.md`, landed on this branch
as commit `f540789`) pins the user-facing scope of the rag-chat
tool-calling extension: a `ToolCatalog` constants surface in
`rag-chat-domain`, a `ToolDispatcher` adapter in `rag-chat-infra`, a
multi-turn function-calling branch in `StreamChatTurnUseCase`, three new
SSE events (`tool_call` / `tool_result` / `tool_error`), and a 7-value
`ToolErrorCode` enum. It deliberately defers eight implementation-shape
questions to this per-milestone ADR (PRD §"Open questions for ADR-17"),
plus the exact wording of the ADR-08 Exception 4 row and the ADR-14
§5.2 SSE grammar amendment.

ADR-14 did the same job for M4 (Spring AI streaming, Redisson rate
limiter, chat schema, cross-schema SELECT exception, Resilience4j
`spark-gateway` breaker). ADR-16 did it for M6 (Apache PDFBox, Vision
OCR fallback, mime_type column, 6 new error codes); ADR-12's M6.1
amendment dissolved rag-ingestion into docs and retired ADR-08
Exception 1. M7 follows the same shape — pin libraries with concrete
coordinates, pin the function-calling call shape against Spring AI
1.0.0 GA, pin the SSE wire-shape representation, capture each deferred
PRD question with a definite answer.

The M7 BC change is, in shape, **the smallest BC modification since
M2's S3 — a pure additive extension of the existing rag-chat BC**:

- **No new BC.** rag-chat-api remains the only entry point; the four
  rag-chat modules each gain a small additive surface (`ToolCatalog`
  and `ToolDescriptor`/`ToolResult`/`ToolError`/`ToolErrorCode` in
  `-domain`; `ToolDispatcherPort` in `-app`; `WebClientToolDispatcher`
  + per-tool Resilience4j breaker registry in `-infra`; multi-turn
  function-calling branch in `StreamChatTurnUseCase` in `-app`).
- **No new Kafka surface.** rag-chat continues to be a non-producer /
  non-consumer per ADR-14 §1.5. Tool calls are synchronous HTTP — the
  ADR-08 amendment §A08 below sanctions Exception 4 for exactly this
  shape.
- **No new Postgres schema.** `chat.sessions` / `chat.messages` /
  `chat.message_citations` are unchanged. M7 P0 does not persist tool
  call traces (in-memory only — same shape as M4's abort path per
  ADR-14 §13).
- **No new module or port.** Module count stays at 22, port allocation
  stays as ADR-00 v2 (post-M6.1) pins it.
- **No new external dependency.** Resilience4j 2.2.0 is reused from
  ADR-14 §4; Spring AI 1.0.0 GA is reused from ADR-04. The only new
  WebClient instance machinery is per-descriptor wiring; the WebClient
  library itself is already on the classpath via
  `spring-boot-starter-webflux` (ADR-14 §B).
- **No `pgvector` change.** M3-derived chunk schema and HNSW index are
  unaffected.

What M7 does change:

- **`ToolCatalog` constant class** in `rag-chat-domain`: zero entries
  at M7 ship. M8 lands the first descriptor.
- **One new port and one new adapter**: `ToolDispatcherPort`
  (`rag-chat-app`) + `WebClientToolDispatcher` (`rag-chat-infra`).
- **Multi-turn branch in `StreamChatTurnUseCase`**: detects the LLM's
  tool-call response, dispatches via the port, feeds the result back
  on a new turn, repeats up to depth cap (default 5).
- **Three new SSE events** (`tool_call` / `tool_result` /
  `tool_error`) emitted as **standalone `event:` names**, not as
  `phase` step discriminators (see §3 + §A14 for the rationale and
  the ADR-14 amendment that retires the placeholder phase steps).
- **7-value `ToolErrorCode` enum** in `rag-chat-domain` (matches the
  PRD §"Wire-shape contracts" + Story 5 enumeration).
- **Per-tool Resilience4j circuit breaker** registry — one breaker per
  descriptor, named `tool-<descriptor-name>`. Thresholds mirror the
  `spark-gateway` breaker (failure-rate 50% / 60 s window / minimum 10
  calls / OPEN duration 30 s / 1 half-open probe) verbatim — see §5.
- **One new env var** — `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH` (default
  `5`) — pinned in §6.

Two transverse ADR amendments land in the same PR set:

- **ADR-08 §A08** — sanctions Exception 4 (`rag-chat-api` →
  tool-implementer BCs HTTP); the M6.1 retirement of Exception 1 set
  the precedent that this section can carry both retirements and
  additions.
- **ADR-14 §A14** — reconciles §5.2 with M7's SSE wire-shape
  (standalone `event:` names) and retires the `step: tool_call` /
  `step: tool_result` placeholder values from the original spec
  §5.2 grammar table.

ADR-00 gains a row for ADR-17; ADR-08 and ADR-14 gain amendment
markers. Module count is unchanged.

## Decision

### 1. Spring AI `ChatClient.tools(...)` — pinned call shape against Spring AI 1.0.0 GA

**Decision: use Spring AI 1.0.0 GA's native function-calling API as
exposed by `ChatClient`. The tool registration shape is
`chatClient.prompt(prompt).tools(callbacks).stream().content()` where
`callbacks` is a `ToolCallback[]` built from the rag-chat
`ToolDescriptor` set. Spring AI is pinned to the same 1.0.0 GA line
ADR-04 and ADR-14 already use (no version bump).**

Closes PRD open question 1 ("Spring AI 1.0 GA `ChatClient.tools(...)`
API stability") and spec §5's first open question.

| Concern | Pin |
|---|---|
| Spring AI BOM | **`org.springframework.ai:spring-ai-bom:1.0.0`** — same coordinate as ADR-04 / ADR-14 §B. Confirmed against `backend/gradle/libs.versions.toml` (`spring-ai = "1.0.0"`). No version bump for M7. |
| Starter | `spring-ai-openai-spring-boot-starter` (same as ADR-14 §B) — the function-calling integration is part of the OpenAI starter for any OpenAI-compatible endpoint, vLLM included. |
| Registration shape | **Per-call** via `chatClient.prompt(prompt).tools(toolCallbacks)`, not per-builder `defaultTools(...)`. Per-call is preferred because the `ToolCatalog` is loaded once at BC bootstrap but the streaming pipeline reconstructs the prompt + tool array on every user turn — keeping it per-call avoids a stale-catalog hazard if a future M7.1 introduces dynamic registration. |
| Streaming vs `.call()` | **`.stream().content()`** for chat turns (matches M4's `SparkInferenceChatAdapter.stream(...)` shape in `rag-chat-infra`). The function-calling round-trip is internal to Spring AI: the framework detects the model's `tool_calls` field on the streaming response, invokes the registered `ToolCallback`, feeds the result back, and continues the same `Flux<ChatResponse>`. M7 does **not** drive the multi-turn loop manually unless Spring AI's auto-handling breaks our SSE event emission invariant (see §3 for the ordering invariant + §1.1 for the fallback). |
| `ToolCallback` builder | The `ToolCatalog` exposes immutable descriptors (no Spring annotations — `-domain` invariant). The `-infra` adapter (`ToolCallbackFactory`) maps each descriptor to a Spring AI `ToolCallback` via `ToolCallbacks.builder(...)` or equivalent 1.0.0 GA factory — exact 1.0.0 factory method is `MethodToolCallback.builder()` for annotated cases and `FunctionToolCallback.builder()` for `BiFunction`-shaped callbacks; we use `FunctionToolCallback.builder(name, function).description(desc).inputSchema(jsonSchema).inputType(JsonNode.class).build()` so the descriptor's `parameterSchema` (JSON-Schema string in `-domain`) wires directly without reflection on a generated Java class. |
| Tool name collisions | Spring AI rejects duplicate `name` on the `ToolCallback[]`. The `ToolCatalog`'s `descriptors()` method enforces uniqueness at startup (fail-fast on duplicate names — `@PostConstruct` validation in the `-app` use-case wiring). |

#### 1.1. Fallback if Spring AI's auto-multiturn doesn't honor our SSE emission invariant

If the implementer discovers during Stage 3 verification that Spring AI
1.0.0 GA emits all tool-call → tool-result → continuation tokens as a
single opaque `Flux<ChatResponse>` (i.e., we **cannot** intercept the
mid-stream `tool_calls` boundary to emit our `tool_call` / `tool_result`
SSE events at the moments §3 requires), the fallback is:

- Drive the multi-turn loop manually in `StreamChatTurnUseCase`. The
  use-case takes ownership of the loop: call
  `chatClient.prompt(...).tools([]).stream().content()` (no tool
  callbacks registered) and parse the model's tool-call markers from
  the streamed JSON response manually; emit the `tool_call` event;
  invoke `ToolDispatcherPort.invoke(...)`; emit the `tool_result`
  event; continue the loop with a new `chatClient.prompt(...)` call
  whose prompt includes the prior tool-result as a synthetic
  assistant/tool message.
- The fallback shape is documented but the **preferred path** is
  Spring AI's native function-calling because it co-evolves with the
  underlying OpenAI tool-call schema (OpenAI / vLLM may change the
  wire shape; Spring AI absorbs that). The implementer evaluates which
  path Spring AI 1.0.0 GA supports cleanly during Stage 3 verification.

If the fallback is required, this ADR is amended (a new §1.1.x
sub-decision adds the manual-loop wiring); the ADR's high-level
contract (per-tool breaker, depth cap, SSE event grammar) is unaffected.

#### 1.2. Multi-turn depth — counted at `StreamChatTurnUseCase`, not at Spring AI

Spring AI 1.0.0 GA does not expose a per-`prompt(...)` depth cap. The
depth count lives in our `StreamChatTurnUseCase`: a
`java.util.concurrent.atomic.AtomicInteger` initialized to 0 at turn
start, incremented on every `tool_call` emission, gated against the
configured cap (§6). Cap exceeded → emit `tool_error` with
`code: "MAX_DEPTH"`, dispose the upstream `Flux`, terminate the user
turn. This works for both the native path (§1) and the fallback path
(§1.1) — Spring AI invokes the registered `ToolCallback`, which wraps
`ToolDispatcherPort.invoke(...)` and includes the depth check; if the
cap is exceeded, the callback throws a `MaxDepthExceededException`
that the use-case catches and translates into the terminal
`tool_error` event.

### 2. Spring AI tool-error semantics — no built-in retry; rely on Resilience4j

**Decision: Spring AI 1.0.0 GA's `ToolCallback` framework treats any
exception thrown from the callback as a tool execution failure and
surfaces it to the model as a `tool_result` carrying an error
message. It does **not** auto-retry. Our `WebClientToolDispatcher`
handles retry-free policy + per-tool circuit breaker + timeout
internally — no `Retry` from `resilience4j-retry` is added.**

Closes PRD open question 2 ("Spring AI tool error / retry semantics")
and spec §5's second open question.

| Failure mode | Behaviour | SSE event |
|---|---|---|
| Tool BC 4xx (request shape wrong — LLM hallucinated args that violate `parameterSchema`) | Dispatcher returns failure synchronously; **not** counted against the breaker per ADR-14 §4's "4xx is a code/data bug, not a gateway-health signal" invariant. The error is fed back to the LLM as a `tool_result` with shape `{ "error": "<message>" }` so the LLM has a chance to retry with corrected args within the same turn (subject to depth cap). | `tool_error` with `code: "UPSTREAM_4XX"` — emitted **once**, on each 4xx response. The LLM may issue another `tool_call` on the next turn (counts against depth cap). |
| Tool BC 5xx (tool internal failure) | Dispatcher returns failure; **counted** against the breaker. The error is fed back to the LLM as a `tool_result` carrying the failure so the LLM can either retry within depth (the LLM may decide that the tool is permanently broken and apologize, or it may try once more — the depth cap is the safety net). | `tool_error` with `code: "UPSTREAM_5XX"`. |
| WebClient timeout (descriptor's `timeout` exceeded) | Dispatcher aborts the in-flight HTTP request via Reactor cancellation (matches ADR-14 §14's vLLM-disconnect pattern — best-effort connection close). **Counted** against the breaker. | `tool_error` with `code: "TIMEOUT"`. |
| Breaker OPEN | Dispatcher returns failure **immediately** without an HTTP call — saves the LLM round-trip cost. The Spring AI callback throws a synthetic exception that the use-case maps to terminal `tool_error` (no further LLM turn) — the operator's cost-protection intent (PRD Story 6) overrides the "feed error to LLM" branch above. | `tool_error` with `code: "CIRCUIT_OPEN"`. Terminal — no further LLM round-trip. |
| Schema validation failure (LLM produced args that fail JSON-Schema check) | Spring AI 1.0.0 GA's function-calling layer validates args against the registered `inputSchema` before invoking the callback. Validation failure → no upstream HTTP call; the validation error is fed back to the LLM as a `tool_result` so the LLM retries within depth. | `tool_error` with `code: "SCHEMA_INVALID"`. |
| Dispatcher internal failure (e.g., JSON deserialization of tool response throws) | Treated as `INTERNAL` — fed back to LLM with generic apology so the LLM gracefully terminates the turn. | `tool_error` with `code: "INTERNAL"`. |
| Depth cap exceeded (Story 8) | The use-case-level guard fires before invoking the dispatcher — see §1.2. | `tool_error` with `code: "MAX_DEPTH"`. Terminal — no further LLM round-trip. |

The `ToolErrorCode` enum in `rag-chat-domain` is the 7-value set the
PRD pinned:

```java
package com.playground.ragchat.domain.tool;

public enum ToolErrorCode {
    TIMEOUT,
    CIRCUIT_OPEN,
    MAX_DEPTH,
    UPSTREAM_4XX,
    UPSTREAM_5XX,
    SCHEMA_INVALID,
    INTERNAL
}
```

**Considered alternative:** add `resilience4j-retry` 2.2.0 around the
WebClient call. Rejected — combining `Retry` with a circuit breaker
needs careful sequencing (`Retry` must wrap `CircuitBreaker`, not the
other way around, to avoid retries failing fast after the breaker
opens); the gain is marginal because tool BCs are local compose-network
peers (no transient network failures on the order of seconds that
retries would cover), and adding `Retry` doubles the failure-shape
surface area without a concrete win for personal-scale. If a future
operator observes transient 5xx that retries would smooth, this ADR is
amended.

### 3. SSE wire shape — standalone `event:` names, not `phase` step discriminators

**Decision: M7 emits `tool_call` / `tool_result` / `tool_error` as
**standalone SSE event names**, not as values of the existing `phase`
event's `step` field. The `ChatStreamEvent` sealed interface in
`shared-kernel/chat/ChatStreamEvent.java` gains three new record types
— `ToolCall`, `ToolResult`, `ToolError` — siblings of the existing
`Phase` / `Token` / `Done` / `Error`. The wire-level event name is the
lowercase record name (`tool_call` / `tool_result` / `tool_error`).**

Closes PRD open question 7 (Q-A) and reconciles the conflict the PRD
raised between ADR-14 §5.2 (which lists `step: "tool_call"` /
`step: "tool_result"` as `phase` event step discriminators) and the
M7 spec §5 (which writes standalone `event: tool_call` names).

**Why standalone event names:**

- **The shipped M4 frontend `chat.sse.ts` already supports standalone
  `event:` dispatch via a `switch (eventName)` block** (the FE source
  at `frontend/src/shared/api/chat.sse.ts` parses `event: foo` and
  switches; unknown event names return `null` and are silently
  dropped, so the frontend is forward-compatible with new event names
  without breaking the M4 grammar).
- **No production code consumes `phase` step values for `tool_call` /
  `tool_result`.** The M4 `ChatTurnService` (`rag-chat-app`) emits
  exactly one `Phase` event per turn — `step: "retrieval"` — and the
  M4 frontend's `chat.sse.ts` has a comment explicitly noting that
  `tool_call` is a forward-compat placeholder ("Unknown event types
  are dropped silently — a future server might add `usage` or
  `tool_call` events"). The spec §5.2 grammar table's `step:
  "tool_call"` / `step: "tool_result"` rows are placeholders that
  never shipped.
- **`EventSource`-style FE ergonomics.** A frontend that wants to
  render tool-call cards differently from progress phases benefits
  from a dedicated event name (matches the React reducer pattern
  `case 'tool_call': return ...`). Conflating tool calls with progress
  phases would require the FE to disambiguate the same `phase` event
  into two different rendering paths via the `step` field — an
  unnecessary indirection given the wire format can carry the
  distinction directly.
- **Amendment cost is bounded.** Because no M4 production code emits
  `step: "tool_call"`/`"tool_result"` phase events, the ADR-14 §5.2
  amendment is narrow: it retires the placeholder rows in the spec
  example block and replaces them with the new standalone event
  rows. See §A14 below for the exact wording.

**`ChatStreamEvent` extension** (in `shared-kernel`, lives in
`backend/shared-kernel/src/main/java/com/playground/shared/chat/ChatStreamEvent.java`):

```java
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Phase, ChatStreamEvent.Token,
                ChatStreamEvent.Done, ChatStreamEvent.Error,
                ChatStreamEvent.ToolCall, ChatStreamEvent.ToolResult,
                ChatStreamEvent.ToolError {

    // ... existing Phase / Token / Done / Error records unchanged ...

    /** LLM has decided to invoke a tool — emitted immediately before dispatch. */
    record ToolCall(String id, String name, Object args) implements ChatStreamEvent {}

    /** Tool BC returned successfully — emitted after dispatch completes. */
    record ToolResult(String id, String name, Object result) implements ChatStreamEvent {}

    /** Tool dispatch failed — emitted in place of ToolResult on failure. */
    record ToolError(String id, String name, ToolErrorCode code, String message)
            implements ChatStreamEvent {}
}
```

The `ChatStreamController.toSse(...)` mapper in `rag-chat-api` gains
three new branches mapping each record to an `event:` name + JSON
`data:` payload. SSE event-name convention (lowercase
snake_case-of-record-name) matches the existing `Phase` → `phase`,
`Token` → `token`, etc.

#### 3.1. SSE event ordering invariant (multi-turn)

Closes PRD open question 4 ("Multi-turn SSE event ordering guarantee")
and spec §5's fourth open question.

For a single user turn that triggers tool-calling, the SSE event
sequence is:

1. Exactly one `phase{step:"retrieval", data:{count:K}}` — retrieval
   ran (M4 invariant; emitted even if K=0).
2. Zero or more `token` events — initial LLM streaming (assistant
   reasoning or apology) if the LLM emits text before the tool call.
   In practice on Spring AI 1.0.0 GA's native function-calling path
   the LLM commonly emits zero pre-tool-call text; the implementer
   must support both shapes.
3. For each tool invocation (depth `i` from 1 to N where N ≤
   `MAX_DEPTH`):
   a. Exactly one `tool_call{id, name, args}` — emitted **before**
      the `WebClient` HTTP call is issued.
   b. Either:
      - Exactly one `tool_result{id, name, result}` — emitted **after**
        the HTTP response deserializes successfully, **before** the
        result is fed back to the LLM, OR
      - Exactly one `tool_error{id, name, code, message}` — emitted on
        any failure mode in §2's table.
   c. Zero or more `token` events as the LLM continues with the
      tool result in context.
4. **Exactly one terminal event**: `done` (assistant message persisted
   per M4 §10 cite-persistence policy) or `error` (chat-level fatal —
   the M4 §6.5 enum: `GATEWAY_5XX` / `RATE_LIMIT` / `RETRIEVAL_EMPTY` /
   `ABORTED` / `INTERNAL`). M4's `error` event remains chat-level
   fatal; **tool-level errors do not fall back to `error`** — they
   emit `tool_error` instead. The single exception: a `tool_error`
   with `code: "CIRCUIT_OPEN"` or `code: "MAX_DEPTH"` terminates the
   turn without a further `done` / `error` (the `tool_error` is itself
   terminal for those two codes; the partial assistant text accumulated
   so far is **not** persisted, matching M4 §13's abort-path rule).

**Pairing rule:** every `tool_call{id:X}` is followed by exactly one
`tool_result{id:X}` or `tool_error{id:X}`. Multiple `tool_call`s may
appear in a single turn (depth > 1); each carries its own `id` so the
frontend can pair calls with results unambiguously.

#### 3.2. `tool_call.id` correlation IDs

Closes PRD open question 8 (Q-B — "tool_call.id correlation IDs").

**Decision: include the correlation `id` in all three event payloads
(`tool_call.id`, `tool_result.id`, `tool_error.id`). The id is the
**`tool_call_id` value Spring AI surfaces from the underlying OpenAI
tool-call schema** when the native function-calling path is used (§1).
When the manual fallback (§1.1) is used, the id is generated server-side
by `ToolDispatcher` as `"call_" + ULID()`.**

**Why include the id:**

- Spring AI 1.0.0 GA already carries a tool-call id internally on its
  `ToolCallback` invocation context — passing it through is zero
  marginal cost.
- The frontend pairing rule (§3.1) requires it for multi-tool turns
  where the same `name` is invoked twice (e.g., `generate_massing`
  called twice with different `briefDocId` args — without an id, the
  FE can't tell which result belongs to which call).
- Forward compatibility with parallel tool calls (M7.1 scope): when
  Spring AI's parallel function-calling lands and we adopt it, the
  `id` is the only correlation mechanism that works without ordering
  guarantees.

**Format:** opaque string. The frontend MUST NOT parse the id; it is
strictly an opaque correlation token. Server-side it is either Spring
AI's pass-through (in which case the format follows OpenAI's
`call_<24-char-base62>` convention) or our generated ULID-prefixed
form (in which case the format is `call_<26-char-base32-ULID>`). Both
fit in 32 characters.

### 4. Tool result max size — 16 KiB hard cap, truncate-and-warn

**Decision: tool BC response bodies are capped at **16 KiB** of JSON
post-deserialization. Above the cap, the dispatcher truncates the
`result` payload and feeds a placeholder back to the LLM with a
warning marker; the truncated portion is **not** sent to the LLM
context, but the frontend SSE `tool_result` payload also carries the
full original up to 16 KiB.**

Closes PRD open question 5 ("Tool result max size") and spec §5's
fifth open question.

| Concern | Pin |
|---|---|
| Hard cap | **16 KiB** post-JSON-serialization byte size. Measured on the JSON string returned by the tool BC. |
| Why 16 KiB | Qwen3-32B's context window is 32 768 tokens. M4's existing budget allocates 24 576 tokens to history (ADR-14 §8). At ~4 chars/token, 16 KiB ≈ 4 K tokens — fits inside a single tool-result message without forcing history truncation in the same turn. Higher caps (e.g., 32 KiB) push tool results into the history-truncation zone, which silently drops prior turns; lower caps (e.g., 8 KiB) overfit to small JSON and break M8's `programJson` payload (which can reach ~10 KiB for a 30-room program). 16 KiB is the working trade. |
| Over-cap behaviour | The dispatcher truncates the JSON body byte-wise to 16 KiB and appends an error marker — actual Spring AI tool-result message contents become `{"truncated": true, "originalBytes": <n>, "excerpt": "<first 16 KiB - 64 bytes>"}` so the LLM knows the result was clipped and can apologize / re-call with narrower args. |
| SSE wire shape | The `tool_result.result` payload on SSE carries the **full** up-to-16-KiB result (no further FE-side truncation needed). |
| Configurability | `playground.rag-chat.tool.max-result-bytes` in `application.yml` with default `16384`. Env-var override: `PLAYGROUND_RAG_CHAT_TOOL_MAX_RESULT_BYTES`. |
| Error code on over-cap | This is **not** a tool error per se — the result was returned successfully but is too large. The dispatcher emits the truncated `tool_result` (not `tool_error`) so the frontend renders the result with a "truncated" indicator. The LLM separately sees the truncation marker and may emit `tool_error`-equivalent text in its response. |

**Considered alternative:** drop the result entirely if over the cap
and emit `tool_error` with `INTERNAL`. Rejected — the LLM has no
mechanism to know **what** the tool produced if dropped wholesale; a
truncated excerpt is strictly more useful than a dropped result for
the LLM's reasoning.

### 5. Per-tool circuit breaker — Resilience4j 2.2.0 reused, one breaker per descriptor

**Decision: each `ToolDescriptor` gets its own Resilience4j
`CircuitBreaker` instance registered in the existing
`CircuitBreakerRegistry`. Breaker name is `tool-<descriptor-name>`
(e.g., `tool-generate_massing`). Thresholds mirror ADR-14 §4's
`spark-gateway` breaker verbatim — same library version, same
configuration template, same exception classification rules.**

Mirrors PRD Story 6's acceptance criteria + spec §5's working numbers.

| Concern | Pin |
|---|---|
| Library | `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` + `resilience4j-reactor:2.2.0` — **reused from ADR-14 §4**, no version bump. |
| Breaker name template | `tool-<descriptor-name>` (e.g., `tool-generate_massing`) |
| Registration | One `CircuitBreaker` per descriptor, registered at `WebClientToolDispatcher` construction time via the shared `CircuitBreakerRegistry` bean. Concurrent map keyed by descriptor name guards against late-registration races. |
| Failure-rate threshold | **50 %** over a **sliding window of 60 seconds** (matches ADR-14 §4). |
| Minimum number of calls | **10** (matches ADR-14 §4) |
| OPEN duration | **30 s** (matches ADR-14 §4) |
| Permitted calls in HALF_OPEN | **1** (matches ADR-14 §4) |
| Exception classification | `WebClientResponseException.5xx`, `IOException`, `TimeoutException` count as failure. `WebClientResponseException.4xx` does **not** count as failure (M4 ADR-14 §4 invariant — 4xx = code/data bug, not breaker-trip signal). |
| Configurability | Per-descriptor thresholds NOT exposed as env vars in M7 P0 (all descriptors use the same default thresholds). M7.1 may add per-tool overrides via `application.yml` if a descriptor's traffic profile warrants tuning. |
| Metric exposure | Micrometer auto-binding (same as ADR-14 §4). Metrics: `resilience4j_circuitbreaker_state{name="tool-<n>"}`, `resilience4j_circuitbreaker_calls{name="tool-<n>",kind="..."}`. |

**Isolation invariant (PRD Story 6):** the per-tool breakers are
**independent** — `tool-A` going OPEN does not affect `tool-B`'s
state. WireMock integration tests in `rag-chat-infra` register two
synthetic descriptors pointing at different WireMock stub URLs;
hammering one with 5xx pushes its breaker OPEN while the other's
remains CLOSED. (PRD acceptance bullet "Per-tool isolation".)

### 6. Depth cap default + env var name

**Decision: max tool-call depth per user turn = **5** (default).
Override via env var **`PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH`**, exposed
through `application.yml` as
`playground.rag-chat.tool.max-depth: ${PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH:5}`.**

Closes PRD open question 3 ("Max tool-call depth default and env-var
name") and spec §5's third open question.

| Concern | Pin |
|---|---|
| Default | **5** (PRD working number, ratified) |
| Env var | **`PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH`** (matches the
`PLAYGROUND_RAG_CHAT_RETRIEVAL_K` convention from ADR-14 §7 — BC-scoped prefix). |
| Property path | `playground.rag-chat.tool.max-depth` |
| Bound to | A `RagChatToolProperties` `@ConfigurationProperties` POJO in `rag-chat-app` (alongside the existing `RagChatRetrievalProperties` from ADR-14 §7). |
| Enforcement layer | `StreamChatTurnUseCase` — see §1.2 for the counter mechanism. |
| Behaviour on cap exceeded | Emit `tool_error{code: "MAX_DEPTH"}`. Terminal — no further LLM round-trip; partial assistant content accumulated so far is **not** persisted (matches M4 §13's abort-path policy). |

**Why 5 is the right default:**

- M8's first-consumer scenario (`generate_massing`) is single-tool —
  depth 1 covers it.
- A multi-tool turn (M9+, e.g., "find brief → summarize → generate
  massing") would typically chain 2–3 distinct tools — depth 3 covers it.
- Headroom of 2 above the realistic case absorbs the LLM occasionally
  retrying the same tool after a 4xx (without exhausting the cap on
  the first chain).
- Cap above ~10 risks the LLM falling into a self-call loop on a
  malformed result interpretation — costly without operational benefit.

The env var override exists for experiments (operator may set it to
`2` to test depth-cap UX, or `10` to validate a complex chain) but the
default is the production setting.

### 7. Cost accounting — chat-level token bucket counts tool-inflated turns; no per-tool quota in P0

**Decision: M4's hourly + daily token bucket (`RRateLimiter` per ADR-14
§5) continues to count every user turn as exactly **one** turn,
regardless of how many tool round-trips the turn includes internally.
No per-tool quota, no per-tool token accounting in M7 P0.**

Closes PRD open question 6 ("Cost accounting for tool-inflated
prompts") and spec §5's sixth open question.

| Concern | Pin |
|---|---|
| Token bucket count granularity | Per **user turn**, not per tool round-trip. A turn that triggers 3 tool calls still consumes 1 hourly token + 1 daily token from the bucket. |
| Token-count telemetry | Per-turn `tokens_in` / `tokens_out` recorded in `chat.messages` (ADR-14 §F) reflects the cumulative LLM-side prompt + completion token count across all multi-turn rounds within the single user turn. The tool-result feedback rounds inflate `tokens_in` (the tool result text gets sent back to the LLM); the implementer counts that inflation against the same row. |
| Why no per-tool quota | M7 ships zero registered tools. M8 lands one (`generate_massing`). At single-digit tool count, the chat-level bucket is sufficient operator protection — a per-tool quota adds Redisson `RRateLimiter` keys per descriptor for no observable cost gain. **If M9+ adds 5+ distinct tools** with materially different cost profiles (e.g., a fast `search_docs` vs a slow `render_3d`), this ADR is amended with per-tool quotas. |
| M5 metrics dashboard impact | The M5 dashboard's "tokens-by-user-by-day" rollup (per ADR-15) will see tool-inflated turns as higher `tokens_in` rows — visible to the operator but not separated by `(turn was tool-inflated vs not)`. M5.1 may add a `tool_call_count` column on `chat.messages` for separability if the operator wants the split; M7 P0 does not add it. |

**Considered alternative:** debit the bucket by N tokens per tool
round-trip (N = LLM `prompt_tokens` of the tool-result feedback).
Rejected for P0 — implementing it requires multi-round token-count
accumulation in the streaming pipeline + per-round bucket debits, which
is a non-trivial change to the rate-limit code path for a problem that
doesn't yet exist at the scale of one registered tool.

### 8. Components — module placement summary

Mirrors ADR-14 §1's component-placement table for the M7 additions.

| Component | Module | Spring-free? |
|---|---|---|
| `ToolCatalog` (constants class exposing `List<ToolDescriptor> descriptors()`) | `rag-chat-domain` | **Yes** — no Spring annotations; classpath constants. P0 returns an empty list (no descriptors registered). M8 lands the first descriptor. |
| `ToolDescriptor` (immutable record: `name`, `description`, `parameterSchema` as JSON-Schema string, `endpoint` URI, `timeout` Duration) | `rag-chat-domain` | **Yes** |
| `ToolResult` (immutable record wrapping tool BC response: `id`, `name`, `result` as `JsonNode`) | `rag-chat-domain` | **Yes** |
| `ToolError` (immutable record: `id`, `name`, `code` of `ToolErrorCode`, `message`) | `rag-chat-domain` | **Yes** |
| `ToolErrorCode` (7-value enum from §2) | `rag-chat-domain` | **Yes** |
| `ToolCallEnvelope` (immutable record carrying the LLM-produced `(id, name, args)` triple) | `rag-chat-domain` | **Yes** |
| `ToolDispatcherPort` (port interface — `Mono<ToolResult> invoke(ToolCallEnvelope, UserId, GoogleSub)`) | `rag-chat-app` | **Yes** (port = interface only) |
| `WebClientToolDispatcher` (adapter implementing `ToolDispatcherPort`) | `rag-chat-infra` | **No** — uses Spring WebFlux `WebClient`, Resilience4j `CircuitBreaker`, Jackson |
| Per-tool `CircuitBreaker` registry | `rag-chat-infra` (wired in a `@Configuration` class) | **No** — Resilience4j registry beans |
| `ToolCallbackFactory` (maps `ToolDescriptor` → Spring AI `ToolCallback`) | `rag-chat-infra` | **No** — uses Spring AI 1.0.0 GA APIs |
| Multi-turn function-calling branch in `StreamChatTurnUseCase` | `rag-chat-app` | **Partial** — uses `ToolDispatcherPort` (Spring-free) + `ChatGenerationPort` (Spring-free); the use-case orchestrator class itself may use `@ConfigurationProperties` per ADR-02 / ADR-14 §7 invariant |
| SSE event mapper additions (`toSse(ToolCall)`, `toSse(ToolResult)`, `toSse(ToolError)`) | `rag-chat-api` (in `ChatStreamController`) | **No** — controller is Spring-bound |
| `RagChatToolProperties` (`@ConfigurationProperties` POJO) | `rag-chat-app` | **Partial** (acceptable per ADR-02 — only the `@ConfigurationProperties` annotation) |
| `ChatStreamEvent.ToolCall` / `.ToolResult` / `.ToolError` records | `shared-kernel` (`chat/ChatStreamEvent.java`) | **Yes** (the sealed interface lives in shared-kernel; no Spring) |

The `-domain` Spring-free invariant per ADR-02 is upheld.

### 9. Tool BC HTTP wire shape — auth headers, content-type, error contract

**Decision: tool dispatcher calls tool BCs over HTTP at the URI pinned
in the descriptor. Auth headers are forwarded; content negotiation is
JSON only.**

Closes PRD Story 3 acceptance criteria.

| Concern | Pin |
|---|---|
| HTTP method | `POST` |
| URI source | `ToolDescriptor.endpoint` (compose-internal URI, e.g., `http://massing-gen-api:18086/internal/tools/generate-massing`) |
| Content-Type | `application/json` request and response |
| Request body | The `args` JSON produced by the LLM, verbatim (matches the descriptor's `parameterSchema`). |
| Response body | Tool BC's free-form JSON. Deserialized into `JsonNode` and wrapped as `ToolResult.result`. |
| Auth headers forwarded | `X-User-Id` (chat session's authenticated user, sourced from the originating SSE request's `X-User-Id` per ADR-07) **+** `X-User-Sub` (Google sub, ADR-14 §15 invariant). |
| Other headers | `Content-Type: application/json`, `Accept: application/json`, `User-Agent: rag-chat-tool-dispatcher/<version>` (informational only). |
| **NOT forwarded** | `Authorization` header (chat session is gateway-OAuth-validated; backend-to-backend channel is trusted compose-network per ADR-08). Cookie headers. CSRF headers. |
| WebClient instance | Shared `WebClient.Builder` bean configured in `rag-chat-infra`. Per-descriptor `WebClient` instances built lazily and cached; descriptor's `timeout` applied per-call via `.timeout(descriptor.timeout())`. |
| Timeout enforcement | descriptor-supplied `timeout` Duration (e.g., 30 s for `generate_massing`). Reactor `.timeout(...)` operator; on expiry, the upstream connection is best-effort closed via Reactor cancellation (matches ADR-14 §14's vLLM pattern). |
| Failure responses | 4xx vs 5xx classified per §2. Connect-refused / DNS failure mapped to `INTERNAL`. |
| Descriptor `timeout` missing | Fail-fast at `@PostConstruct` validation in `WebClientToolDispatcher` — descriptor missing `timeout` is a developer bug, not a runtime concern. |

### 10. Persistence policy — no tool-call trace persisted in P0

**Decision: M7 P0 does **not** persist tool-call traces. `tool_call` /
`tool_result` / `tool_error` events are in-memory-only; on page reload
of `/chat/sessions/{id}/messages` the user sees only the persisted
assistant message body (which may reference tool outputs via natural
language but does not carry the tool-call envelopes).**

Matches PRD acceptance bullet "M7 P0는 tool 호출 trace 영구 저장 안 함"
and the ADR-14 §13 abort-path persistence pattern.

| Concern | Pin |
|---|---|
| `chat.messages` schema change | **None.** No new columns, no new tables. |
| `chat.tool_calls` table (forward-looking) | **Not introduced in M7 P0.** M7.1 may add it for audit/debugging if operator demand materializes. |
| What persists | The assistant's final natural-language response (which incorporated the tool result via the multi-turn LLM context) — written exactly as M4 §10 + ADR-14 §F describe. |
| What does **not** persist | Tool call envelopes, tool results, tool errors. |
| Audit log shape | The structured audit log (ADR-14 §15) gains new event types: `tool_call_invoked`, `tool_call_succeeded`, `tool_call_failed`. Each carries `userId`, `userSub`, `sessionId`, `messageId` (the in-progress assistant message), `toolName`, `toolDepth`, `toolDurationMs`, and (for failed) `toolErrorCode`. The log is the operator's only post-hoc record of tool activity in M7 P0. |

### 11. Configuration — env vars + `application.yml` keys

| Env var | Property path | Default | Origin |
|---|---|---|---|
| `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH` | `playground.rag-chat.tool.max-depth` | `5` | §6 |
| `PLAYGROUND_RAG_CHAT_TOOL_MAX_RESULT_BYTES` | `playground.rag-chat.tool.max-result-bytes` | `16384` | §4 |
| `PLAYGROUND_RAG_CHAT_TOOL_DEFAULT_TIMEOUT_MS` | `playground.rag-chat.tool.default-timeout-ms` | `30000` | (fallback only — descriptor's own `timeout` always wins; this default is used only if a descriptor missed `timeout` in dev and dev profile suppresses the fail-fast — production profile fails fast) |

No new env vars for breaker thresholds in P0 — descriptors all share
the ADR-14 §4 thresholds. Per-descriptor overrides land in M7.1 if
needed.

### 12. Test surface

Mirrors PRD acceptance criteria; the implementer covers each below
during Stage 3.

| Test class | Location | What it covers |
|---|---|---|
| `ToolCatalogTest` (unit) | `rag-chat-domain/src/test/java/.../tool/` | `descriptors()` returns immutable list; descriptors with duplicate `name` are rejected; descriptor records reject null/blank `name`, null `endpoint`, null `timeout`. |
| `ToolErrorCodeTest` (unit) | same | Enum cardinality (exactly 7 values); name stability. |
| `WebClientToolDispatcherTest` (integration, WireMock) | `rag-chat-infra/src/test/java/.../tool/` | (a) Happy path: descriptor + WireMock stub at descriptor URI → returns 200 with JSON → `ToolResult.result` deserialized correctly. (b) `X-User-Id` + `X-User-Sub` headers forwarded (WireMock capture + assertion). (c) 4xx → `ToolError{UPSTREAM_4XX}`, breaker NOT tripped. (d) 5xx burst → breaker OPEN at minimum-calls + failure-rate threshold; next call returns `ToolError{CIRCUIT_OPEN}` without HTTP call (WireMock request count stays unchanged). (e) Breaker HALF_OPEN → success → CLOSED transition. (f) Per-tool isolation: two descriptors, breaker on A opens, breaker on B unaffected. (g) Timeout: WireMock stub delays past descriptor `timeout` → `ToolError{TIMEOUT}` + breaker counts the timeout. (h) Result over 16 KiB cap → truncated `ToolResult` emitted with `truncated: true` marker. |
| `ChatTurnServiceToolCallingTest` (slice) | `rag-chat-app/src/test/java/.../service/` | (a) Multi-turn: mock `ChatGenerationPort` to emit `tool_call` envelope on first LLM call + final text on second call; mock `ToolDispatcherPort` to return success. Verify SSE event sequence: `phase{retrieval}` → `tool_call{id:X}` → `tool_result{id:X}` → `token×N` → `done`. (b) Depth cap exceeded: mock to emit `tool_call` 6 times → 5 dispatches succeed, 6th → `tool_error{MAX_DEPTH}` terminal. (c) `ToolCatalog` empty → no `tools(...)` call passed to `ChatClient` → M4 path unchanged. |
| `ChatStreamControllerToolEventTest` (slice) | `rag-chat-api/src/test/java/.../controller/` | `toSse(ToolCall)` → event name `tool_call`, JSON data matches PRD §Wire-shape contracts. Same for `ToolResult` / `ToolError`. |
| `M7EchoToolE2ETest` (end-to-end) | `rag-chat-api/src/test/java/.../e2e/` | Test-only synthetic `echo` tool descriptor registered (test fixture; not in main classpath). Spring AI ChatClient mocked to emit a `tool_call("echo", {text: "ping"})` envelope on first turn, final text on second. WireMock stub at echo descriptor URI returns `{"echoed": "ping"}`. Full SSE event sequence asserted end-to-end via `WebTestClient`. |
| `M4RegressionInvariantTest` (existing) | `rag-chat-*/src/test/...` | M4 test suite passes **unchanged** when `ToolCatalog.descriptors()` returns empty list. |

### 13. Rollout — single-PR slice with internal commits

**Decision: M7 lands as a single PR off branch `worktree-m7-tool-calling`.
Internal commits break the work into reviewable chunks but the PR is
not split.**

Matches PRD §"Open questions for ADR-17" final note ("single-PR slice
with internal commits") + spec §5's sizing note ("2–3 weeks, generic
infra with substantial test surface, worth pinning version isolation").

Suggested commit topology (implementer-determined; not normative):

1. `rag-chat-domain`: `ToolCatalog` + `ToolDescriptor` +
   `ToolErrorCode` + `ToolResult` + `ToolError` + `ToolCallEnvelope`
   records and enums (Spring-free).
2. `shared-kernel`: `ChatStreamEvent` extension (3 new sealed
   subtype records).
3. `rag-chat-app`: `ToolDispatcherPort` interface +
   `RagChatToolProperties`.
4. `rag-chat-infra`: `WebClientToolDispatcher` + per-tool
   `CircuitBreaker` registry + `ToolCallbackFactory`.
5. `rag-chat-app`: `StreamChatTurnUseCase` multi-turn branch + depth
   counter + tool-result max-bytes cap.
6. `rag-chat-api`: `ChatStreamController.toSse(...)` extensions for
   the three new event records.
7. Test suite (all six classes from §12).
8. ADR-08 §A08 + ADR-14 §A14 + ADR-00 row + roadmap M7 row
   updates (done in this commit set).

The PR ships after end-to-end verification with the synthetic `echo`
tool fixture (§12 `M7EchoToolE2ETest`). No real tool BC needed for
M7 close; M8 cycle wires `massing-gen` as the first non-test consumer.

## Additional decisions (not in PRD's question list but architect-owned)

### A. Compose service block — no change to `rag-chat-api` block

The `rag-chat-api` compose block from ADR-14 §A is unchanged by M7.
No new env vars are exposed in `docker-compose.yml` because the M7
defaults (depth cap 5, max result bytes 16 KiB) are baked into
`application.yml`; operator override is by setting the env var on the
compose service, not by amending the compose file.

If the operator wants to reduce the depth cap for an experiment, they
add `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH: "3"` to the
`rag-chat-api.environment` block; the compose file in the repo is
not modified.

### B. Library versions — all reused, no new dependencies

| Coordinate | Version | Source |
|---|---|---|
| `org.springframework.ai:spring-ai-bom` | **1.0.0** | reused from ADR-04 + ADR-14 §B |
| `org.springframework.ai:spring-ai-openai-spring-boot-starter` | (from BOM 1.0.0) | reused |
| `io.github.resilience4j:resilience4j-spring-boot3` | **2.2.0** | reused from ADR-14 §4 |
| `io.github.resilience4j:resilience4j-reactor` | **2.2.0** | reused |
| `org.springframework.boot:spring-boot-starter-webflux` | (Spring Boot 3.3.5 BOM) | reused |
| `com.github.tomakehurst:wiremock-standalone` | **3.x** (test scope) | reused from ADR-14 §B |
| **NEW for M7** | (none) | — |

The "no new dependency" outcome is intentional and matches PRD
§"Bounded Context: RAG-Chat (M7 amendment)" → "외부 의존성 (신규): 없음".

### C. JSON-Schema validation library — none in P0, rely on Spring AI

**Decision: in M7 P0, JSON-Schema validation of LLM-produced args
against the descriptor's `parameterSchema` is performed by Spring AI
1.0.0 GA's internal validation layer (which uses Jackson's
JsonSchemaValidator under the hood). M7 does NOT add
`com.networknt:json-schema-validator` or `everit-json-schema` as a
separate validator.**

Spec §5 mentions M8 (`massing-gen`) using `everit-json-schema`
explicitly for its own brief-program extraction step — that's an M8
decision (ADR-18), not an M7 decision. M7's args-validation layer
sits inside Spring AI's `ToolCallback` framework.

If, during Stage 3, the implementer discovers Spring AI 1.0.0 GA
does **not** validate args before invoking the callback, the
implementer raises an issue and this ADR is amended to add
explicit `com.networknt:json-schema-validator` to `rag-chat-infra`
with a manual validation step before WebClient dispatch.

### D. Tool descriptor governance — no runtime registration in P0

`ToolCatalog.descriptors()` returns a hardcoded `List<ToolDescriptor>`
assembled at classpath-load time. New tools land via PR to
`rag-chat-domain` adding a new descriptor constant. Dynamic /
config-driven registration (P2 per spec §5 and PRD scope) is not
implemented in P0 or M7.1.

Forward-compatibility hooks:

- The `ToolCatalog` API exposes `descriptors()` returning
  `List<ToolDescriptor>` — a future dynamic implementation can swap
  the static list for a registry-backed list without changing
  consumers.
- The `ToolDispatcherPort` is descriptor-agnostic — it accepts a
  `ToolCallEnvelope` carrying the tool `name` and looks up the
  descriptor at call time. A future hot-add adapter swap is
  source-compatible.

---

## §A08 — Amendment to ADR-08 (Inter-Service Communication)

> This amendment is appended to `docs/adr/08-inter-service-comms.md`
> as a new amendment block following the existing M6.1 block
> (§A08.1–§A08.7). It introduces Exception 4 (`rag-chat-api` →
> tool-implementer BCs HTTP for LLM function-calling).

### §A08.8. Exception 4 — `rag-chat-api` → tool-implementer BCs (HTTP)

**Sanctioned route:** `rag-chat-infra`'s `WebClientToolDispatcher` may
call any `POST /internal/tools/<tool-name>` endpoint on any
tool-implementer BC registered in `rag-chat-domain.ToolCatalog`.

**Justification:** synchronous, user-facing chat requires synchronous
tool result to feed back into the next LLM turn; Kafka's async
semantics breaks the LLM context flow. Tool dispatch IS the chat
synchronous critical path — it cannot be moved off-thread without
breaking the chat surface's invariants.

**Allowed methods and paths (per-tool-BC sub-row):**

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/<tool-name>` | `rag-chat-api` | tool BC (`-api`) | LLM-driven tool invocation; one row per registered tool BC. |

At M7 ship: **zero sub-rows** (`ToolCatalog.descriptors()` returns
empty). M8 adds the first sub-row when `massing-gen-api` lands:

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/generate-massing` | `rag-chat-api` | `massing-gen-api` | Brief PDF → `.3dm` massing (M8). |

Subsequent tool BCs add sub-rows per their own ADR (e.g., ADR-18 for
M8, ADR-19 for the second tool BC, etc.).

**Constraints on this exception:**

- **One direction only.** `rag-chat-api` → tool BC. Tool BCs MUST NOT
  call back into `rag-chat-api` over HTTP. (They may publish Kafka
  events on their own topics if needed; that's outside Exception 4's
  scope.)
- **Internal route prefix (`/internal/**`).** Tool BC routes prefixed
  `/internal/` are explicitly **not** exposed through the gateway
  (gateway's route table per ADR-07 does not forward `/internal/**`,
  matching Exception 1's prior shape and Exception 3's shape).
- **User identity propagation.** `X-User-Id` and `X-User-Sub` headers
  ARE forwarded (the originating chat user's identity). This is
  **different from Exception 1's "no user identity propagation"
  rule** — Exception 1 was ingestion bookkeeping (no user); Exception
  4 is user-facing tool invocation (the tool BC needs the user
  identity to do tenant-scoped reads, e.g., reading the brief doc).
- **Reliability discipline.** WebClient timeout = descriptor's
  `timeout` (per-descriptor, not a single global value); Resilience4j
  circuit breaker per descriptor (50% / 60s window / 30s OPEN); no
  WebClient-level retries (the breaker handles burst isolation, and
  the LLM handles retry-with-correction via the depth-bounded
  multi-turn loop).
- **Each new tool BC requires a sub-row.** This exception is a
  template, not a wildcard. Adding a new tool BC requires the new
  BC's per-milestone ADR to add its specific sub-row to the table
  above, mirroring how M6.1 retired Exception 1 and how Exception
  3's docs→identity row was sub-listed.

### §A08.9. Allowed-channels table (post-A08.8)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) → gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway → any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC → Kafka → BC | Kafka events | Per ADR-03 envelope |
| BC → external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC → Postgres (`postgres-playground`) | JDBC | Per ADR-05 |
| BC → OpenSearch (`opensearch-playground`) | HTTP (REST) | Per ADR-05 amendment |
| gateway → Redis (`redis-playground`) | Redis protocol | Spring Session (ADR-07) |
| `docs-api` → Redis (`redis-playground`) — `view:*` + `docs:lock:*` namespaces | Redis protocol | Sanctioned (ADR-12 amendment + M6.1 amendment A08.2) |
| `docs-api` → `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | Sanctioned (ADR-12 amendment 2026-05-17) |
| `docs-api` → `minio-playground:9000` | HTTP / S3 protocol | Sanctioned (M6.1 amendment A08.3) |
| `rag-chat-api` → cross-schema SELECT into `docs.*` + `identity.*` | JDBC (cross-schema) | Sanctioned (ADR-14 amendment; M6.1-narrowed to 2 schemas) |
| **`rag-chat-api` → tool BC `-api` `/internal/tools/<name>`** | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.8) — Exception 4.** Per-tool-BC sub-row required. |

### §A08.10. Future-exception discipline (unchanged)

Every future BC-to-BC HTTP path still requires a fresh ADR amendment
row. M7's net effect on the cross-BC HTTP exception count is **+1**
(Exception 4 introduced; sub-rows added per tool BC). The exception
TYPE count is now 4 (Exception 1 retired; Exception 2 retired in
M6.1; Exception 3 surviving; Exception 4 introduced by this amendment).

The exception count by sub-row:
- Exception 3 (docs → identity): 1 sub-row
- Exception 4 (rag-chat → tool BCs): 0 sub-rows at M7 ship; 1 sub-row
  after M8 (`massing-gen`)

See `docs/adr/17-m7-rag-chat-tool-calling.md` §9 for the full Exception
4 wire-shape specification.

---

## §A14 — Amendment to ADR-14 (M4 RAG-Chat)

> This amendment is appended to `docs/adr/14-m4-rag-chat.md` as a new
> "Amendment 2026-05-22 — M7 SSE grammar reconciliation" block at the
> end. It reconciles the SSE wire shape introduced by M7 with the
> placeholder `step:` values mentioned in the spec §5.2 grammar
> example, without modifying any shipped M4 behavior.

### §A14.1. SSE wire shape — standalone event names supersede placeholder phase steps

ADR-14 §5.2 references the M4 spec's revised SSE grammar (PR B in spec
§5.2). That grammar's example block carried two forward-looking
placeholder rows (added as "PR C onward — extra phase events from
tool-calling"):

```
event: phase
data: {"step":"tool_call","label":"공개 문서 검색 중","data":{"tool":"searchPublicDocs","args":{"query":"…"}}}
```

with an implicit symmetric `step:"tool_result"` row.

**These placeholder rows are retired** by M7 (ADR-17 §3). M7's
function-calling SSE events ship as **standalone `event:` names**
(`event: tool_call`, `event: tool_result`, `event: tool_error`), not
as `phase` step discriminators. The `phase` event continues to exist
for non-tool progress signaling (`step: "retrieval"`,
`step: "generating"`, `step: "thinking"`); its `step` discriminator
domain is now closed to progress-class values — tool-class values do
not appear there.

### §A14.2. Why standalone event names

- **The shipped M4 backend never emitted `step: "tool_call"` or
  `step: "tool_result"` phase events.** The
  `rag-chat-app.ChatTurnService.stream(...)` method emits exactly one
  `Phase` event per turn — `step: "retrieval"`. The placeholder rows
  in spec §5.2 were forward-looking annotations, not shipped wire shape.
- **The shipped M4 frontend `chat.sse.ts` already supports standalone
  event-name dispatch** via a `switch (eventName)` block in
  `parseFrame(...)`. Unknown event names are dropped silently — the
  frontend has been forward-compatible with new `event:` names since
  M4 ship. Adding `tool_call` / `tool_result` / `tool_error` is purely
  additive; the existing FE code does not break.
- **Standalone event names match FE dispatch ergonomics.** A frontend
  that wants to render tool-call cards distinctly from retrieval
  progress benefits from a dedicated event name (matches React
  reducer / `addEventListener('tool_call', ...)` patterns).
  Conflating tool calls with progress phases would force the FE to
  disambiguate the same `phase` event into two different rendering
  paths via the `step` field — unnecessary indirection.
- **Forward compatibility for parallel tool calls** (M7.1 scope). When
  Spring AI's parallel function-calling lands and is adopted, the
  `tool_call.id` correlation ID (ADR-17 §3.2) is the FE's mechanism
  for pairing multiple in-flight tool calls — a dedicated event name
  per tool action makes the pairing JSON-shape compact.

### §A14.3. Updated §5.2 grammar example (replaces the PR C placeholder block)

The revised §5.2 grammar example block in the spec (and quoted in
ADR-14's references to §5.2) reads, post-M7:

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

**Notable invariants preserved from M4:**

- `phase` events remain progress-class only.
- `done` is the success terminal; `error` is the chat-level fatal
  terminal. Neither changes.
- `tool_error` does **not** fall back to `error` — tool failures are
  distinct from chat-level fatals (`error` continues to carry only
  the M4 §6.5 5-value enum: `GATEWAY_5XX` / `RATE_LIMIT` /
  `RETRIEVAL_EMPTY` / `ABORTED` / `INTERNAL`).
- `tool_error` with `code: "CIRCUIT_OPEN"` or `code: "MAX_DEPTH"`
  terminates the turn without a further `done` / `error` (the
  `tool_error` event is itself terminal in those two cases; partial
  assistant content accumulated so far is not persisted, matching
  ADR-14 §13 abort-path semantics).

### §A14.4. `ChatStreamEvent` sealed-interface extension

The M4 `ChatStreamEvent` sealed interface in `shared-kernel` gains
three new permitted subtype records — `ToolCall`, `ToolResult`,
`ToolError`. The existing `Phase` / `Token` / `Done` / `Error`
records are unchanged. The pre-PR-B compat note in the existing
`ChatStreamEvent` javadoc remains accurate as historical context.

The wire-name convention (lowercase snake_case-of-record-name) is
preserved — `ToolCall` → `tool_call`, `ToolResult` → `tool_result`,
`ToolError` → `tool_error`. The existing `Phase` → `phase` etc.
unchanged.

### §A14.5. ADR-14 §1 component-placement table — additive only

ADR-14 §1's table gains three new component rows (the M7 components
from ADR-17 §8). The existing 18 rows are unchanged. The M4
implementation in `rag-chat-{api,app,domain,infra}` ships unmodified
in the M7 PR set — the M7 commits are purely additive.

See `docs/adr/17-m7-rag-chat-tool-calling.md` §3 + §8 + §10 for the
full M7 specification.

---

## Consequences

- **Positive:** M4's chat surface gains a hot-swappable infra for
  LLM-driven tool invocation without changing any user-visible
  behavior at M7 ship. M8 can land a single descriptor PR and unlock
  the brief-to-massing vertical.
- **Positive:** Per-tool circuit breaker isolation means one sick
  tool BC does not degrade chat overall.
- **Positive:** Standalone SSE event names align with FE
  `addEventListener`-style ergonomics and forward-compatible with
  parallel tool calls (M7.1).
- **Positive:** Zero new external dependencies (Resilience4j, Spring
  AI, WebClient all reused).
- **Positive:** Zero schema changes — M7 P0 is in-memory-only for
  tool traces; audit log shape carries operator's only post-hoc
  record.
- **Negative / trade-off:** The depth cap default of 5 may be too
  high for chat surfaces that rarely chain tools (cost overhead of
  exploring failed args before giving up). Operator override is
  available; M7.1 may revise the default down based on observed
  behavior.
- **Negative / trade-off:** No per-tool token quota in P0 — if a
  future tool BC inflates prompts heavily, the chat-level token
  bucket may not give the operator fine-grained protection per tool.
  M7.1 may add per-tool quotas if needed.
- **Negative / trade-off:** Spring AI's tool-result feedback for
  4xx/5xx errors gives the LLM the chance to retry within the same
  turn — which can chew through depth cap if the LLM keeps trying.
  The depth cap is the safety net; observed misbehavior raises a
  per-tool retry-count config knob in M7.1.
- **Negative / trade-off:** If Spring AI 1.0.0 GA's native
  function-calling path does not let us interject our SSE event
  emission at the tool-call boundary, the fallback (§1.1) requires
  manual multi-turn driving — more code surface to maintain than the
  native path. The implementer evaluates during Stage 3.

## Diagrams

No new diagrams. The M7 dependency graph is `rag-chat-api` → tool BC
`-api` (compose-internal HTTP) as the new edge; the existing M4
diagram in ADR-14 covers everything else. M8's PR set will add the
massing-gen edge to ADR-00's module-dependency graph when it lands.
