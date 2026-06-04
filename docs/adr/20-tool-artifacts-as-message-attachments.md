# ADR-20: Tool-produced file artifacts as chat message attachments

## Status
Accepted (2026-06-04)

## Context

The M8 `generate_massing` tool produces a binary `.3dm` file. Today
(ADR-18 / ADR-19) the `architecture` BC (in `agent-tools`) persists that
file itself — `arch.outputs.file_bytes` (BYTEA) — and serves it from its
own `GET /api/arch/outputs/{id}` download endpoint, returning a `fileUrl`
in the tool result.

Two problems with that shape, and one generalization:

1. **The artifact belongs to the conversation, not the tool BC.** A
   `.3dm` is the product of a chat turn; the natural owner is the chat
   message that produced it, not a side table in a tool BC. Modelling it
   as a **message attachment** (rag-chat / M4) matches the chat UX and
   keeps tool BCs stateless.
2. **Storage in a tool BC duplicates infrastructure.** Each future
   file-producing tool (slide-gen, image-gen, …) would otherwise grow its
   own blob store + download endpoint. Centralizing artifact persistence
   in rag-chat (which already owns the conversation) avoids that.
3. **Hard constraint — bytes must not enter the LLM context.** In M7
   (ADR-17) a tool's JSON result is fed back to the model for the next
   reasoning step. A 20 KB `.3dm` (≈27 KB base64) in the LLM-visible
   result is pure token waste and the model never needs it. So the
   artifact bytes must travel on a channel that bypasses the LLM.

**Decision (owner-confirmed):** tools may emit a file **artifact**; the
rag-chat ToolDispatcher captures it (off the LLM path), stores the bytes
in **MinIO**, and records an **`Attachment`** bound to the chat message.
The attachment record holds the **MinIO storage key + metadata only — never
the bytes**. Download is served by rag-chat. `agent-tools` becomes a
stateless generator (its `arch.outputs` BYTEA store + `/outputs` download
are retired).

## Decision

### D1 — `Attachment`: a new rag-chat domain concept

A message-bound artifact, modelled in `rag-chat-domain` (the `Message`
aggregate references its attachments). It is a small entity that points at
the blob; **it does not carry the bytes.**

| Field | Notes |
|---|---|
| `id` | UUID |
| `messageId` | the assistant message that produced it (FK app-level, per ADR-14 §11 style) |
| `kind` | e.g. `tool-artifact` (room for future kinds) |
| `filename` | e.g. `massing-<slug>-<ts>.3dm` (RFC-6266 download name) |
| `contentType` | MIME type, e.g. `model/vnd.3dm` or `application/octet-stream` for `.3dm`, `image/png`, `application/json`. **Stored deliberately** so the FE can render type-aware previews (D5), not just downloads, and the download endpoint can set the right `Content-Type`. |
| `sizeBytes` | for the `Content-Length` header + FE size display |
| `storageKey` | **MinIO object key** (the only pointer to the bytes) |
| `toolName` | which tool produced it (e.g. `generate_massing`) |
| `createdAt` | |

New table **`chat.message_attachments`** (schema-per-BC, ADR-05). No audit
table for extracted inputs — the tool `result` (programJson / summary)
already rides the message; that is sufficient (owner decision).

### D2 — Tool contract extension (amends ADR-17)

A tool's HTTP response to the dispatcher MAY carry an optional `artifact`
**alongside** the LLM-visible `result`:

```json
{
  "result":   { "summary": "...", "programJson": {...}, "floorCount": 4, ... },
  "artifact": { "filename": "...", "contentType": "application/octet-stream",
                "base64": "<bytes>" }
}
```

