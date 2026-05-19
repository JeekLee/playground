# Design Context: M5 — Metrics

> PRD: `docs/prd/M5-metrics.md`
> Spec: `docs/superpowers/specs/2026-05-19-m5-metrics-design.md`
> ADR: `docs/adr/15-m5-metrics.md` (per-milestone; pins the 11-cell service health grid, observability self-monitoring P0, Loki label set, health verdict rules)
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md`
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN (M5 row at y = 3300, below the M4 row that ends at y = 3100; the 5 M5 frames sit on a single horizontal track at y = 3300)
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill, brand row), `docs/design/M2-docs.md` (sidebar Apps section with locked/unlocked rows + badge convention, ⌘K search pill), `docs/design/M4-rag-chat.md` (account footer, viewport-locked layout principle from §1.5, accent line-chart style, warning palette `#B58A2B` / `#F4E8C7` for the optional 429 banner). All chrome is reused verbatim; M5 only introduces what is new (sticky range pill strip, 11-cell service health grid, Updated-Ns-ago indicator + refresh button, sparkline / line-chart card primitives, per-widget degrade overlay).

Stage 2 output for the Metrics (M5) bounded context. **5 desktop frames at 1440 × 900**, built strictly against the playground design system tokens. The page is viewport-locked per the convention introduced for M4 §1.5 — there is no scrollable composer or input on `/metrics`; the sticky range-pill strip is anchored to the top of the content area, the sidebar account footer stays anchored to viewport bottom, and the dashboard widgets occupy the area in between. Sidebar follows the M2 spec §7.1 single-Apps-section convention; the **System status** row is the **active row** on every M5 dashboard frame (replacing the pre-M5 `🔒 M5` milestone-lock badge). Crucially, the M4 `🔒 Sign in` auth-lock variant **does not apply** here — `/metrics` is public per ADR-09 amendment in ADR-15 §G.2, so the only two states for this sidebar row are *milestone-locked (pre-M5 ship)* and *active (post-M5 ship)*. Tokens table below is sourced verbatim from the design system spec; **no new tokens** are introduced by this milestone.

> **Asset note (carried from M1 / M2 / M4 design rounds):** PNG export via `mcp__TalkToFigma__export_node_as_image` returns base64 to the harness as inline visual content rather than passing through as text. The same blocker applies here. The Figma file is the canonical visual reference; the operator one-off renders `docs/design/assets/M5/{loaded,degraded,skeleton,widget-failed,sidebar-variants}.png` via `File → Export selected → PNG @ 2x` per the same workflow used in earlier milestones. The 5 frames are fully assembled and verified via per-frame export-and-visualization during the design run.

> **Frame-name limitation (carried from M2 / M4):** the Talk to Figma plugin allowlist exposes `set_text_content` for TEXT nodes but no frame-rename tool. **Three of the five M5 frames carry the stale clone name "M5 — System status (loaded, all healthy)  /metrics"** because they were built by cloning the verified Frame 1 and mutating interiors. The frame's **internal content** is M5-correct; the human reviewer should right-click → Rename in Figma before exporting PNG assets. Mapping table immediately below.

## Frame mapping (5 frames)

| # | Frame node-id | Current Figma name (stale where flagged) | Intended name | Position (x, y) |
|---|---|---|---|---|
| 1 | `74:598` | (canonical, correct) | `M5 — System status (loaded, all healthy)  /metrics` | (0, 3300) |
| 2 | `74:762` | `M5 — System status (loaded, all healthy)  /metrics` *(stale)* | `M5 — System status (one BC degraded)  /metrics` | (1490, 3300) |
| 3 | `74:927` | `M5 — System status (loaded, all healthy)  /metrics` *(stale)* | `M5 — System status (initial load — skeleton)  /metrics` | (2980, 3300) |
| 4 | `74:1091` | `M5 — System status (loaded, all healthy)  /metrics` *(stale)* | `M5 — System status (one widget failed)  /metrics` | (4470, 3300) |
| 5 | `74:1257` | (canonical, correct) | `M5 — Sidebar System status row variants (locked + active)  global` | (5960, 3300) |

## 1. Overview

M5 is the playground's **first observability surface** and the first BC whose UX is **read-only, polling-driven, and entirely composed of data widgets**. There is no composer, no input, no toggle that mutates server state — the page exists to answer "is the stack alive, what's its current shape?" at a 15 s cadence. Its design therefore introduces five primitives that no prior milestone had to express:

1. **Sticky range-pill strip** spanning the top of the content area (under the topbar) — five pills (`15m / 1h● / 6h / 24h / 7d`) on the left, the `Updated Ns ago` indicator + `⟳` manual refresh button on the right (per spec §7.2). Always visible; never scrolls out.
2. **11-cell Service Health grid** — 6 BCs (gateway, identity-api, docs-api, rag-ingestion-api, rag-chat-api, metrics-api) on row 1, then spark-inference-gateway + 4 observability self-cells (prometheus, loki, alloy, cadvisor) on row 2 (per ADR-15 §17). Spec's §7.1 wireframe shows 6 cells but is **out of date**; ADR-15 §17 mandates the extra 4 cells for self-monitoring so the operator can distinguish "stack down" from "observability stack down" (per ADR-15 §17 "Why P0").
3. **Per-widget degrade overlay** — when `/api/metrics/timeseries` returns 5xx for a single chart OR the widget is flagged `"degraded": true` (PromQL budget exceeded — spec §8.2), only that widget shifts to a danger.soft fill + `⚠ Failed to refresh` overlay + retry link. The remaining 18 widgets stay normal (per spec §7.3 row "Stale (one widget)").
4. **Service health cell with status badge** — `✅` accent.soft variant for `up`, `⚠️` warning variant for `degraded`, `🛑` danger variant for `down` (verdict rules in ADR-15 §9 truth table). The cell carries a label + a one-line detail (uptime, latency, target count) sized for at-a-glance scan.
5. **Skeleton placeholder treatment** — on initial load every value text becomes `—` in `text.subtle`, every chart line color drops to `surface.soft`, and the `Updated Ns ago` indicator reads `Loading…`. No animation is encoded in the static mock; the implementer adds a subtle pulse via CSS animation. (Per spec §7.3 row "Initial load.")

All other chrome — sidebar primitives, topbar primitives, account pill, ⌘K search pill, `font.body` rendering, button tokens — comes from M1 + M2 + M4 verbatim. Frontend-implementer does **not** scaffold a second design token set, a second sidebar widget, or a second topbar. The intent: the user reaches `/metrics` and the shell feels indistinguishable from `/docs` and `/chat`.

### 1.5 Viewport-locked layout (load-bearing, inherited from M4 §1.5)

The `/metrics` page occupies one viewport (typically 1440 × ~900 on desktop). It is **not** a scrollable feed. The header strip (range pills + Updated indicator) is **sticky at the top of the content area**, the sidebar account-footer stays **anchored to the sidebar bottom**, and the 11 widgets are sized to fit within the 900 px viewport without internal scroll on the canonical desktop breakpoint. If the host enlarges its browser the cards grow proportionally (Recharts is CSS-variable / viewport-aware per ADR-15 §8); if the host shrinks below 1440, widgets reflow with hard breakpoints at 1024 (3 widgets / row), 768 (2 / row), and ≤720 (single-column sparkline-only fallback per spec §7.5, M5.1 produces the real mobile reflow).

