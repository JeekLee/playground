# ADR-04: Spring AI Version + LLM Backend (spark-inference-gateway)

## Status
Accepted

## Context
The playground does **not** run its own model. It calls the existing
`spark-inference-gateway` (a vLLM server with an OpenAI-compatible API) running
on the developer's host. We need:
- A pinned Spring AI version that is GA-stable on Spring Boot 3.3 / JDK 21.
- A single client abstraction usable by both `rag-ingestion` (embeddings) and
  `rag-chat` (generation).
- Compose wiring that lets containers reach a host-bound port.

Alternatives considered:
- Calling the OpenAI client library directly — rejected: Spring AI's `ChatClient`
  / `EmbeddingModel` abstractions give us a uniform Retrieval-Augmented
  Generation pipeline (advisors, structured output).
- Running a local Ollama in compose — rejected: duplicates infrastructure that
  `spark-inference-gateway` already provides on this machine, and would not use
  the same model weights.

## Decision

### Spring AI
- Version: **Spring AI 1.0.0 (GA)** — coordinates `org.springframework.ai:spring-ai-bom:1.0.0`.
  The 1.0 line is the first GA, released against Spring Boot 3.3.
- Starter used: **`spring-ai-openai-spring-boot-starter`** (works against any
  OpenAI-compatible endpoint, including vLLM).

If, at the time M3/M4 is implemented, a newer 1.0.x patch is available on Maven
Central, the implementer pins the latest `1.0.x` and notes it in the milestone
ADR.

### LLM backend wiring

`spark-inference-gateway` is a host process bound to **`127.0.0.1:10080`**. To
reach it from inside compose, every service that uses Spring AI declares:

```yaml
# infra/docker-compose.yml fragment
extra_hosts:
  - "host.docker.internal:host-gateway"
```

and configures Spring AI:

```yaml
# application.yml in rag-ingestion / rag-chat
spring:
  ai:
    openai:
      base-url: http://host.docker.internal:10080
      api-key: dummy-not-used         # vLLM ignores it but the starter requires the property
      chat:
        options:
          model: Qwen3-32B
          temperature: 0.2
      embedding:
        options:
          model: BGE-M3
```

### Models

| Capability | Model | Notes |
|---|---|---|
| Chat / generation | **Qwen3-32B** | Used by `rag-chat`. Context window per vLLM config (assume 32k). |
| Embeddings | **BGE-M3** | Used by `rag-ingestion`. Output dimension: **1024** (the standard BGE-M3 dense head; the model also exposes sparse + multi-vector heads, but we use only the dense vector for pgvector). |

### Vector dimension contract
The pgvector column for chunk embeddings is declared `vector(1024)`. If we ever
switch embedding models, both the column and any cached embeddings must be
re-built — captured as a per-milestone ADR at the time.

### Which services depend on Spring AI

| Service | Uses ChatClient | Uses EmbeddingModel |
|---|---|---|
| `rag-ingestion` | no | yes (BGE-M3) |
| `rag-chat` | yes (Qwen3-32B) | yes (query-time embedding via BGE-M3) |
| All others | no | no |

### Operational note
The implementer is responsible for verifying that `Qwen3-32B` and `BGE-M3` are
the exact model names served by the local `spark-inference-gateway` (the model
ID is whatever vLLM was launched with). If the served names differ, override the
`spring.ai.openai.chat.options.model` / `embedding.options.model` properties; do
not change this ADR.

## Consequences
- Positive: One LLM stack for the whole machine; playground inherits whatever
  model upgrades the gateway ships.
- Positive: Spring AI 1.0 GA gives a stable `ChatClient` / advisor API.
- Negative: Tight host coupling (`host.docker.internal`) means the playground
  cannot run on a remote Docker host without re-configuring `base-url`.
- Negative: No fallback model — if `spark-inference-gateway` is down,
  `rag-chat` and `rag-ingestion` fail. Acceptable for a dev-only personal
  service.

## Amendment (2026-05-18, ADR-14) — ChatClient streaming exercised by rag-chat

ADR-14 (M4 RAG-Chat per-milestone) **confirms** every pin in this ADR
with **no semantic change**. The amendment is informational, recording
the first end-to-end exercise of the streaming path.

- **Streaming `ChatClient`** — M4 invokes
  `chatClient.prompt().messages(...).stream().chatResponse()`, mapping
  the returned `Flux<ChatResponse>` one-to-one to SSE `token` events on
  the gateway-routed `POST /api/rag/chat` endpoint (per ADR-14 §1). The
  base URL (`http://host.docker.internal:10080`), the model name
  (`Qwen3-32B`), and the default `chat.options.temperature=0.2` are
  unchanged. ADR-14 §6 applies a per-call `temperature=0.1` override for
  the auto-title path only; it is a Spring AI `ChatOptions` override per
  call, not a property change.
- **Query-time `EmbeddingModel`** — M4's per-turn query embedding uses
  the same `EmbeddingModel.embed(text)` shape M3 uses for chunk
  embedding (per ADR-13 §10). BGE-M3, 1024-dim dense output, OpenAI-
  compatible `/v1/embeddings` endpoint — unchanged.
- **Resilience4j wrap** — ADR-14 §4 wraps both `ChatClient` and
  `EmbeddingModel` invocations with a Resilience4j `CircuitBreaker`
  named `spark-gateway`. ADR-04's "No fallback model" consequence
  remains in force; the breaker short-circuits faster but does not
  introduce a substitute model.

