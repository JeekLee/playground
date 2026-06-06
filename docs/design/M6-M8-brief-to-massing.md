# Design Context: M6 + M7 + M8 — Brief-to-Massing Vertical (unified)

> PRD/ADR: TBD per milestone (M6 → PRD/ADR-16; M7 → PRD/ADR-17; M8 → PRD/ADR-18). Each cycle opens individually after this design lands.
> Spec: `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` (the canonical source — 501 lines after PR #165 amendments locked the `tool_result` card render contract).
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md`
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN (M6+M7+M8 row at y = 4500; the 6 frames sit on a single horizontal track at y = 4500, with 50 px gaps between frames).
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill, brand row), `docs/design/M2-docs.md` (file picker UI, doc detail layout, `+ New document` dropdown pattern), `docs/design/M4-rag-chat.md` (the entire `/chat` chrome — sidebar with Chat active, topbar with breadcrumb, tab strip, viewport-locked composer, user/assistant turn primitives). All chrome is reused verbatim; M6 + M7 + M8 only introduce what is new (the `(PDF)` badge, the `tool_result` card primitive, the per-tool variants of that card).

> **Amendment 2026-05-21 (M6 PRD cycle)** — M6 PRD (`docs/prd/M6-docs-pdf.md`) closed the spec §4 "binary storage / page-count" open question: the documents schema gains `mime_type` only — **`pdf_page_count` is NOT introduced**. PDFs are converted to markdown (via PDFBox text extraction + Vision LLM OCR fallback per page) and the page count concept does not survive that conversion. Consequently the doc-detail meta row in §2.6 drops the `(12 pages)` parenthetical — the tail is `· source: .pdf` only. §6.2 has no `(N pages)` cells to amend (the upload-error matrix never carried the page-count substring). This amendment touches §2.6 only (three occurrences inline in the prose); all other M6/M7/M8 surfaces are unchanged.

> **Amendment 2026-05-22 (M6.1 — async extraction + MinIO + SSE).** M6.1 retrofits async extraction onto every PDF/MD upload (ADR-12 amendment 2026-05-22 §A12.2 + §A12.5). The M6 PRD's synchronous-request-thread shape is retired; the user's POST returns within milliseconds, the extraction runs on a dedicated `ExecutorService(n=5)` inside docs-api, and the doc detail page subscribes to a Server-Sent Events stream. This amendment threads three UX shifts through the existing M6 frames; **no new frames are created**, no token vocabulary changes.
>
> **Frame impact:** the M6 dropdown overlay (`78:1531`), the M6 doc detail with `(PDF)` badge (`78:1552`), and the M2 frames `30:859` / `30:860` / `31:66` / `36:191` / `36:246` that this design doc inherits from `docs/design/M2-docs.md` all receive textual + small-component amendments per the M2-docs amendment 2026-05-22 (which is the canonical home for the upload-UX details — that doc owns the upload flow, this doc owns the chat/tool-result flow). Frames `78:1329`, `78:1347`, `78:1392`, `78:1437` (the chat-side M7 + M8 frames) are **unchanged by M6.1** — the chat surface doesn't see the extraction pipeline directly; it sees the materialized body via the existing `[doc:{id}] {title}` context-injection pattern.
>
> **Shift A — §2.1 dropdown row 2 behavior post-upload.** The M6 dropdown's row 2 (`↑ Import .md or .pdf…`) previously navigated to `/docs/{id}` showing the populated doc immediately (since extraction was synchronous on the request thread). Post-M6.1, the click still navigates to `/docs/{id}` but the first paint shows the **Analyzing…** skeleton state (defined in `docs/design/M2-docs.md` amendment 2026-05-22 — slim `surface.soft` + `accent` border pill in the topbar-adjacent strip with copy `⏳ Analyzing… <N> pages` for PDFs). On SSE `completed` event the page reloads the body; on `failed` it shows a `danger`-bordered error card with the failure reason + a `Retry extraction` ghost button + the always-visible download-original affordance.
>
> **Shift B — §2.6 doc detail (PDF badge) gains the "Download original" affordance.** The `(PDF)` badge inline next to the title is preserved verbatim from §2.6. **New M6.1 addition:** to the right of the existing URL pill on the same row as the author block, a new `↓ Original .pdf` button — `surface` bg + `border` 1px + `radius.pill` 999, padding `8px 16px`, label in 11/500/`text.muted`. Renders only when the `documents` row was uploaded as a file (signal: `mime_type !== 'text/markdown'` OR the DTO carries an explicit `hasOriginal` boolean — implementer's call). Clicking it calls `GET /api/docs/{id}/source` which streams from the new MinIO sidecar (`minio-playground` per ADR-12 §A12.4) with the doc's visibility check applied (public → anyone via PLAYGROUND_ANON; private → owner only). Filename comes from the server's `Content-Disposition` header (`{title-slugified}.pdf`), so the saved file uses the doc title, not the UUID object key.
>
> **Shift C — §2.6 Analyzing… state coexists with the (PDF) badge.** While `extraction_status='processing'` (i.e., right after the upload, before the worker completes), the doc detail page renders:
>
> - **Title slot:** the filename stem (e.g., `KFI-청사-확충`) in the title typography — the title is populated synchronously at upload time, the body is not.
> - **`(PDF)` badge inline with the title:** rendered immediately (the `mime_type` column is set at upload-INSERT time).
> - **Author block:** rendered (author identity is known at upload time).
> - **URL pill + Original .pdf button:** rendered (both work pre-extraction).
> - **Analyzing… status pill** (NEW per Shift A): between the topbar and the article block, on a 4px-padded strip on `bg`, label `⏳ Analyzing… 23 pages` (the `23` is the PDFBox-derived page count, surfaced as a transient field via the DTO).
> - **Body region:** the standard "Loading" skeleton (6 paragraph skeletons matching the article block geometry) per the M2 `/docs/{id}` loading state. **NOT a hard `Loading…` placeholder** — the visual continuity is "this doc exists, we're filling in the body".
>
> On SSE `completed`:
> - The Analyzing pill closes (200ms fade or instant — implementer's call).
> - The body region's skeleton swaps for the populated Markdown render.
> - A 2s `success.soft` chip toast `✓ Analysis complete` appears in the topbar (implementer's standard toast slot).
> - The `(PDF)` badge stays.
>
> On SSE `failed`:
> - The Analyzing pill swaps to a `danger`-soft pill with copy `⚠ Analysis failed` (same `radius.pill`, swap `accent` → `danger`).
> - The body region's skeleton swaps for a `danger`-bordered error card centered in the article column: title `Could not extract this PDF` in `font.h3` 16/600/`text`; body `<failure reason from server>` in `font.body` 15/400/`text.muted`; below that, a row with `Retry extraction` ghost button + `Open original` link button (the latter just routes to `GET /api/docs/{id}/source`).
> - The `(PDF)` badge stays.
> - **The user retains access to the original bytes** — the MinIO blob is intact, the download-original affordance works, the doc is recoverable. (This is the user-visible payoff of ADR-16 amendment §A16.4 — partial reversal of §13's "discard original bytes" policy.)
>
> **Reused / unchanged:**
>
> - **§2.2 — Generic `tool_result` card primitive (frame `78:1329`):** unchanged. The card pattern's slot anatomy is independent of the extraction pipeline.
> - **§2.3 — M8 happy-path chat with `tool_result` (frame `78:1347`):** unchanged. The chat invokes `generate_massing` on an already-extracted doc; M6.1's async extraction is upstream of `/chat`.
> - **§2.4 — Expanded program-details accordion (frame `78:1392`):** unchanged.
> - **§2.5 — `tool_error` card (frame `78:1437`):** unchanged. M6.1 does add a new error class on the docs side (`EXTRACTION_FAILED` surfacing in the doc detail per Shift C), but the chat-side `BRIEF_EXTRACTION_FAILED` (M8) is a different error class — it fires when the user asks M8 to generate massing from a doc whose extraction completed but yielded no room program. The two error surfaces are independent; the doc detail's "Analysis failed" state is a precondition the chat doesn't need to handle (a user can only chat against `completed` docs anyway — the chat composer's `[doc:{id}]` context-injection rejects `processing` / `failed` docs upstream).
>
> **Token usage:** every M6.1 addition reads from the existing token vocabulary already cataloged in §5 ("Tokens used — only existing; zero new"). Specifically:
>
> - Analyzing… status pill: `surface.soft` + `accent` (border + label) + `radius.pill` + `font.small`.
> - "Analysis failed" status pill: same except `accent` → `danger`.
> - Download-original button: `surface` + `border` + `radius.pill` + `font.small`/500/`text.muted`.
> - "✓ Analysis complete" success toast: `success.soft` bg + `success` border + `radius.md` + `font.small`/500/`success` fg (standard M1 toast vocabulary).
> - Error card replacing the body: `danger`-bordered card per the standard `danger` color treatment from the design system spec §6.3.
>
> Zero new tokens, zero new layout primitives, zero new components beyond the inline pill + button additions.
>
> **Operator-side rename hint:** the existing `78:1531` (M6 dropdown) and `78:1552` (M6 doc detail) frames don't need rename. Their state annotations could be updated via `set_text_content` to mention "Analyzing… skeleton on first paint", but this is optional — the M2-docs amendment owns the canonical upload-UX prose.

> **Amendment 2026-05-22 (M8 PRD + ADR-18 cycle) — `summary` string is Korean-fixed at render time.** The design doc's reference value `12 rooms · 3 floors · 480 m² total` (used in §2.2 / §2.3 / §2.4 / §6.5 prose) is the **slot anatomy reference** — it documents the spacing, separator (`·`), and three-number layout. ADR-18 §5 pins the actual rendered string as Korean-fixed `"%d실 · %d층 · 총 %.0f m²"` (e.g., `12실 · 3층 · 총 480 m²`) — the playground UI is Korean-primary (M4 chat composer accepts Korean; the surrounding LLM prose is Korean). The visual treatment (font.small 13/500/text, accordion below, button anchored top-right) is **unchanged** — only the literal text in the summary slot changes to Korean. Frontend implementers render `MassingSummary.format(...)` server-side and pipe the result into the slot. Frame `78:1347` (Figma) still shows the English-anatomy text; the operator may update via `set_text_content` to `12실 · 3층 · 총 480 m²` for asset accuracy, but this is optional — the design **slot contract** is unchanged.

Stage 2 output for the **brief-to-massing vertical** (M6 + M7 + M8 unified). **6 desktop frames at 1440 × 900**, built strictly against the playground design system tokens. The vertical's visual story:

> architect uploads a brief PDF (M6) → opens `/chat` → asks `이 brief 보고 매싱 만들어줘` → LLM invokes `generate_massing` tool (M7 infra) → result card appears (M8 visual) → architect clicks **Download .3dm** → opens in Rhino.

The 6 frames span this whole flow as a single design narrative.

**Why unified?** M6 is a tiny extension (one MIME branch + one inline badge), M7 has no proper UI of its own (just SSE event handling), M8 has the real visual work. Bundling them produces a clearer narrative than three fragmented design docs — and a single token-table audit. Each milestone's per-feature PRD/ADR will still ship on its own cadence; this doc is the **shared visual contract**.

> **Asset note (carried from M1 / M2 / M4 / M5 design rounds):** PNG export via `mcp__TalkToFigma__export_node_as_image` returns the image to the harness as inline visual content rather than as text. The Figma file is the canonical visual reference; the operator one-off renders `docs/design/assets/M6-M8/{m6-docs-new,m7-tool-result-primitive,m8-massing-success,m8-massing-expanded,m8-massing-error,m6-doc-detail-pdf}.png` via `File → Export selected → PNG @ 2x` per the same workflow used in M1 / M2 / M4 / M5. The 6 frames are fully assembled and verified via per-frame export-and-visualization during the design run.

> **Frame-name limitation (carried from M2 / M4):** the Talk to Figma plugin allowlist exposes `set_text_content` for TEXT nodes but no frame-rename tool. **Two of the six frames carry the stale clone name `M8 — /chat with completed generate_massing tool result card  /chat`** because they were built by cloning Frame 3 (78:1347) and rewriting interiors. The frame's **internal content** is correct for its intended state; the human reviewer should right-click → Rename in Figma before exporting PNG assets. Mapping table in §"Frame mapping" below.

## Frame mapping (6 frames)

| # | Frame node-id | Current Figma name | Intended name | Position (x, y) | Created via |
|---|---|---|---|---|---|
| # | Frame node-id | Current Figma name | Intended name | Position (x, y) | Created via |
|---|---|---|---|---|---|
| 1 | `78:1531` | `M2 — + New document dropdown (overlay)  /docs` (stale clone of `30:859`) | `M6 — + New document dropdown (overlay, .md or .pdf)  /docs` | (0, 5500) | `clone_node` of M2's `30:859` + `set_text_content` on the Import row + state annotation |
| 2 | `78:1329` | `M7 — Generic tool_result card pattern (DS primitive)  global` | (same — built fresh) | (1490, 4500) | `create_frame` |
| 3 | `78:1347` | `M8 — /chat with completed generate_massing tool result card  /chat` | (same — built fresh) | (2980, 4500) | `create_frame` |
| 4 | `78:1392` | `M8 — /chat with completed generate_massing tool result card  /chat` (stale clone) | `M8 — /chat with tool_result card EXPANDED (program details)  /chat` | (4470, 4500) | `clone_node` of Frame 3 |
| 5 | `78:1437` | `M8 — /chat with completed generate_massing tool result card  /chat` (stale clone) | `M8 — /chat with tool_error card (generation failed)  /chat` | (5960, 4500) | `clone_node` of Frame 3 |
| 6 | `78:1552` | `M2 — Document detail (public)  /docs/public/{slug}` (stale clone of `31:66`) | `M6 — Document detail (PDF source) with (PDF) badge  /docs/{id}` | (1490, 5500) | `clone_node` of M2's `31:66` + title/meta `set_text_content` + new `(PDF)` badge rectangle+text |

The fresh-built frames (`create_frame`) carry their final names. The four cloned frames (1, 4, 5, 6) carry the stale parent name and need an operator manual rename (cursor-talk-to-figma plugin has no `set_node_name` tool).

**Superseded frame note:** an earlier iteration of this design built Frame 1 (`78:1280`) and Frame 6 (`78:1492`) as fresh full-page redesigns of `/docs/new` and the doc detail — that approach was rejected because it broke M2 chrome consistency and over-designed what is fundamentally a 1-line dropdown change + 1-badge addition. `78:1492` has been deleted; `78:1280` remains in the Figma file as an orphan that the operator can delete manually (delete from Figma file when next opened). This `Frame mapping` table references only the corrected frames.

## 1. Overview

### 1.1 What this vertical introduces

M6 + M7 + M8 together is the first **agentic vertical** in playground — a single chat turn that triggers (a) an LLM extraction call on a PDF document, (b) a deterministic algorithm in a new BC, (c) a sidecar binary serialization, (d) a streaming SSE event sequence that lands a **structured artifact card** in the chat surface. None of the prior milestones produces an artifact the user takes elsewhere; massing-gen is the first.

From a UX perspective, four affordances are new across the three milestones:

1. **`.pdf` MIME branch** in M2's `+ New document → Import .md…` dropdown (M6) — the dropdown's second-row label becomes `↑ Import .md or .pdf…`, the native file picker's `accept` attribute gains `.pdf`, and the uploaded doc gets a small `(PDF)` indicator badge inline next to the title on the doc detail page (plus on doc cards in `/docs/mine`, when the implementer adds it there — see Open Question #4). **No new page or redesigned UI** — the `/docs/new` editor page (frame `36:191`) is unchanged; the only visual deltas are one dropdown row label and one inline badge.
2. **`tool_result` card** in `/chat` (M7 + M8) — a structured card that materializes **below** the LLM's natural-language assistant message when an SSE `tool_result` event arrives. M7 defines the slot anatomy; M8 fills in the slots for `generate_massing`.
3. **`tool_error` card** in `/chat` (M7 + M8) — same card shell but in `warning` palette, with a secondary action ("Try a different brief") instead of a primary action.
4. **Expandable metadata accordion** inside the `tool_result` card (M8) — the `▾ Program details` accordion that opens to reveal the extracted room program table. Same visual pattern as M4's citation accordion (`▾ Citations · N`), kept consistent across the design system.

Everything else — sidebar, topbar, tab strip, composer, user/assistant turn primitives, breadcrumb, account pill, search pill, account footer — is **M1 + M2 + M4 verbatim**.

### 1.2 Viewport-locked layout (load-bearing — inherited from M4 §1.5)

The `/chat` page in Frames 3, 4, and 5 inherits M4's viewport-locked layout: composer sticky to the viewport bottom (16 px from frame bottom — frame-relative `(280, 828)` in a 900 px frame); messages scroll internally between the tab strip and the composer. **The tool_result card scrolls with the messages** — it is NOT pinned. When the program-details accordion expands (Frame 4), the card grows downward inside the scroll area; the composer stays where it is.

Specifically for the M8 surface:

- The card height grows from **~120 px (collapsed)** to **~340 px (expanded)** when `Program details` opens. The composer's y-position does not change. The older messages above scroll up to make room (gradient mask hint at the top edge implies overflow above — same convention as M4).
- The Download .3dm button is anchored to the card's top-right corner — it does NOT move when the accordion expands. (Architectural reason: when the architect expands the accordion to scan the room table, the primary download affordance stays visible — no scroll to find it.)
- Error variant (Frame 5): the warning-palette card does NOT expand (no program to show). The card height stays at ~120 px regardless of state.

### 1.3 Why unified design (M6 + M7 + M8 as one flow)

Three reasons:

1. **Storytelling.** The user-facing slice is one continuous flow (upload → chat → tool result). Three separate design docs would fragment that.
2. **Design surface size.** M6's UI delta is one MIME branch + one badge — 5 minutes of design work. M7 has zero direct user surface (it's infra; the only visual artifact is the SSE event grammar that materializes as the `tool_result` card pattern). M8 has the real card variant. A per-milestone doc for M6 and M7 would be 80% boilerplate.
3. **Token-table audit.** A single unified token table makes it easy to verify zero new tokens — the implementer scaffolding for all three milestones reads from the same `client/src/shared/ui/tokens/` file.

The per-milestone PRD + ADR cycles still happen — they cover backend / contract / library-pin decisions. This design doc is the user-facing visual contract that all three milestones share.

## 2. Per-screen specifications

### 2.1 M6 — `+ New document` dropdown with `.md or .pdf` — frame node-id: `78:1531`

- **Purpose:** M6's only new-upload surface change. The `+ New document` button on `/docs/mine` opens a 2-row dropdown overlay; **row 2's label is the ONE element M6 modifies** — `↑ Import .md…` → `↑ Import .md or .pdf…`. Everything else (button position, dropdown shape, hover styling, `Blank document` row, the underlying `/docs/mine` page chrome) is **M2 verbatim** — this frame is a clone of M2's `30:859` with the one-line text change applied.
- **Why no `/docs/new` redesign:** `/docs/new` (M2 frame `36:191`) is the **Notion-style block editor**, not an upload UI. Upload happens via this dropdown's row 2 → native file picker. M6 changes the dropdown row label + the file picker's `accept` attribute. No M6 frame for `/docs/new` is needed.
- **PRD acceptance criteria covered:** roadmap §M6 acceptance — `POST /api/docs/upload` accepts `application/pdf`; frontend file picker accepts `.pdf`.
- **Spec trace:** §4 (M6 scope — `application/pdf` MIME accepted, `mime_type` column).
- **Auth state:** signed-in only — the underlying `/docs/mine` page is auth-gated per M2 spec.
- **Figma frame:** stale clone name `M2 — + New document dropdown (overlay)  /docs` — node `78:1531` (operator manual rename to `M6 — + New document dropdown (overlay, .md or .pdf)  /docs`). ![screenshot](assets/M6-M8/m6-dropdown.png)
- **Key elements (M2 verbatim except row 2 label):**
  - **Sidebar (232 px, `surface.soft`):** brand row → search pill → APPS section. Layout identical to M2's `30:859`.
  - **Topbar (1208 × 60, `bg`, `border-bottom 1px border`):** breadcrumb `Documents` on left.
  - **Page title:** `My documents` in `font.h2` 20 / 600 / `text`.
  - **Search input** (280 × 36, top-right) and **`+ New document` primary button** (180 × 36, `accent` bg, with `▾` chevron divider on the right edge).
  - **Dropdown card** (200 × 88, `surface` bg, `border` 1 px, `radius.md`) anchored below the button:
    - Row 1: `+  Blank document` (font.body 13 / 500 / text) — unchanged.
    - Row 2: **`↑  Import .md or .pdf…`** (font.body 13 / 500 / text) — **M6's only label change**. Hovered state shown — row 2 has `surface.soft` bg.
- **Below-the-fold state annotation** (in `text.muted` 11 / 400): `state: dropdown OPEN, row 2 hovered. M6 change = label gains ".pdf" alongside ".md". Click Blank document → /docs/new. Click Import .md or .pdf… → native file picker (.md, .pdf) → POST /api/docs multipart.`
- **Interactions:**
  - Click `+ Blank document` → navigate to `/docs/new` (M2 frame `36:191`, editor unchanged).
  - Click `↑ Import .md or .pdf…` → trigger native file picker dialog with `accept=".md,.pdf"`. On file select → POST `/api/docs/upload` multipart with `Content-Type: application/pdf` (for `.pdf`) or `text/markdown` (for `.md`). On success → navigate to `/docs/{id}` (Frame 6 — the doc detail surface).
- **Per-state (only the row 2 hover state is drawn):**
  - **Hover row 2 (rendered):** row 2 bg → `surface.soft`, label color unchanged.
  - **Click row 2** (not drawn): dropdown closes; native OS file picker opens. No additional Figma frame needed — the file picker is OS chrome.
  - **Upload in progress** (not drawn): the page shows a transient toast `Uploading…` and the dropdown closes. Implementer's choice whether to visualize a progress bar in P0.
  - **Upload failed (corrupted PDF, 400)** (not drawn): toast `Could not read this PDF — try a different file.` Per M6 ADR-16 open question (exact 400 error code is closed there).
- **Spacing tokens used:** identical to M2 `30:859` — `spacing.md` 16 px between button and dropdown card top edge; `spacing.xs` 4 px between dropdown rows.

### 2.2 M7 — Generic `tool_result` card pattern (DS primitive) — frame node-id: `78:1329`

- **Purpose:** documentation frame. Captures the **slot anatomy** of the generic `tool_result` card that M8 (and any future tool BC: `slide-gen`, `image-gen`, …) reuses. Annotated with leader-line labels pointing to each slot + a lifecycle description (skeleton → fully-populated → error variant) so the implementer can scaffold this primitive once.
- **PRD acceptance criteria covered:** roadmap §M7 acceptance — "`rag-chat` chat session with LLM tool_choice="auto" can invoke a registered tool via WireMock-stubbed endpoint" + "SSE stream emits `tool_call` → `tool_result` → `token` events in order". The card primitive is the **frontend half** of that contract.
- **Spec trace:** §5 (M7 — `Frontend tool_result rendering` bullet added in PR #165 — locks card pattern: tool display name + summary + primary action button; co-exists with LLM text; `outputUrl` relative for cookie auth; download = plain `<a href download>`).
- **Auth state:** N/A — this is a design-system documentation frame, not a route.
- **Figma frame:** `M7 — Generic tool_result card pattern (DS primitive)  global` — node `78:1329`. ![screenshot](assets/M6-M8/m7-tool-result-primitive.png)
- **Key elements:**
  - **Title:** `tool_result card — design-system primitive` in `font.h1` 28 / 700 / `text`.
  - **Subtitle:** 2-line paragraph in `font.body` 15 / 400 / `text.muted` explaining "every tool BC reuses this shell; each tool owns the per-slot copy + the primary action's `outputUrl`; the chrome is shared".
  - **Canonical empty card** (820 × 140, centered, `surface` bg, `border` 1 px stroke, `radius.md`):
    - Slot ①: **leading icon** — `📁` placeholder, 22 px, top-left padding 20 / 22.
    - Slot ②: **tool display name** — `sample_tool` in `font.body` 15 / 600 / `text`.
    - Slot ③: **one-line summary** — `(tool returns a one-line summary here)` in `font.small` 13 / 400 / `text.muted`, directly below the name with 6 px gap.
    - Slot ④: **primary action button** — `↓  Action` button (116 × 32, `accent` bg, `radius.md`, white label 13 / 600), anchored top-right of the card with 20 px right padding.
    - Slot ⑤: **metadata accordion** — collapsed `▸ Details` in `font.small` 12 / 500 / `text.muted` at the card's bottom-left, 22 px from card top + 78 px gap. Optional per-tool; tool BC chooses whether to expose one.
  - **Leader-line annotations** in `text.muted` 12 / 400 — five rows below the card, one per slot, explaining "tool BC fills this".
  - **Card lifecycle section** (header in `font.body` 15 / 600 + 3 lines below in `font.small` 13 / 400 / `text.muted`):
    ```
    tool_call event arrives    →    skeleton card (icon + name + "Running…" + spinner; no action button yet)
    tool_result event arrives  →    fully-populated card as drawn above (summary + primary action enabled)
    tool_error event arrives   →    error variant (warning palette, secondary action e.g. "Try again", no primary action)
    ```
- **Interactions:**
  - The icon, name, summary, accordion, and primary action are all per-tool **slots** — `M8` fills them with `📁` / `generate_massing` / `12 rooms · 3 floors · 480 m² total` / `▸ Program details` / `↓ Download .3dm`.
  - The primary action is a plain `<a href={outputUrl} download>` for file outputs. For non-file outputs (e.g., a future `image-gen` tool returning an inline preview URL), the implementer swaps the action — same slot, different element. The card chrome doesn't care.
  - Hover on the card itself: subtle elevation transition (`shadow.card` → `shadow.pop`, 200 ms ease). Not visualized in the static frame.
- **Per-state:**
  - **Skeleton (mid-flight)** — `tool_call` arrived but `tool_result` has not. Icon + name shown; summary placeholder = `Running…` in `text.muted` + a 14 × 14 spinner in `accent` to the right of `Running…`. Primary action slot empty.
  - **Fully populated (rendered)** — the drawn state.
  - **Error variant** — same shell but `warning.soft` bg + `warning` border + `warning` icon (`⚠`) + `warning` tool-name color. Primary action button swaps to a **secondary action** (white bg + `warning` 1 px border + `warning` fg) labeled per the tool's error semantics (Frame 5 shows `↗  Try a different brief` for M8's `BRIEF_EXTRACTION_FAILED`).
- **Spacing tokens used:** `spacing.md` 16 px card padding (top/left/right); `spacing.sm` 8 px between adjacent annotation lines; `spacing.lg` 24 px between the card and the annotation block; `spacing.lg` 24 px between annotations and the lifecycle section.

### 2.3 M8 — `/chat` with completed `generate_massing` tool result — frame node-id: `78:1347`

- **Purpose:** the canonical "happy path" view. Architect's question + LLM's natural-language commentary + the populated `generate_massing` tool result card with the **Download .3dm** primary action. This is the load-bearing M8 frame.
- **PRD acceptance criteria covered:** roadmap §M8 acceptance — "Tool registered in rag-chat's `ToolCatalog`; end-to-end chat → tool → file URL works" + "`POST /internal/tools/generate-massing` with a valid brief doc id returns a `.3dm` file URL + program JSON + summary". The card is the user-facing artifact of that path.
- **Spec trace:** §6 (M8 — Tool result card surface bullet added in PR #165 — Content-Disposition trigger, summary like "12 rooms · 3 floors · 480 m²", Download .3dm button).
- **Auth state:** signed-in only.
- **Figma frame:** `M8 — /chat with completed generate_massing tool result card  /chat` — node `78:1347`. ![screenshot](assets/M6-M8/m8-massing-success.png)
- **Key elements:**
  - **Sidebar + topbar + tab strip:** M4 verbatim. Sidebar's `Chat` row is active (`accent.soft` bg + `accent` fg + weight 600). Tab strip has one active tab `💬  Brief으로 매싱 만들기      ⋯` (220 × 32, `surface` bg + `border` 1 px) and the `+` new-tab button.
  - **Breadcrumb:** `Home  /  Chat  /  Brief으로 매싱 만들기`.
  - **User turn (y = 138):** `YOU` eyebrow (`font.eyebrow` 11 / 600 / `text.muted`) + user message `서울대 도서관 brief 보고 매싱 만들어줘. 대지 20m × 10m, 층고 3.5m로.` in `font.body` 15 / 400 / `text`.
  - **Assistant turn (y = 208):** `ASSISTANT` eyebrow (`font.eyebrow` 11 / 600 / `accent`) + multi-line LLM commentary in `font.body` 15 / 400 / `text`:
    > Brief에서 12실(예: 강의실 3, 회의실 2, 카페테리아 1, 화장실 2, 사무실 3, 로비 1)을 추출했어요.
    >
    > 총 480 m²로 3개 층에 분배했고, 사이트 footprint 200 m² × 3층으로 계산했어요.
    >
    > 1층에 로비+카페테리아+화장실, 2-3층에 강의실+회의실+사무실. .3dm 파일로 다운로드 가능합니다.
    (verbatim Korean — pinned by spec §6; the LLM still describes what it did in prose, the card surfaces the artifact handle)
  - **`tool_result` card** (820 × 120, anchored at frame-relative `(280, 344)` — directly below the assistant message body with `spacing.md` 16 px gap):
    - Leading icon: `📁` in 22 px / 600.
    - Tool name: `generate_massing` in `font.body` 15 / 600 / `text`.
    - Summary: **`12 rooms · 3 floors · 480 m² total`** in `font.small` 13 / 500 / `text` (slightly bolder than the generic primitive's 400, because the summary is informational not placeholder).
    - Accordion: `▸ Program details` collapsed line at the card bottom-left.
    - Primary action: `↓  Download .3dm` button (144 × 32, `accent` bg, `radius.md`, white label 13 / 600), anchored card top-right.
  - **Composer (viewport-bottom-pinned at frame-relative `(280, 828)`):** standard M4 composer — enabled, placeholder `Ask anything about your corpus…`, `⏎ Send` primary button.
- **Interactions:**
  - Click `↓ Download .3dm` → triggers a browser download. The `outputUrl` from the SSE `tool_result` payload is a relative URL (`/api/arch/outputs/{id}`) so the gateway-issued session cookie carries `X-User-Id` to the M8 BC's download endpoint. The endpoint returns `application/octet-stream` with `Content-Disposition: attachment; filename="massing-<briefSlug>-<timestamp>.3dm"`. Browser shows a save dialog. No JS fetch — plain `<a href download>`.
  - Click `▸ Program details` → expands the accordion (Frame 4). Other accordions in this conversation are NOT auto-collapsed (same independent-state semantic as M4 citations).
  - Click anywhere on the card body (excluding the button): no-op. The card is informational; only the button + the accordion are interactive. (Hover does NOT make the whole card a click target — keeps the affordance focused.)
- **Per-state:**
  - **In-flight (tool_call received but tool_result not yet):** card shows `📁` + `generate_massing` + `Running…` summary + spinner (no Download button yet). LLM commentary text streams above as usual.
  - **Success (rendered):** the drawn state.
  - **Error:** see Frame 5.
- **Spacing tokens used:** `spacing.md` 16 px gap between assistant body and tool_result card; `spacing.md` 16 px card padding (top/bottom); `spacing.sm` 8 px between tool name and summary; `spacing.lg` 24 px gap between the user turn and the assistant turn.

### 2.4 M8 — `/chat` with `tool_result` card EXPANDED — frame node-id: `78:1392`

- **Purpose:** captures the **`▾ Program details` accordion in expanded state**. Demonstrates that the room-program table is folded behind a click — the card stays compact by default and the architect can drill in when desired.
- **PRD acceptance criteria covered:** roadmap §M8 acceptance — "Tool registered in rag-chat's `ToolCatalog`; end-to-end chat → tool → file URL works". The expanded accordion is the surfacing of `programJson` returned in the same payload.
- **Spec trace:** §6 (M8 — `programJson` field returned by tool; design choice is to make it expandable rather than inline).
- **Auth state:** signed-in only.
- **Figma frame:** `M8 — /chat with completed generate_massing tool result card  /chat` (stale clone — to be renamed to `M8 — /chat with tool_result card EXPANDED (program details)  /chat`) — node `78:1392`. ![screenshot](assets/M6-M8/m8-massing-expanded.png)
- **Key elements:**
  - **Sidebar + topbar + tab strip + user turn + assistant turn:** identical to Frame 3.
  - **`tool_result` card (expanded, 820 × 340):** same top section as Frame 3 (icon, tool name, summary, Download button anchored top-right). Below the summary, the accordion is now **`▾ Program details`** (chevron rotated). Below that header, a 1 px `border` divider, then a **4-column table** (FLOOR, ROOM, DIMENSIONS, AREA — all `font.eyebrow` 11 / 600 / `text.muted` for headers) with 6 sample rows:
    | FLOOR | ROOM | DIMENSIONS | AREA |
    |---|---|---|---|
    | 1F | 로비 (Lobby) | 8 × 6 × 3.5 m | 48 m² |
    | 1F | 카페테리아 (Cafeteria) | 6 × 5 × 3.5 m | 30 m² |
    | 1F | 화장실 #1 (Restroom) | 3 × 2 × 3.5 m | 6 m² |
    | 1F | 화장실 #2 (Restroom) | 3 × 2 × 3.5 m | 6 m² |
    | 2F | 강의실 #1 (Lecture room) | 8 × 5 × 3.5 m | 40 m² |
    | 2F | 강의실 #2 (Lecture room) | 8 × 5 × 3.5 m | 40 m² |
    - Footer: `… and 6 more rooms across floor 2 + 3 (총 12실, 480 m²)` in `font.small` 12 / 500 / `text.muted`.
  - **Composer:** identical to Frame 3. Note: the composer's y does NOT change when the accordion expands — the card grows downward into the scroll area; if the conversation gets long, the older messages scroll up off-screen (per §1.2).
- **Interactions:**
  - Click `▾ Program details` again → collapses back to Frame 3 state.
  - Click `↓ Download .3dm` → identical behavior to Frame 3 (the button is anchored top-right; expanding the accordion does NOT move the button).
  - Click a row in the table: **no-op in P0**. Future M8.1 may add per-room hover to highlight that room's box in a small 3D preview, but the preview is deferred per spec §6 "Out of scope — M8.1: Cover image / thumbnail preview of the massing".
- **Per-state:**
  - **Showing 6 of 12 rooms** (rendered): the natural compromise — enough to convey shape without scrolling the table itself. The "… and 6 more rooms" footer is the affordance hint that the full list is available in `programJson`. (If the implementer wants to make the table itself scrollable to show all 12 within the card, that's acceptable; the design just pins "first 6 + footer summary" as the minimum.)
  - **Empty table (massing succeeded but with 0 rooms)** — not visualized; backend semantics make this impossible (the algorithm returns ≥ 1 room or the tool emits `tool_error`).
- **Spacing tokens used:** `spacing.md` 16 px between accordion header and the table; `spacing.sm` 8 px between adjacent table rows; `spacing.md` 16 px gap between the table and the "and N more" footer; `spacing.lg` 24 px gap between accordion content and the card bottom border.

> **2026-06-05 — 3D 미리보기 (post-M8 iteration):** the result card gained a
> `▸ 3D 미리보기` accordion above `▸ Program details`. It lazy-loads
> `@google/model-viewer` and renders the preview `.glb` (uploaded by the
> architecture BC next to the `.3dm`, same key prefix) from
> `GET /api/rag/chat/attachments/{id}/preview` — 320px-tall, card-wide
> inline viewer (sizing lives on a wrapper div — React 18 writes `className`
> on custom elements as a literal attribute, so Tailwind cannot target
> `<model-viewer>` directly),
> camera-controls + auto-rotate. The .glb carries preview-only styling
> (2026-06-05 readability pass): deterministic muted zone colors (olive /
> clay / slate / sand / mauve, below-grade dimmed), a translucent ground
> slab at grade, and a 0.15m top slit per floor box so stacked floors read
> as separate slabs — the .3dm deliverable stays untouched. Legacy
> attachments without a `.glb` show
> fallback copy inside the accordion when the fetch 404s ("미리보기를 불러올
> 수 없습니다…"). Spec:
> `docs/superpowers/specs/2026-06-05-massing-glb-preview-design.md`.

> **2026-06-05 — 실별 분할 (room-split):** 매싱이 zone 통짜에서 실 단위로
> 세분화됐다. FFD bin-packing으로 각 실이 한 층에 통째 배치되고 잔여는
> `공용·기타` 박스. `.glb`는 zone hue + 실별 명도 단계 + 공용 저채도 톤,
> 실명은 model-viewer hotspot 라벨(HTML 어노테이션)로 표시. Program
> details는 zone 그룹핑 4열(ZONE/ROOM/FLOOR/AREA) 테이블 — 레거시
> 페이로드는 기존 2열 유지. Spec:
> `docs/superpowers/specs/2026-06-05-room-split-massing-design.md`.

> **2026-06-05 — programJson 영속화 (.glb extras):** `programJson`은 이제
> 생성 시점에 `.glb`의 `scenes[0].extras`에도 임베드된다 (SSE와 단일 빌더
> `program_wire.build_program_json` 공유). 히스토리 카드는
> `shared/lib/glb-extras.ts`의 의존성-제로 GLB 파서로 그것을 복원해
> 스트리밍 카드와 동일하게 hotspot 라벨 + 실별 테이블을 렌더한다. 레거시
> .glb(extras 없음)는 지금처럼 테이블/라벨 없이 graceful 생략. Spec:
> `docs/superpowers/specs/2026-06-05-glb-extras-program-json-design.md`.

> **2026-06-06 — 도구 스트리밍 + 진행 표시:** 도구 wire가 NDJSON 스트리밍
> (progress/heartbeat/result|error)으로 전환 — 타임아웃 기준이 총량(120s)에서
> idle(60s, total cap 600s)로 바뀌어 LLM-bound 장시간 실행이 안전하다.
> in-flight 카드는 제네릭 `ToolRunCard`: 서버 발신 displayName + 한국어 단계
> label + (시도 N) + stageCount 핍 바 — 전부 wire 구동이라 새 도구는 FE 코드
> 없이 진행 표시를 얻는다. Spec:
> `docs/superpowers/specs/2026-06-06-tool-streaming-progress-design.md`.
> (2026-06-06 후속: rag-chat 서비스는 chat으로 개명 — 본문 명칭은 작성 시점 기록.)

### 2.5 M8 — `/chat` with `tool_error` card — frame node-id: `78:1437`

- **Purpose:** the failure path. Architect uploads a non-brief PDF (e.g., a CV or a marketing flyer); the LLM extraction step fails. The card surfaces a `BRIEF_EXTRACTION_FAILED` error in `warning` palette with a user-actionable secondary action.
- **PRD acceptance criteria covered:** roadmap §M8 acceptance — implicit "graceful failure when extraction fails" (no acceptance criterion explicitly names this, but spec §11 Risks calls out "User uploads a non-brief PDF expecting massing" with mitigation = "the 'summary' field that the LLM uses to tell the user 'couldn't find room program in this brief — is this the right PDF?'"). This frame visualizes that mitigation.
- **Spec trace:** §5 (M7 — `tool_error` SSE event grammar) + §6 (M8 — extraction validation via JSON Schema) + §11 risk row 6 (user-uploaded non-brief).
- **Auth state:** signed-in only.
- **Figma frame:** `M8 — /chat with completed generate_massing tool result card  /chat` (stale clone — to be renamed to `M8 — /chat with tool_error card (generation failed)  /chat`) — node `78:1437`. ![screenshot](assets/M6-M8/m8-massing-error.png)
- **Key elements:**
  - **Sidebar + topbar + tab strip:** identical to Frames 3 and 4.
  - **User turn:** `방금 올린 PDF로 매싱 만들어줘.` (Korean — verbatim).
  - **Assistant turn:** LLM commentary explaining the failure user-actionably (Korean verbatim):
    > 죄송해요 — 이 PDF에서 room program(실명 + 면적)을 찾지 못했어요.
    >
    > 경쟁 brief가 아니거나 스캔된 이미지 PDF일 수 있어요. 다른 파일을 올려서 다시 시도해 보세요.
    The LLM's apology + diagnosis + suggested next step is the **primary surface for resolution**; the card is a secondary, machine-actionable handle.
  - **`tool_error` card (820 × 120, `warning.soft` bg, `warning` 1 px border, `radius.md`):**
    - Leading icon: `⚠` in `warning` (`#B58A2B`) 22 px.
    - Tool name: `generate_massing` in `warning` 15 / 600 (same fg as the icon — the entire title row reads in the warning palette).
    - Summary: `Could not extract room program — is this a competition brief PDF?` in `font.body` 15 / 500 / `text` (dark text on warning.soft for readability).
    - Code line (replaces the accordion in the success variant): `code: BRIEF_EXTRACTION_FAILED  ·  3.2s elapsed` in `font.small` 12 / 500 / `text.muted` — useful telemetry handle for the architect to share if they want to ask the operator.
    - **Secondary action button** (200 × 32, `surface` bg, `warning` 1 px border, `warning` fg 13 / 600, `radius.md`): `↗  Try a different brief`. Anchored card top-right with the same 20 px right padding as the success variant.
  - **Composer:** enabled (same as Frame 3). The architect can either type a new message or click the secondary action.
- **Interactions:**
  - Click `↗ Try a different brief` → navigates to `/docs/new` (Frame 1). The originating chat session is preserved; on returning to `/chat`, the same tab is still active.
  - The composer is NOT disabled (different from M4's 429 RATE_LIMIT banner) — the error is artifact-specific, not chat-wide. The architect can keep asking other questions.
  - The card does NOT auto-dismiss. It persists in the conversation history (loaded again on reload via `GET /sessions/{id}/messages`). Audit trail.
- **Per-state:**
  - **BRIEF_EXTRACTION_FAILED (rendered):** the drawn state. JSON Schema validation rejected the LLM's extracted program; algorithm did not run; no `.3dm` produced.
  - **MASSING_ALGORITHM_FAILED** (not drawn): the LLM extraction succeeded but the algorithm couldn't fit the rooms (e.g., total area > site footprint × max floors). Same card, different `code`. Summary copy is per the implementer + ADR-18.
  - **TIMEOUT** (not drawn): the M8 BC didn't respond within Resilience4j's timeout. Same card, `code: TIMEOUT  ·  30.0s elapsed`. Secondary action becomes `↻ Retry`.
  - **5XX** (not drawn): M8 BC returned a 5xx. Same card, `code: TOOL_5XX`. Secondary action becomes `↻ Retry`.
  - All four error sub-states share the same visual shell — only the icon, summary copy, code string, and secondary action label change.
- **Spacing tokens used:** identical to Frame 3 (same card shape, different palette).

### 2.6 M6 — Doc detail (PDF source) with `(PDF)` badge — frame node-id: `78:1552`

- **Purpose:** the doc detail page when the document was uploaded as a `.pdf`. **The page is M2's `Document detail` (frame `31:66`) verbatim — sidebar, topbar, breadcrumb format, title typography, author meta row, copy-link pill chip, view counter, like button, body rendering, footer back link.** M6 adds **two surgical changes** only: (a) the `(PDF)` badge inline next to the title, (b) the meta row gains `· source: .pdf` at the end. No new actions, no `Use in /chat` button (rejected as out-of-spec), no extra blockquote callouts. (Amendment 2026-05-21: the earlier `· source: .pdf (12 pages)` tail dropped the page-count parenthetical — the M6 PRD did NOT introduce `pdf_page_count` because PDFs are stored as markdown after OCR-hybrid extraction, so page count is no longer a meaningful row attribute.)
- **PRD acceptance criteria covered:** roadmap §M6 acceptance — "Doc detail page shows `(PDF)` indicator for PDF-sourced docs".
- **Spec trace:** §4 (M6 — `mime_type = 'application/pdf'` on the row drives the badge render). (Amendment 2026-05-21: M6 PRD closed the open question — `pdf_page_count` is NOT introduced; the meta extra is `· source: .pdf` only.)
- **Auth state:** Frame 78:1552 cloned the **public-view variant** of M2's doc detail (31:66 — same surface authenticated and anonymous viewers see when the doc is `visibility = 'public'`). Auth-gated variant (when the doc is `private` and only owner can view) follows the same badge convention; not drawn separately since the M6 delta is identical.
- **Figma frame:** stale clone name `M2 — Document detail (public)  /docs/public/{slug}` — node `78:1552` (operator manual rename to `M6 — Document detail (PDF source) with (PDF) badge  /docs/{id}`). ![screenshot](assets/M6-M8/m6-doc-detail-pdf.png)
- **Key elements (M2 31:66 verbatim except the two M6 deltas marked):**
  - **Sidebar + topbar:** M2's public-chrome verbatim. `Viewing publicly` chip + `Sign in with Google` button on right (when anon); for the auth variant the right side becomes the `● Signed in` chip + account pill.
  - **Breadcrumb:** `Documents / 서울대 도서관 공모 지침서` in `font.body` 13 / 500 / `text.muted`. (M2's format — kept verbatim, NOT `Home / Docs / ...`.)
  - **Doc title:** `서울대 도서관 공모 지침서` in `font.h1` 28 / 700 / `text`.
  - **🆕 `(PDF)` badge** inline immediately right of the title with ~16 px gap from the last character: 56 × 22, `accent.soft` bg, `radius.pill`, `accent` fg 11 / 600, label `(PDF)`. **This is the first M6 delta.** Visual treatment matches the M5 sidebar's milestone-badge pattern (small inline pill).
  - **Author meta row:** 32 × 32 khaki avatar + `JeekLee` (13 / 600) + `published 3 days ago` (11 / 400 / text.muted).
  - **Copy-link pill chip** (400 × 32, `surface` bg, `border` 1 px, `radius.pill`) anchored right of the author meta with the relative URL: `⎘ /docs/a3f2b9c1-7e5d-4abc-9def-1234567890ab`.
  - **🆕 Meta inline row** (below the avatar/copy-link block): `brief · 12 min read · 👁 1,247 · source: .pdf` in `font.small` 13 / 400 / `text.muted`. **The `· source: .pdf` tail is the second M6 delta** — surfaces the `mime_type` metadata so a reader knows the body was OCR-hybrid-extracted from a PDF rather than authored as markdown. (Implementer choice — can hide if preferred; recommend show for transparency.) (Amendment 2026-05-21: dropped the earlier `(12 pages)` parenthetical — M6 PRD did not introduce `pdf_page_count`, so page-count is no longer surfaceable here.)
  - **Like button** (76 × 26, `surface` bg, `border` 1 px, `radius.pill`) with `♡ 42` label + `Sign in to like` hint (when anon).
  - **Body content** — PDFBox-extracted text rendered as markdown. M2 31:66's body content is preserved verbatim in the cloned frame (the demo content reads `Building an agent team` content — illustrative; the actual brief content would replace this at runtime). The body rendering pipeline is M2 verbatim — `unified + remark-gfm + rehype-sanitize + shiki`, no M6-specific behavior.
  - **Back link:** `← All documents` in `accent` 13 / 500 — bottom of body area.
- **Interactions (M2 verbatim):**
  - Click copy-link pill → copies URL to clipboard, brief toast `Link copied`.
  - Click `♡ 42` → if signed in, toggles like; if anon, brief tooltip → `Sign in to like`.
  - Click `← All documents` → `/docs` (M2 frame `37:363`).
  - Click `Sign in with Google` (when anon) → OAuth flow.
- **Per-state:**
  - **PDF source, public, anon viewing (rendered):** the drawn state.
  - **PDF source, public, signed-in viewing:** topbar right side swaps to `● Signed in` chip + account pill; like button works; everything else identical.
  - **PDF source, private (owner-only):** topbar right side shows `● Signed in` chip + account pill (auth-only path; no anon access).
  - **Markdown source (no PDF badge):** identical chrome, no `(PDF)` badge inline with title, meta row drops the `· source: .pdf` tail. **All other elements unchanged** — same page, conditionally rendered badge + meta extra.
- **Spacing tokens used:** identical to M2 `31:66`. `spacing.md` 16 px between title row and avatar meta row; `spacing.lg` 24 px between avatar meta and meta-inline row; `spacing.lg` 24 px between meta-inline row and body content. The `(PDF)` badge sits with `spacing.md` 16 px gap from the title's last character.

## 3. Reused chrome elements (M1 / M2 / M4 components, NOT reinvented)

These widgets ship from M1 / M2 / M4 verbatim; M6 + M7 + M8 do NOT introduce alternative versions. The frontend-implementer should reuse the existing FSD `widgets/Sidebar/`, `widgets/Topbar/`, `widgets/Composer/`, and `widgets/TabStrip/` and add **only the new components from §4** as new modules.

| Element | Source design doc | Source frame in Figma | M6-M8 frames where it appears |
|---|---|---|---|
| **Brand row** (J glyph + stacked `JeekLee's` / `PLAYGROUND` wordmark) | M1 §"Public Home" | `14:2` | All 6 frames |
| **Sidebar APPS section** (with active/inactive row treatment) | M2 §7.1, M4 §3 | All M2 + M4 frames | All 6 frames — `Docs` active on Frame 1 & 6; `Chat` active on Frames 3, 4, 5 |
| **⌘K Search pill** (sidebar trigger) | M2 §"⌘K palette" | `27:704` | All 6 frames |
| **Signed-in account footer card** (28 px khaki avatar + name + email) | M1 §"Signed-in Home" | `14:135` | All 6 frames |
| **Slim topbar** (breadcrumb + `● Signed in` chip + account pill) | M1 §"Signed-in Home" + M4 §3 | `14:135` | All 6 frames |
| **Account pill** (24 px avatar + display name + chevron) | M1 + M2 frame `30:892` | `14:135` + `30:892` | All 6 frames |
| **Top tab strip** (52 px tall, active tab + `+` button) | M4 §4.1 | `54:8` + `54:233` | Frames 3, 4, 5 |
| **Composer** (1112 × 56, viewport-bottom-pinned with `radius.lg` 12 px) | M4 §1.5 + §3 | `54:8` + `54:233` | Frames 3, 4, 5 |
| **User/assistant turn primitives** (eyebrow `YOU` / `ASSISTANT` + body) | M4 §2.2 + §2.3 | `54:180` + `54:233` | Frames 3, 4, 5 |
| **Markdown-rendered body content** (`unified` + `remark-gfm` + `rehype-sanitize` + `shiki` per M4 §9) | M4 §9 | All M4 + M2 detail frames | Frames 3, 4, 5 (assistant body); Frame 6 (doc body) |
| **Blockquote rule** (3 px `border.strong` left rule + 14 px `text.muted` body) | M2 §"Document detail" | `31:108` | Frame 6 |
| **`+ New document` dropdown pattern** (overlay anchored to a button, 200 × 88, `surface` bg + `border` + `radius.md` + `shadow.pop`) | M2 §"+ New document dropdown" | `30:859` | Frame 1 builds on this pattern (the import card is a more prominent variant of the same affordance — both still POST to `/api/docs/upload`) |
| **Citation accordion visual pattern** (`▸` / `▾` chevron + label + expandable content) | M4 §4.2 | `54:233` | Frames 3, 4 (the `▾ Program details` accordion reuses identical visual to `▾ Citations · N`) |

## 4. New components introduced by M6-M8

Three new components. All read from existing tokens — zero new tokens.

### 4.1 Generic `tool_result` card (slot-based, design-system primitive — M7)

- **Container:** 820 × auto (compact 120 px, expanded grows downward), `surface` bg + `border` 1 px + `radius.md` + `shadow.card`. Hover transitions to `shadow.pop` (200 ms ease).
- **Slot anatomy:** see §2.2.
- **Slot type semantics:**
  - `icon` — emoji or Lucide glyph. Per-tool.
  - `name` — string. Tool BC-owned.
  - `summary` — string (1 line). Tool BC-owned.
  - `primaryAction` — `{ label, href, downloadName? }` for file outputs, or `{ label, onClick }` for non-file. Tool BC-owned.
  - `metadata` — JSON, optional. If present, renders an expandable accordion at the card bottom. The tool BC owns the rendering of the expanded content (per-tool freedom — could be a table like M8, a code block, a small image grid, etc.).
- **Lifecycle:** see §2.2 — three states (skeleton on `tool_call`, populated on `tool_result`, error on `tool_error`).
- **Visualized in:** Frames 2 (canonical), 3 (M8 success), 4 (M8 expanded), 5 (M8 error).

### 4.2 M8-specific tool_result variant — massing generation card

- Same container + chrome as §4.1.
- **Slots filled per spec §6:**
  - `icon` = `📁`
  - `name` = `generate_massing`
  - `summary` = `{rooms.length} rooms · {floor_count} floors · {total_area_m2} m² total` (verbatim string contract — implementer formats from the SSE `tool_result` payload). Reference render: `12 rooms · 3 floors · 480 m² total`.
  - `primaryAction.label` = `↓ Download .3dm` (M8-pinned)
  - `primaryAction.href` = `outputUrl` from the SSE payload (relative path, gateway cookie auth)
  - `primaryAction.downloadName` = derived from the server's `Content-Disposition` header (handled automatically by `<a download>` when `download` attribute is empty-valued)
  - `metadata` = the `programJson` object (rooms array + total area + floor count). Renders as the 4-column table in Frame 4 (FLOOR / ROOM / DIMENSIONS / AREA).
- **Visualized in:** Frames 3 (collapsed) + 4 (expanded).

### 4.3 `tool_error` card variant (warning palette — M7)

- Same container shape as §4.1; palette swapped:
  - bg: `warning.soft` (`#F4E8C7`)
  - border: `warning` (`#B58A2B`)
  - icon color: `warning`
  - name color: `warning`
  - summary color: `text` (dark on light warning.soft for legibility)
- **Replaces the `metadata` accordion with a code/timing line** (`code: <ERROR_CODE>  ·  <elapsed_seconds>s elapsed`) in `font.small` 12 / 500 / `text.muted`.
- **Replaces the primary action with a secondary action** (white bg + `warning` border + `warning` fg), labeled per the error code:
  - `BRIEF_EXTRACTION_FAILED` → `↗ Try a different brief` (links to `/docs/new`)
  - `MASSING_ALGORITHM_FAILED` → `↻ Retry`
  - `TIMEOUT` → `↻ Retry`
  - `TOOL_5XX` → `↻ Retry`
- **Visualized in:** Frame 5.

### 4.4 `(PDF)` badge — tiny inline indicator (M6)

- 52–60 × 20–22 px (auto-fit to label width + 14 px padding), `accent.soft` bg, `radius.pill`, `accent` fg 11 / 600.
- **Label:** literal `(PDF)` — including the parentheses. This is intentional: it reads as "(format note)" rather than "PDF" which could be confused with a button.
- **Rendered:**
  - **Inline next to doc cards** in the recent-docs row of `/docs/new` (Frame 1) and on the doc list `/docs` (`37:363`, M2 — implementer to add post-M6).
  - **Inline next to the doc title** on `/docs/{id}` (Frame 6) and the public variant `/docs/public/{slug}` (`31:66`, M2 — implementer to add post-M6).
- **Driven by:** `mime_type === 'application/pdf'` on the doc row. Markdown-sourced docs render the same surface without the badge.
- **Why not a separate icon?** The team chose a text badge over a `📄` glyph because (a) the cream/olive palette doesn't carry a "PDF" iconography slot — Lucide's `FileText` looks generic — and (b) the literal `(PDF)` reads as document metadata, not as decoration. Consistent with the project's editorial / restraint posture (design system §2.3).
- **Visualized in:** Frame 1 (on card 2 inline), Frame 6 (next to the title).

## 5. Tokens used (only existing; zero new)

Every value is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer should already have these wired into `client/src/shared/ui/tokens/` from M1 / M2 / M4 / M5; M6 + M7 + M8 introduce **ZERO new tokens**.

| Token | Value | Where used in M6-M8 |
|---|---|---|
| `color.bg` | `#FAF7EF` | Main content bg on every frame; tab-strip bg; topbar bg |
| `color.surface` | `#FFFFFF` | Composer; active tab; tool_result card (success variant); doc card; account pill; account-footer card; recent doc cards; import card; tool_error secondary button |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; `+` new-tab button bg; account-footer hover; disabled inputs |
| `color.border` | `#E6E0CB` | All card / chrome / chip strokes; account-pill stroke; tool_result card stroke; program-table row dividers |
| `color.border.strong` | `#D6CFB3` | Composer stroke; import-card stroke at 1.5 px (drag affordance hint); blockquote rule (3 × 68 px) |
| `color.khaki` | `#C2B88A` | Account-footer avatar; topbar account-pill avatar; doc detail author avatar |
| `color.text` | `#2A2C20` | Frame titles; doc title; user / assistant message bodies; tool_result name + summary (success); table row data |
| `color.text.muted` | `#6F6A55` | Breadcrumb; user-role `YOU` eyebrow; doc meta row; tool_result accordion line; program-table header eyebrows; "and N more rooms" footer; blockquote body |
| `color.text.subtle` | `#8B8670` | Search-pill placeholder; sidebar APPS section label; composer placeholder |
| `color.accent` | `#6E7A3A` | Brand glyph J fill; active sidebar row fg; primary CTA buttons (Browse files, Download .3dm); `(PDF)` badge fg; assistant-role `ASSISTANT` eyebrow; back link; "Use in /chat" link |
| `color.accent.hover` | `#5C6730` | Primary button hover (not visualized in static frames) |
| `color.accent.soft` | `#E9E8D1` | Active sidebar row bg; `(PDF)` badge bg; (reserved for `[N]` citation pills on chat assistant bodies per M4) |
| `color.success` | `#4F6B2E` | Topbar `● Signed in` chip fg |
| `color.success.soft` (= `#E5EBD9` per M1 spec) | — | Topbar `● Signed in` chip bg |
| `color.warning` | `#B58A2B` | tool_error card border + icon + name + secondary button fg/border |
| `color.warning.soft` (= `#F4E8C7`) | — | tool_error card bg |
| `font.h1` | 28 / 700 / -0.02em | Page titles ("New document", "tool_result card — design-system primitive", doc title) |
| `font.h2` | 20 / 600 / -0.01em | Section headers in doc body ("1. 사이트 개요", "2. 실 구성 (Room program)") |
| `font.h3` | 16 / 600 | Import card title ("Drop a file here, or click to browse"); reserved for tool_result card titles when a tool prefers H3 weight |
| `font.body` | 15 / 400 | All user/assistant message bodies; doc body paragraphs; subtitle copy on Frame 1 and Frame 2; tool_result name slot (with weight 600) |
| `font.small` | 13 / 400–600 | Recent doc card titles (16 / 600); recent doc card excerpts (13 / 400 / muted); tool_result summary (13 / 500); composer placeholder (14 / 400); button labels (13 / 600); meta row (11 / 400) |
| `font.eyebrow` | 11 / 600 / +0.14em / uppercase | `APPS` sidebar label; `YOU` / `ASSISTANT` role labels; `RECENT` label on Frame 1; `FLOOR` / `ROOM` / `DIMENSIONS` / `AREA` table column headers on Frame 4 |
| `font.mono` | 13 / 400 | Reserved for the `code: <ERROR_CODE>` line on tool_error cards (the implementer may swap to mono for tighter visual identity with the technical handle; the static mock renders as Inter for legibility, but mono is preferred when the implementer wires it up) |
| `spacing.xs` | 4 px | Chip glyph-to-label gap |
| `spacing.sm` | 8 px | Tab-strip gap between tabs; intra-card gap between tool name and summary; table row vertical spacing |
| `spacing.md` | 16 px | Card padding (top/bottom); inter-element vertical rhythm in body sections; gap between assistant body and tool_result card |
| `spacing.lg` | 24 px | Frame outer padding; gap between turns in the conversation; topbar vertical rhythm; gap between page title and content; gap between body sections in doc detail |
| `spacing.xl` | 40 px | Gap between hero (import card) and the recent-docs section on Frame 1 |
| `radius.sm` | 6 px | Reserved for inputs / kbd (not used directly in M6-M8 surfaces; M2 search input uses it) |
| `radius.md` | 10 px | tool_result card; tool_error card; sidebar active-row bg; primary buttons (Download .3dm, Browse files); active tab bg; recent doc cards; blockquote-card if elevated |
| `radius.lg` | 14 px | Import card (Frame 1) — slightly larger radius for the "hero" upload card to distinguish it from neighboring small cards; composer uses 12 px per M4 convention |
| `radius.pill` | 999 px | Search pill; account pill; sidebar avatar; topbar pill avatar; `(PDF)` badge; `● Signed in` chip |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | Resting tool_result card; resting composer; resting doc cards |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | tool_result card on hover; (reserved for any dropdown / popover in this milestone — none currently designed) |

**Verification note:** every color hex in this document appears verbatim in the design system spec §3.1 / §3.2 / §3.3 (or §5.3 for elevation). No new tokens were invented. No spec hex was substituted with a near-miss.

## 6. Per-state behavior matrix (cross-screen interactions)

### 6.1 `tool_result` card lifecycle (M7-defined, M8-specialized)

| SSE event sequence | Card state | Visualized in |
|---|---|---|
| `tool_call` arrives (first event) | Skeleton card: icon + name + `Running…` summary + 14 × 14 spinner; no primary action | NOT explicitly drawn (described in Frame 2 lifecycle section); implementer renders this between the first `tool_call` and the `tool_result` |
| `tool_result` arrives (second event) | Fully populated card: summary text replaces "Running…", primary action button enables | Frame 3 (collapsed accordion), Frame 4 (expanded accordion) |
| `tool_error` arrives instead of `tool_result` (terminal failure) | Warning-palette card: icon `⚠`, name in `warning` fg, summary = the error's `message`, secondary action button per error code | Frame 5 |
| LLM continues with `token` events after `tool_result` | Card stays as-is (Frame 3 state); LLM's natural-language commentary continues above and may stream more tokens describing the artifact | Frame 3 + 4 |
| `done` event arrives | Card stays as-is; composer re-enables | Frame 3 + 4 |
| User clicks Download .3dm | Browser triggers save dialog via `<a href download>`; card visual unchanged | Frame 3 + 4 |
| User clicks `▸ Program details` | Card grows to 340 px height; chevron rotates to `▾`; table renders below | Frame 3 → Frame 4 |
| User clicks `▾ Program details` again | Card shrinks back to 120 px; chevron rotates to `▸` | Frame 4 → Frame 3 |
| Page reload mid-stream (tool was running) | Server's `ActiveTurnRegistry` (M4 §6.4) replays the partial SSE; if `tool_result` already emitted before reload, card renders directly in fully-populated state (no skeleton replay) | Same UX as M4 mid-stream re-join |
| Tab switch while tool is running | Active stream aborts (same path as M4 Stop); server pipeline keeps running; on return, the just-arrived `tool_result` is picked up from history | Same as M4 §1.5 |

### 6.2 `.pdf` upload flow + error states (M6)

| User action | Source frame | Target state |
|---|---|---|
| Drop a `.pdf` onto the import card | Frame 1 (idle) | (not drawn) drag-over state: `accent` border + `accent.soft` bg overlay |
| Drop completes, upload starts | Frame 1 → (not drawn) | Card body replaced by `Uploading… <pct>%` progress bar |
| Upload succeeds, PDFBox extracts text | (not drawn) | Navigate to `/docs/{id}` (Frame 6 — with `(PDF)` badge) |
| Upload succeeds, PDFBox returns empty string (image-only PDF) | (not drawn) | Navigate to Frame 6 but body area renders `This document is empty.` empty state; the `(PDF)` badge + blockquote callout still render |
| Upload fails (corrupted PDF, 400) | Frame 1 | Card border → `danger`; error copy `Could not read this PDF — try a different file.`; user stays on `/docs/new` |
| Upload fails (file too large, 413) | Frame 1 | Card border → `danger`; error copy `File too large (max <N> MB).`; user stays on `/docs/new`. The size limit is M6 ADR-16 territory |
| Click `Browse files` | Frame 1 | Native OS file picker with `accept=".md,.pdf"` |
| Click a recent doc card | Frame 1 | `/docs/{id}` for that doc (Frame 6 shape if PDF, otherwise M2 doc detail shape) |

### 6.3 Re-trying after `tool_error`

| User action in Frame 5 | Target state |
|---|---|
| Click `↗ Try a different brief` (for `BRIEF_EXTRACTION_FAILED`) | Navigate to `/docs/new` (Frame 1). Current chat session preserved; tab still active on return |
| Click `↻ Retry` (for `TIMEOUT` / `TOOL_5XX` / `MASSING_ALGORITHM_FAILED`) | Re-submit the same user message → server re-runs the tool. Card transitions from error → skeleton → (success or error again) |
| Type a new message in the composer | Standard chat flow. Error card persists in the conversation history (audit trail) |
| Click the `Use in /chat` link from Frame 6 of a different brief | Returns to `/chat` with composer pre-filled with the new brief's `[doc:{id}] {title}` |

### 6.4 Co-existence with M4's existing error banners

The M4 surface has two error banners: 503 GATEWAY_DOWN (red, anchored above composer) and 429 RATE_LIMIT (yellow, anchored above composer). These banners are **viewport-pinned** and span the full composer width. The M8 `tool_error` card is **NOT a banner** — it's an in-conversation artifact that scrolls with the chat history.

**Why the distinction:**
- The M4 banners are about the **chat service** failing (gateway down, rate limit hit) — they affect the user's ability to chat at all.
- The M8 tool_error card is about a **specific artifact** failing — the chat itself is fine, only this one tool invocation didn't work.

**If both surface simultaneously** (e.g., rate limit hits during a tool retry): the M4 banner takes priority and is shown above the composer; the M8 tool_error card remains in conversation history. Both are independent.

## 7. Open questions for the implementer (M6 / M7 / M8 frontend implementer)

The spec's per-milestone "Key open questions" lists (§4 for M6, §5 for M7, §6 for M8) are closed by their respective ADRs (ADR-16 / ADR-17 / ADR-18) when each milestone opens. The questions specifically deferred to **Stage 3 frontend implementation** of this design — i.e., to the `frontend-implementer` agent reading this doc — are:

1. **Skeleton card spinner animation.** The Frame 2 lifecycle section names the skeleton state but doesn't pin the spinner geometry. Suggest: a 14 × 14 circular spinner in `accent` color, 2 px stroke, `cubic-bezier(0.4, 0, 0.2, 1)` 800 ms rotation, placed inline after the `Running…` text with `spacing.xs` 4 px gap. Implementer's call within the existing token set.

2. **Hover state for the `tool_result` card.** Spec doesn't pin whether the entire card is a click target on hover. Recommendation: NO — hover only changes `shadow.card → shadow.pop` (200 ms ease); the only interactive elements remain the Download button + the `Program details` accordion. The card body itself is informational. This keeps the affordance focused — same posture as M4 citation cards.

3. **Auto-prefill on `↗ Use in /chat` (Frame 6).** Spec doesn't pin whether clicking this link should pre-fill the chat composer with `[doc:{id}] {title}`. Recommendation: defer the auto-prefill to M6.1; P0 just opens `/chat`. The architect types their own question. (Auto-prefill needs a chat-side affordance for "I've added this doc as context" which is M6.1 design territory.)

4. **`(PDF)` badge placement on the doc list `/docs` (M2 frame `37:363`).** The doc list is not part of this design cycle, but the implementer needs to add the badge there as well per M6 acceptance criterion. Recommendation: badge sits inline next to the title in the doc list row, with the same `accent.soft` chip treatment. M6 implementation PR should include that update.

5. **Mobile (≤ 719 px) fallback for tool_result card.** M4 mobile is deferred to M4.1; M8 inherits the same constraint. On mobile, the card should still render but the Download button can move below the summary (vertical stack) instead of anchored top-right, since 320 px is too narrow for the horizontal layout. The implementer designs this within tokens; the design doc takes no formal position beyond "stays inside the token vocabulary; vertical stack is the obvious fallback."

6. **Long summary text (over 1 line).** What if the LLM returns a summary like `12 rooms, 3 floors, total area 480 m², site footprint 200 m² × 3 floors, deepest box 8m × 6m × 3.5m, smallest box 3m × 2m × 3.5m` (wraps to 2-3 lines)? Spec contract is "one-line summary" — but a hostile LLM might violate it. Recommendation: the implementer truncates the summary at the second line with ellipsis (CSS `-webkit-line-clamp: 2`) and shows the full summary on hover via a tooltip. Or: don't truncate, let the card grow vertically. Implementer picks; the design doc accepts either as within token vocabulary.

7. **Empty `programJson` array on success (12 rooms expected but 0 returned by the algorithm — boundary case).** Spec §6 says the algorithm output is `≥ 1 box per room`, so this shouldn't happen — but if it does (edge case the backend hasn't gated), the accordion should render the message `(no rooms in program — this is a degenerate result, please report)` in `text.muted` 13 / 400 / italic, NOT the table. Implementer's call.

8. **`Use in /chat` button styling on Frame 6.** Currently drawn as a plain accent text link. Alternative: a small outline button (`secondary` variant). Implementer's call within the existing component primitives.

---

**End of design context.** The frontend-implementer reads this doc as the canonical specification for Stage 3 frontend work for M6 + M7 + M8; it does NOT modify the per-milestone PRD or ADR (those are PM / architect property). If a UI question is genuinely under-specified beyond this doc + the spec, the implementer surfaces it as a question to the orchestrator rather than guessing.

The Figma file is the canonical visual reference. PNG assets land in `docs/design/assets/M6-M8/{m6-docs-new,m7-tool-result-primitive,m8-massing-success,m8-massing-expanded,m8-massing-error,m6-doc-detail-pdf}.png` once the operator does the right-click → Export pass on the 6 frames (the harness can't write the Talk-to-Figma export bytes to disk — same constraint as M1 / M2 / M4 / M5).