Implementation contract:

- Page shell uses `height: 100vh` (or `100dvh` on mobile-capable browsers).
- Sidebar (232 × 100vh) and topbar (56 px) are page chrome — always visible, never overlapped.
- **Sticky header strip** (range pills + `Updated Ns ago` + `⟳`) sits directly under the topbar with 1px `border-bottom: 1px solid border`; height 72 px (top padding 22 / content 28 / bottom padding 22); never scrolls.
- **Widget area** is the only scrollable region (when the viewport is too short to fit all widgets) — `flex: 1; overflow-y: auto;` between the sticky header strip and the sidebar bottom. On the canonical 1440 × 900 frame the widgets pack vertically without overflow.
- **Sidebar account footer card** is anchored to the sidebar's bottom edge (the sidebar itself = 100vh), 16 px from bottom. It does NOT scroll independently.
- **Per-widget degrade overlay** does NOT pop over neighbouring widgets — the danger.soft fill stays inside the widget's `border-radius: 10px` envelope. Other widgets retain their normal `surface` fill.
- **No composer.** No input. No toggle. The only mutable affordances on the page are: range pill click (URL + URL-derived state), `⟳` manual refresh (resets the 15 s polling timer), per-widget Retry link (inside a degraded widget — calls the per-widget endpoint again).

Coordinate contract for the Figma mocks (every dashboard frame is 1440 × 900 with top-left at `(frame_x, 3300)`):

| Element | Frame-relative top-left | Frame-relative bottom-right | Notes |
|---|---|---|---|
| Sidebar (232 × 900) | `(0, 0)` | `(232, 900)` | Full-height, anchored to viewport. |
| Topbar (1208 × 56) | `(232, 0)` | `(1440, 56)` | Breadcrumb left, `Viewing publicly` chip + account pill right. |
| Sticky header strip (1208 × 72) | `(232, 56)` | `(1440, 128)` | Range pills (left, x=258 onward) + `Updated Ns ago` + `⟳` (right, x=1265 onward). Border-bottom 1 px `border`. |
| Service Health grid 6-cell row 1 (1110 × 56) | `(258, 170)` | `(1368, 226)` | 6 cells × 175 + 5 gaps × 12. |
| Service Health grid 5-cell row 2 (923 × 56) | `(258, 238)` | `(1181, 294)` | 5 cells × 175 + 4 gaps × 12. Right edge ragged — the 5-cell row is intentionally narrower than row 1 (no need to stretch). |
| Host card row (1116 × 96) | `(258, 336)` | `(1374, 432)` | 4 cards × 270 + 3 gaps × 12. |
| JVM heap chart row | `(258, 474)` | dynamic | Auto-sized to the dashboard payload's `jvm[]`. Slice-1 ships 6 JVM-bearing services. The grid is `xl:grid-cols-6` (single row ≥1280 px), `lg:grid-cols-3` (2 rows of 3), `md:grid-cols-2`, else single column. Each card stays 128 px tall; width auto-sizes to the column track. The original `4 cards × 270` fixed grid was retired post-slice-1 when the row widened from 4 → 6 services. |
| HTTP rate cards (175 × 236) × 3 + spark-latency wide (365 × 236) + spark-models (175 × 236) | `(258, 644)` to `(1374, 880)` | — | Mixed-width row anchored to the bottom of the viewport. |
| Sidebar account footer card (200 × 66) | `(16, 818)` | `(216, 884)` | 16 px from sidebar bottom. |

## 2. Per-screen specifications

### 2.1 `/metrics` (loaded, all healthy) — frame node-id: `74:598`