See `docs/adr/14-m4-rag-chat.md` §1 + §4 + §6 + §17 for the full
specification.

## Amendment (2026-05-20) — compose-network attach replaces host.docker.internal

The original wiring (§"LLM backend wiring" above) assumed the host directly
runs a bare vLLM at `127.0.0.1:10080`, reached from compose via
`extra_hosts: host.docker.internal:host-gateway`. The operator has since
moved the Spark inference stack into a **separate compose project on a
bridge named `spark-inference-net`**:

- `spark-inference-gateway` container — Bearer-auth + model-routing layer,
  the only one exposed to the host (host port `127.0.0.1:10080`).
- `spark-inference-qwen3-30b-a3b`, `spark-inference-bge-m3` — backend model
  servers, NO host port (compose-internal only).

`host.docker.internal:10080` reaches the gateway via the host loopback
hop, but only when the host process binds `0.0.0.0` (or a route exists
from the docker bridge to the host's loopback) — fragile, and unnecessary
when the rag-chat / rag-ingestion / metrics containers can join the same
bridge directly.

**Decision:** `rag-ingestion-api`, `rag-chat-api`, and `metrics-api` are
now attached to two networks: the playground compose default (DB / Kafka /
Redis / gateway hops) and the external `spark-inference-net`. Endpoint
becomes `http://spark-inference-gateway:8000` — DNS resolves to the gateway
container's IP on the spark bridge with no host hop.

The port distinction matters: the host's `127.0.0.1:10080` is the
**outside** mapping of the gateway container's `8000`; from inside the
bridge the gateway is reachable on `:8000` directly. (Shape (b) — host
loopback fallback — still uses `:10080` because that's the host-facing
port.)

- `infra/docker-compose.yml` declares `spark-inference-net` as
  `external: true` and adds it to the three services' `networks:` lists.
  The `default` network is now explicit because Compose stops
  auto-attaching it once any service has a `networks:` block.
- `extra_hosts: host.docker.internal:host-gateway` is **kept** on the same
  three services as a fallback — it costs nothing and gives the operator
  an escape route if the spark stack is ever swapped back to a host
  process.
- The `SPRING_AI_OPENAI_BASE_URL` env knob carries the actual endpoint;
  the per-service `application.yml` defaults still point at
  `host.docker.internal:10080` so a stand-alone compose run (without the
  spark bridge) still finds a vLLM if one happens to run on the host.
- Operator prerequisite: the spark-stack compose project must be up
  first so `spark-inference-net` exists; otherwise `docker compose up`
  errors with `network spark-inference-net declared as external, but
  could not be found.`

ADR-04's other consequences (no fallback model, OpenAI-compatible
endpoint shape, model-name verification responsibility) are unchanged.

## Amendment (2026-05-21, ADR-16) — Vision modality introduction

ADR-16 introduces the **first use of Spring AI's multimodal (vision)
API** in this codebase. Every pin in this ADR — Spring AI 1.0 GA
coordinate, `spark-inference-gateway` base URL, OpenAI-compatible
endpoint shape, "no fallback model" consequence — is **unchanged**.
The amendment is informational, recording the new modality and the
new vision-model env var.

- **Vision modality** — docs-api's `VisionOcrAdapter` (ADR-16 §2)
  invokes `ChatClient.prompt().messages(UserMessage.builder().media(pngMedia).build()).options(ChatOptions.builder().model(...).build()).call().content()`
  with a PNG attached as `Media`. This is Spring AI 1.0 GA's
  multimodal API per the OpenAI image-input contract. The base URL
  (`http://spark-inference-gateway:8000` via the `spark-inference-net`
  external bridge per the 2026-05-20 amendment, with
  `host.docker.internal:10080` as the fallback) is unchanged.
- **Vision model env var** — `SPRING_AI_VISION_MODEL` (default
  `qwen3-vl-30b-a3b` per ADR-16 §2) selects the vision endpoint on
  `spark-inference-gateway`. The existing
  `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` env var continues to select
  the text endpoint (currently `qwen3-30b-a3b` per the 2026-05-21
  swap from `Qwen3-32B`). Both models may share a single vLLM
  instance with model-name aliasing, or run as parallel instances
  behind the same gateway — the BC code is identical either way.
- **Which services use Vision** — only `docs-api` (via
  `docs-infra`'s `VisionOcrAdapter`) starting at M6. `rag-chat-api`
  and `rag-ingestion-api` continue to use text-only Spring AI
  calls; the text vs. vision split is per-call, not per-BC.
- **No fallback model** — the original consequence holds. If
  `spark-inference-gateway` is unreachable during a PDF upload's
  OCR fallback, the affected pages contribute empty Markdown per
  ADR-16 §3 (graceful per-page degradation), not a full upload
  failure. The per-page softer surface is bounded by — and
  consistent with — ADR-04's "no fallback model" invariant.
- **Model availability note** — at the time of this amendment,
  `qwen3-vl-30b-a3b` is downloading on the operator's
  spark-inference-gateway. Backend integration tests use WireMock
  until the model lands (per ADR-16 §14 + §16); a manual E2E gates
  the full M6 acceptance.

See `docs/adr/16-m6-docs-pdf.md` §2 + §6 + §7 + §16 for the full
M6-Vision specification.
