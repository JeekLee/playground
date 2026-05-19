# Spec: Post-M5 Roadmap — M6/M7/M8 (Brief-to-Massing Vertical)

**Date:** 2026-05-19 (v1)
**Status:** Draft (brainstorming output — roadmap-level)
**Audience:** future PM/architect/product-designer/implementer agents that pick up M6, M7, or M8 individually; the human reviewer.

**Relationship to other docs:**
- **Supersedes** `docs/roadmap.md` §M6+ — the "Agents TBD" placeholder is replaced by three concrete milestones M6/M7/M8 forming a brief-to-massing vertical.
- **Will amend** `docs/roadmap.md` — add §M6 (PDF in docs) + §M7 (tool-calling infra) + §M8 (massing-gen BC) entries. The existing single-line `| M6+ | Agents | TBD (future cycles) | deferred |` row is removed and replaced by three new rows.
- **Forward-references** the future per-milestone ADRs ADR-16 (M6 PDF), ADR-17 (M7 tool-calling), ADR-18 (M8 massing-gen). Each closes its own open-question list.
- **References, does not supersede**: ADR-04 (Spring AI + spark-inference-gateway — Qwen3-32B reused; no new model); ADR-08 (will be amended in ADR-17 to add Exception 4 for rag-chat → tool BC HTTP).
- **Will be the canonical input** for the M6/M7/M8 brainstorming → PRD → ADR → design cycles when each milestone is opened individually.

## 0. Terminology

- **Brief / 공모 지침서**: PDF document containing an architectural design competition brief — site information, room program (room name + required area), height limits, FAR, etc. Korean-language briefs are the primary target (P0).
- **Room program**: structured list of `{ name, area_m2 }` extracted from a brief.
- **Massing**: a basic 3D box model representing a building's volume — stacked floors, each containing rectangular room footprints. NOT a finished design; the kickoff point an architect builds on.
- **.3dm**: the file format used by Rhinoceros 3D CAD software. OpenNURBS-based binary format. The target output of M8.
- **Tool (in LLM function-calling sense)**: a callable function exposed to the LLM in `ChatClient.tools(...)`. The LLM decides to invoke it via `tool_call` in its response. Different from "agent team" tools (PM/architect/etc.).
- **Tool BC**: a bounded context whose primary purpose is to expose one or more tools via HTTP for `rag-chat` to call. M8's `massing-gen` is the first tool BC.
- **Brief-to-massing vertical**: the M6+M7+M8 deliverable as a single user-facing capability — upload brief → chat about it → get massing.

## 1. Purpose

Fill in the `M6+ Agents TBD` placeholder in `docs/roadmap.md` with three concrete milestones that together deliver a useful **architectural early-stage automation** for design competition briefs:

1. **M6** extends M2's docs BC to accept PDF input — so a brief PDF can be uploaded the same way a `.md` document is.
2. **M7** introduces generic **LLM function-calling infrastructure** in rag-chat — so the chat surface can invoke external tools (not just answer with text).
3. **M8** ships the first domain-specific tool — a **`massing-gen` BC** that takes a brief and produces a Rhino `.3dm` file with a basic massing model satisfying the room area requirements.

