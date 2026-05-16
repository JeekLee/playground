# Design: M2 — Docs (Markdown authoring + public documents + search)

> Spec: `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md`
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md` (canonical token vocabulary)
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN/playground-%E2%80%94-M1-Identity (M2 frames added below the M1 row at y ≥ 1000; M1 frames are unchanged)
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill — all reused verbatim except the `Docs` Apps row, which is unlocked + active on every M2 route)

Stage 2 output for the Docs (M2) bounded context. Six desktop frames at 1440 wide, built strictly against the design system spec (tokens, layout shell, chip vocabulary) with the M2 spec's §7.1 sidebar override applied: the `Docs` Apps row is **shipped/active** on every M2 route (`accent.soft` bg + `accent` label, weight 600). Chat (M4) and System status (M5) remain locked. Tokens table below is sourced verbatim from the design system spec — frontend-implementer mirrors them into `client/src/shared/ui/tokens/`; no new tokens are introduced by this milestone.

> Terminology note (v4): per M2 spec §0, the user-facing noun is **`Document`** / **`Documents`** everywhere. The previous draft of this design doc used `essay` / `Essays` — those have been migrated. The home section is labeled `Latest documents`, the sidebar entry is `Docs`, the public list lives at `/docs/public`, single document detail lives at `/docs/public/{slug}`. The DTO names in §6.4 of the spec are `PublicDocListItem` and `PublicDocDetail`.

> Asset note: PNG export from the Figma MCP returns base64 that the harness intercepts as inline visual content rather than passing through as text, same blocker as M1. The Figma file is the canonical visual reference; ASCII wireframes inline here remain accurate.

## Screens

### Documents (public list) (`/docs/public`)

- **Purpose:** the public reader's entry into the owner's published documents. Renders the owner-filtered public feed (`GET /api/docs/public`) as a 3-column thumbnail grid with the design-system gradient thumbs, surfacing view + like counts as part of the post-M2 card-meta contract.
- **Spec trace:** M2 spec §6.1 (`GET /api/docs/public`), §7.2 row 1 (`/docs/public`), §7.3 (view/like meta on cards). ADR-09 (public route, no `X-User-*` headers).
- **Auth state:** public (logged-out lands here; logged-in can view too — chrome adapts but the page itself is identical in either state).
- **Figma frame:** `M2 — Documents (public list)  /docs/public` (node `14:258`; frame `name` field still reads `M2 — Essays (public list)  /essays` because the Talk to Figma allowlist does not expose a node-rename tool — see Open questions).
- **Key elements:**
  - **Sidebar (232px, `surface.soft`):** brand row → `⌘K` search pill → Apps section with `Home` (inactive, `text` weight 400), `Docs` (**active**, `accent.soft` bg + `accent` label, weight 600 — no badge, no lock since M2 has shipped per spec §7.1), `Chat` (locked, `M4 🔒` badge), `System status` (locked, `M5 🔒` badge) → spacer → account footer reading `Not signed in / Sign in to write/chat privately.` (logged-out variant of the M1 footer pattern).
  - **Slim topbar:** breadcrumb `Documents`; right side `Viewing publicly` neutral chip + primary `Sign in with Google` button (spec §2.4 + §6.1 — same component as M1's public home).
  - **Hero:** `font.h1` title `Documents by JeekLee` + one-line subtitle in `text.muted` ("Notes on building this, on Spark in personal projects, on agent teams"). No display type — document-detail page is where display type would live; the list is editorial-restrained.
  - **Section header `Latest`** + accent text-link `View archive →` (`accent`, `font.small`/500). Spec §6.1 cursor pagination — the archive link is a placeholder for the deeper-history view.
  - **3-column thumbnail grid (6 cards, 2 rows):** each card per design system §9 — 124px gradient thumbnail (alternating `khaki #C2B88A` / `surface.soft #F4EFDF` / chip-success-soft `#E5EBD9` sage — all values already in the spec, no new tokens introduced), `font.h3` title, 2-line excerpt in `font.small text.muted`, meta row `<accent tag chip> · N min · {date} · 👁 viewCount · ♥ likeCount`. The whole card is hover-as-link (spec §6.4 hover-as-link variant — border → `accent`, lift `translateY(-2px)`, shadow → `shadow.pop`).
  - **Pagination row:** centered `Load more →` secondary button (`surface` bg + `border.strong` stroke, spec §6.1 secondary variant). Cursor pagination per spec §6.1.
- **Interactions:**
  - Clicking any card navigates to `/docs/public/{slug}` (a public route, no auth required).
  - `Sign in with Google` (topbar) and any topbar account control follow the M1 OAuth path (ADR-07).
  - `View archive →` opens the full archive (out of the M2 scope for visual; same component as `Load more` at a different state).
  - `Load more →` fetches the next cursor page; on completion the new cards append below.
- **Empty / error / loading states:**
  - **Empty (no owner documents yet):** copy `No documents yet. Track progress on GitHub.` rendered in place of the card grid using the same empty-state card pattern as M1's home (M1 design doc has the canonical empty-state component — reuse).
  - **Loading (initial / next page):** card skeletons matching the card geometry — 124px `surface.soft` thumb, 60% width title bar, two 90% width excerpt bars. No layout shift.
  - **Error (5xx from public feed):** non-blocking `danger`-chip toast in the topbar `Couldn't load documents — retry`; cards keep their last successful render if any, otherwise the empty-state copy.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents                    [Viewing publicly] [Sign in w/ Google]│
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │  Documents by JeekLee                                               │
│  [⌕ Search]  │  Notes on building this, on Spark in personal projects, on agent…  │
│              │                                                                     │
│  APPS        │  Latest                                              View archive → │
│  ⌂ Home      │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                  │
│  ▤ Docs   ●  │  │ [thumb K]   │ │ [thumb S]   │ │ [thumb G]   │                  │
│  💬 Chat M4🔒│  │ Building an │ │ Spark, but  │ │ Why olive…  │                  │
│  📊 Stat M5🔒│  │ agent team  │ │ for one…    │ │             │                  │
│              │  │ agents · 8m │ │ spark · 12m │ │ design · 4m │                  │
│              │  │ 👁1.2K ♥42  │ │ 👁864 ♥31   │ │ 👁2.1K ♥73  │                  │
│              │  └─────────────┘ └─────────────┘ └─────────────┘                  │
│              │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                  │
│              │  │ MSA in a    │ │ OpenSearch  │ │ RAG,        │                  │
│              │  │ garage      │ │ sidecar     │ │ honestly    │                  │
│              │  │ infra · 7m  │ │ search · 6m │ │ rag · 9m    │                  │
│              │  │ 👁1.5K ♥58  │ │ 👁612 ♥18   │ │ 👁3.4K ♥102 │                  │
│              │  └─────────────┘ └─────────────┘ └─────────────┘                  │
│              │                       [ Load more → ]                              │
│  ┌─────────┐ │                                                                     │
│  │ Not     │ │                                                                     │
│  │ signed  │ │                                                                     │
│  │ in …    │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Document detail (public) (`/docs/public/{slug}`)