- The dispatcher feeds **only `result`** to the LLM and to the `tool_result`
  SSE event — **`artifact` never enters the LLM context** (D-context #3).
- `artifact` is optional; tools that produce no file omit it (M7 behaviour
  unchanged for them).
- 20 KB base64 over the internal `rag-chat → agent-tools` HTTP hop is fine
  (it is not the LLM context). If artifacts grow large later, a streaming /
  fetch-by-reference variant can replace the inline base64 — out of scope now.

### D3 — rag-chat owns MinIO storage (mirrors docs-api ADR-12 §A12.4)

- New `BlobStoragePort` (rag-chat-app) + `MinioBlobStorageAdapter`
  (rag-chat-infra), `libs.minio`, configured for `minio-playground` (already
  on `playground-net`). rag-chat gains `MINIO_*` env (same pattern as docs).
- Object key convention: `chat/{sessionId}/{messageId}/{attachmentId}-{filename}`.
- Flow inside the turn: the ToolDispatcher receives `{result, artifact}` →
  if `artifact` present → `BlobStoragePort.put(key, bytes)` → build an
  `Attachment(messageId=<assistant message>, storageKey=key, …)` → persist in
  `chat.message_attachments`. The `tool_result` / `done` payload carries the
  attachment **download URL** (see D4), not a tool-BC URL.

**Integration note (timing):** the tool dispatch happens mid-stream (Spring AI
function-calling callback) before the assistant message is persisted. The
assistant `messageId` must therefore be allocated up-front (or the attachment
staged and linked at `persistAssistantAndDone`). The implementer pins the
exact sequencing; the invariant is: an attachment is always linked to the
assistant message of the turn that produced it.

### D4 — Download relocates to rag-chat; agent-tools store retired

- **rag-chat** serves `GET /api/rag/chat/attachments/{id}` — authenticated,
  **owner-only** (X-User-Id must match the attachment's message owner; non-owner
  → 404, same tenant-isolation rule as ADR-14 §6.5 / massing download). Streams
  from MinIO with RFC-6266 `Content-Disposition` (the Korean-filename fix from
  the M8 download carries over — reuse the same content-disposition helper
  semantics).
- **gateway**: the `/api/arch/**` download route is removed; the attachment
  route under `/api/rag/chat/**` is already authenticated (ADR-09 / ADR-14 §1).
- **agent-tools (`architecture` BC)** becomes a **stateless generator**:
  `generate_massing` returns `{result, artifact}` and no longer writes
  `arch.outputs` or serves `/outputs/{id}`. The `arch` schema + `arch.outputs`
  table are **retired** (drop migration; supersedes ADR-18 §12 / §21 and
  ADR-19's preservation of `/api/arch/**` + `arch.outputs`). `/internal/tools/
  generate-massing` and the `generate_massing` tool name are unchanged.

### D5 — Frontend: type-aware attachment card (not a bare link)

Attachments are rendered as a **dedicated, `contentType`-driven Attachment
card**, not a plain download link. The card branches on `contentType`:

| contentType | rendering |
|---|---|
| `.3dm` (binary/model) | metadata card (filename, size, the massing summary `N실 · 지상 A층 …`) + a download action. No inline preview (binary CAD). |
| `image/*` (e.g. `image/png`) | **inline thumbnail/preview** + download. |
| `application/json` / text | **inline preview** (pretty-printed / collapsible) + download. |
| other / unknown | generic file card (icon + filename + size) + download. |

- The card reads the attachment's `downloadUrl`, `filename`, `contentType`,
  `sizeBytes` from the `tool_result` / `done` payload (which now carries an
  `attachment` object instead of the old `/api/arch/outputs/{id}` string).
- A reusable `AttachmentCard` (FSD: a `feature` or `entity/attachment` widget)
  selects the renderer by `contentType`, so future tool artifacts (images,
  JSON, etc.) get type-correct presentation for free. The download action keeps
  the `<a href download>` pattern (ADR-17 §3); previews fetch the blob via the
  same owner-only endpoint (D4).
- For Phase 3b the only live type is `.3dm` (metadata + download card); the
  image/json preview branches are scaffolded for the generalization but exercised
  when a tool first emits those types. (frontend-design pre-flight applies — the
  card's visual spec traces to the M-cycle design doc.)

## Consequences

- **Positive:** artifacts belong to the conversation (message attachments);
  one storage/download path for all file-producing tools; tool BCs become
  stateless generators; bytes never touch the LLM context; the BYTEA→MinIO
  migration ADR-18 §12 foresaw lands here, but in rag-chat.
- **Positive:** generalizes — a future `slide-gen`/`image-gen` tool emits an
  `artifact` and gets persistence + download for free.
- **Negative / scope:** cross-BC change — amends ADR-17 (tool contract),
  ADR-14 (chat schema gains `message_attachments` + MinIO), ADR-18/19
  (agent-tools store/download retired), gateway route, and the FE. It is the
  first time rag-chat writes a blob store.
- **Negative / migration:** existing `arch.outputs` rows (and their
  `/api/arch/outputs/{id}` URLs already handed out) become dead when the
  endpoint is removed. At personal/playground scale this is acceptable
  (regenerate); noted rather than back-filled.
- **Negative / coupling:** the dispatcher now has a side-effect (blob write +
  attachment persist) beyond returning the tool result. Kept narrow: only when
  an `artifact` is present; the orchestration boundary (ADR-17 §D3 / ADR-19)
  still holds — tools don't orchestrate, the dispatcher just persists what a
  tool emitted.

## Amendments to other ADRs (inline notes appended to each)
- **ADR-17** (M7 tool-calling): the tool→dispatcher contract gains the optional
  non-LLM `artifact`; the dispatcher persists it as a message attachment.
- **ADR-14** (M4 rag-chat): `chat.message_attachments` table + `BlobStoragePort`
  / `MinioBlobStorageAdapter` + `GET /api/rag/chat/attachments/{id}` download.
- **ADR-18 / ADR-19** (massing / agent-tools): `arch.outputs` BYTEA store +
  `/api/arch/outputs/{id}` retired; `architecture` BC is now a stateless
  generator returning `{result, artifact}`.
- **ADR-00**: index row for ADR-20.

## Implementation phases (Phase 3b)
1. ADR-20 (this) — decision/gate.
2. rag-chat: `Attachment` domain + `chat.message_attachments` migration +
   `BlobStoragePort`/`MinioBlobStorageAdapter` + dispatcher artifact capture +
   download endpoint. (backend)
3. agent-tools: return `{result, artifact}`; retire `arch.outputs` + `/outputs`. (backend)
4. gateway route + FE attachment URL. (infra + frontend)
Each verified by the real-gateway E2E (chat → .3dm attached to the message →
download from rag-chat).