The user-facing experience:
1. Architect uploads a competition brief (PDF) at `/docs/new` (with M6's PDF support).
2. Architect opens `/chat`, asks: "이 brief 보고 기본 매싱 만들어줘" — references the brief doc.
3. rag-chat's LLM responds with `tool_call(generate_massing, {briefDocId: ...})`.
4. M7's ToolDispatcher invokes M8's `/internal/tools/generate-massing`.
5. M8 reads the brief text via M2's existing `/internal/docs/{id}/body` endpoint, extracts room program via an LLM call, runs the massing algorithm, serializes a `.3dm` file, returns the file URL.
6. rag-chat folds the tool result back into the LLM context; LLM generates the final natural-language summary with a download link.
7. Architect downloads the `.3dm`, opens in Rhinoceros, uses it as the kickoff geometry for the actual design.

This spec **does not** describe per-milestone PRDs (user stories + acceptance criteria), per-milestone ADRs (library versions, exact algorithms, library pins), or designs (Figma frames). Those land in their canonical homes when each individual milestone cycle opens.

## 2. Scope summary

### In scope across M6+M7+M8 (P0)
- M2 docs BC accepts `.pdf` uploads with text extraction
- New SSE event types (`tool_call`, `tool_result`, `tool_error`) added to rag-chat
- rag-chat `ChatTurnUseCase` extended with Spring AI 1.0 function-calling
- `ToolCatalog` constants class + `ToolDispatcher` adapter in rag-chat
- ADR-08 Exception 4 added (rag-chat → tool BCs HTTP)
- New `massing-gen` BC with full 4-module quadruplet
- One Node sidecar container (`rhino3dm-bridge`) for `.3dm` serialization
- Brief-text → structured room program extraction via Qwen3-32B (re-use ADR-04 pin)
- Basic massing algorithm (rectangular stacking; first-fit + area balance)
- New `arch.outputs` table for generated `.3dm` artifacts
- Resilience4j circuit breaker per tool

### Deferred to M6.1 / M7.1 / M8.1 (P1, ship if cycle has slack)
- OCR for scanned PDFs (M6 P0 = text-PDFs only)
- Layout-aware PDF extraction (tables, columns) (M6.1)
- Tool progress streaming from tool BC back to rag-chat via SSE — currently P0 returns one JSON when done (M7.1)
- Parallel tool calls in one chat turn (M7.1)
- Tool result Redis caching (M7.1)
- Non-rectangular site footprint (M8.1)
- Atrium / setback / irregular floor plates (M8.1)
- Multi-floor optimization beyond first-fit (M8.1)
- Grasshopper parametric output (`.gh`) (M8.1)
- Tool ACL beyond auth-only (M7.1) — P0 = any signed-in user can invoke any registered tool

### Out of scope (P2 — future milestones)
- `.docx`, `.pptx`, other binary doc formats
- Dynamic tool registry (P0 = hardcoded in rag-chat-domain)
- External MCP server connection (Anthropic MCP, OpenAI plugins)
- User-defined tools
- Compliance verification mode (PDF + .3dm → PASS/FAIL report) — separate future milestone
- Non-massing architectural tools (floor plan generation, facade study, etc.)

## 3. Architectural shape (cross-milestone)

```
                                       [User browser]
                                              │
                                          /chat | /docs
                                              ▼
                                         [Gateway]
                                              │
              ┌───────────────────────────────┼───────────────────────────────┐
              │                               │                               │
              ▼                               ▼                               ▼
        [docs-api]                     [rag-chat-api]                   (other M1–M5)
       (M2 extended                   (M4 extended by                       │
        by M6: PDF)                   M7: tool-calling)                     │
              │                               │                             │
   Apache PDFBox                       Spring AI ChatClient                 │
   (text extraction                    .tools(toolCatalog)                  │
   in -infra)                          │                                    │
                                       │ LLM tool_call("generate_massing")  │
                                       ▼                                    │
                                ToolDispatcher                              │
                                (rag-chat-infra)                            │
                                       │                                    │
                                  WebClient                                 │
                                  HTTP POST                                 │
                                       ▼                                    │
                          [massing-gen-api]  ◄── M8 NEW BC                  │
                                       │                                    │
                                       ▼                                    │
                         ┌─────────────┴───────────────┐                    │
                         │                             │                    │
              read brief body                 LLM call                      │
              (M2 /internal/                  (Qwen3-32B)                   │
              docs/{id}/body —                program JSON                  │
              Exception 1)                    extraction                    │
                         │                             │                    │
                         └─────────────┬───────────────┘                    │
                                       ▼                                    │
                            MassingAlgorithm                                │
                            (-domain, Spring-free)                          │
                                       │                                    │
                                       ▼                                    │
                         [rhino3dm-bridge]  ◄── Node sidecar                │
                         (single sidecar                                    │
                          container)                                        │
                                       │                                    │
                                       ▼                                    │
                              .3dm bytes saved                              │
                              into arch.outputs                             │
                              (new Postgres table,                          │
                              schema-per-BC per ADR-05)                     │
                                       │                                    │
                                       │ HTTP response {fileUrl, ...}        │
                                       └──────► back to ToolDispatcher       │
                                                       │                     │
                                                       ▼                     │
                                          rag-chat folds tool result          │
                                          into next LLM call                  │
                                                       │                     │
                                                       ▼                     │
                                            SSE token stream to browser      │
                                            (with tool_result event +       │
                                             link to .3dm)                  │
```

**Transport policy** (ADR-08 amendment in ADR-17):
- Synchronous tool calling is HTTP (Exception 4 — sanctioned for LLM function-calling sync semantic).
- Tool BCs register at `/internal/tools/<tool-name>` POST endpoints.
- Each new tool BC adds a sub-row to Exception 4 (massing-gen is the first).
- Kafka NOT used for tool calls (async semantic breaks LLM context flow).
- gRPC NOT introduced (stack consistency; WebClient HTTP fits).

**BC ownership clarity**:
- `rag-chat`: owns tool catalog (what tools the LLM sees), tool dispatcher (how to call them). Does NOT implement any tool logic.
- `massing-gen`: owns the massing tool's implementation (brief reading, program extraction, algorithm, .3dm serialization, file storage). Does NOT know about `rag-chat`.
- `docs`: extended in M6 for PDF; otherwise unchanged. Massing-gen reads from docs via existing Exception 1.

## 4. M6 — PDF in docs BC

### Goal
M2 docs BC accepts `.pdf` uploads. The uploaded PDF's text content is extracted server-side and stored in the existing `body TEXT` column. M3's RAG ingestion pipeline (Kafka consumer + chunking + embedding) processes the extracted text exactly like Markdown content — no M3 code change.

### Scope (in)
- `docs-api` upload endpoint accepts `application/pdf` MIME type
- `docs-infra` adds `PdfExtractorAdapter` using Apache PDFBox (Apache 2.0 license, mature, no new container needed — runs in-process)
- `docs.documents` schema gains an optional `mime_type` column (default `text/markdown`; new uploads set `application/pdf` when applicable)
- For PDF uploads, the workflow is:
  1. Receive `multipart/form-data` with `.pdf` file
  2. PdfExtractor → linear text (PDFBox `PDFTextStripper.getText()`)
  3. Store the extracted text in `body` (treated as Markdown — same downstream as M2 ships)
  4. `mime_type = 'application/pdf'` for the row
  5. Original PDF binary handling: **deferred to M6.1** (P0 doesn't persist the binary; only the extracted text is retained)
- Frontend `/docs/new` file picker accepts `.pdf` in addition to `.md`; doc detail page shows `(PDF)` indicator when `mime_type = 'application/pdf'`

### Scope (out — deferred to M6.1)
- OCR for scanned PDFs (P0 PDFBox-only handles text-PDFs)
- Layout-aware extraction (tables, columns) — P0 uses default PDFBox linearization
- Original PDF binary download surface — M8 will need it, but M6 P0 doesn't ship it. M6.1 (or as part of M8 work) adds the binary storage decision.

### Scope (out — P2)
- `.docx`, `.pptx`, other binary formats
- Image extraction from PDFs

### Dependencies
M0, M1, M2 (M2 must be merged, including the BlockNote editor work currently in flight on the m2 implementation session)

### Key open questions for ADR-16
- Apache PDFBox version pin
- `mime_type` column migration (Flyway V20260520xxxx__add_mime_type.sql)
- Text extraction quality testing approach (Korean PDF samples needed)
- Failure modes (corrupted PDF → 400 with what error code?)
- Binary storage decision (P0 defer vs include) — likely picked up by M8's needs

### Sizing
1–2 weeks. Small single-BC extension. One library, one migration, one new MIME branch in the upload handler. Worth shipping early so M8 has something to feed into.

## 5. M7 — rag-chat tool-calling infrastructure

### Goal
rag-chat (M4) gains the ability to invoke external tools via LLM function-calling. Generic infrastructure — domain-neutral. The same machinery that calls `massing-gen` in M8 will call any future tool BC (e.g., `slide-gen`, `image-gen`).

### Scope (in)
- `ToolCatalog` constants class in `rag-chat-domain`:
  ```java
  public static final ToolDescriptor GENERATE_MASSING = new ToolDescriptor(
      "generate_massing",
      "Given a brief document ID, extract the room program and generate a basic stacked massing model. Returns a Rhino .3dm file URL and a summary.",
      jsonSchema("""
          { "type": "object", "required": ["briefDocId"], "properties": {
              "briefDocId": { "type": "string", "format": "uuid" },
              "siteWidth": { "type": "number" },
              "siteDepth": { "type": "number" },
              "floorHeight": { "type": "number", "default": 3.5 }
          }}"""),
      URI.create("http://massing-gen-api:18086/internal/tools/generate-massing"),
      Duration.ofSeconds(30)
  );
  ```
  Tools are hardcoded constants in P0. Dynamic registry is P2.
- `ToolDispatcher` adapter in `rag-chat-infra`:
  - Spring WebFlux `WebClient` per-tool (configured by descriptor URL + timeout)
  - Resilience4j circuit breaker per tool (5xx >50% in 60s → OPEN for 30s)
  - Auth header forwarding: `X-User-Id` from the originating chat session forwarded to tool BC (so tool can do tenant-scoped reads)
- `ChatTurnUseCase` extended for function-calling:
  - Passes `toolCatalog.descriptors()` to `ChatClient.tools(...)`
  - If LLM response is `tool_call`, invokes `ToolDispatcher.invoke(name, args)`, gets result
  - Feeds result back to LLM via Spring AI's tool-result message
  - Multi-turn: LLM might call multiple tools in sequence (P0 = serial; M7.1 = parallel)
  - Maximum tool-call depth per user turn: **5** (configurable env var) — prevents runaway loops
- **SSE event grammar extension** (amends ADR-14 §5.2):
  ```
  event: tool_call          // 1+ per turn, optional (UX transparency)
  data: { "name": "generate_massing", "args": { ... } }

  event: tool_result        // 1 per tool_call
  data: { "name": "generate_massing", "summary": "...", "outputUrl": "..." }

  event: tool_error         // alternative terminal for tool failure
  data: { "name": "generate_massing", "code": "TIMEOUT|5XX|...", "message": "..." }
  ```
- ADR-08 amendment adding **Exception 4** (`rag-chat` → tool BCs HTTP). Subsequent tool BC additions become sub-rows of Exception 4.
- **Frontend `tool_result` rendering — structured tool-result card.** When the SSE stream emits a `tool_result` event, the chat UI renders a structured card **below** the LLM's natural-language assistant message that triggered the tool. The card contains:
  - Tool display name (e.g., `📁 generate_massing`)
  - The `summary` field (one-line description)
  - A primary action button — typically **Download** when the payload carries `outputUrl`, but may vary per tool (e.g., `Open document`, `Preview`)
  - Optional `programJson` / metadata expandable accordion (per-tool design choice)

  The card co-exists with the LLM's natural-language text — the LLM still describes what it did in prose; the card surfaces the artifact handle. Visual design (file icon, spacing, color tokens, hover state) is **each tool's Stage-2 design responsibility** — M8 Stage-2 design pins the `generate_massing` card visual; future tool BCs (e.g., `slide-gen`, `image-gen`) each pin their own.

  Backend contract: `outputUrl` in the `tool_result` payload **must** be a relative URL like `/api/arch/outputs/{id}` so the browser's session cookie (gateway-issued from M1) carries `X-User-Id` to the tool BC's download endpoint automatically. The Download button is a plain `<a href={outputUrl} download>` — no JS fetch needed.

### Scope (out — M7.1)
- Parallel tool calls in one chat turn
- Streaming progress from tool BC back to rag-chat (P0 = single JSON when done)
- Tool result Redis caching (idempotency for repeated identical calls)
- Tool ACL beyond auth-only (any signed-in user can call any tool in P0)
- Tool-specific rate-limiting (chat-level rate limit covers it in P0)

### Scope (out — P2)
- External MCP server connection (Anthropic MCP, OpenAI plugins)
- User-defined tools (UI for tool registration)
- Dynamic tool catalog (config-file or DB-backed registry)

### Dependencies
M0, M1, M2, M3, M4 (M4 must be shipped — M4 implementation is in flight in another session)

### Key open questions for ADR-17
- Spring AI 1.0 GA function-calling API stability (current `ChatClient.tools(...)` shape vs future-proof)
- Spring AI's tool error handling — what happens when tool returns 5xx? Does Spring AI retry? Configurable?
- Maximum tool-call depth default (P0 working: 5)
- SSE event ordering guarantees during multi-turn tool calls
- Tool result max size (LLM context budget consideration)
- Cost accounting — tool calls inflate prompt size; rate limiter granularity stays unchanged?

### Sizing
2–3 weeks. Generic infra with substantial test surface (WireMock per-tool, multi-turn flow validation, circuit breaker scenarios). M7 itself doesn't add a user-visible feature — it enables M8. Worth pinning version isolation (don't bundle with M8) for clean validation.

## 6. M8 — `massing-gen` BC

### Goal
First domain-specific tool BC. Exposes a `generate_massing` tool that converts a brief PDF document into a `.3dm` massing file. Implements the brief-to-massing vertical's domain logic end-to-end. Registers itself in rag-chat's `ToolCatalog`.

### Scope (in)
- New 4-module quadruplet: `massing-gen-{api, app, domain, infra}` (port `18086` candidate, ADR-18 confirms)
- HTTP endpoint `POST /internal/tools/generate-massing`:
  - Body: `{ briefDocId: uuid, siteWidth?: number, siteDepth?: number, floorHeight?: number }`
  - Returns: `{ fileUrl, programJson, totalAreaM2, floorCount, summary }`
- Brief reading via M2's existing `/internal/docs/{id}/body` endpoint (ADR-08 Exception 1 — widened from "M3 only" to "M3 + massing-gen")
- `BriefProgramExtractor` (in `-app`) — Qwen3-32B call with a domain-specific prompt:
  - System prompt: "Korean architecture competition brief. Extract site dimensions + room program (실명, 면적[㎡]). Output validated JSON Schema."
  - LLM output is JSON-Schema-validated before proceeding (Jackson + everit-json-schema)
- `MassingAlgorithm` (in `-domain`, Spring-free):
  - Inputs: room program, site footprint, floor height, height limit (optional)
  - Algorithm (P0): rectangular first-fit + area balance
    1. Total floor area = sum of all `room.area_m2`
    2. Floor count = ceil(total / site_footprint)
    3. Allocate rooms to floors balanced by area; pack rectangles greedily per floor
  - Output: `List<RoomBox{ floor, x, y, width, depth, height, roomName }>`
- New Postgres schema `arch` + table `arch.outputs`:
  ```sql
  CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK)
    file_bytes      BYTEA        NOT NULL,                  -- the .3dm binary
    program_json    JSONB        NOT NULL,                  -- the extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
  );
  ```
- File download endpoint `GET /api/arch/outputs/{id}` (authenticated, owner-only). Returns `application/octet-stream` with `Content-Disposition: attachment; filename="massing-<briefSlug>-<timestamp>.3dm"` header so the browser triggers a download dialog rather than inline render.
- **Tool result card surface in /chat**: per M7's frontend rendering contract, the `tool_result` event from `generate_massing` materializes a card below the LLM message containing tool name, summary (e.g., "12 rooms, 3 floors, 480 m² total"), and a **Download .3dm** button linking to `outputUrl`. M8 Stage-2 design pins the exact card visual (file icon, accent token, summary field layout). The LLM still produces natural-language commentary above the card; the card just gives the click target.
- `Rhino3dmAdapter` (in `-infra`) calls the `rhino3dm-bridge` sidecar (Node 18 + `rhino3dm` npm package) via HTTP. Adapter sends JSON `[{x,y,w,d,h}, ...]`; sidecar returns `.3dm` binary.
- `rhino3dm-bridge` sidecar container added to `infra/docker-compose.yml`:
  - Image: build from `node:18-alpine` + `rhino3dm` package
  - Single HTTP endpoint: `POST /serialize { boxes: [...] } → application/octet-stream .3dm`
  - Compose-internal port `4000`, not exposed to host
- Tool registration: `MassingTool` descriptor added to `rag-chat-domain.ToolCatalog` (this is a same-PR addition since M7 owns the catalog)
- Resilience4j circuit breaker on `rhino3dm-bridge` (5xx >50% in 60s)

### Scope (out — M8.1)
- Non-rectangular site footprint (P0 = rectangular only)
- Atrium, setbacks, irregular floor plates
- Multi-floor optimization beyond first-fit
- User input hints ("2nd floor = cafeteria")
- Grasshopper parametric output (`.gh` file)
- Cover image / thumbnail preview of the massing

### Scope (out — P2)
- LLM-direct OpenNURBS command generation
- Compliance verification mode (existing .3dm + brief → PASS/FAIL)
- Floor plan generation (rooms with walls)
- Facade studies

### Dependencies
M0, M1, M2 (extended in M6), M3, M4 + M7

### Key open questions for ADR-18
- `.3dm` library — confirm `rhino3dm.js` Node sidecar vs alternatives (openNURBS C JNI, rhino3dm.py Python sidecar). Working assumption: Node sidecar (smallest activation energy, active library).
- LLM prompt template for Korean brief extraction (corpus needed)
- Output JSON Schema for program (rooms, site, FAR, height_limit — what's required vs optional)
- Massing algorithm tolerance — what if extracted total area exceeds site × max_floors? Error vs auto-adjust?
- File storage — `arch.outputs.file_bytes` BYTEA (P0 working) vs object storage container (Minio, deferred)
- Cleanup policy — orphaned `arch.outputs` rows when brief doc is deleted (cascade? soft-delete?)
- Naming the BC: `massing-gen` (functional) vs `arch-massing` (domain-prefixed) vs other. Lean toward `massing-gen` to match `rag-ingestion` / `rag-chat` functional-prefix pattern.
- Tool descriptor parameter granularity (more fine-grained vs minimal — P0 working: minimal)
- Cost protection — massing-gen makes an LLM call per request; need per-user rate limit? (P0: chat-level limit covers it)

### Sizing
4–6 weeks. Largest of the three milestones. LLM prompt engineering + algorithm + Node sidecar + `.3dm` serialization + storage = 5 substantial pieces. Likely split into sub-slices internally.

## 7. Cross-cutting concerns

### ADR-08 amendment (in ADR-17)

Add Exception 4 — `rag-chat` → tool-implementer BCs HTTP for LLM function-calling:

> **Exception 4.** `rag-chat-api` → tool-implementer BCs HTTP. Sanctioned because synchronous user-facing chat requires synchronous tool result to feed back into the next LLM turn; Kafka's async semantics breaks the LLM context flow. Tool BCs register at `/internal/tools/<tool-name>` POST endpoints. Each new tool BC adds a sub-row below.
>
> | Caller | Callee | Endpoint | Purpose |
> |---|---|---|---|
> | `rag-chat-api` | `massing-gen-api` | `POST /internal/tools/generate-massing` | Brief PDF → .3dm massing |

### Existing ADR-08 Exception 1 widening (in ADR-18)

Exception 1 currently says "M3 only" reads docs via internal HTTP. Widen to "M3 + M8":

> Exception 1: `rag-ingestion-api` AND `massing-gen-api` → `docs-api` HTTP for fetching extracted body text. Sanctioned because Kafka payloads can't carry large doc bodies; both consumers need the same `/internal/docs/{id}/body` endpoint.

### SSE event grammar (in ADR-17 → amends ADR-14)

ADR-14's SSE grammar (M4 spec §5.2) gains 3 new event types:
- `tool_call`
- `tool_result`
- `tool_error`

Existing `retrieval` / `token` / `done` / `error` are unchanged. Frontend (post-M4 implementation) needs to handle the new events; M7 PR set includes the frontend changes.

### `.3dm` library decision (in ADR-18)

Three candidates evaluated; ADR-18 picks one:
- `rhino3dm.js` Node sidecar (**working assumption**) — most active, npm-installable, Node 18 base
- OpenNURBS C via JNI — most mature, but JNI integration ugly, build complexity high
- `rhino3dm.py` Python sidecar — wheels available, similar profile to Node option

Pick Node by default; revisit if perf or memory becomes an issue.

### Tool descriptor governance

Tool catalog lives in `rag-chat-domain` as a constants class. Adding a new tool means:
1. New tool BC defines its endpoint contract
2. PR to `rag-chat-domain.ToolCatalog` adds the descriptor constant
3. ADR-08 Exception 4 sub-row gets a new row
4. M7's ToolDispatcher picks it up via classpath (no runtime registration in P0)

This is intentionally low-tech for personal-scale. Dynamic registries are P2.

## 8. Per-milestone open-question summary

### M6 (PDF in docs) — ADR-16 to close
- Apache PDFBox version pin
- `mime_type` column migration
- Korean PDF text extraction quality test corpus
- Corrupted PDF error semantic
- Binary storage decision (defer or include)

### M7 (tool-calling) — ADR-17 to close
- Spring AI 1.0 `ChatClient.tools(...)` API stability
- Tool error handling (Spring AI retry policy)
- Maximum tool-call depth (P0 working: 5)
- SSE event ordering guarantees in multi-turn tool calls
- Tool result max size
- Cost accounting for tool-inflated prompts

### M8 (massing-gen) — ADR-18 to close
- `.3dm` library pin (Node sidecar vs alternatives)
- LLM prompt template for Korean brief extraction
- Output JSON Schema for program
- Massing algorithm tolerance (over-area handling)
- File storage (BYTEA vs object storage)
- Orphan cleanup policy
- BC naming
- Per-user rate limit on massing-gen (likely none, chat-level covers it)

## 9. Dependencies + execution order

```
M5 implementation (in flight in other sessions)
   │
   │ (no direct dependency — M5 is metrics, unrelated)
   │
M6 (PDF in docs)  ← depends on M2 shipped (currently in flight)
   │
M7 (tool-calling)  ← depends on M4 shipped (currently in flight)
   │
M8 (massing-gen)  ← depends on M6 + M7
```

M6 and M7 can proceed in parallel (different BCs touched). M8 strictly waits on both.

Tasks 1–4 of each milestone's plan (PRD / ADR / GitHub issues / Stage-2 design) can run in parallel sessions. Implementation tasks (5+) block on the doc/design phase + their explicit dependencies.

## 10. Acceptance criteria (per milestone)

### M6 — PDF in docs
- [ ] `POST /api/docs/upload` accepts `.pdf` files (`Content-Type: application/pdf`)
- [ ] Extracted text appears in `docs.documents.body`; `mime_type = 'application/pdf'`
- [ ] M3 RAG ingestion (existing) processes PDF-derived text identically to MD content
- [ ] Frontend `/docs/new` file picker accepts `.pdf`
- [ ] Doc detail page shows `(PDF)` indicator for PDF-sourced docs

### M7 — tool-calling
- [ ] `rag-chat` chat session with LLM tool_choice="auto" can invoke a registered tool via WireMock-stubbed endpoint
- [ ] SSE stream emits `tool_call` → `tool_result` → `token` events in order
- [ ] Circuit breaker opens on stubbed 5xx and recovers via half-open probe
- [ ] Tool descriptor list passes Spring AI function-calling schema validation
- [ ] Multi-turn tool call (LLM calls tool A → result → calls tool B → result → text) flows correctly
- [ ] Maximum depth (5) enforcement triggers `tool_error` with `MAX_DEPTH_EXCEEDED`

### M8 — massing-gen
- [ ] `POST /internal/tools/generate-massing` with a valid brief doc id returns a `.3dm` file URL + program JSON + summary
- [ ] Generated `.3dm` opens in Rhino without errors and contains room boxes labeled by name
- [ ] Total area of generated boxes ≥ sum of required room areas (within tolerance)
- [ ] No box exceeds site footprint
- [ ] Floor count = ceil(total / site)
- [ ] Tool registered in rag-chat's `ToolCatalog`; end-to-end chat → tool → file URL works
- [ ] `arch.outputs` row persisted with owner = caller's `X-User-Id`
- [ ] Cleanup of orphan rows when brief doc is deleted (cascade or soft-delete per ADR-18)

## 11. Risks + mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Spring AI 1.0 function-calling API changes between Spring AI minor versions | Medium | High (M7 broken) | Pin Spring AI version in ADR-17; lock until M8 ships |
| `rhino3dm.js` Node sidecar perf insufficient (single-thread Node) | Low | Medium | M8 only generates one file per chat invocation; concurrency is rate-limited at chat layer |
| LLM (Qwen3-32B) accuracy on Korean brief extraction | Medium-High | High (M8 outputs incorrect program) | M8 ADR pins extraction prompt template + JSON Schema validation; allow architect to review extracted program before generation in M8.1 |
| `.3dm` BYTEA storage growing unbounded | Low (personal scale) | Medium | M8.1 introduces an `arch.outputs.expires_at` + nightly cleanup of rows older than N days |
| Tool calls inflate chat token cost (rate limit hit faster) | Medium | Low | M7 doesn't change M4's rate limit; if observed problem, M7.1 adds per-tool token budget |
| User uploads a non-brief PDF expecting massing | High | Low (LLM extraction will produce sparse program, algorithm will fail or give weird output) | M8 PRD includes a "summary" field that the LLM uses to tell the user "couldn't find room program in this brief — is this the right PDF?" |
| M2 / M4 implementation in flight in other sessions might delay M6 / M7 | Medium | Medium | M6 and M7 brainstorm/PRD/ADR/design can proceed in parallel sessions; only implementation waits for their respective dependencies |

## 12. Supersession + downstream amendments

The M6/M7/M8 PRD/ADR PR sets must apply:

| File | Section | Change | Triggered by |
|---|---|---|---|
| `docs/roadmap.md` | §M6+ row (the table at top) + §M6+ block | Replace single `| M6+ | Agents | TBD | deferred |` row with three rows for M6/M7/M8. Replace `## M6+ — Agents` block with three new sections — one per milestone. | M6 PRD PR (the earliest of the three) |
| `docs/adr/00-overview.md` | Index | Add rows for ADR-16, ADR-17, ADR-18 as each lands. Update module count line (M7 adds 0 modules — chat extension; M8 adds 4 modules — new BC; M6 adds 0 — docs extension). | Each respective ADR PR |
| `docs/adr/08-inter-service-comms.md` | New "Exception 4" section + Exception 1 widening | Per §7 above. | ADR-17 PR (Exception 4) and ADR-18 PR (Exception 1 widening) |
| `docs/adr/14-m4-rag-chat.md` | §5.2 SSE grammar | Add `tool_call` / `tool_result` / `tool_error` event types. | ADR-17 PR |
| Sidebar `Architecture` / `건축` row (frontend) | M5-style row pattern | Add a new sidebar Apps row routing to the new architecture surface. | M8 PR (frontend implementer) |
| `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` §"Features (initial roadmap)" | Roadmap table | Update the M6+ row to reflect the three new milestones. | M6 PRD PR (first to ship) |

## 13. Migration from "M6+ Agents TBD"

The original `M6+ — Agents — TBD (future cycles)` placeholder in `docs/roadmap.md` (and in `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`) referred ambiguously to either (a) the agent team used to BUILD playground (already operational — PM/architect/etc.) or (b) AI agents acting on behalf of users in the product.

This spec **resolves the ambiguity by closing M6+ as a feature placeholder** and replacing it with three concrete domain-tool milestones. The placeholder issue #31 (`M6+: Define scope, acceptance, and design for the Agents milestone`) is closed by M6 PRD PR with a comment pointing here.

If at some future point real "AI agents acting autonomously on behalf of users" is desired as a feature (vs. tools the user explicitly invokes via chat), that becomes a P2+ concept — distinct from M6/M7/M8.
