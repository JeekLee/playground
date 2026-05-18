# Design Context: M4 — RAG-Chat

> PRD: `docs/prd/M4-rag-chat.md`
> Spec: `docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md`
> ADR: `docs/adr/14-m4-rag-chat.md` (per-milestone, includes the cross-doc amendments to ADR-09, ADR-05, ADR-04, roadmap §M4, M2 spec §8, M3 PRD §"M4 retrieval contract")
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md`
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN (M4 row at y = 2200, below the M2 row that ends at y = 1900; the 8 M4 frames sit on a single horizontal track at y = 2200)
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill, brand row) and `docs/design/M2-docs.md` (sidebar Apps section with locked/unlocked rows + badge convention, ⌘K search pill, signed-in account footer). All chrome is reused verbatim; M4 only introduces what is new (top tab strip, citation accordion, streaming message + Stop, error banners, auth-lock badge variant).

Stage 2 output for the RAG-Chat (M4) bounded context. **8 desktop frames at 1440 wide**, built strictly against the playground design system tokens. Sidebar follows the M2 spec §7.1 single-Apps-section convention. Chat (M4) row in the sidebar is **shipped/active** on every M4 frame (replacing M1/M2's `🔒 M4` lock badge); the new auth-lock badge state `🔒 Sign in` (introduced by ADR-14's amendment to ADR-09 §"Auth-lock vs milestone-lock") visualizes the anonymous variant in Frame 8. Tokens table below is sourced verbatim from the design system spec; no new tokens are introduced by this milestone.

> **Asset note (carried from M1 / M2 design rounds):** PNG export via `mcp__TalkToFigma__export_node_as_image` returns base64 to the harness as inline visual content rather than passing through as text. The same blocker applies here. The Figma file is the canonical visual reference; the operator one-off renders `docs/design/assets/M4/{empty,mid-stream,loaded,overflow,tab-menu,503,429,sidebar-variants}.png` via `File → Export selected → PNG @ 2x` per the same workflow used in M1 / M2. The 8 frames are fully assembled and verified via per-frame export-and-visualization during the design run.

> **Frame-name limitation (carried from M2):** the Talk to Figma plugin allowlist exposes `set_text_content` for TEXT nodes but no frame-rename tool. **Seven of the eight M4 frames carry the stale clone name "M2 — My documents /docs"** because they were built by cloning a verified M2 frame and rewriting interiors. The frame's **internal content** is M4-correct; the human reviewer should right-click → Rename in Figma before exporting PNG assets. Mapping table is in the next section.

## Frame mapping (8 frames)

| # | Frame node-id | Current Figma name (stale) | Intended name | Position (x, y) |
|---|---|---|---|---|
| 1 | `54:8` | `M2 — My documents  /docs` | `M4 — Chat (empty state, signed-in)  /chat` | (0, 2200) |
| 2 | `54:180` | `M2 — My documents  /docs` | `M4 — Chat (mid-stream)  /chat` | (1490, 2200) |
| 3 | `54:233` | `M2 — My documents  /docs` | `M4 — Chat (loaded conversation + citation accordion)  /chat` | (2980, 2200) |
| 4 | `54:301` | `M2 — My documents  /docs` | `M4 — Top-tab overflow dropdown (open)  /chat` | (4470, 2200) |
| 5 | `54:379` | `M2 — My documents  /docs` | `M4 — Tab ⋯ menu (Rename + Delete)  /chat` | (5960, 2200) |
| 6 | `54:443` | `M2 — My documents  /docs` | `M4 — 503 GATEWAY_DOWN error state  /chat` | (7450, 2200) |
| 7 | `54:509` | `M2 — My documents  /docs` | `M4 — 429 RATE_LIMIT error state  /chat` | (8940, 2200) |
| 8 | `54:575` | `M4 — Sidebar Chat row variants (auth-lock + active)  global` | (same — built fresh, no rename needed) | (10430, 2200) |

## 1. Overview

M4 is the playground's first **user-facing LLM surface** and the first BC whose UX hinges on **streaming**. Its design therefore introduces four affordances that no prior milestone had to express:

1. **Top tab strip** for parallel session management — picks the "lots of side-quests / quick switching" model over the sidebar list model used by code editors and Slack, because chat-as-app-surface (not chat-as-utility) is what the playground is shipping (per spec §7.2).
2. **Inline citation accordion** directly below each assistant message — the `[N]` marker in body text is the click target that scrolls / expands the matching accordion card. No side panel, no overlay (per spec §7.3, ADR-14 §11).
3. **Streaming message affordance** — pulsing block cursor `▍` at the tail of streaming text + an in-message Stop button + the composer's Send-becomes-Stop toggle. Three visible affordances for one abort action — kept intentionally redundant for discoverability (per spec §7.4).
4. **Auth-lock vs milestone-lock badge distinction** — `🔒 Sign in` (auth-lock, takes you to `/login`) versus `🔒 Mx` (milestone-lock, no-op, "available when M5 ships"). M4 deploy unlocks the `Chat` Apps row, so in this milestone the `🔒 Sign in` state is the first auth-lock to ship (per ADR-14 §G.4 amendment to ADR-09).

All other chrome — sidebar primitives, topbar primitives, account pill, ⌘K search pill, `font.body` rendering, button tokens — comes from M1 + M2 verbatim. Frontend-implementer does **not** scaffold a second design token set, a second sidebar widget, or a second topbar. The intent: the user reaches `/chat` and the shell feels indistinguishable from `/docs`.

## 2. Per-screen specifications

### 2.1 `/chat` (empty state) — frame node-id: `54:8`

- **Purpose:** the user's first contact with chat — a freshly-created session with zero messages. Surfaces the system prompt as a centered hero question and 3 starter chips. The chips' verbatim copy is pinned by ADR-14 §12 (Korean primary).
- **PRD user stories covered:** Story 16 (Empty / loading state is explicit), Story 17 (Sidebar `Chat` row unlock on M4 deploy — visible in this frame as `accent.soft` + `accent` fg with no badge).
- **Spec trace:** §7.1 (page anatomy), §7.5 row "Empty session" (centered card + 3 static suggestion chips, click pre-fills composer but does not auto-send), §7.7 (Apps row state: signed-in → active no badge → click → `/chat`).
- **Auth state:** signed-in only. Anon hits → `/login?return=/chat` per spec §7.6 (no flicker — server-side gate).
- **Figma frame:** `M4 — Chat (empty state, signed-in)  /chat` — node `54:8`. ![screenshot](assets/M4/empty.png)
- **Key elements:**
  - **Sidebar (232px, `surface.soft`):** brand row (J glyph + stacked `JeekLee's` / `PLAYGROUND` wordmark) → `⌘K` search pill → Apps section (`Home` inactive, `Docs` inactive — note: Docs is **shipped** post-M2 so it renders in primary `color.text` not muted; this is a difference from M1's design where Docs was milestone-locked, `Chat` **active** with `accent.soft` bg + `accent` fg weight 600 NO badge, `System status` muted with `M5 🔒` badge) → spacer → signed-in account footer card (28px khaki avatar + `JeekLee` / `jeeklee1120@gmail.com` stacked).
  - **Topbar:** breadcrumb `Home  /  Chat  /  New chat` on the left; `● Signed in` success chip + account pill (`JL` khaki avatar + `JeekLee ▾`) on the right.
  - **Tab strip (below topbar, 52px tall, `bg` color, `border-bottom 1px border`):** single active tab `💬  New chat` (200×32 `surface` bg + `border` 1px + `radius.md`, label in `text` weight 600) with the `⋯` overflow hint at its right edge. The `+` new-tab button (32×32, `surface.soft` bg, `radius.md`) sits immediately to the right of the active tab.
  - **Empty-state hero (centered horizontally, ~316px from frame top):**
    - Title: `What do you want to know about your corpus?` (`font.h2` 22px / 600 / `text`). Centered to the message-area column.
    - Subtitle: `Ask anything about your public + private docs. Citations link back to the source chunk.` (`font.small` 14px / 400 / `text.muted`).
  - **Three suggestion chips** (vertically stacked, `surface` bg + `border` 1px + `radius.md` 10px + `font.small` 13px / 500 / `text`):
    1. `최근에 올린 문서를 요약해 줘` (320×36)
    2. `이 주제에 대해 내 공개 문서에 뭐가 있어?` (420×36)
    3. `ADR-13의 chunking 정책이 어떻게 되지?` (330×36)
    - Strings pinned VERBATIM by ADR-14 §12 (Korean primary, English fallback per `Accept-Language`).
  - **Composer (bottom, ~30px from frame bottom):** 1112×56 `surface` bg + `border.strong` 1px + `radius.lg` 12px; placeholder `Ask anything about your corpus…` in `text.subtle` 14px on the left; `⏎ Send` primary button (`accent` 76×32 + `radius.md`) on the right.