- **Purpose:** the canonical happy path. Operator (or anon visitor) lands on `/metrics`, the page has finished its first `GET /api/metrics/dashboard?range=1h` round-trip, all 11 service health cells are `up`, all hosts / JVM / HTTP / spark widgets carry recent data.
- **PRD user stories covered:** Story 6 (dashboard payload), Story 7 (services endpoint reflects in grid), Story 8 (per-chart timeseries), Story 12 (anon-accessible), Story 13 (auto-refresh + Updated Ns ago), Story 14 (range presets), Story 18 (spark-inference-gateway widgets).
- **Spec trace:** §7.1 (page anatomy — but 11 cells per ADR-15 §17 supersedes the spec's "6 cells"), §7.2 (range + refresh UX), §5.2 (dashboard payload field-by-field).
- **Auth state:** **public**. Identical render for anon and signed-in. The topbar chip reads `Viewing publicly` for anon and would carry `● Signed in` (success.soft) for signed-in; the mock renders the anon variant.
- **Figma frame:** `M5 — System status (loaded, all healthy)  /metrics` — node `74:598`. ![screenshot](assets/M5/loaded.png)
- **Key elements:**
  - **Sidebar (232 px, `surface.soft`):** brand row (J glyph + stacked `JeekLee's` / `PLAYGROUND` wordmark) → `⌘K` search pill → Apps section (`Home` inactive primary fg, `Docs` inactive primary fg, `Chat` inactive primary fg — both Docs and Chat are shipped from M2/M4, **`System status` active** with `accent.soft` bg + `accent` fg weight 600, NO badge) → spacer → signed-in account footer card (28 px khaki avatar + `JeekLee` / `jeeklee1120@gmail.com` stacked).
  - **Topbar:** breadcrumb `Home  /  System status` on the left; `Viewing publicly` neutral chip + account pill (`JL` khaki avatar + `JeekLee ▾`) on the right.
  - **Sticky header strip (1208 × 72, frame-relative y = 56–128):** `Range:` label (12 px / 500 / `text.muted`) at x = 258 → five range pills laid out left-to-right: `15m` (`surface` bg + 1 px `border`), `1h●` (`accent.soft` bg, `accent` fg weight 600, NO border), `6h`, `24h`, `7d` (each `surface` + 1 px `border`, label in `text` 12 / 500); right side at x = 1265: `Updated 4s ago` in `text.muted` 12 / 400; at x = 1380: `⟳` refresh button (`surface` bg + 1 px `border`, 32 × 28, `radius.md` 8 px, `text` 14 / 500).
  - **Service Health grid (11 cells, two rows):**
    - **Row 1 (y = 170, 6 cells × 175 wide × 56 tall, gap 12):** `✅ gateway` / `up · 3h 32m`, `✅ identity-api` / `up · 3h 32m`, `✅ docs-api` / `up · 3h 30m`, `✅ rag-ingestion` / `up · 3h 30m`, `✅ rag-chat-api` / `up · 2h 14m`, `✅ metrics-api` / `up · 1h 02m`. Each cell: `surface` bg + 1 px `border` + `radius.md` 10 px; `✅` glyph in `success` (`#4F6B2E`) 14 px at top-left padding 12 px; service name in `text` 13 / 600 at top-left padding 12 + 23; detail line in `text.muted` 11 / 400 at top-left padding 12 + 23 below service name.
    - **Row 2 (y = 238, 5 cells × 175 wide × 56 tall, gap 12):** `✅ spark-gateway` / `p95 340 ms · 2 models`, `✅ prometheus` / `up · 11 targets`, `✅ loki` / `up · 3d retention`, `✅ alloy` / `up · scraping 5 BCs`, `✅ cadvisor` / `up · 14 containers`. Same cell style as row 1.
    - All 11 cells render `up` in this frame. Verdict rules in ADR-15 §9 truth table + §12 spark probe + §17 self-cell rule.
  - **Host section (frame-relative y = 312 header, y = 336 cards, 4 cards × 270 × 96):**
    - **CPU card:** `CPU` eyebrow → `18.2%` big number (`font.h2` 22 / 700 / `text`) → sparkline placeholder (170 × 18, `surface.soft` filled, `radius.sm` 3) → `last 1h` sub.
    - **MEMORY card:** `MEMORY` eyebrow → `12.4 / 64 GB` big number → progress bar (238 × 10, `surface.soft` bg + `accent` fill at 46/238 ≈ 19 %).
    - **DISK card:** `DISK` eyebrow → `42% · 420 GB` big number → progress bar (238 × 10, `accent` fill at 100/238 ≈ 42 %).
    - **LOAD AVG card:** `LOAD AVG` eyebrow → `1.2  0.8  0.6` big number → `1m · 5m · 15m` sub.
  - **JVM heap per BC (y = 450 header, y = 474 cards, one card per JVM-bearing service × 128 height):**
    - Slice-1 lists 6 JVM-bearing services in deterministic order: `gateway`, `identity-api`, `docs-api`, `rag-ingestion-api`, `rag-chat-api`, `metrics-api` (matches Alloy's `backend_bcs` scrape order). Grid is responsive: `xl:grid-cols-6` (single row on ≥1280 px), `lg:grid-cols-3` (2 rows × 3), `md:grid-cols-2`, else single-column.
    - Each card: service name in `text` 12 / 600 → `420 / 1024 MB` value in `accent` 17 / 600 → chart area (`surface.soft` bg, `radius.sm` 6, fills remaining card width) with a 2 px `accent` line representing the heap-over-time series.
    - Slice-1 sample values: rag-chat 420/1024, docs 280/1024, identity 180/512, rag-ingestion 220/1024, gateway / metrics-api typically smaller (gateway ~80–150 MB, metrics-api ~120–200 MB depending on load).
  - **HTTP request rate (y = 620 header, y = 644 cards, 3 cards × 175 × 236):** narrower portrait-shaped cards. Each: service name in `text` 12 / 600 → big `2.4 rps` in `accent` 18 / 700 → small `error 0%` (or `error 1.0%` for docs-api) in `text.muted` 11 / 400 → 151 × 144 chart area (`surface.soft` bg) with a 2 px `accent` line.
  - **spark-inference-gateway (y = 620 header, same row as HTTP rate, mixed-width):**
    - **Latency P95 card (365 × 236, x = 822):** title `Latency P95 (ms)`, value `340 ms` in `accent` 22 / 700, legend `● BGE-M3   ● Qwen3-32B` in `text.muted` 11 / 500, 341 × 154 chart area with two stacked 2 px lines: top `accent` (BGE-M3, faster), bottom `khaki` (#C2B88A — Qwen3-32B, slower).
    - **Models loaded card (175 × 236, x = 1199):** title `Models loaded` → `✅ BGE-M3` and `✅ Qwen3-32B` rows in `text` 13 / 500 → `HOST` eyebrow → `host.docker.internal :10080` in `text.muted` 11 / 400 → `UPTIME` eyebrow → `3h 32m` in `text` 14 / 600.
- **Interactions:**
  - Click any range pill → URL updates to `?range=Xh`; the four backend routes refetch; the 15 s polling timer resets (per spec §7.2).
  - Click `⟳` → immediate refetch of `/api/metrics/dashboard` + the in-flight `/api/metrics/timeseries` calls; 15 s timer resets (per spec §7.2).
  - Hover any service health cell → `surface` → `surface.soft` (subtle hover, not visualized — implementer's call).
  - Hover any chart card → `border` → `border.strong` (subtle hover, optional — implementer's call).
  - Tab loses focus → browser-default polling throttle; explicit pause is NOT in P0 (per spec §12 "Frontend").
- **Per-state:**
  - All cells `up`, all charts populated — the rendered state in this frame.
  - One BC degraded → see Frame 2.
  - Initial load → see Frame 3.
  - One widget failed → see Frame 4.
- **Spacing tokens used:** `spacing.lg` 24 px outer page padding (left edge of widgets starts at frame x = 258 = sidebar 232 + 26 padding); `spacing.md` 16 px between section header and first widget row; `spacing.sm` 8 px within widget interiors (eyebrow-to-value, value-to-chart); 12 px gap between adjacent cards within a row.

### 2.2 `/metrics` (one BC degraded) — frame node-id: `74:762`

- **Purpose:** the dashboard shows the operator a *real failure* — `spark-inference-gateway` is reachable but returning elevated latency. The cell turns warning yellow, the spark-latency P95 widget shows a sharp spike, the rest of the stack is healthy.
- **PRD user stories covered:** Story 7 (services grid surfaces `degraded`), Story 13 (data freshness even during partial degradation), Story 18 (spark widget shows the bad signal).
- **Spec trace:** §7.5 (no separate state defined for "degraded BC" — it's the same surface as `up`, just with a different badge palette per ADR-15 §9 truth table row "All 2 of last 2 → `1`, /actuator/health: yes/non-`UP` → degraded"). For spark specifically: ADR-15 §12 (HEAD `/v1/models` reachable + non-200 → degraded) + §9 row "Reachable + non-200 → degraded".
- **Auth state:** public.
- **Figma frame:** `M5 — System status (one BC degraded)  /metrics` — node `74:762` (Figma name still shows the cloned `(loaded, all healthy)`; operator must rename). ![screenshot](assets/M5/degraded.png)
- **Key elements:** identical to Frame 1 EXCEPT:
  - **`✅ spark-gateway` cell (row 2, position 1) → `⚠️ spark-gateway`:**
    - Cell bg: `surface` → `warning.soft` (`#F4E8C7`).
    - Cell border: `border` → `warning` (`#B58A2B`) 1 px.
    - Icon: `✅` → `⚠️` (in `warning` color).
    - Detail line: `p95 340 ms · 2 models` → `degraded · p95 3.4 s`.
  - **spark latency P95 widget (right side, wide card):**
    - Big value: `340 ms` → `3 400 ms` rendered in `warning` (`#B58A2B`) instead of `accent`.
    - Chart area gains a **spike marker**: a 40 × 62 px filled rectangle in `warning` 0.85 alpha sitting at the right edge of the chart (frame-relative ~x = 980, y = 730 within the spark-latency chart bg). Visually conveys "value just shot up." Implementer renders the actual line via Recharts with a single tail-spike data point.
- **Interactions:** identical to Frame 1 — no extra surface. Operator clicks `⟳` or waits for the next 15 s poll.
- **Per-state:**
  - **Spark `degraded`** — the rendered state.
  - **Spark `down`** (HEAD `/v1/models` unreachable per ADR-15 §12): same cell shape but `surface` → `danger.soft` (`#F4E1DA`), border `danger` (`#B14B3B`), icon `🛑`, detail `down · last seen 2 m ago`. Not visualized as a separate frame; reuses this frame's structure with the danger palette swap. Spark latency widget would render `—` instead of a value and the chart area would carry `⚠ Failed to refresh` per the degrade overlay convention (Frame 4 pattern).
  - **Single BC in row 1 `degraded`** (e.g., `rag-chat-api` after a recent restart): exactly the same swap (`✅` → `⚠️`, `surface` → `warning.soft`, border → `warning`); the cell sits in its original row 1 position. The big latency / heap chart for that BC may or may not also degrade depending on the failure mode — left to the implementer to wire from the dashboard payload's per-service `status` field.
- **Spacing tokens used:** identical to Frame 1.

### 2.3 `/metrics` (initial load — skeleton) — frame node-id: `74:927`

- **Purpose:** the first paint of the page before `/api/metrics/dashboard` has resolved. Every widget renders its scaffolding (border, background, eyebrow label) but its values, chart lines, and progress bar fills are skeletons — gray placeholders that pulse subtly.
- **PRD user stories covered:** Story 16 (initial-load skeleton).
- **Spec trace:** §7.3 row "Initial load" (skeleton placeholders for every widget) and §12 "Frontend" ("initial-load skeleton placeholders render for every widget").
- **Auth state:** public.
- **Figma frame:** `M5 — System status (initial load — skeleton)  /metrics` — node `74:927` (Figma name stale; operator rename). ![screenshot](assets/M5/skeleton.png)
- **Key elements:** identical chrome to Frame 1; data layer replaced as follows:
  - **`Updated Ns ago` indicator → `Loading…`** in `text.muted`. The `⟳` button stays clickable.
  - **All 11 service health cells:** icon `•` (small bullet in `text.subtle`), service name `—`, detail `loading…`. Cell bg + border unchanged (still `surface` + `border`). This is the "we don't know what the verdict is yet" state.
  - **Host cards:** big values become `—` in `text.subtle` (`#8B8670`). Memory + disk progress bar fills become `surface.soft` (no `accent` fill yet). Host-CPU sparkline rectangle stays `surface.soft` (visually it's a skeleton block — implementer adds the pulse animation).
  - **JVM cards + HTTP rate cards + spark widgets:** values become `—` in `text.subtle`; chart lines become `surface.soft` (the chart background stays `surface.soft` too, so the line is effectively invisible — that's the point). Sub-labels (`error 0%`, `host.docker.internal`) become `loading…` in `text.muted`.
  - **Models loaded card:** `✅ BGE-M3` → `—`, `✅ Qwen3-32B` → `—`, HOST line → `loading…`, UPTIME → `—`.
- **Interactions:**
  - `⟳` button still active — clicking it triggers a manual fetch even mid-skeleton.
  - Range pills: still clickable; clicking pre-empts the in-flight initial fetch with a new range query (per spec §7.2, no special wait state).
  - On first 200 response, all skeletons hydrate to real values via a content-cross-fade (no explicit transition designed; implementer's call). On subsequent refresh cycles the page does NOT return to skeleton — existing data stays visible while `⟳` spins (per spec §7.3 row "Subsequent refresh").
- **Per-state:**
  - **In-flight initial load** — rendered state.
  - **Initial load failed (3× consecutive 5xx)** → page transitions to a top banner `Couldn't reach metrics service. Retrying in 30s.` (per spec §7.3 row "Stale (whole dashboard)"). Not visualized as a separate frame in M5 P0 — implementer reuses the M4 503 banner pattern (`docs/design/M4-rag-chat.md` §2.6) with the metrics-flavored copy.
- **Spacing tokens used:** identical to Frame 1.

### 2.4 `/metrics` (one widget failed) — frame node-id: `74:1091`

- **Purpose:** a single chart's `/api/metrics/timeseries` call returned 5xx OR the dashboard payload marked it `"degraded": true` (PromQL budget exceeded — spec §8.2). Only that widget degrades; the rest of the dashboard stays fully populated. This is the "per-widget graceful degrade" surface that distinguishes one stale metric from a whole-dashboard outage.
- **PRD user stories covered:** Story 15 (per-widget error degrade), Story 20 (PromQL budget exceeded → partial response with `"degraded": true`).
- **Spec trace:** §7.3 row "Stale (one widget)" (`⚠ Failed to refresh` overlay + retry icon; other widgets unaffected). The frame uses the rag-chat-api JVM heap widget as the example.
- **Auth state:** public.
- **Figma frame:** `M5 — System status (one widget failed)  /metrics` — node `74:1091` (Figma name stale; operator rename). ![screenshot](assets/M5/widget-failed.png)
- **Key elements:** identical to Frame 1 EXCEPT the **`rag-chat-api` JVM heap card (top-left of the JVM row)**:
  - Card bg: `surface` → `danger.soft` (`#F4E1DA`).
  - Card border: `border` → `danger` (`#B14B3B`) 1 px.
  - Card title `rag-chat-api`: unchanged (stays in `text` weight 600). The title intentionally stays legible so the operator instantly identifies *which* widget is broken.
  - Value: `420 / 1024 MB` → `— / 1024 MB` in `danger` 17 / 600. The max-heap denominator stays because that comes from the dashboard payload's `jvm[].heapMaxMb` field (still fresh); only the used-heap timeseries failed.
  - Chart line: `accent` 2 px → `danger.soft` 2 px (essentially invisible against the chart background — the chart area looks empty).
  - **Overlay text** added in the card's empty chart area (frame-relative `(4744, 3852)`): `⚠ Failed to refresh` in `danger` 12 / 600. Below: `↻ Retry` in `text.muted` 12 / 500 — the implementer wires this to refetch `/api/metrics/timeseries?metric=jvm-heap-rag-chat-api&range=1h&step=30s`. The card's other content (eyebrow, title) stays visible above the overlay text.
- **Interactions:**
  - Click `↻ Retry` inside the degraded widget → calls the per-widget endpoint again. On 200 the card hydrates back to the normal state (Frame 1 appearance for this card). On a second failure the overlay re-renders (with no exponential backoff in P0 per ADR-15 §H — implementer just re-triggers the fetch on each retry click).
  - The next 15 s auto-poll cycle also retries automatically — the user does not have to click Retry. The button exists for impatience.
  - All other widgets continue to poll on the normal cadence and render their fresh data — the per-widget failure is isolated.
- **Per-state:**
  - **Single timeseries 5xx** — rendered state.
  - **Single widget `"degraded": true` (PromQL budget)** — visually identical to the rendered state (per spec §7.3 row "Stale (one widget)" — both failure modes share the UI surface). Implementer disambiguates the failure mode in the tooltip on the `⚠` glyph (M5.1 nicety; not in P0).
  - **Multiple widgets failed simultaneously (e.g., 3 of 4 JVM cards):** each card degrades independently. The dashboard remains operational; the operator can still read service health + host + spark widgets.
- **Spacing tokens used:** identical to Frame 1. The overlay text uses `spacing.sm` 8 px between the warning line and the retry link.

### 2.5 Sidebar System status row variants (locked + active) — frame node-id: `74:1257`

- **Purpose:** documents the two states the sidebar `System status` row can be in: **milestone-locked (pre-M5 ship)** with the `🔒 M5` badge, and **active (post-M5 ship)** with no badge. **Critically, there is NO auth-lock variant for this row** — `/metrics` is public per ADR-09 amendment in ADR-15 §G.2, so the M4 `🔒 Sign in` convention does not apply.
- **PRD user stories covered:** Story 12 (Sidebar System status row unlocks; route is public to anon).
- **Spec trace:** §7.4 (Sidebar "System status" row state transition table — Anon and Signed-in both get the active no-badge state post-M5 ship).
- **Auth state:** the frame visualizes the row state transition; both variants are auth-agnostic because the row routes to a public destination.
- **Figma frame:** `M5 — Sidebar System status row variants (locked + active)  global` — node `74:1257`. ![screenshot](assets/M5/sidebar-variants.png)
- **Key elements:**
  - **Frame title:** `Sidebar 'System status' row — milestone-lock vs active` (`font.h2` 20 / 600 / `text`).
  - **Frame subtitle:** `/metrics is public — NO auth-lock variant. The M4 🔒 Sign in convention does not apply.` in `text.muted` 12 / 400.
  - **LEFT sidebar (PRE-M5 SHIP, frame-relative `(40, 120, 232 × 440)`):** `surface.soft` bg. APPS section: `Home`, `Docs`, `Chat` (all inactive primary fg), `System status` in `text.subtle` muted with a `🔒 M5` badge right-aligned in the row (`surface` bg pill + `text.subtle` fg 10 / 500). Caption below: `click: no-op` in `text.subtle` 11 / 400.
  - **RIGHT sidebar (POST-M5 SHIP, frame-relative `(488, 120, 232 × 440)`):** identical APPS list but `System status` is the **active row** — `accent.soft` bg, `accent` fg weight 600, no badge. Caption below: `click: → /metrics  (anon + signed-in)` in `accent` 11 / 400.
  - **Bottom note** at `(40, 594)`: `No 🔒 Sign in variant — /metrics is public; ADR-09 amendment keeps logs/** as the only auth-gated metrics path.` in `text.muted` 12 / 500.
- **Interactions:**
  - **Pre-M5 row (left):** click → no-op (`cursor: default`; tooltip `Available when M5 ships` per M1 spec).
  - **Post-M5 row (right):** click → `/metrics` (public page, no login redirect, anon and signed-in get the same destination).
- **Per-state:**
  - **Pre-M5 ship** — left variant. The day M5 ships this state goes away.
  - **Post-M5 ship** — right variant. This is the steady state for the entire post-M5 lifetime of the project; there is no future state for this row to transition INTO.
- **Spacing tokens used:** `spacing.xl` 40 px between the two sidebar columns; `spacing.lg` 24 px padding inside each sidebar; `spacing.md` 16 px between APPS eyebrow and the first row; `spacing.sm` 8 px vertical gap between adjacent rows; 32 px vertical rhythm between row label and the next row to make the badge legible.

## 3. Reused chrome elements

These widgets ship from M1 / M2 / M4 verbatim; M5 does NOT introduce alternative versions. The frontend-implementer should locate the existing FSD `widgets/Sidebar/` and `widgets/Topbar/` and ensure the `System status` row data wires through the same locked-row / shipped-row vocabulary used by Docs and Chat — not fork a metrics-specific sidebar.

| Element | Source design doc | Source frame in Figma | M5 frames where it appears |
|---|---|---|---|
| **Brand row** (J glyph + stacked `JeekLee's` / `PLAYGROUND` wordmark) | M1 §"Public Home" | `14:2`, `14:135` | All 4 dashboard frames (1–4) |
| **⌘K search pill** (sidebar trigger that opens the global palette overlay) | M2 §"⌘K palette" | `27:704` | All 4 dashboard frames (1–4) |
| **Sidebar Apps section with locked-row treatment** | M2 §7.1 | All M2 + M4 frames with signed-in sidebar | All 4 dashboard frames (1–4) — Docs + Chat + System status all in shipped state |
| **Signed-in account footer card** (28 px khaki avatar + stacked name/email) | M1 §"Signed-in Home" | `14:135` | All 4 dashboard frames (1–4) |
| **Slim topbar** with breadcrumb + status chip + account pill | M1 §"Signed-in Home" | `14:135` | All 4 dashboard frames (1–4) — breadcrumb reads `Home / System status` |
| **Account pill** (24 px khaki avatar + display name + chevron, `surface` bg + `border` 1 px + `radius.pill`) | M1 §"Signed-in Home" + M2 "Account-pill dropdown" overlay | `14:135` + `30:892` | All 4 dashboard frames (1–4) |
| **`Viewing publicly` neutral chip** (anon variant of the topbar status chip) | M1 §"Public Home" | `14:2` | All 4 dashboard frames (1–4) — `/metrics` is anon-friendly so the mocks render the anon chip; signed-in users would see the `● Signed in` success chip variant from M1 |

## 4. New components introduced by M5

These are new — the frontend-implementer scaffolds them under `client/src/widgets/Metrics/` (or feature-folder of their choice). All read from the same token vocabulary; **no new tokens**.

### 4.1 11-cell Service Health Grid

- **Container:** two stacked rows. Row 1 = 6 cells (the 6 BCs), Row 2 = 5 cells (spark + 4 observability self). Each cell: 175 × 56, `surface` bg, 1 px `border` stroke, `radius.md` 10 px. Cells separated by 12 px horizontal gap; the two rows separated by 12 px vertical gap.
- **Cell anatomy:** padding 12 px. Status glyph (`✅`, `⚠️`, or `🛑`) at top-left, 14 px font; service name in `text` 13 / 600 to the right of the glyph (gap 8 px); detail line in `text.muted` 11 / 400 below the service name.
- **`up` variant (default):** glyph `✅` in `success`, bg `surface`, border `border`. Detail line shows uptime + optional secondary signal (`up · 3h 32m`, `up · 11 targets`, `up · 3d retention`).
- **`degraded` variant:** glyph `⚠️` in `warning`, bg `warning.soft` (`#F4E8C7`), border `warning` (`#B58A2B`). Detail line carries the degradation hint (`degraded · p95 3.4 s`, `degraded · scrape miss`).
- **`down` variant:** glyph `🛑` in `danger`, bg `danger.soft` (`#F4E1DA`), border `danger` (`#B14B3B`). Detail line shows last-seen / failure reason (`down · last seen 2m ago`).
- **Verdict source:** per ADR-15 §9 (BCs), §12 (spark-inference-gateway), §17 (4 observability self-cells use each tool's native readiness endpoint).
- **Visualized in:** Frame 1 (11 cells all `up`), Frame 2 (spark cell `degraded`), Frame 3 (skeleton — all cells render placeholder bullets), Frame 4 (all `up` — the failure is in a chart widget, not a service cell), Frame 5 (the row in the sidebar maps to this grid's overall state — sidebar shows the aggregate).

### 4.2 Range preset pills (sticky header strip)

- **Container:** horizontal flex strip, 1208 × 72, anchored sticky to the top of the content area (below the topbar). Bg `bg` (`#FAF7EF`), border-bottom 1 px `border` (`#E6E0CB`). Padding 22 px top + 22 px bottom; left side starts at x = 258 with the `Range:` label, right side ends at x = 1412 with the refresh button.
- **`Range:` label:** 12 / 500 / `text.muted`.
- **Pill (inactive):** 44–52 × 28 (auto-fit to label width), `surface` bg, 1 px `border` stroke, `radius.pill` 999 px, label in `text` 12 / 500 centered.
- **Pill (active):** same size, `accent.soft` bg (`#E9E8D1`), NO border, label in `accent` 12 / 600 centered.
- **Strip behavior:** the active pill is determined by `?range=Xh` URL param; default `1h` when the param is absent (per spec §7.2). Clicking a pill (a) updates the URL via `router.push` or equivalent, (b) refetches all four backend routes for the new range, (c) resets the 15 s polling timer.
- **Visualized in:** all 4 dashboard frames (1–4).

### 4.3 Updated Ns ago indicator + manual refresh button

- **Indicator:** right-aligned text at x = 1265 (12 / 400 / `text.muted`). Format: `Updated Ns ago` where `N` is the seconds since the last successful `/api/metrics/dashboard` response. Ticks every 1 s client-side (per spec §7.2). During a manual or auto refresh in flight, the indicator stops ticking and the `⟳` button rotates (CSS animation — implementer's call). On initial load the indicator reads `Loading…` (per Frame 3).
- **Refresh button:** 32 × 28 at x = 1380, `surface` bg + 1 px `border` stroke + `radius.md` 8 px. Label `⟳` in `text` 14 / 500 centered. Click → immediate `GET /api/metrics/dashboard?range=<current>` + parallel per-chart `/timeseries` refetches; resets the 15 s polling timer.
- **Visualized in:** all 4 dashboard frames (1–4).

### 4.4 Per-widget degrade overlay

- **Container:** activated on a single widget card when its data source fails. The card's `surface` bg → `danger.soft` (`#F4E1DA`); 1 px `border` → 1 px `danger` (`#B14B3B`). All other styling (radius, padding, internal layout) stays.
- **Value text:** the big-number value renders as `—` (or `— / 1024 MB` for cards with a fraction) in `danger` instead of `accent`. Title + eyebrow stay in their original colors so the operator can still identify the widget.
- **Chart line:** color → `danger.soft` (invisible against the danger.soft card bg; effectively hidden).
- **Overlay text:** `⚠ Failed to refresh` in `danger` 12 / 600, placed inside the empty chart area, top-aligned. Below it: `↻ Retry` in `text.muted` 12 / 500, click-target (`button` element semantically).
- **Behavior:** the overlay is widget-local; neighbouring widgets stay unaffected. The next 15 s auto-poll cycle automatically retries; the Retry button is for impatience. On success the card hydrates back to its normal state with a content cross-fade (implementer's call).
- **Visualized in:** Frame 4 (rag-chat-api JVM card).

### 4.5 Service health cell with status badge (✅ / ⚠️ / 🛑)

- Already described in §4.1 above (the 11-cell grid is built out of this primitive). Listed separately here so the implementer treats the cell as a reusable component (it's also referenced by the sidebar's compact aggregate view — though M5 P0 doesn't render an aggregate badge in the sidebar; the System status row stays a normal navigation row without a live verdict).

### 4.6 Line-chart card primitives (JVM heap / HTTP rate / spark latency)

- **Container:** rectangular card, `surface` bg + 1 px `border` + `radius.md` 10 px. Width varies by row (270 for JVM heap, 175 for HTTP rate, 365 for spark latency wide). Height 128 for JVM heap (compact eyebrow + chart) and 236 for HTTP rate / spark latency (portrait — bigger numbers, larger chart).
- **Card anatomy:** padding 12–14 px. Title in `text` 12 / 600 top-left. Big-number value in `accent` 17–22 / 600–700 below the title. Chart area below the value: full-width minus padding, `surface.soft` bg, `radius.sm` 6 px, height filling the remaining card space.
- **Chart line:** Recharts SVG line in `accent` 2 px stroke (per ADR-15 §8 — Recharts confirmed). Multi-series charts (spark latency P95) use `accent` for series 1 and `khaki` (`#C2B88A`) for series 2 — both are existing design tokens.
- **Visualized in:** Frames 1, 2, 4 (all show populated charts); Frame 3 (chart areas + lines collapse to `surface.soft` skeleton).

### 4.7 Host card primitives (single-value + sparkline / progress bar)

- **Container:** `surface` bg + 1 px `border` + `radius.md` 10 px, 270 × 96.
- **CPU + LOAD AVG:** eyebrow (11 / 600 / `text.muted` uppercase) + big number (`font.h2` 22 / 700 / `text`) + sparkline or sub-label.
- **MEMORY + DISK:** eyebrow + big number + progress bar (238 × 10, `surface.soft` track + `accent` fill at the proportional width).
- **Visualized in:** Frame 1.

## 5. Tokens used (colors, spacing, typography)

Every value is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer should already have these wired into `client/src/shared/ui/tokens/` from M1; **M5 introduces ZERO new tokens**.

| Token | Value | Where used in M5 |
|---|---|---|
| `color.bg` | `#FAF7EF` | Main content bg on every dashboard frame; topbar bg; sticky header strip bg |
| `color.surface` | `#FFFFFF` | Service health cells (`up`); host cards; JVM cards; HTTP rate cards; spark latency card; spark models card; range pills (inactive); refresh button; account footer card; account pill; `Viewing publicly` chip variant; search pill |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; chart area backgrounds (JVM heap, HTTP rate, spark latency); host card sparkline + progress bar tracks; range pill background hover state; skeleton chart-line color (Frame 3) |
| `color.border` | `#E6E0CB` | All card / cell strokes; topbar `border-bottom`; sticky header strip `border-bottom`; range pill (inactive) stroke; refresh button stroke; account-pill stroke |
| `color.border.strong` | `#D6CFB3` | Reserved for hover treatments — implementer's call (not visualized statically) |
| `color.khaki` | `#C2B88A` | Sidebar-footer avatar fill; topbar account-pill avatar fill; spark latency P95 chart series 2 (Qwen3-32B) line color |
| `color.text` | `#2A2C20` | Frame title `System Status`; section headers; service health cell names; host / JVM / HTTP / spark card titles; range pill labels (inactive); refresh button glyph |
| `color.text.muted` | `#6F6A55` | Breadcrumb; `Updated Ns ago` indicator; service health cell detail lines; host / JVM / HTTP / spark sub-labels; chart legend; account-pill subtext; eyebrow labels (CPU / MEMORY / DISK / LOAD AVG); section eyebrows; sidebar variant captions |
| `color.text.subtle` | `#8B8670` | Search-pill placeholder; sidebar APPS section label; locked Apps row labels and badges (`🔒 M5`); skeleton placeholder text (`—` in Frame 3); composer placeholder (NOT used in M5 — no composer) |
| `color.accent` | `#6E7A3A` | Active `System status` sidebar row fg; active range pill fg; big-number values on JVM heap / HTTP rate / spark latency cards (loaded state); host memory + disk progress bar fills; sparkline / line-chart series 1; spark models `✅` glyphs |
| `color.accent.soft` | `#E9E8D1` | Active `System status` sidebar row bg; active range pill bg |
| `color.success` | `#4F6B2E` | Service health cell `✅` glyph color (`up` state); topbar `● Signed in` chip fg (signed-in variant, not visualized — anon chip rendered instead) |
| `color.success.soft` (= `#E5EBD9`) | — | Topbar `● Signed in` chip bg (signed-in variant) |
| `color.danger` | `#B14B3B` | Per-widget degrade overlay border; `⚠ Failed to refresh` title; degraded value text (`— / 1024 MB`); `🛑` glyph color for `down` service health cells (not visualized — described in §4.1) |
| `color.danger.soft` | `#F4E1DA` | Per-widget degrade overlay bg (Frame 4 rag-chat-api JVM card); `down` service health cell bg (described, not visualized) |
| `color.warning` | `#B58A2B` | Service health `degraded` cell border + glyph + detail-line tint (Frame 2 spark-gateway); spark latency P95 big-number color when `degraded`; spike marker fill (0.85 alpha) |
| `color.warning.soft` (= `#F4E8C7`) | — | Service health `degraded` cell bg (Frame 2 spark-gateway); optional rate-limit banner bg (not built — see Open question §7 below) |
| `font.h2` | 20 px / 600 / -0.01em | Sidebar variants frame title (`Sidebar 'System status' row — milestone-lock vs active`); host card big numbers (CPU 18.2 %, MEMORY 12.4 / 64 GB, DISK 42 % · 420 GB, LOAD AVG 1.2  0.8  0.6); spark latency P95 big number (340 ms / 3 400 ms) |
| `font.h3` | 16 px / 600 / 0 | Reserved for card titles — not used in M5 because the dashboard cards use smaller titles (`font.small`-sized 12 / 600) to keep the dense grid scannable |
| `font.body` | 15 px / 400 / 0 | Sidebar variants frame subtitle / bottom note; reserved for tooltips and modals (not in M5 P0) |
| `font.small` | 13 px / 400–500 / 0 | Service health cell names (13 / 600); JVM / HTTP / spark card titles (12 / 600); HTTP rate big numbers (18 / 700 — slightly larger than `font.small`); sidebar Apps row labels (13 / 500–600) |
| `font.eyebrow` | 11 px / 600 / +0.14em / uppercase | `APPS` sidebar label; section eyebrows (`Service Health`, `Host`, `JVM heap per BC`, `HTTP request rate`, `spark-inference-gateway`); host card eyebrows (`CPU`, `MEMORY`, `DISK`, `LOAD AVG`); spark models card eyebrows (`HOST`, `UPTIME`); sidebar variant labels (`PRE-M5 SHIP`, `POST-M5 SHIP (current)`) |
| `font.mono` | 13 px / 400 | Reserved for the operator CLI helper output (ADR-15 §19 `tools/metrics-logs.sh`) — not rendered on the dashboard; M5 P0 has no logs UI |
| `spacing.xs` | 4 px | Cell padding fine-tune; reserved for tight icon-label gaps |
| `spacing.sm` | 8 px | Sidebar row vertical gap; cell glyph-to-label gap; skeleton overlay subline gap; per-widget overlay `⚠` → `↻ Retry` vertical gap |
| `spacing.md` | 16 px | Sidebar Apps section row vertical rhythm; range pill horizontal gap (effective gap 8 px after pill borders); chart card internal title-to-value gap; sidebar variants frame internal padding |
| `spacing.lg` | 24 px | Page outer padding (left edge of widgets at frame x = 258 = sidebar 232 + 26 padding); section header → first widget row gap; topbar vertical rhythm |
| `spacing.xl` | 40 px | Gap between the two sidebar columns in Frame 5 |
| `radius.sm` | 6 px | Chart area inside cards; CPU sparkline rectangle; progress bar tracks |
| `radius.md` | 10 px | Service health cells; host cards; JVM cards; HTTP rate cards; spark widgets; refresh button (8 px — slightly tighter); sidebar active-row bg |
| `radius.lg` | 14 px | Reserved (modals, page sections) — not used in M5 (no modals on the dashboard) |
| `radius.pill` | 999 px | Range preset pills; topbar `Viewing publicly` chip; account pill; sidebar avatar; topbar account-pill avatar; sidebar variants `🔒 M5` badge |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | Reserved for hover affordances on the cards — not used statically (the dashboard is dense; resting shadows would feel busy). Implementer may add on hover. |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | Reserved for any popover (e.g., tooltips on cell hover with the full verdict ADR-15 §9 truth table); not used in P0 |

**Verification note:** every color hex in this document appears verbatim in the design system spec §3.1 / §3.2 / §3.3 (or §5.3 for elevation). No new tokens were invented. No spec hex was substituted with a near-miss.

## 6. Per-state behavior matrix (interactions across screens)

Cross-frame state transitions the frontend-implementer must wire. Each row reads as "trigger → resulting frame and observable state delta."

| User action / event | Source frame | Target frame / state | Notes |
|---|---|---|---|
| First paint, `/api/metrics/dashboard` in flight | (initial render) | Frame 3 (skeleton) | All widgets render placeholders; `Updated Ns ago` reads `Loading…`. |
| Initial `/api/metrics/dashboard` 200 OK | Frame 3 (skeleton) | Frame 1 (loaded, all healthy) | Cards hydrate via content cross-fade; `Updated Ns ago` starts ticking from 0 s. |
| Auto-poll tick at 15 s elapsed | Frame 1 | Frame 1 (refreshed) | Existing data stays visible; `⟳` button rotates briefly; `Updated Ns ago` stops then resets to `Updated 0s ago` on success. **No skeleton on refresh** (per spec §7.3 row "Subsequent refresh"). |
| User clicks `⟳` | Frame 1 / 2 / 4 | Same frame (refreshed) | Same as auto-poll tick; resets the 15 s timer. |
| User clicks any range pill | Frame 1 | Frame 1 (new range) | URL updates to `?range=Xh`; all four backend routes refetch; charts re-render with the new range data; 15 s timer resets. |
| Spark-gateway probe returns non-200 | Frame 1 | Frame 2 (one BC degraded) | Spark cell turns `warning.soft` + `⚠️`; spark latency value turns warning + spike marker appears in the chart. |
| Spark-gateway probe times out | Frame 1 | (down variant of Frame 2 — not separately rendered) | Spark cell turns `danger.soft` + `🛑`; spark latency widget shows the degrade overlay (Frame 4 pattern). |
| Single `/api/metrics/timeseries` 5xx | Frame 1 | Frame 4 (one widget failed) | Only that widget switches to `danger.soft` + degrade overlay; rest of the dashboard stays normal. |
| Single widget arrives with `"degraded": true` in the dashboard payload (PromQL budget exceeded) | Frame 1 | Frame 4 (same visual) | Same UI as a 5xx; the failure mode is invisible to the user in P0 (M5.1 may distinguish via tooltip). |
| User clicks `↻ Retry` in a degraded widget | Frame 4 | (refresh per widget) → Frame 1 widget OR stays Frame 4 widget | Calls only that widget's `/timeseries`. On 200, widget hydrates. On repeat 5xx, overlay re-renders. |
| `/api/metrics/dashboard` returns 5xx 3 times in a row | Frame 1 | Whole-dashboard banner (not designed — reuses M4 503 banner pattern with metrics copy) | Polling pauses; manual `⟳` always available; banner says `Couldn't reach metrics service. Retrying in 30s.` (per spec §7.3 row "Stale (whole dashboard)"). |
| User hits the 30 / min / IP rate limit on `/api/metrics/dashboard` | Frame 1 | 429 banner (optional Frame 6 — not built) | Yellow banner with countdown derived from `Retry-After`; polling paused; manual `⟳` greyed. See Open question §7. |
| Anon user clicks sidebar `System status` row (post-M5 ship) | Frame 5 right variant | Frame 1 (or 3 on first paint) | No login redirect; `/metrics` is public. |
| Signed-in user clicks sidebar `System status` row (post-M5 ship) | Frame 5 right variant | Frame 1 (or 3 on first paint) | Same destination as anon; the topbar chip differs (`● Signed in` vs `Viewing publicly`). |
| Any user clicks sidebar `System status` row (pre-M5 ship) | Frame 5 left variant | (no-op) | Cursor `default`; tooltip `Available when M5 ships` per M1 spec. |
| Tab loses focus mid-poll | Any dashboard frame | (browser-default throttle) | Per spec §12 "Frontend" — no explicit pause; browser may delay the next `setInterval` tick. The `Updated Ns ago` indicator may briefly read a stale value on return. |
| Direct URL `/metrics?range=24h` | (cold load) | Frame 3 (skeleton with 24h pill active) → Frame 1 (24h data) | Range pill `24h` renders as the active pill from the first paint; the initial fetch uses `range=24h`. |

## 7. Open questions for the frontend-implementer

The PRD's "Open questions for the implementer" (PRD §"Open questions for the implementer") and ADR-15's "Open questions deferred beyond M5" are the canonical lists. **All 20 PRD / spec questions are closed in ADR-15** — the implementer does not need to make any of those decisions. The visual / UX questions specifically deferred to Stage 3 of the frontend (this document's reader) are:

1. **Chart line styling beyond color.** The mocks show 2 px straight `accent` lines as placeholders. The real Recharts output should use the design system's `accent` (`#6E7A3A`) for series 1 and `khaki` (`#C2B88A`) for series 2 (already pinned in §5 above); the line width is 2 px; line interpolation `monotone` (smoother on sparse 5 s scrape data than `linear`); dot rendering OFF (per the dense data — dots would be noise). Implementer confirms in the Recharts `<Line>` props.

2. **Skeleton pulse animation cadence.** The static mock shows a static gray. Intended behavior: the skeleton placeholder rectangles (chart areas, progress bars, big-number text) pulse via CSS `@keyframes` between `surface.soft` and a slightly lighter shade at ~1.6 s cadence (similar to the M4 `chat-cursor` keyframe but slower because the dashboard skeleton lives longer — ~400 ms TTFB to first 200 per ADR-15 §16). Exact curve is implementer's call.

3. **Per-widget Retry button click behavior.** The mock shows `↻ Retry`. The implementer wires this to refetch ONLY that widget's `/api/metrics/timeseries` call (not the full `/dashboard`). On a second consecutive failure the implementer chooses between (a) keeping the overlay open and re-triggering on the next user click, (b) disabling the button for N seconds (rate-limit on the client). Recommendation: (a) — the dashboard is single-page operator-facing, no need to throttle the operator's own clicks; the server-side rate limit (`/api/metrics/timeseries` has no explicit per-IP cap per ADR-15 §8.2) is the safety net.

4. **Hover affordances on cards + cells.** The mocks don't render hover. Recommended treatment within the token vocabulary: card border `border` → `border.strong` on hover; cursor stays `default` (cards aren't clickable in P0); optional tooltip on the service health cell glyph showing the full verdict source (`/actuator/health: UP, up{}: 1, last scrape 4s ago`). M5 P0 doesn't require these — implementer's call.

5. **429 RATE_LIMIT banner.** Spec §8.2 + PRD Story 19 prescribe a 30 / min / IP cap on `/api/metrics/dashboard`. When the cap is hit the operator sees a 429 with `Retry-After`. The optional Frame 6 was scoped but not built; the implementer should reuse the M4 §2.7 banner pattern verbatim (`warning.soft` bg + `warning` border + countdown pill) with metrics copy: title `You've hit the metrics rate limit.`, body `Anonymous viewers are capped at 30 requests / minute / IP. Try again in NN sec.`. Polling pauses; manual `⟳` greyed; banner dismisses on countdown 0 or successful manual refresh.

6. **Whole-dashboard 5xx banner copy.** Spec §7.3 row "Stale (whole dashboard)" pins `Couldn't reach metrics service. Retrying in 30s.`. Implementer renders this with the M4 §2.6 503 banner pattern (`danger.soft` bg + `danger` border + `↻ Retry now` outline button). The 30-second value comes from the implementer's polling backoff schedule (ADR-15 §H may have specifics; if not, the implementer picks the value).

7. **OpenGraph metadata for `/metrics`.** Unlike `/chat` (which is auth-only — see M4 §7 Q6) and `/docs/{id}` (which has rich per-doc metadata), `/metrics` is a public dashboard that's interesting as a workshop signal. Recommendation: render a generic `og:title` = `System status · JeekLee's playground` with the brand glyph as `og:image`; `og:description` = `A live look at the playground's stack — Spring Boot services, host metrics, JVM heap, spark-inference-gateway latency. Refreshed every 15 s.`. The implementer takes this position unless ADR-15 amends.

8. **Compact mobile (≤719 px) layout.** Spec §7.5 + PRD Story 17: viewport < 720 px → single-column stack with sparkline only; full charts hidden. The mocks don't visualize this state; M5.1 produces the real mobile reflow. P0 the implementer ensures the desktop layout gracefully overflows horizontally (scrollable parent) below ~720 px rather than breaking visually. Service health grid: 11 cells stack vertically (1 cell per row, full width minus padding). Host cards stack vertically. JVM / HTTP / spark cards become single-column. Sparklines stay; chart areas hide.

---

**End of design context.** The frontend-implementer reads this doc as the canonical specification for Stage 3 frontend work; it does NOT modify the PRD or ADR-15 (those are PM / architect property). If a UI question is genuinely under-specified beyond this doc + the spec + the ADR, the implementer surfaces it as a question to the orchestrator rather than guessing.