- **Purpose:** render a single public document with full GFM + syntax-highlighted markdown body, view/like counts inline under the title, and the like control as a login-gated inline button.
- **Spec trace:** M2 spec §6.1 (`GET /api/docs/public/{slug}`), §6.2 (`POST /api/docs/{id}/like` / `DELETE /api/docs/{id}/like`), §7.2 row 2 (`/docs/public/{slug}`), §7.3 (like button anonymous "sign in to like" state), §9 (markdown feature scope — GFM, code highlighting, fenced code, blockquote, list, inline code, external-URL images only).
- **Auth state:** public; the like button changes state based on whether `X-User-Id` is forwarded.
- **Figma frame:** `M2 — Document detail (public)  /docs/public/{slug}` (node `14:342`; frame `name` field still reads `M2 — Essay detail (public)  /essays/{slug}` — same allowlist limitation, see Open questions).
- **Key elements:**
  - **Sidebar:** same as `/docs/public` — `Docs` row active, locked Chat/System status rows below.
  - **Topbar:** breadcrumb `Documents / Building an agent team` (with ellipsis at 320px). Right side `Viewing publicly` chip + `Sign in with Google` button (since the mock is the anonymous reader's view).
  - **Article (centered, max-width 720px for prose readability):**
    - `font.h1` title `Building an agent team`.
    - **Meta row** directly under title in `font.small text.muted`: `<accent tag chip> · N min read · published {date} · 👁 viewCount` + **inline like button**. The like button is outline-`border.strong` + `font.small`/500 + `♥ likeCount` glyph; for anonymous viewers it renders disabled (`text.muted` fg, no hover) with a small `text.muted` tooltip-hint span next to it reading `Sign in to like` (the spec's "render disabled with sign-in-to-like tooltip" working default — design system spec §6.3 chip vocabulary plus the spec §7.3 disabled treatment).
    - **MD body** (`font.body` 15px / 1.6 line height): one paragraph, one h2 (`font.h2` 20px/600), one paragraph that contains an inline-code span (`font.mono` 13px, `surface.soft` bg, `radius.sm` 6px, 4px x-padding), one fenced code block (`surface.soft` bg, `radius.md` 10px, 16px padding, `font.mono` 13px multi-line), one bulleted list (`font.body` with `•` markers indented 24px), one blockquote (3px-wide `border.strong` left rule, 16px padding-left, body in `text.muted`).
  - **Back link** at the bottom of the article: `← All documents` in `accent`, `font.small`/500.
- **Interactions:**
  - **View counter:** the page load fires `POST /api/docs/public/{slug}/view` once; the API dedup uses the `PLAYGROUND_ANON` cookie (24h TTL per ADR-09 + M2 spec §6.1). The counter rendered in the meta row reflects the post-increment value.
  - **Like button (anonymous viewer):** click opens a tooltip `Sign in to like` then routes to `/oauth2/authorization/google` via `Sign in with Google` (the topbar control acts as the funnel — clicking the disabled like button surfaces the hint, not a sign-in modal; tooltip is the affordance).
  - **Like button (authenticated viewer):** click toggles the like via `POST /api/docs/{id}/like` (idempotent — repeat clicks don't double-count per spec §10 like-idempotency). Optimistic update: heart fills `accent`, counter increments by 1 immediately; on 5xx the optimistic state rolls back with a `danger`-chip toast.
- **Empty / error / loading states:**
  - **404 (slug unknown or `visibility != public`):** dedicated 404 card centered in main column (mirrors M1 `/401` card geometry but with `info.soft` chip "404 · NOT FOUND" instead of `danger.soft`) — copy `That document isn't published.` + `Go to all documents →` link in `accent`.
  - **Loading:** title skeleton (1 line 70% width), meta-row skeleton, 6 paragraph skeletons. SSR may pre-render so this is the spinner state only on client-side reload, not first paint.
  - **Error (5xx body):** the article frame renders title + meta normally; the body region shows a `danger`-bordered card with copy `Couldn't load this document — retry` + a `retry` ghost button.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents / Building an agent team [Viewing publicly] [Sign in G…]│
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │                                                                     │
│  [⌕ Search]  │           Building an agent team                                    │
│              │           agents · 8 min · published 3d ago · 👁 1,247  [♥ 42]     │
│  APPS        │                                                Sign in to like     │
│  ⌂ Home      │                                                                     │
│  ▤ Docs   ●  │           Most days I write more prompts than I write code…       │
│  💬 Chat M4🔒│                                                                     │
│  📊 Stat M5🔒│           Why a team, not a single agent                            │
│              │                                                                     │
│              │           One agent has to be everything; a team can specialize…  │
│              │           Read more in [.claude/agents/] — that's the whole int…  │
│              │                                                                     │
│              │           ┌─────────────────────────────────────────────────┐     │
│              │           │ $ /design M2                                     │     │
│              │           │ → dispatching product-designer for M2 (docs)    │     │
│              │           │ → reads design system spec + M2 spec            │     │
│              │           └─────────────────────────────────────────────────┘     │
│              │                                                                     │
│              │           Three things make this work without supervision:        │
│              │           •  each agent has a narrow brief                        │
│              │           •  the orchestrator never improvises                    │
│              │           •  every cycle ends in a checkpoint                     │
│              │                                                                     │
│              │           │ The thing I keep coming back to: the agents work…    │
│              │                                                                     │
│              │           ← All documents                                           │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### My documents (`/docs`)

- **Purpose:** the author's at-a-glance index of every document they've authored, mixed visibility. Tab-switched between `All / Drafts / Published`. Top-right surfaces the per-screen search (separate from the global ⌘K palette) and the primary `+ New document` CTA.
- **Spec trace:** M2 spec §6.2 (`GET /api/docs/mine`), §7.2 row 3 (`/docs`), §7.1 (the sidebar `Docs` row when signed-in shows the `published/total` numeric badge — visible here as `4/12` in `accent` `font.small`/500).
- **Auth state:** authenticated (401 from this route lands on `/login` per M1).
- **Figma frame:** `M2 — My documents  /docs` (node `14:388`).
- **Key elements:**
  - **Sidebar:** brand row → search pill → Apps section with `Home`, `Docs` (active, with `4/12` numeric badge in `accent`), `Chat M4 🔒`, `System status M5 🔒` → spacer → **signed-in account footer** (28px khaki avatar + stacked `JeekLee` / `jeeklee1120@gmail.com`).
  - **Topbar:** breadcrumb `Documents`; right side **signed-in chip** (`● Signed in`, `success.soft` bg + `success` fg, with a 4px dot) + account pill (24px khaki avatar + `JeekLee  ▾`, `surface` bg + `border` stroke, `radius.pill`).
  - **Page header (top-left of main):** `font.h2` `My documents`. **Top-right of main:** 280px-wide `Search my documents…` input (`radius.sm`, `surface` bg, `border.strong` stroke per design system §6.2) + primary `+ New document` button (`accent` per §6.1, `radius.md`).
  - **Segmented switcher** below the header: three segments `All / Drafts / Published`. Active segment (`All` in the mock) gets `accent.soft` bg + `accent` label weight 600; inactive segments are `text.muted` weight 500 on transparent. Background of the segment container is `surface.soft` with `radius.md`, padding 4px.
  - **Document list card** (full-width, `surface` bg, `border` stroke, `radius.md`, `shadow.card`) with 6 rows separated by 1px `border` dividers. Each row:
    - **Left:** title (`font.h3` 16px/600) + visibility chip on the same line. `Draft` chip uses `surface.soft` bg + `text.muted` fg; `Published` chip uses `accent.soft` bg + `accent` fg.
    - **Middle:** `Updated <relative time>` in `font.small text.muted`.
    - **Right:** `👁 viewCount · ♥ likeCount` in `font.small text.muted` — only present for Published rows. Draft rows show nothing here (matches the spec — view/like counters only exist meaningfully for public-reachable rows).
    - The whole row is the hover-as-link target (border-top + bg → `surface.soft`).
  - **6 mock rows** in the mock: `Building an agent team` (Published, 2h ago, 1.2K/42), `Why olive, not blue` (Published, 1d ago, 2.1K/73), `M2 brainstorming notes` (Draft, 3d ago), `The OpenSearch sidecar I almost didn't add` (Published, 5d ago, 612/18), `Random thoughts on RAG` (Draft, 1w ago), `Spark, but for one person` (Published, 2w ago, 864/31).
- **Interactions:**
  - Clicking a row navigates to `/docs/{id}` (the editor / edit screen).
  - Clicking `+ New document` navigates to `/docs/new`.
  - Search input is a per-page narrow search (filters the visible list client-side; the full search experience lives at `/docs/search`).
  - Switcher tabs filter by visibility; URL gets a `?status=drafts|published` query param so deep-linking works.
- **Empty / error / loading states:**
  - **Empty (zero documents):** centered empty-state card `No documents yet. Start writing.` + primary `+ New document` button. Same card pattern as M1's empty-state card.
  - **Empty (filter result is empty — e.g., Drafts tab with zero drafts):** `No drafts.` line in `text.muted` rendered inside the list card.
  - **Loading:** 5 row skeletons (title bar 50% width + chip + meta-row skeleton); list card frame visible immediately to prevent layout shift.
  - **Error (5xx from `/api/docs/mine`):** the list card swaps to a `danger`-bordered variant with `Couldn't load your documents — retry` and a `retry` ghost button. The header (title + search + `+ New document`) stays interactive.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents              [● Signed in]    [(JL) JeekLee ▾]           │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │  My documents                  [⌕ Search my documents…] [+ New doc]│
│  [⌕ Search]  │  ┌──────────────────────┐                                          │
│              │  │ [ All ] Drafts Pub'd │                                          │
│  APPS        │  └──────────────────────┘                                          │
│  ⌂ Home      │  ┌─────────────────────────────────────────────────────────────┐  │
│  ▤ Docs 4/12 │  │ Building an agent team    [Published]   2h ago    👁1.2K ♥42│  │
│  💬 Chat M4🔒│  │─────────────────────────────────────────────────────────────│  │
│  📊 Stat M5🔒│  │ Why olive, not blue       [Published]   1d ago    👁2.1K ♥73│  │
│              │  │─────────────────────────────────────────────────────────────│  │
│              │  │ M2 brainstorming notes    [Draft]       3d ago             │  │
│              │  │─────────────────────────────────────────────────────────────│  │
│              │  │ The OpenSearch sidecar…   [Published]   5d ago    👁612 ♥18│  │
│              │  │─────────────────────────────────────────────────────────────│  │
│              │  │ Random thoughts on RAG    [Draft]       1w ago             │  │
│              │  │─────────────────────────────────────────────────────────────│  │
│              │  │ Spark, but for one person [Published]   2w ago    👁864 ♥31│  │
│              │  └─────────────────────────────────────────────────────────────┘  │
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  │ JeekLee │ │                                                                     │
│  │ jee…@   │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### New document (editor) (`/docs/new`)

- **Purpose:** the in-app split-view editor for creating a brand-new document. Left pane is the raw Markdown source, right pane is the live preview using the same `unified` pipeline as `/docs/public/{slug}`. Title input lives in the editor toolbar (top strip).
- **Spec trace:** M2 spec §6.2 (`POST /api/docs`), §7.2 row 4 (`/docs/new`), §9 (MD feature scope — the preview pane uses the same rendering pipeline as the public document detail), §11 row 3 (editor library choice deferred to per-milestone ADR — visual is library-agnostic split-view).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — New document (editor)  /docs/new` (node `14:463`).
- **Key elements:**
  - **Sidebar:** same signed-in shell as `/docs` — `Docs` active with `4/12` badge, signed-in footer.
  - **Topbar:** breadcrumb `Documents / New`; right side = JL account pill only (no `Signed in` chip in the editor topbar — the editor toolbar's save-state already carries that affordance; reduces toolbar clutter for the editor surface).
  - **Editor toolbar** (full-width strip directly under the topbar, height 64, `surface.soft` bg, `border-bottom 1px border`):
    - **Left:** large title input — placeholder `Untitled` in `text.subtle` rendered at `font.h2` (20px/600), transparent background, no border, no padding. On focus (out of scope visually) only shows a 2px-wide `accent` underline.
    - **Right:** save-state pill in `text.muted` (`Saving…` in the mock; `Saved <time> ago` is the resting state) + primary `Publish` button (`accent`, `radius.md`).
  - **Split-view body** (below the toolbar, fills remaining vertical space):
    - **Left pane** (50%, `surface.soft` bg): raw MD source rendered in `font.mono` 14px, padding 24px/28px. The mock source shows H1, paragraph, H2, paragraph, fenced code block, bulleted list — enough to verify every preview-pane primitive.
    - **Right pane** (50%, `bg`): live preview, max content-width ~640px inside the pane for prose readability, padding 32px/40px. Renders the same MD primitives as `/docs/public/{slug}`: `font.h1` for `#`, `font.h2` for `##`, `font.body` paragraphs, `font.mono` + `surface.soft` for inline code and fenced code blocks (`radius.md`), bulleted list with 24px indent.
    - **Thin 1px `border` vertical divider** between the two panes.
- **Interactions:**
  - **Auto-save** is M2.1 — for M2 the save-state pill flips manually on each `PATCH` triggered by the implementer's chosen editor library (per spec §11 row 3); the visual stays the same either way.
  - **Publish** opens a publish modal (visual deferred — confirmable slug + excerpt fields per spec §6.2 `PublishRequest`); on confirm the doc gets a `publish_meta` row and the screen optionally redirects to `/docs/{id}` (now in edit mode).
  - Typing in the left pane re-renders the right pane (debounced 200ms is a reasonable default for the implementer — not pinned by spec).
- **Empty / error / loading states:**
  - **First-load (empty doc):** left pane shows a faint placeholder `# Start writing…` in `text.subtle` (gets cleared on first keystroke); right pane shows the same heading rendered.
  - **Save error (4xx/5xx on PATCH):** save-state pill swaps to `danger`-fg `Save failed — retry`; left pane stays editable; the publish button disables until the next successful save.
  - **413 (body too large per spec §6.5):** save-state pill swaps to `danger`-fg `Document too large — trim`; cursor stays in the editor.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents / New                          [(JL) JeekLee ▾]          │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │┌───────────────────────────────────────────────────────────────────┐│
│  [⌕ Search]  ││ Untitled                                  Saving…   [ Publish ]  ││
│              │└───────────────────────────────────────────────────────────────────┘│
│  APPS        │┌────────────────────────────┬──────────────────────────────────────┐│
│  ⌂ Home      ││ # Notes on the design      │ Notes on the design pipeline         ││
│  ▤ Docs 4/12 ││   pipeline                 │                                      ││
│  💬 Chat M4🔒││                            │ The team is a PM, an architect, a    ││
│  📊 Stat M5🔒││ The team is a PM, an       │ designer, a reviewer — none of them  ││
│              ││ architect, a designer …    │ me, none of them human.              ││
│              ││                            │                                      ││
│              ││ ## Why a team              │ Why a team                           ││
│              ││                            │                                      ││
│              ││ One agent has to be …      │ One agent has to be everything;      ││
│              ││                            │ a team can specialize.               ││
│              ││ ```                        │ ┌──────────────────────────────────┐ ││
│              ││ $ /design M2               │ │ $ /design M2                     │ ││
│              ││ ```                        │ │ → dispatching product-designer…  │ ││
│              ││                            │ └──────────────────────────────────┘ ││
│              ││ - each agent has a brief   │                                      ││
│              ││ - the orchestrator …       │ •  each agent has a narrow brief    ││
│              ││ - every cycle ends in …    │ •  the orchestrator never improvis…││
│              ││                            │ •  every cycle ends in a checkpoint ││
│  ┌─────────┐ │└────────────────────────────┴──────────────────────────────────────┘│
│  │(JL)     │ │                                                                     │
│  │ JeekLee │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Edit document (`/docs/{id}`)

- **Purpose:** edit an existing document. Same split-view editor as `/docs/new`, with three additions for the published-document case: a `→ View public` deep-link above the toolbar (visible only when published), an `Unpublish` outline button and a `Delete` ghost button in the toolbar (before the primary), and a primary CTA reframed from `Publish` to `Publish changes`.
- **Spec trace:** M2 spec §6.2 (`PATCH /api/docs/{id}`, `POST /api/docs/{id}/publish`, `POST /api/docs/{id}/unpublish`, `DELETE /api/docs/{id}`), §7.2 row 5 (`/docs/{id}`).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Edit document  /docs/{id}` (node `14:508`).
- **Key elements:**
  - **Sidebar + topbar:** identical to `/docs/new` — `Docs` active with `4/12` badge, account pill on the right. Breadcrumb changes to `Documents / Building an agent team` (full title; ellipsis at 320px).
  - **`→ View public: /docs/public/building-an-agent-team`** accent text-link (`font.small`/500) rendered between the topbar and the editor toolbar (4px-padded strip on `bg`). Shown only when the doc is currently published; in the mock the doc is `Published` so the link is present.
  - **Editor toolbar** (same `surface.soft` strip):
    - **Left:** title input populated with `Building an agent team` (no placeholder; the actual saved title in `font.h2`/600).
    - **Right:** save-state pill `Saved 3s ago` → outline `Unpublish` button (`surface` bg + `border.strong` stroke, `radius.md`) → ghost `🗑 Delete` button (`surface.soft` bg, `text.muted` fg, becomes `danger` on hover per the implementer's CSS — not visible in this static mock) → primary `Publish changes` button (`accent`).
  - **Split-view body:** identical pane layout to `/docs/new`. Mock content is a fuller version of the same MD source / preview as the new-doc screen — long enough to exercise paragraph, h2, inline code, fenced code, list, blockquote in the preview pane.
- **Interactions:**
  - `Publish changes` calls `PATCH /api/docs/{id}` (body) then implicitly re-emits the slug + excerpt edit if changed via the publish modal (spec §6.2 `PublishRequest`).
  - `Unpublish` opens a confirm modal `Unpublish this document? Its slug is retained for re-publish.` (matches the spec §4.4 state-machine guarantee that re-publish reuses the existing `publish_meta` row, slug intact).
  - `Delete` opens a destructive-action confirm modal `Delete this document? This cannot be undone.` with `Cancel` (secondary) + `Delete` (danger, `danger` bg + white text per design system §6.1 danger variant).
  - The `→ View public` link opens `/docs/public/{slug}` in a new tab — gives the author a one-click sanity check of the rendered output.
- **Empty / error / loading states:**
  - **404 (someone else's doc id — per spec §10 tenant isolation):** the editor doesn't render; instead a centered 404 card identical to the `/docs/public/{slug}` 404 with copy `That document doesn't exist (or isn't yours).` + `Go to my documents →` link.
  - **Save error:** same `danger`-fg save-state pill behavior as `/docs/new`.
  - **Loading the initial doc:** title input and panes show skeletons.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents / Building an agent team        [(JL) JeekLee ▾]         │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │ → View public: /docs/public/building-an-agent-team                  │
│  [⌕ Search]  │┌───────────────────────────────────────────────────────────────────┐│
│              ││ Building an agent team    Saved 3s ago  [Unpub] [🗑Del] [Pub chg] ││
│  APPS        │└───────────────────────────────────────────────────────────────────┘│
│  ⌂ Home      │┌────────────────────────────┬──────────────────────────────────────┐│
│  ▤ Docs 4/12 ││ # Building an agent team   │ Building an agent team               ││
│  💬 Chat M4🔒││                            │                                      ││
│  📊 Stat M5🔒││ Most days I write more     │ Most days I write more prompts than  ││
│              ││ prompts than I write code  │ I write code. Three months in, that  ││
│              ││ …                          │ math has stayed honest…              ││
│              ││                            │                                      ││
│              ││ ## Why a team, not a       │ Why a team, not a single agent       ││
│              ││    single agent            │                                      ││
│              ││                            │ One agent has to be everything;      ││
│              ││ One agent has to be …      │ a team can specialize. Read more in  ││
│              ││ `.claude/agents/` —        │ [.claude/agents/] — that's the…     ││
│              ││ that's the whole int…      │                                      ││
│              ││                            │ ┌──────────────────────────────────┐ ││
│              ││ ```bash                    │ │ $ /design M2                     │ ││
│              ││ $ /design M2               │ │ → dispatching product-designer…  │ ││
│              ││ ```                        │ └──────────────────────────────────┘ ││
│              ││                            │                                      ││
│              ││ - each agent has a brief   │ •  each agent has a narrow brief    ││
│              ││ - the orchestrator …       │ •  the orchestrator never improvis…││
│              ││                            │ •  every cycle ends in a checkpoint ││
│              ││ > The thing I keep …       │ │ The thing I keep coming back to…  ││
│              │└────────────────────────────┴──────────────────────────────────────┘│
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Search results (`/docs/search`)

- **Purpose:** full-page full-text search against the OpenSearch projection (`GET /api/docs/search?q=…&scope={mine|public}`). `⌘K` palette eventually wires into this — for M2 P0 the palette is deferred (spec §2 deferred list), so this page is reached from the per-page search input or by direct URL.
- **Spec trace:** M2 spec §6.2 (`GET /api/docs/search?q=…&scope=mine`), §6.1 (`GET /api/docs/search?q=…&scope=public`), §7.2 row 6 (`/docs/search`), §10 (OpenSearch projection lag tolerance, search failure isolation).
- **Auth state:** authenticated (the `mine` scope requires `X-User-Id`; the `public` scope is also reachable here for the author since they can scope-toggle).
- **Figma frame:** `M2 — Search results  /docs/search` (node `14:563`).
- **Key elements:**
  - **Sidebar + topbar:** signed-in shell. Breadcrumb `Documents / Search`. Right side = JL account pill.
  - **Sticky search bar** at the top of main content (y=1086 in the mock): large search input (placeholder `Search…`, `radius.pill`, `font.body` 15px, max-width 720px, `border.strong` stroke) — mock shows the query `agent team` typed in. Right of the input: a 2-segment scope toggle (`Mine` / `Public`) in a `surface.soft` rounded container, mirroring the segmented switcher pattern from `/docs`. `Mine` is active in the mock (`accent.soft` bg + `accent` label weight 600).
  - **Result count** line: `6 results` in `font.small text.muted`.
  - **6 mock hits**, each a stacked block:
    - **Title** (`font.h3` 16px/600).
    - **Snippet** in `font.body text.muted` with the matched keyword wrapped in a `<mark>`-equivalent `accent.soft` background span (no border, no radius needed — the bg-tint reads as the highlight). Hits 1-3 carry the full highlight treatment; hits 4-6 use a plain snippet line (the highlight is the same component, mocked once on a few hits to keep the frame readable).
    - **Meta row** in `font.small text.subtle`: `<visibility chip — Published or Draft> · /docs/{id-prefix} · updated <relative time>`.
- **Interactions:**
  - Typing in the input fires the search on debounce (200ms is a reasonable default); the URL updates with `?q=…&scope=…` for share-ability.
  - Toggle scope to `Public` swaps the result set to the owner-filtered public search (`scope=public`) — same component, different hits.
  - Clicking a hit navigates to `/docs/{id}` (always — even for `scope=public`, the author is the only one searching their own corpus; M4's public chat will use the public scope for retrieval, not this UI).
- **Empty / error / loading states:**
  - **Empty query:** "Start typing to search your documents." in `text.muted`, no results region rendered.
  - **Empty result set:** `No matches for "<q>"` + a `text.muted` suggestion `Try a broader keyword or switch scope.`
  - **Loading:** 4 hit skeletons (title 70% width, two snippet bars, meta-row skeleton).
  - **503 (OpenSearch unavailable — per spec §6.5):** a danger-bordered card replaces the results region with `Search is unavailable right now. Other features still work.` The search input stays interactive; clicking retry retries the same query. This matches spec §10 search-projection-failure-isolation — the rest of M2 keeps working.

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents / Search                  [(JL) JeekLee ▾]               │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │  ┌──────────────────────────────────────────┐  ┌──────────────┐    │
│  [⌕ Search]  │  │ ⌕  agent team                            │  │[Mine] Public │    │
│              │  └──────────────────────────────────────────┘  └──────────────┘    │
│  APPS        │  6 results                                                          │
│  ⌂ Home      │  Building an agent team                                             │
│  ▤ Docs 4/12 │  …the [agent team] is a PM, an architect, a designer, a reviewer  │
│  💬 Chat M4🔒│  [Published]  ·  /docs/a3f2b9  ·  updated 2h ago                   │
│  📊 Stat M5🔒│                                                                     │
│              │  M2 brainstorming notes                                             │
│              │  …the [agent team] for Docs ships a per-user search projection…   │
│              │  [Draft]  ·  /docs/9c1d2e  ·  updated 3d ago                       │
│              │                                                                     │
│              │  Why olive, not blue                                                │
│              │  …the design system spec calls the [agent team] the source of …   │
│              │  [Published]  ·  /docs/77a1ba  ·  updated 1d ago                   │
│              │                                                                     │
│              │  Spark, but for one person                                          │
│              │  …why I keep reaching for a cluster engine on a side project …    │
│              │  [Published]  ·  /docs/4b2090  ·  updated 2w ago                   │
│              │                                                                     │
│              │  Random thoughts on RAG                                             │
│              │  …the agent team's reviewer agent does its best work when …       │
│              │  [Draft]  ·  /docs/12fe9c  ·  updated 1w ago                       │
│              │                                                                     │
│              │  MSA in a garage                                                    │
│              │  …the agent team's architect cares deeply about which BC owns…    │
│              │  [Published]  ·  /docs/55ab30  ·  updated 3w ago                   │
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

## Home composition deltas (no new M2 frame; deltas only)

M2 spec §7.3 supersedes design system §9 item 3 in two ways. These deltas apply to the **existing M1 home frames** (`14:2` Public Home and `14:135` Signed-in Home) when M2 ships — `frontend-implementer` applies them then; this design pass does NOT modify those M1 frames.

1. **Section header rename:** the home's documents section is labeled **`Latest documents`** (M2 spec §7.3 — supersedes any prior "Latest from the blog" wording). The data source becomes the owner-filtered `GET /api/docs/public` (already owner-filtered at the API per spec §6.1), so the wording leans into the personal-platform posture (design system §2.4).
2. **Card meta extension:** the M1 mock currently shows the empty-state card (no real document cards yet). When M2 ships, the empty-state card is replaced by the same 3-column thumbnail grid used in `/docs/public`. The **per-card meta row** extends from `· N min · {date}` to `· N min · {date} · 👁 viewCount · ♥ likeCount` (icon glyphs are placeholders — frontend-implementer swaps to Lucide `Eye` / `Heart` per design system §7 emoji-to-icon migration rule).

Both deltas are textual + data-source changes; no new layout, no new tokens. The existing card geometry and the existing chip vocabulary cover everything.

## Traceability matrix

Every M2 spec subsection that has user-facing surface area is mapped to one or more screens, or to `N/A — backend-only` with a reason.

| Spec section | Screen(s) |
|---|---|
| §3 — Bounded context (`docs` owns content + visibility + counters + search projection) | N/A — backend-only (architectural; surfaces only via the routes below) |
| §4.1 — Postgres `documents` schema (visibility, view_count, like_count) | Documents (public list) (counts in card meta); Document detail (counts in title meta + like control); My documents (counts in published-row meta); Edit document (visibility surfaced via Unpublish button + `→ View public` link) |
| §4.1 — `publish_meta` (slug, excerpt) | Document detail (URL = slug; meta = excerpt-derived); Edit document (publish modal — visual deferred); My documents (Published chip = `publish_meta` exists) |
| §4.1 — `document_likes` (per-user like) | Document detail (the inline like button with `likedByMe` state — disabled for anonymous reader in the mock) |
| §4.2 — OpenSearch index (`docs-v1`) | Search results (the entire screen is the index's UI surface). Public-scope search also reachable from this screen via the scope toggle. |
| §4.4 — Document state machine (private → public ↔ private, slug stable) | Edit document (Unpublish + Publish-changes + `→ View public` link together expose every legal transition; slug stability is implicit in the `→ View public` URL being stable across re-publishes) |
| §5 — Kafka events (`docs.document.uploaded` / `.deleted` / `.visibility-changed`) | N/A — backend-only (no user-facing surface; the surface is the resulting consistency between the editor and the search-projection + RAG handoff) |
| §5.1 — In-service search projector | N/A — backend-only (observability: the lag tolerance is enforced by the projector, but the surface is `/docs/search` working correctly) |
| §6.1 — `GET /api/docs/public` (owner-filtered list) | Documents (public list); Home (post-M2 "Latest documents" section, via the §7.3 deltas above) |
| §6.1 — `GET /api/docs/public/{slug}` | Document detail |
| §6.1 — `GET /api/docs/search?scope=public` | Search results (via the scope toggle = `Public`) |
| §6.1 — `POST /api/docs/public/{slug}/view` | Document detail (fired on page load; the displayed `viewCount` reflects the post-increment value) |
| §6.2 — `GET /api/docs/mine` | My documents |
| §6.2 — `GET /api/docs/search?scope=mine` | Search results (default `Mine` scope in the mock) |
| §6.2 — `POST /api/docs` (in-app create + `.md` file upload) | New document (editor) — the in-app create path. The `.md` file upload is mentioned in the spec but the upload affordance is M2.1 visual (drag-and-drop or button in the editor) — flagged in Open questions below. |
| §6.2 — `GET /api/docs/{id}` | Edit document |
| §6.2 — `PATCH /api/docs/{id}` | New document + Edit document (the save-state pill is the user-facing artifact) |
| §6.2 — `POST /api/docs/{id}/publish` | Edit document (Publish-changes button) and New document (Publish button) |
| §6.2 — `POST /api/docs/{id}/unpublish` | Edit document (Unpublish button) |
| §6.2 — `DELETE /api/docs/{id}` | Edit document (🗑 Delete ghost button) |
| §6.2 — `POST /api/docs/{id}/like` / `DELETE /api/docs/{id}/like` | Document detail (inline like button — disabled for anonymous in the mock, fully active in the authenticated-viewer interaction) |
| §6.3 — Owner resolution (`PLAYGROUND_OWNER_GOOGLE_SUB`) | N/A — deployment-time concern (the env var resolves before any of these screens render; no in-UI affordance) |
| §6.4 — DTOs (PublicDocListItem, PublicDocDetail, MyDocListItem, MyDocDetail, SearchHit) | All 6 screens consume one of these shapes — each "Key elements" section above names the data fields actually rendered |
| §6.5 — Error semantics (400/401/404/409/413/503) | Per-screen "Empty / error / loading states" entries cover the user-visible variants. 401 redirects to `/login` per M1 design. 503 from OpenSearch surfaces only on Search results (per spec, the rest of M2 still works). |
| §7.1 — Sidebar (Apps section, `Docs` row active/badged when shipped) | All 6 screens (every sidebar mock shows the `Docs` row active with `accent.soft` bg + `accent` label) |
| §7.2 — Client routes | Each route maps to one screen (1:1, 6 rows = 6 screens) |
| §7.3 — Home composition deltas (rename + meta extension) | Deltas-only section above (no new M2 frame); applied to existing M1 home frames at implementation time |
| §8 — RAG handoff trace | N/A — backend-only (the docs BC's responsibility is publishing accurate events; M3 + M4 own the user-facing chat surface) |
| §9 — Markdown feature scope (GFM, code highlighting, fenced code, blockquote, list, inline code, external-URL images) | Document detail (renders the full set); New document + Edit document (preview pane uses the same pipeline) |
| §10 — Non-functional requirements (tenant isolation, search lag tolerance, view dedup, like idempotency) | Surfaces only through correct behavior; tenant-isolation 404 explicitly mocked in Edit document's 404 state. Search lag and view dedup are invisible when working correctly. |

Every §6 / §7 row that has a user-facing surface is mapped above. Backend-only rows are explicitly tagged.

## Design tokens used

Every value below is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer mirrors them into `client/src/shared/ui/tokens/` (per ADR-06) — no hex value in this document is invented, every appearance maps to a spec entry.

| Token | Value | Where used |
|---|---|---|
| `color.bg` | `#FAF7EF` | App background on all six frames; topbar bg; preview pane bg in editor |
| `color.surface` | `#FFFFFF` | Document cards, doc list card, account footer card, like button, search input, account pill, secondary-button bg, segment-switcher inactive bg |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; editor toolbar bg; editor MD pane bg; inline-code bg; fenced-code-block bg; segment-switcher container bg; Draft chip bg; sand thumbnail gradient on document cards; Delete-ghost-button bg |
| `color.border` | `#E6E0CB` | Card strokes; topbar `border-bottom`; editor-toolbar `border-bottom`; list-row dividers; split-view vertical divider; account-pill stroke; account-footer card stroke |
| `color.border.strong` | `#D6CFB3` | Search input border; large search-bar border; secondary `Load more` button stroke; Unpublish-outline button stroke; blockquote left rule |
| `color.khaki` | `#C2B88A` | Khaki thumbnail gradient on document cards; sidebar-footer avatar; topbar account-pill avatar |
| `color.text` | `#2A2C20` | Page titles, document titles, doc titles in list rows, hit titles, account name, body MD text, preview h1/h2 fg, highlight `<mark>` fg |
| `color.text.muted` | `#6F6A55` | Hero subtitle, document excerpts, breadcrumb, neutral chip fg, Draft chip fg, all "updated …" meta, blockquote body, save-state pill, hit chip-Draft fg, account email, list-row meta numbers, sidebar wordmark line 2, Sign-in-to-like tooltip hint |
| `color.text.subtle` | `#8B8670` | Sidebar `APPS` label, sidebar search-pill placeholder, locked Apps row labels and milestone badges (Chat M4, System status M5), editor `Untitled` placeholder, search-bar meta fg in hit-card meta rows |
| `color.accent` | `#6E7A3A` | All primary CTA fills (`Sign in with Google`, `+ New document`, `Publish`, `Publish changes`), active nav fg (Docs `▤ Docs` active label), active-segment label (All in `/docs`, Mine in `/docs/search`), all `→` and `←` text-links, tag-chip fg, Published-chip fg, hit-Published-chip fg, sidebar `4/12` Docs badge, glyph J fill, `→ View public` link |
| `color.accent.hover` | `#5C6730` | (Reserved — primary-button hover treatment; not visible at rest in these static mocks but the implementer applies it on hover per spec §6.1) |
| `color.accent.soft` | `#E9E8D1` | Active nav bg (`▤ Docs` active row bg), active-segment bg, tag-chip bg, Published-chip bg, search-result highlight `<mark>` bg |
| `color.success` | `#4F6B2E` | `● Signed in` topbar chip fg (signed-in screens) |
| `success` chip bg | `#E5EBD9` | `● Signed in` chip bg; sage thumbnail gradient on document cards (this is the spec's `success` chip bg from §6.3, reused for the sage decorative gradient — same value, no new token) |
| `font.h1` | 28px / 1.2 / 700 / -0.02em | Page titles: `Documents by JeekLee`, document article titles, preview-pane h1 |
| `font.h2` | 20px / 1.3 / 600 / -0.01em | Section titles (`Latest`, `My documents`), preview-pane h2, MD body h2, editor toolbar title input |
| `font.h3` | 16px / 1.4 / 600 / 0 | Document card titles, doc list row titles, search hit titles |
| `font.body` | 15px / 1.6 / 400 / 0 | Hero subtitle, document article body paragraphs, preview-pane paragraphs, search hit snippets, search input text |
| `font.small` | 13px / 1.5 / 400 / 0 | Document card excerpts, breadcrumb, all button labels (13px / 500 per spec §6.1), all `→` accent links, account-pill name, list-row meta text, segment labels, hit-meta chip-row text |
| `font.eyebrow` | 11px / 1.2 / 600 / +0.14em / uppercase | Sidebar `APPS` label |
| `font.mono` | 13px / 400 | All inline code (`.claude/agents/`), all fenced code blocks (document detail + both editor preview panes), MD source pane (rendered at 14px mono per the editor convention, still spec mono family) |
| `spacing.xs` | 4px | Intra-element micro-gaps (chip dot to label, view-public link to toolbar, search-input icon to text) |
| `spacing.sm` | 8px | Eyebrow → title gap, tile internal gap, save-state to button gap, toolbar button-row gap |
| `spacing.md` | 16px | Card internal padding, hero spacing, blockquote padding-left, fenced-code-block padding |
| `spacing.lg` | 24px | Section vertical rhythm, hero → grid gap, page-header → segment-switcher gap, list-card internal padding-y, editor pane padding-y |
| `spacing.xl` | 40px | Editor preview pane padding-x; topbar → editor-toolbar offset |
| `radius.sm` | 6px | Inline code rounded background; search input (`/docs` per-page narrow search); kbd pill |
| `radius.md` | 10px | Buttons (primary + secondary + outline + ghost), cards, list card, segment-switcher container, fenced code blocks, sidebar nav-item active bg, account footer card |
| `radius.lg` | 14px | (Reserved — for modals like the Publish modal and the Delete confirm; no static modal mocked in these frames but the implementer reserves this radius) |
| `radius.pill` | 999px | Sidebar search pill, all chips, account pill, avatars, like button, large search bar in `/docs/search`, scope-toggle active segment |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | All cards at rest (document thumbnail cards, list card, account footer) |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | (Reserved — hover-as-link card lift per spec §6.4; not visible at rest, applied on hover by the implementer) |

**Verification note:** every hex value above appears in the design system spec at §3.1 / §3.2 / §3.3 / §6.3 or in §5.3 elevation. No new tokens. The thumbnail gradients explicitly reuse `khaki`, `surface.soft`, and the spec §6.3 `success` chip bg `#E5EBD9` — no fourth thumbnail color is introduced.

## Out of scope (this milestone)

Items the M2 spec defers to M2.1, plus the P2 list:

- **Image / attachment upload** — M2.1 P1 (presigned to local volume or Postgres `bytea`, decided in M2.1 ADR). The editor toolbar in `/docs/new` and `/docs/{id}` does NOT show an image-upload button.
- **Editor auto-save** — M2.1 P1. The mock shows a manual `Saving…` save-state pill; an "Auto-save on" indicator is deferred.
- **`⌘K` global palette** — M2.1 P1. The sidebar's `⌘K` search pill remains a visual placeholder (it exists from M1; the kbd glyph stays); enter-from-palette → `/docs/search` is implementer's call but the palette UI itself is M2.1.
- **Cover image on documents** — M2.1 P1 (`publish_meta.cover_image_url` is nullable in M2 per spec §4.1; the Documents list cards use the design-system gradient thumbs, not cover images).
- **Comments on public documents** — M2.1 P1. The Document detail page does NOT have a comments region; the like button is the sole engagement signal in M2.
- **`.md` file upload affordance in the editor** — the API accepts `multipart/form-data` per spec §6.2, but the visual entry point (drag-and-drop zone or upload button) is **flagged as an Open question** below — it could ship with M2 if the implementer wires a small affordance into the editor toolbar, or defer to M2.1 alongside image upload. Mocked as deferred for now.
- **Tags / categories** — P2. The mock tag chips on document cards (`agents`, `spark`, `design`, `infra`, `search`, `rag`) are visual-only — they are stored nowhere in M2 schema and are NOT clickable. Per spec §2 the data model has no tag table; tags-as-data ship with a future milestone.
- **RSS / Atom feeds** — P2. No feed link in the Documents list header.
- **Version history / diff view** — P2. No "history" affordance in the editor toolbar.
- **Multi-author** — P2 (the site stays single-author; owner resolution is configured at deploy time per spec §6.3).
- **Engagement-driven ranking** — P2 (view/like counters are stored, not yet used to re-order the public feed).
- **Slug rename action** — spec §11 row 5 deferred to M2.1+. No "rename slug" affordance in the Edit document toolbar.
- **Account-pill dropdown contents** — carried over from M1 Open questions. The chevron is visible on the JL pill but the menu contents (likely `My documents` / `Sign out`) are still deferred.
- **Mobile / responsive layouts** — desktop 1440 only. Sidebar collapse modes (768-1023 icon rail, <768 hamburger drawer) are specified in design system §8.1 but visual mocks are deferred to M4 (the first read-on-the-phone use case).
- **Dark mode** — token names are reserved per design system §3.4.

## Open questions for the next cycle

- **Figma frame `name` field still stale on two M2 frames.** The Talk to Figma plugin allowlist exposes `set_text_content` for TEXT nodes but no node-rename tool, so frames `14:258` and `14:342` still carry the v3 names `M2 — Essays (public list)  /essays` and `M2 — Essay detail (public)  /essays/{slug}` even though all their internal text was migrated to the new vocabulary. The four other M2 frames (`14:388`, `14:463`, `14:508`, `14:563`) already had v4-compliant names. Action: the human reviewer can rename the two frames in Figma directly (right-click → Rename) to `M2 — Documents (public list)  /docs/public` and `M2 — Document detail (public)  /docs/public/{slug}` before exporting PNG assets, OR the cursor-talk-to-figma maintainer can extend the plugin to expose `set_node_name`. Cost is minimal either way.

- **`.md` upload affordance.** Spec §6.2 says `POST /api/docs` accepts `multipart/form-data` with a `.md` file plus optional `title`. The mock does NOT include an upload entry point in the editor toolbar. Two options:
  1. Add a small `↑ Upload .md` ghost button to the `/docs/new` toolbar (left of `Publish`). Ships with M2 with no schema or API change required.
  2. Defer to M2.1 alongside image upload — both upload paths land together with a single drag-and-drop zone in the editor body. Cleaner UX but the API is sitting unused for one release.
  Recommendation: option 1, since the API ships either way and a 32×32 ghost button is trivial. Flagging for the implementer to confirm during Stage 3.

- **Publish modal visual.** Spec §6.2 defines `PublishRequest { slug?, excerpt? }` and §10 mandates slug-stability tests, but the modal that surfaces the slug + excerpt fields at publish-time has no mock here. M2 should ship one. Pattern recommendation: a `radius.lg` 14px modal centered on a 50% `bg` scrim, 480×420, with two `font.body` text inputs (`Slug` and `Excerpt`), a `Cancel` (secondary) + `Publish` (primary, accent) button row at the bottom. Same modal is reused for `Publish changes` (pre-populated with current values).

- **Delete confirm modal visual.** Same structure as the publish modal but with `danger`-bg `Delete` primary (spec §6.1 danger variant). Working default: `Cancel` (secondary) + `Delete document` (danger, white text). Open: does the modal require typing the document title to confirm (GitHub-style)? For a single-author personal site that's probably overkill; flagging anyway.

- **Account pill dropdown contents.** Same open question as M1's design doc — gets answered here at M2 since `My documents` is now a second item the menu can carry. Recommendation: `My documents` (links to `/docs`), divider, `Sign out` (calls `/logout` per ADR-07). Frontend-implementer can ship this as an M2 visual at no extra cost since the chevron is already mocked.

- **Empty-state CTA wording in `/docs`.** When the user has zero documents, the empty-state card says `No documents yet. Start writing.` with a `+ New document` button. Open: should the empty state also surface the `.md` file upload affordance (if Open question 1 lands) so first-time users can either type fresh or import? Working default: yes, with a small "or upload a .md file" secondary link below the primary button. Flagging for the implementer.

- **Search-result row click target — full row vs. title only.** The list rows in `/docs` are hover-as-link on the whole row. The search hits in `/docs/search` look similar but the meta chip + slug span feel like they could be separate targets. Working default: whole hit-block is the click target (matches the `/docs` pattern); the slug `/docs/{id-prefix}` text in the meta is non-interactive copy. Flagging in case the implementer disagrees.

- **Inline PNG capture.** Same Figma MCP base64-intercept blocker as M1 — the Figma file is canonical, ASCII wireframes are the inline reference. Manual one-call-per-frame export from Figma (`File → Export selected → PNG @ 2x`) drops the 6 frames into `assets/M2/{documents-list,document-detail,my-documents,new-document,edit-document,search-results}.png` when the human reviewer wants them inlined here.