- **Interactions:**
  - Clicking a suggestion chip pre-fills the composer with the chip's exact string. **Does not auto-send** (per spec §7.5). The user can edit and then press `Enter` / click `⏎ Send`.
  - The `+` button creates a new empty session (POST `/api/rag/chat/sessions`); a new tab is inserted at the head of the strip and becomes active.
  - The tab's `⋯` reveals on hover (see Frame 5 for the menu).
  - Pressing `Enter` in the composer submits the user turn; the page transitions to the streaming state (Frame 2).
- **Per-state:**
  - **Hover (suggestion chip):** chip's border shifts from `border` to `border.strong`; bg → `surface.soft` (subtle).
  - **Focus (composer):** outline `2px solid accent`, outline-offset `1px` per design system §6.2.
  - **Empty state with 0 messages OR all messages deleted:** identical render (per spec §7.5 row "Empty session").
- **Spacing tokens used:** `spacing.lg` 24px around the hero column; `spacing.md` 16px between subtitle and the chip stack; `spacing.sm` 8px between adjacent chips; `spacing.lg` 24px between hero and the composer baseline.

### 2.2 `/chat` (mid-stream) — frame node-id: `54:180`

- **Purpose:** an in-flight stream — the assistant is rendering tokens in real time. Composer is disabled (per spec §7.4); a pulsing block cursor + Stop button visualize the live stream.
- **PRD user stories covered:** Story 4 (TTFT — once the first `retrieval` event arrives the empty state's "Thinking…" placeholder dismisses; this frame is the post-first-token state), Story 14 (Stop affordance during streaming), Story 16 (streaming token state).
- **Spec trace:** §7.4 (Composer becomes Stop), §7.5 row "Streaming" (pulsing `▍` cursor + Stop button), §6.5 row "Aborted" (P95 ≤ 200ms abort).
- **Auth state:** signed-in.
- **Figma frame:** `M4 — Chat (mid-stream)  /chat` — node `54:180`. ![screenshot](assets/M4/mid-stream.png)
- **Key elements:**
  - **Sidebar + topbar:** identical to Frame 1. Breadcrumb updates to `Home  /  Chat  /  How does the chunker handle long md?` (truncated session title). Tab label updated to `💬  Chunking policy` (the current session's auto-titled or manually-renamed title).
  - **User turn (frame-relative y=140):** small `you` label (`font.eyebrow` 11px / 600 / `text.muted`) followed by the user message body (`font.body` 15px / 400 / `text`). The body wraps at ~820px max-width.
  - **Assistant turn (y=220):** `assistant` label (`font.eyebrow` 11px / 600 / `accent`) followed by the streaming assistant text (`font.body` 15px / 400 / `text`). The text contains inline `[1]` `[2]` markers — these are 1-indexed superscript pills in the design system §6.3 chip vocabulary (`accent.soft` bg / `accent` fg / `font.eyebrow` 11px); rendered here as inline `[1]` for legibility in the static mock.
  - **Pulsing block cursor `▍`:** an 10×20 rectangle in `accent` color placed at the end of the streaming text (currently at frame-relative ~(956, 352)). The implementer animates `opacity` between 0.3 and 1.0 at ~1s cadence to convey "live."
  - **In-message Stop button:** 90×28, `surface` bg + `border.strong` 1px + `radius.md` 10px. Label `⏹ Stop` in `text` 13px / 500. Click → P95 ≤ 200ms SSE abort (per spec §12 "UX").
  - **Composer (disabled):** bg swaps to `surface.soft`; placeholder copy becomes the pinned Korean string `응답이 생성 중입니다…` in `text.subtle`. The Send button bg recolors to `danger` (`#B14B3B`) and the label flips to `⏹ Stop` in white — a second affordance for the same abort action. **Both Stop buttons (in-message + composer) wire to the same SSE abort handler** — kept duplicated for discoverability per spec §7.4.
- **Interactions:**
  - Click any Stop button → SSE `close()` → server-side `Subscription.cancel()` propagates per ADR-14 §14. Partial assistant text is NOT persisted (per ADR-14 §13); reloading the page after Stop shows the user turn alone, with no assistant reply.
  - Composer is non-interactive (`pointer-events: none`, `opacity: 0.6` per design system §6.2 disabled-input convention).
  - Tab switch during stream → same abort path (per spec §7.2). No `ABORTED` notification needed (same authority).
- **Per-state:**
  - **First-token still pending ("Thinking…"):** Frame not drawn separately — this state shows the `assistant` label + a small spinner in place of the body text. Frontend-implementer renders this between user-submit and the arrival of the first `retrieval` SSE event (per spec §7.5 row "Thinking").
  - **Token stream flowing:** the rendered state in this frame.
  - **Stream completed successfully (`done` event):** Frame transitions to a Frame-3-like loaded state — composer re-enables, cursor disappears, in-message Stop disappears.
- **Spacing tokens used:** `spacing.md` 16px between turn role label and body; `spacing.lg` 24px between turns.

### 2.3 `/chat` (loaded conversation + citation accordion) — frame node-id: `54:233`

- **Purpose:** the canonical "I've had this conversation and want to see the citations" view. Two complete turns, second turn's citation accordion **expanded** showing 3 doc cards; first turn's accordion **collapsed** showing `▸ Citations · 2`.
- **PRD user stories covered:** Story 8 (Sessions persist across browser sessions — this is what the user sees on reload), Story 9 (Citations are persisted with `[N]` markers, page reload → identical render), Story 13 (Inline citation accordion).
- **Spec trace:** §7.3 (accordion anatomy — collapsed `▾ Citations · N`, expanded as cards), ADR-14 §11 (stale-citation copy — not visualized here since all cited docs still exist, but the implementer must wire the deleted state per the verbatim string `[n] (deleted) — 이 문서는 더 이상 사용할 수 없습니다`).
- **Auth state:** signed-in.
- **Figma frame:** `M4 — Chat (loaded conversation + citation accordion)  /chat` — node `54:233`. ![screenshot](assets/M4/loaded.png)
- **Key elements:**
  - **Sidebar + topbar:** same as Frame 2.
  - **Turn 1 (y=130):** user role + body `How does the chunker handle long markdown files?` → assistant role + body with inline `[1]` `[2]` → **collapsed** accordion `▸ Citations · 2` (text-only line in `text.muted` 12px / 500). Click the chevron line → expands inline (animate height auto, ~140ms ease).
  - **Turn 2 (y=330):** user role + body `what's the retry policy on chunk-embed failures?` → assistant role + body with inline `[3]` `[4]` `[5]` markers → **expanded** accordion header `▾ Citations · 3` in `accent` 12px / 600 → accordion card list (820×220, `surface` bg + `border` 1px + `radius.md` 10px) containing 3 citation cards:
    - **Card 1 — `[3]  ADR-13 §6 Retry & backoff`** (title `font.small` 13px / 600 / `text`), excerpt in `text.muted` 12px / 400 ("Three retries on transient embedding failures: 400ms / 1.6s / 6.4s with full jitter…"), `↗ open` link in `accent` 12px / 500 (right-aligned, 740px from card left).
    - **Card 2 — `[4]  M3 PRD §"Failure handling — DLQ"`** same shape.
    - **Card 3 — `[5]  ADR-13 §10 Resilience4j retry`** same shape.
    - Cards are separated by 1px `border` dividers.
  - **Composer** (enabled, ready for next user turn): bg `surface`, placeholder `Ask anything about your corpus…`, `⏎ Send` primary button.
- **Interactions:**
  - Click `[1]` `[2]` `[3]` `[4]` `[5]` inline markers in the assistant body → scrolls / focuses the matching accordion card (in the collapsed state for turn 1, the chevron auto-expands first).
  - Click `↗ open` on a card → opens `/docs/{documentId}` in a new tab (the chunk anchor `#chunk-{chunkIndex}` is M4.1 per spec §7.3).
  - Click the chevron `▾`/`▸` toggles the accordion's expanded state.
- **Per-state:**
  - **Collapsed accordion** (rendered for Turn 1): single line `▸ Citations · N` in `text.muted` 12px / 500.
  - **Expanded accordion** (rendered for Turn 2): header in `accent` weight 600 + card list below.
  - **`RETRIEVAL_EMPTY` (N=0)** (per spec §7.5): not visualized here — accordion renders `▾ Citations · none` and expand reveals "(no citations — answer was unsupported)". The assistant body still streams (the system prompt guides Qwen3-32B to admit absence rather than fabricate).
  - **Stale citation** (per ADR-14 §11): not visualized here — when a cited doc is deleted, the card renders as `[n] (deleted) — 이 문서는 더 이상 사용할 수 없습니다` (Korean verbatim) or `(deleted — this document is no longer available)` (English fallback), with NO `↗ open` link, NO chunk_index shown. The accordion row stays present (audit trail).
- **Spacing tokens used:** `spacing.md` 16px between role label and body; `spacing.lg` 24px between turns; `spacing.sm` 8px between accordion header and the card list.

### 2.4 Top-tab overflow dropdown (open) — frame node-id: `54:301`

- **Purpose:** when the user has > 7 sessions, the 8th and beyond are folded behind a `▾ N more` trigger. This frame captures the dropdown OPEN, with 4 older sessions visible. Selecting one promotes it to the head of the visible strip (per spec §7.2).
- **PRD user stories covered:** Story 12 (Top-tab parallel sessions, max 7 visible + overflow dropdown).
- **Spec trace:** §7.2 (overflow rule — max 7 visible by `updated_at DESC`, 8th+ in the dropdown).
- **Auth state:** signed-in.
- **Figma frame:** `M4 — Top-tab overflow dropdown (open)  /chat` — node `54:301`. ![screenshot](assets/M4/overflow.png)
- **Key elements:**
  - **Sidebar + topbar + conversation body:** identical to Frame 3 (the dropdown is an overlay on top of the loaded conversation surface).
  - **Tab strip with 7 visible tabs:** Tab 1 active (`💬  Chunking policy`, 160×32 with `⋯` overflow inside it), then 6 inactive tabs with text-only labels in `text.muted` 13px / 500 (no bg, separated by ~14px gap): `Retry policy`, `Spark gateway notes`, `M3 backfill plan`, `ADR-09 amendments`, `SSE protocol grammar`, `Pgvector tuning`.
  - **`▾ 4 more` trigger:** 88×32 `surface` bg + `border` 1px + `radius.md` 8px (visually identical to the inactive-tab-on-hover state), label `▾ 4 more` in `text` 12px / 600. Currently in the OPEN state.
  - **`+` new-tab button** to the right of the `▾ more` trigger (same `surface.soft` bg as in Frames 1–3).
  - **Open dropdown overlay (280×180, `surface` bg + `border` 1px + `radius.md` 10px + `shadow.pop`):** anchored to the trigger's bottom-left.
    - Header: `OLDER SESSIONS` eyebrow in `text.muted` 11px / 600.
    - 4 rows, each: title in `text` 13px / 500 (left), date in `text.muted` 11px / 400 (right, 200px from row left).
    - Row 1: `Kafka topic provisioning · 3d ago`
    - Row 2: `M0 bootstrap retro · 5d ago`
    - Row 3: `OAuth callback debug · 1w ago`
    - Row 4: `PRD M2 review · 2w ago`
- **Interactions:**
  - Click any dropdown row → that session becomes the head of the visible strip; the oldest visible tab (`Pgvector tuning`) falls back into the dropdown.
  - Click outside the dropdown → close (no selection).
  - Hover a row → bg → `surface.soft` (subtle hover; not visualized in static frame).
- **Per-state:**
  - **Closed:** the `▾ 4 more` trigger renders alone; the dropdown overlay is not visible.
  - **Open (rendered):** the overlay is visible, the trigger's chevron rotates to `▴` (or stays `▾` per implementer preference — not pinned).
- **Spacing tokens used:** `spacing.sm` 8px between adjacent inactive tabs; `spacing.md` 16px between trigger and `+` button; `spacing.md` 16px padding inside the dropdown overlay (top/bottom and left).

### 2.5 Tab ⋯ menu (Rename + Delete) — frame node-id: `54:379`

- **Purpose:** the per-tab actions surface. Reveals on hover of the tab's right-edge `⋯` glyph; reveals Rename (inline edit) and Delete (confirm dialog).
- **PRD user stories covered:** Story 10 (Session DELETE — confirm dialog gate), Story 11 (Manual rename overrides auto-title), Story 12 (Tab hover affordances).
- **Spec trace:** §7.2 row "Tab hover affordances" — `⋯` reveals on hover, Rename inlines the tab into a text input, Delete fires the confirm dialog.
- **Auth state:** signed-in.
- **Figma frame:** `M4 — Tab ⋯ menu (Rename + Delete)  /chat` — node `54:379`. ![screenshot](assets/M4/tab-menu.png)
- **Key elements:**
  - **Underlying frame:** identical to Frame 3 (loaded conversation).
  - **Open `⋯` menu overlay (180×88, `surface` bg + `border` 1px + `radius.md` 10px + `shadow.pop`):** anchored next to the active tab's `⋯` glyph.
    - Row 1: `✎  Rename` (`text` 13px / 500). Hover → bg `surface.soft`.
    - Divider: 1px `border`.
    - Row 2: `🗑  Delete…` (`danger` 13px / 500, the ellipsis hints at a follow-up confirm dialog). Hover → bg `danger.soft`.
- **Interactions:**
  - Click `Rename` → the tab itself becomes a text input (inline edit, `Enter` commits, `Escape` reverts). Calls `PATCH /api/rag/chat/sessions/{id}` body `{title: "..."}`.
  - Click `Delete…` → opens a separate confirm modal `Delete this conversation? This cannot be undone.` with `Cancel` (secondary) + `Delete` (`danger`) buttons. On confirm: `DELETE /api/rag/chat/sessions/{id}` → tab removed from strip → page navigates to the next-most-recent session or to an empty `/chat` if none remain.
- **Per-state:**
  - **Hover row 1 (Rename):** bg `surface.soft`.
  - **Hover row 2 (Delete):** bg `danger.soft` (a soft preview of the destructive intent).
- **Spacing tokens used:** `spacing.md` 16px padding inside the menu; `spacing.sm` 8px between an icon glyph and the row label.

### 2.6 503 GATEWAY_DOWN error state — frame node-id: `54:443`

- **Purpose:** the inference gateway is unavailable (Resilience4j circuit breaker OPEN, or sustained 5xx exhausted retries). Banner above composer with `Retry last message` button. Conversation history above is unaffected — the user can still scroll and read.
- **PRD user stories covered:** Story 15 (Clear error banner — distinct from silent hang).
- **Spec trace:** §7.5 row "503 GATEWAY_DOWN" + ADR-14 §6 (circuit breaker → 503 → red banner), ADR-14 §G.4 (ADR-09 amendment removing anon route).
- **Auth state:** signed-in.
- **Figma frame:** `M4 — 503 GATEWAY_DOWN error state  /chat` — node `54:443`. ![screenshot](assets/M4/503.png)
- **Key elements:**
  - **Underlying frame:** identical to Frame 3 (loaded conversation).
  - **Banner (1112×52, frame-relative y=792, just above the composer):** bg `danger.soft` (`#F4E1DA`), border 1px `danger` (`#B14B3B`), `radius.md` 10px.
    - Left edge (20px in): `⚠` glyph in `danger`, 18px.
    - Title: `AI service is currently unavailable.` in `danger` 14px / 600.
    - Body subtitle: `The inference gateway is failing. Try again in a moment.` in `text.muted` 12px / 400.
    - Right edge: `↻  Retry last message` outline button (`surface` bg + `danger` 1px border + `danger` fg 13px / 600 + `radius.sm` 8px, 160×32).
  - **Composer:** still enabled (the failure was on the previous turn, not now); user can also type a new message and submit normally.
- **Interactions:**
  - Click `↻  Retry last message` → resubmits the same user message (same `sessionId`, same `message` body). Banner persists until the retry succeeds (then dismisses on `done` event) or fails again (then re-renders the banner with the new failure timestamp).
  - The banner has NO close button — the only dismissal paths are "retry succeeds" or "user submits a different turn that succeeds."
- **Per-state:**
  - **Circuit breaker OPEN** vs **5xx exhausted retries** — same banner copy (both surface as `code: GATEWAY_DOWN` per ADR-14 §6.5).
- **Spacing tokens used:** `spacing.md` 16px between banner and composer; `spacing.sm` 8px padding inside the banner (top/bottom).

### 2.7 429 RATE_LIMIT error state — frame node-id: `54:509`

- **Purpose:** the user's hourly cap (60 completions/hour, per ADR-14 §5) is exhausted. Yellow banner with countdown; composer disabled until cooldown ends.
- **PRD user stories covered:** Story 15 (Error banner — RATE_LIMIT variant), Story 19 (Per-user rate limit enforcement — this is the user-facing artifact).
- **Spec trace:** §7.5 row "429 RATE_LIMIT" (yellow banner with `retryAfter` countdown, composer disabled until countdown reaches 0), ADR-14 §5 (RRateLimiter; `Retry-After` header value drives the countdown).
- **Auth state:** signed-in.
- **Figma frame:** `M4 — 429 RATE_LIMIT error state  /chat` — node `54:509`. ![screenshot](assets/M4/429.png)
- **Key elements:**
  - **Underlying frame:** identical to Frame 3 (loaded conversation).
  - **Banner (1112×52):** bg `warning.soft` (`#F4E8C7`), border 1px `warning` (`#B58A2B`).
    - Left: `⚠` glyph in `warning` 18px.
    - Title: `You've hit your hourly limit.` in `warning` 14px / 600.
    - Body subtitle: `Try again in 13 minutes. Hourly cap is 60 completions per user (cost ceiling).` in `text.muted` 12px / 400.
    - Right edge: countdown pill (`warning.soft` bg + `warning` 1px border + `warning` fg, 160×32, `radius.sm` 8px). Label: `⏱  13 : 24` (mm:ss countdown derived from the `Retry-After` header value).
  - **Composer (disabled):** bg `surface.soft`; placeholder `Rate limit reached — disabled until cooldown ends` in `text.subtle`; Send button bg `surface.soft` + label `text.subtle` (visually-muted Send glyph; click is no-op).
- **Interactions:**
  - The countdown ticks down every second client-side; on reaching `00 : 00` the banner dismisses, composer re-enables. (No server round-trip — the countdown is a UI optimism; the next actual submit will retry server-side and reveal whether the bucket has refilled.)
- **Per-state:**
  - **Cooldown active:** rendered state. Composer disabled.
  - **Cooldown elapsed (countdown reached 0):** banner dismisses, composer re-enables. The frame doesn't visualize this state separately.
- **Spacing tokens used:** identical to Frame 6 (same banner pattern, different palette).

### 2.8 Sidebar Chat row variants (auth-lock + active) — frame node-id: `54:575`

- **Purpose:** documents the new `🔒 Sign in` (auth-lock) sidebar badge alongside the existing `🔒 Mx` (milestone-lock) and active/no-badge variants. ADR-14 §G.4 amends ADR-09 to register this third state.
- **PRD user stories covered:** Story 17 (Sidebar `Chat` row unlock on M4 deploy + lock for anon users).
- **Spec trace:** §7.6 (Anon visiting `/chat` — Apps "Chat" renders with `🔒 Sign in` badge), §7.7 (Apps row state matrix post-M4), §13 (cross-doc amendment: ADR-09 gains the auth-lock badge convention).
- **Auth state:** the frame visualizes both states side by side; the LEFT half is signed-in, the RIGHT half is anonymous.
- **Figma frame:** `M4 — Sidebar Chat row variants (auth-lock + active)  global` — node `54:575`. ![screenshot](assets/M4/sidebar-variants.png)
- **Key elements:**
  - **Frame title:** `Sidebar 'Chat' row — auth state variants` (`font.h2` 20px / 600 / `text`).
  - **Frame subtitle:** `ADR-09 introduces a third sidebar-Apps badge state: 🔒 Sign in for auth-lock, distinct from 🔒 Mx milestone-lock.` (`font.small` 13px / 400 / `text.muted`).
  - **LEFT sidebar (signed-in variant):** 232×560 sidebar in `surface.soft`. Apps section: `Home` (inactive, primary fg), `Docs` (inactive, primary fg — shipped post-M2), `Chat` (active, `accent.soft` bg + `accent` fg + `font.body` 13px / 600, **no badge**), `System status` (muted, `text.subtle` fg, `M5 🔒` milestone-lock badge). Caption below: `Chat row · active · no badge · accent` in `text.muted` 12px / 500.
  - **RIGHT sidebar (anonymous variant):** same 232×560 frame. Apps section: `Home` (active, primary fg — anon's only deeply-clickable destination), `Docs` (inactive but clickable, primary fg — `/docs` is public), `Chat` (muted, `text.subtle` fg, **`🔒 Sign in` auth-lock badge** in `text.subtle` 10px / 500), `System status` (muted, `text.subtle` fg, `M5 🔒` milestone-lock badge). Caption below: `Chat row · muted · 🔒 Sign in badge · click → /login?return=/chat` in `text.muted` 12px / 500.
- **Interactions:**
  - **Active Chat row (left):** click → `/chat` (the user's most-recent session or a fresh new session).
  - **Auth-locked Chat row (right):** click → `/login?return=/chat` (per spec §7.6).
  - **Milestone-locked rows (`M5 🔒` on both sides):** click is no-op (cursor `default`, tooltip `Available when M5 ships` per M1 spec).
- **Per-state:**
  - **Signed-in (left):** Chat = active.
  - **Anon (right):** Chat = auth-locked with `🔒 Sign in` badge.
  - **Future un-shipped milestone (e.g., the day M5 ships):** would render the active state (no badge) for M5, exactly mirroring how Chat unlocks at M4 deploy in this design.
- **Spacing tokens used:** `spacing.xl` 40px between the two sidebar columns; `spacing.lg` 24px padding inside each sidebar; `spacing.md` 16px between APPS label and the first row; `spacing.sm` 8px vertical gap between adjacent rows.

## 3. Reused chrome elements

These widgets ship from M1 / M2 verbatim; M4 does NOT introduce alternative versions. The frontend-implementer should locate the existing FSD `widgets/Sidebar/` and `widgets/Topbar/` and add `Chat` row metadata there rather than fork.

| Element | Source design doc | Source frame in Figma | M4 frames where it appears |
|---|---|---|---|
| **Brand row** (J glyph + stacked `JeekLee's` / `PLAYGROUND` wordmark) | M1 §"Public Home" | `14:2` (M1 public home), `14:135` (signed-in home) | All 7 chat frames (1–7) |
| **⌘K search pill** (sidebar trigger that opens the global palette overlay) | M2 §"⌘K palette" | `27:704` | All 7 chat frames (1–7) |
| **Sidebar Apps section with locked-row treatment** | M2 §7.1 | All M2 frames with signed-in sidebar | All 7 chat frames (1–7) — Docs row inactive (shipped at M2 so primary fg), Chat row active (shipped at M4 with `accent.soft` bg + `accent` fg), System status muted with `M5 🔒` milestone-lock badge |
| **Signed-in account footer card** (28px khaki avatar + stacked name/email) | M1 §"Signed-in Home" | `14:135` | All 7 chat frames (1–7) |
| **Slim topbar** with breadcrumb + `● Signed in` success chip + account pill | M1 §"Signed-in Home" | `14:135` | All 7 chat frames (1–7) — breadcrumb updates per-frame to `Home / Chat / <session title>` |
| **Account pill** (24px khaki avatar + display name + chevron, `surface` bg + `border` 1px + `radius.pill`) | M1 §"Signed-in Home" + M2 "Account-pill dropdown" overlay | `14:135` + `30:892` | All 7 chat frames (1–7) |
| **Composer's `⏎ Send` primary button** | Reused from design system §6.1 primary variant | n/a (primitive) | Frames 1, 3 (active state) |

## 4. New components introduced by M4

These are new — the frontend-implementer scaffolds them under `client/src/widgets/Chat/` (or feature-folder of their choice). All read from the same token vocabulary; no new tokens.

### 4.1 Top tab strip

- **Container:** 1208×52 frame anchored below the topbar, `bg` color, `border-bottom 1px border`, horizontal auto-layout, padding `12px 28px`, gap 8px between adjacent siblings.
- **Active tab:** 160–200×32 (auto-fit to title), `surface` bg + `border` 1px + `radius.md` 8px, label in `text` 13px / 600. Right edge of the active tab hosts the `⋯` overflow trigger in `text.muted` 14px / 500.
- **Inactive tab:** text-only label in `text.muted` 13px / 500 (no bg). Hover → label color shifts to `text`, optional `surface.soft` bg.
- **Overflow `▾ N more` trigger:** 88×32, `surface` bg + `border` 1px + `radius.md` 8px, label in `text` 12px / 600. Shown only when total tabs > 7.
- **`+` new-tab button:** 32×32, `surface.soft` bg + `radius.md` 8px, `+` glyph in `text` 15px / 600 centered.
- **Overflow dropdown** (when `▾ N more` is clicked): 280×auto, `surface` bg + `border` 1px + `radius.md` 10px + `shadow.pop`, anchored bottom-left of the trigger. Padding 12px. Header eyebrow `OLDER SESSIONS` + rows of `<title> · <relative date>` per session.
- **Tab `⋯` menu** (when the `⋯` is clicked on the active tab): 180×88, `surface` bg + `border` 1px + `radius.md` 10px + `shadow.pop`. Two rows: `✎ Rename` and `🗑 Delete…` separated by a 1px `border` divider. Delete uses `danger` fg.
- **Visualized in:** Frame 1 (single active tab + `+`), Frame 4 (7 tabs + `▾ 4 more` open), Frame 5 (`⋯` menu open).

### 4.2 Citation accordion (inline)

- **Container:** sits directly below the assistant message body. **No side panel** (per spec §7.3).
- **Collapsed state:** single text line `▸ Citations · N` in `text.muted` 12px / 500. Click anywhere on the line → expand.
- **Expanded header:** `▾ Citations · N` in `accent` 12px / 600.
- **Card list (when expanded):** 820×auto frame, `surface` bg + `border` 1px + `radius.md` 10px. Cards stacked vertically with 1px `border` dividers between them.
  - **Each card:** padding 14px (top/left/right) + 12px (bottom). Title row: `[n]  <Document title>` in `text` 13px / 600 (left), `↗ open` link in `accent` 12px / 500 (right). Excerpt below the title: first ~160 chars of the chunk text in `text.muted` 12px / 400, max 2 lines with ellipsis on overflow.
  - **Stale citation card** (when the cited doc is deleted): title becomes `[n] (deleted) — 이 문서는 더 이상 사용할 수 없습니다` (Korean primary) or `(deleted — this document is no longer available)` (English fallback) in `text.muted` 13px / 500. **No `↗ open` link, no chunk_index shown** — both hidden per ADR-14 §11.
  - **`RETRIEVAL_EMPTY`** (N=0): header renders `▾ Citations · none`; expanded body renders single line `(no citations — answer was unsupported)` in `text.muted` 12px / 400.
- **Visualized in:** Frame 3 (collapsed accordion for Turn 1, expanded accordion for Turn 2 with 3 cards).

### 4.3 Streaming message (pulsing cursor + Stop button)

- **Pulsing cursor `▍`:** an 10×20 `accent` rectangle, animated `opacity` 0.3 ↔ 1.0 at ~1s cadence. Placed at the tail of the streaming text.
- **In-message Stop button:** 90×28, `surface` bg + `border.strong` 1px + `radius.md` 10px, label `⏹ Stop` in `text` 13px / 500. Click → SSE abort. P95 ≤ 200ms (per spec §12 "UX").
- **Composer Stop variant:** during a stream the composer's Send button recolors to `danger` bg + white `⏹ Stop` label. The composer input itself becomes `surface.soft` bg + `text.subtle` placeholder `응답이 생성 중입니다…` (Korean verbatim per spec §7.4 — the implementer may surface English `Generating response…` when `Accept-Language: en`).
- **Why two Stop affordances:** discoverability. Per spec §7.4: "the same Stop affordance as the in-message Stop, kept duplicated for discoverability."
- **Aborted state** (after Stop is clicked): partial assistant text is greyed out (`text.muted`); a footer line `Generation stopped` in `text.muted` 12px / 400. Per ADR-14 §13 the partial text is NOT persisted — reload removes the assistant turn entirely.
- **Visualized in:** Frame 2 (mid-stream with both Stop affordances).

### 4.4 Auth-lock sidebar badge variant

- **The new `🔒 Sign in` badge:** rendered in `text.subtle` 10px / 500, right-aligned in the Apps row. The Apps row itself is in `text.subtle` (muted state).
- **Distinct from `🔒 M5` milestone-lock:** `🔒 Sign in` click → `/login?return=<currentPath>`; `🔒 Mx` click → no-op (cursor `default`, tooltip `Available when Mx ships`).
- **ADR-09 amendment** (per ADR-14 §G.4) registers this as a first-class third badge state alongside the active (no badge) and milestone-lock states. Future auth-only routes (none currently planned beyond `/chat`) would reuse this badge.
- **Visualized in:** Frame 8 (side-by-side comparison: left = signed-in / Chat active / no badge; right = anon / Chat muted / `🔒 Sign in` badge).

## 5. Tokens used (colors, spacing, typography)

Every value is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer should already have these wired into `client/src/shared/ui/tokens/` from M1; M4 introduces ZERO new tokens.

| Token | Value | Where used in M4 |
|---|---|---|
| `color.bg` | `#FAF7EF` | Main content bg on every chat frame; tab-strip bg; topbar bg |
| `color.surface` | `#FFFFFF` | Composer; active tab; citation accordion container + each card; `+` new-tab button hover; account-footer card; account pill; `▾ more` trigger; tab `⋯` menu; overflow dropdown |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; `+` new-tab button bg; locked-row badges; disabled-composer bg; tab `⋯` menu hover; suggestion-chip hover |
| `color.border` | `#E6E0CB` | All card / tab / accordion 1px strokes; topbar `border-bottom`; tab strip `border-bottom`; accordion card dividers; account-pill stroke |
| `color.border.strong` | `#D6CFB3` | Composer 1px stroke; in-message Stop button stroke |
| `color.khaki` | `#C2B88A` | Sidebar-footer avatar fill; topbar account-pill avatar fill |
| `color.text` | `#2A2C20` | Frame title `Chat`; user / assistant message bodies; suggestion-chip labels; active tab label; tab `⋯` Rename row; `+` glyph |
| `color.text.muted` | `#6F6A55` | Breadcrumb; tab-strip inactive-tab labels; user-role `you` eyebrow; collapsed-accordion line; accordion excerpts; account-pill subtext; 503 / 429 banner body subtitles |
| `color.text.subtle` | `#8B8670` | Search-pill placeholder; sidebar APPS section label; locked Apps row labels and badges; composer placeholder; disabled composer Send label; auth-lock badge `🔒 Sign in` |
| `color.accent` | `#6E7A3A` | Active `Chat` sidebar row fg; assistant-role `assistant` eyebrow; expanded-accordion header `▾ Citations · N`; `↗ open` link; primary Send button bg; suggestion-chip focus ring (focus state); pulsing streaming cursor `▍` |
| `color.accent.soft` | `#E9E8D1` | Active `Chat` sidebar row bg; (reserved for accent chips on inline `[N]` markers when implementer wires the marker → pill transform) |
| `color.success` | `#4F6B2E` | Topbar `● Signed in` chip fg (carried from M1) |
| `color.success.soft` (= `#E5EBD9` from M1) | — | Topbar `● Signed in` chip bg |
| `color.danger` | `#B14B3B` | 503 banner border + glyph + title; tab `⋯` Delete row fg; disabled Send button text (when red) |
| `color.danger.soft` | `#F4E1DA` | 503 banner bg; tab `⋯` Delete hover bg |
| `color.warning` | `#B58A2B` | 429 banner border + glyph + title + countdown pill fg |
| `color.warning.soft` (= `#F4E8C7`) | — | 429 banner bg + countdown pill bg |
| `font.h2` | 20px / 600 / -0.01em | Empty-state hero title (`What do you want to know…`); Frame 8 title (`Sidebar 'Chat' row — auth state variants`) |
| `font.h3` | 16px / 600 | Citation card titles |
| `font.body` | 15px / 400 / 0 | All message bodies (user + assistant); composer placeholder |
| `font.small` | 13px / 400–500 / 0 | Tab labels (active 600, inactive 500); suggestion-chip labels (500); composer placeholder (when 14px); button labels |
| `font.eyebrow` | 11px / 600 / +0.14em / uppercase | `APPS` sidebar label; user-role `you` and assistant-role `assistant`; `OLDER SESSIONS` dropdown header; `SIGNED-IN` / `ANONYMOUS / LOGGED-OUT` variant labels in Frame 8 |
| `font.mono` | 13px / 400 | Reserved for inline `[N]` markers and the `▍` cursor character if the implementer renders them as monospace pills (not required) |
| `spacing.xs` | 4px | Chip/badge dot to label; intra-button gap between glyph and label |
| `spacing.sm` | 8px | Tab-strip gap between tabs; suggestion-chip vertical gap |
| `spacing.md` | 16px | Banner padding (top/bottom); accordion card padding; sidebar Apps row vertical gap; in-message Stop button bottom margin from assistant body |
| `spacing.lg` | 24px | Frame outer padding; gap between turns in the conversation; topbar vertical rhythm |
| `spacing.xl` | 40px | Gap between the two sidebar columns in Frame 8 |
| `radius.sm` | 6px | Reserved for inputs / kbd |
| `radius.md` | 10px | Suggestion chips; banner; in-message Stop button; tab active bg; citation accordion + each card; tab `⋯` menu; overflow dropdown; sidebar active-row bg; `+` new-tab button |
| `radius.lg` | 14px | Composer (per M2 + M1 convention) — but in M4 the composer uses 12px per the spec wireframe ASCII, which sits between md and lg and matches the existing M2 composer treatment |
| `radius.pill` | 999px | Topbar `● Signed in` chip; account pill; sidebar avatar; topbar pill avatar |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | Composer (resting); citation accordion container (resting) |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | Overflow dropdown overlay; tab `⋯` menu overlay |

**Verification note:** every color hex in this document appears verbatim in the design system spec §3.1 / §3.2 / §3.3 (or §5.3 for elevation). No new tokens were invented. No spec hex was substituted with a near-miss.

## 6. Per-state behavior matrix (interactions across screens)

Cross-frame state transitions the frontend-implementer must wire. Each row reads as "trigger → resulting frame and observable state delta."

| User action | Source frame | Target frame / state | Notes |
|---|---|---|---|
| Press `Enter` in empty-state composer | Frame 1 (empty) | Frame 2 (mid-stream) | User message appended; "Thinking…" indicator (not visualized) until first `retrieval` event; then transition to Frame 2 mid-stream rendering. |
| Click any suggestion chip | Frame 1 (empty) | Frame 1 (empty, composer pre-filled) | Per spec §7.5 — chip pre-fills the composer with the verbatim chip string; does NOT auto-send. |
| Click `⏹ Stop` (in-message OR composer) | Frame 2 (mid-stream) | "Aborted" view (not a separate frame — text greyed out + `Generation stopped` footer) | P95 ≤ 200ms SSE abort. Partial assistant text NOT persisted per ADR-14 §13. Page reload removes the assistant turn entirely. |
| Stream `done` event arrives | Frame 2 (mid-stream) | Frame 3 (loaded) | Pulsing cursor + in-message Stop disappear; composer re-enables; new accordion appears below the assistant message (collapsed by default). |
| Click `▸ Citations · N` on a collapsed accordion | Frame 3 (Turn 1 area) | Frame 3 (Turn 1 accordion expanded) | Accordion expands inline (height: auto, ~140ms ease). Other accordions in the same conversation are NOT auto-collapsed. |
| Click `↗ open` on a citation card | Frame 3 | Opens `/docs/{documentId}` in a new tab | Chunk anchor `#chunk-{chunkIndex}` is M4.1 per spec §7.3 — P0 link target is the doc detail page only. |
| Click `▾ N more` overflow trigger | Frame 3 (or any 7+ tab state) | Frame 4 (overflow dropdown open) | Dropdown overlay appears below the trigger; trigger remains visible. |
| Click a dropdown row | Frame 4 | Frame 3 (loaded — selected session becomes head of strip) | The chosen session is promoted to the visible strip's head; the oldest visible tab falls back into the dropdown. |
| Hover the active tab's `⋯` | Frame 3 | Frame 5 (`⋯` menu open) | The menu is anchored adjacent to the `⋯` glyph. Clicking outside dismisses without action. |
| Click `Rename` in `⋯` menu | Frame 5 | Tab becomes inline text input | Per spec §7.2; `Enter` commits via `PATCH /api/rag/chat/sessions/{id}`; `Escape` reverts. |
| Click `Delete…` in `⋯` menu | Frame 5 | Confirm modal (`Delete this conversation? This cannot be undone.`) | Modal not designed as a separate frame — implementer reuses the M2 delete-modal pattern (`29:818`) with the chat copy. On confirm: `DELETE /api/rag/chat/sessions/{id}` → tab removed → navigate to next-most-recent or empty `/chat`. |
| Submit user turn that triggers 503 from gateway | Frame 2 → SSE `error: GATEWAY_5XX` | Frame 6 (503 banner) | The in-flight stream aborts. The user message remains persisted. The banner is non-dismissible until retry succeeds or a different turn succeeds. |
| Click `↻ Retry last message` in 503 banner | Frame 6 | Frame 2 (mid-stream, new attempt) | Resubmits the same user message; same `sessionId`. On success → Frame 3; on repeat failure → Frame 6 stays. |
| Submit user turn that hits rate limit | Frame 1/3 → SSE `error: RATE_LIMIT` | Frame 7 (429 banner) | Composer disables. Countdown ticks every 1s until 0; then composer re-enables, banner dismisses. |
| Tab switch during stream | Frame 2 (mid-stream) | Target session's view | Active stream aborts (same path as Stop). Partial assistant text NOT persisted. |
| Anonymous user clicks sidebar `Chat` row | Frame 8 right variant | `/login?return=/chat` | Per spec §7.6 — Next.js middleware redirects server-side, no chat UI flicker. |
| Signed-in user with NO sessions yet clicks sidebar `Chat` row | Frame 8 left variant | Frame 1 (empty state) | Backend creates a new empty session (auto on first visit) and surfaces it as the active tab. |

## 7. Open questions for the frontend-implementer

The PRD's "Open questions for the implementer" list (PRD §"Open questions for the implementer") and ADR-14's "Open questions deferred beyond M4" are the canonical lists. **All 17 ADR-14 questions are closed in ADR-14** — the implementer does not need to make any of those decisions. The questions specifically deferred to Stage 3 of the frontend (i.e., to the frontend-implementer who reads this design doc) are:

1. **Inline `[N]` marker → pill transform.** The static mocks render `[1]` `[2]` `[3]` as raw text in the assistant body. The actual implementation should transform these (post-Markdown render, via a `remark` plugin per spec §9) into superscript-pill spans: `accent.soft` bg + `accent` fg + `font.eyebrow` 11px + `radius.pill` + 2px x-padding, click target = scrolls / expands the matching accordion card. The design doc does not visualize the pill shape because it would clutter the mock; rendering specifics are the implementer's call within the token vocabulary.

2. **Streaming cursor animation cadence.** The mock shows a static rectangle in `accent`. The intended behavior: `opacity` animation between 0.3 and 1.0 at ~1s cadence. Implementer picks an exact curve (e.g., `cubic-bezier` or `linear`). No spec.

3. **Tab strip overflow rule edge cases.** Spec §7.2 says "max 7 visible." When the user creates an 8th tab via `+`, the spec doesn't say which previously-visible tab gets demoted — the natural rule is "oldest by `updated_at`" but on tie (two tabs with same `updated_at`) the tiebreaker isn't specified. Implementer chooses (e.g., `created_at` DESC, then `id` lexicographic).

4. **Mobile (≤719 px) fallback.** Spec §2 + PRD Story 18: viewport ≤ 719px → render "Chat is desktop-only for now" card; full mobile layout is M4.1. The exact copy + the card's visual treatment are not pinned. Implementer picks within the design system — `surface` card + `font.h3` headline + `font.small` body + an `accent` text-link to a follow-up announce page (TBD).

5. **Auto-scroll pause when user scrolls up mid-stream.** Spec §9 mentions: "if the user scrolls up mid-stream, the auto-scroll pauses and a `Jump to latest` button appears at the bottom-right." That button is NOT visualized in any M4 frame. Implementer designs it: small floating pill in `accent` + `↓ Jump to latest` label, appears at the bottom-right when `scrollTop < scrollHeight - clientHeight - 50`. Within the existing token vocabulary.

6. **OpenGraph metadata for `/chat`.** Unlike `/docs/{id}` (which has `generateMetadata` in M2 §"Sharing + OpenGraph"), `/chat` is auth-only and not share-target-friendly. Frontend-implementer: should `/chat` even export OG metadata? Recommendation: render a generic "JeekLee's playground — Chat" with the brand glyph as `og:image`; do NOT include session titles or content (PII concern). The doc takes no position on this.

7. **Inline `[N]` marker collision across turns.** Spec §6.1 step 10 says "Prior assistant turns are stripped of their `[N]` markers before being included" in the prompt assembly. But on the **rendering** side, the same conversation may have a Turn-1 `[1]` and a Turn-2 `[1]` both visible. The implementer must scope marker-to-citation matching by `(turn, n)` not just `n`. This is implicit in the design (each accordion is per-message) but worth flagging.

---

**End of design context.** The frontend-implementer reads this doc as the canonical specification for Stage 3 frontend work; it does NOT modify the PRD or ADR-14 (those are PM / architect property). If a UI question is genuinely under-specified beyond this doc + the spec + the ADR, the implementer surfaces it as a question to the orchestrator rather than guessing.
