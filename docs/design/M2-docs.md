# Design: M2 — Docs (Markdown authoring + public documents + search)

> Spec: `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md`
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md` (canonical token vocabulary)
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN/playground-%E2%80%94-M1-Identity (M2 frames added below the M1 row at y ≥ 1000; M1 frames are unchanged)
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill — all reused verbatim except the `Docs` Apps row, which is unlocked + active on every M2 route)

Stage 2 output for the Docs (M2) bounded context. Ten 1440-wide frames: six desktop page screens + one global `⌘K` palette overlay + three modal overlays (Publish, Unpublish, Delete). Built strictly against the design system spec (tokens, layout shell, chip vocabulary) with the M2 spec's §7.1 sidebar override applied: the `Docs` Apps row is **shipped/active** on every M2 route (`accent.soft` bg + `accent` label, weight 600). Chat (M4) and System status (M5) remain locked. Tokens table below is sourced verbatim from the design system spec — frontend-implementer mirrors them into `client/src/shared/ui/tokens/`; no new tokens are introduced by this milestone.

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

- **Purpose:** the author's at-a-glance index of every document they've authored, mixed visibility. Tab-switched between `All / Drafts / Published`. Top-right surfaces the per-screen search (complementary to the global ⌘K palette — per-screen search is filter-friendly; ⌘K is keyboard-fastest, both ship in M2 P0) and the primary `+ New document` CTA.
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

### + New document dropdown + .md import affordance

- **Purpose:** two parallel paths to bring content into the system — (a) write from scratch via the BlockNote editor, (b) import an existing `.md` file from disk. Both surface from `/docs` and from any page where a `+ New document` action exists.
- **Spec trace:** M2 spec §2 P0 (`.md` file upload bullet), §6.2 `POST /api/docs` (multipart variant with `.md` file), §7.2 row `/docs` (the `+ New document` button — now with a dropdown chevron).
- **Auth state:** authenticated.
- **Figma frames:** `M2 — + New document dropdown (overlay)  /docs` (node `30:859`) + `M2 — Drag-drop import overlay (active)  /docs` (node `30:860`).
- **Key elements (dropdown):** The `/docs` page-header `+ New document` button gets a small `▾` chevron immediately to the right of the label (inside the same `accent` button surface — a 1px `accent.hover` divider separates the label from the chevron click target so the two are independently clickable). Clicking the label = default action (Blank document → `/docs/new`). Clicking the chevron opens a 200px dropdown card (`surface` bg, `border` 1px, `radius.md`, `shadow.pop` if available) with two rows: `+ Blank document` (primary path) and `↑ Import .md…` (opens native file picker filtered to `.md`).
- **Key elements (drag-drop overlay):** Active on `/docs` AND `/docs/new` AND `/docs/{id}`. Triggered on `dragenter` of a file (any file type) anywhere in the viewport. Full-viewport `color.text @ 0.30α` backdrop + centered drop card (400×200, `surface`, dashed `border.strong` 2px, `radius.lg`). Card content: big `↑` accent glyph + title + subtitle. On `/docs/new` and `/docs/{id}` the title swaps to `Drop .md to replace this document's body` (`color.danger` accent glyph instead of `color.accent` — communicates destructive overwrite). The static Figma mock uses a solid 2px stroke because the Talk to Figma plugin's `create_rectangle` does not expose a dashed-stroke setter; the implementer applies `border-style: dashed` from CSS at impl time (see Open questions).
- **Interactions:**
  - **Click + New document (label):** navigates to `/docs/new` with the current folder path inherited (as today).
  - **Click + New document (chevron):** opens the dropdown.
  - **Click "+ Blank document" in dropdown:** same as clicking the label.
  - **Click "↑ Import .md…" in dropdown:** opens native file picker filtered to `.md` extension. On select, `POST /api/docs` multipart (file body + `path` set to the current folder) → on success navigates to `/docs/{newId}` (the new doc opens in the editor for review).
  - **Drag a file over the viewport:** overlay appears within 100ms. Drag-leave with no drop dismisses. Drop on the card uploads via the same multipart path.
  - **Non-.md file dropped:** the drop is rejected; a `danger`-fg toast appears in the topbar reading `Only .md files are accepted.` for 3s.
- **Empty / error / loading states:**
  - **Loading (upload in progress):** drop card swaps to a single line `Uploading <filename>…` with a small spinner.
  - **Error (413 body too large per spec §6.5):** danger toast `<filename> is too large (>1MB). Trim and try again.`
  - **Error (multipart parse failure):** danger toast `Couldn't read <filename>. Make sure it's a UTF-8 Markdown file.`
- **Open questions:**
  - Chevron click-target separator inside the primary button — using a 1px `accent.hover` divider is borderline visible at this contrast. Implementer may use a wider gap or a darker divider; static mock is illustrative only.
  - Drag-drop overlay on `/docs/{id}` is destructive (replaces body) — should it require a confirm step? Working default: no confirm, but the overlay copy is explicit ("replace"). M2.1 could add a confirm if this turns out to be a footgun.

### New document (editor) (`/docs/new`)

- **Purpose:** in-app block editor for creating a brand-new document. Notion-style single-pane: each line is a block (paragraph, h1/h2/h3, list, quote, code, etc.), `/` summons a block-type picker, blocks are reorderable via a drag handle that appears on row hover. The body roundtrips raw MD: on load, MD parses to blocks via `tryParseMarkdownToBlocks`; on save, blocks serialize back via `blockToMarkdownLossy`. The public render at `/docs/public/{slug}` still uses the `unified` + `remark` + `rehype` + `shiki` pipeline against the raw MD body — BlockNote changes the **authoring** UX, not the **reading** pipeline.
- **Spec trace:** M2 spec §6.2 (`POST /api/docs`), §7.2 row 4 (single-pane block editor — BlockNote), §9 (MD feature scope — preserved because storage is still raw MD), §11 Q3 (DECIDED: BlockNote).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — New document (editor)  /docs/new` (node `14:463`).
- **Key elements:**
  - **Sidebar:** same signed-in shell as `/docs` — `Docs` active with `4/12` badge, locked Chat/System status rows, signed-in account footer.
  - **Topbar:** breadcrumb `Documents / New`; right side = JL account pill only (no `Signed in` chip in the editor topbar — the editor toolbar's save-state already carries that affordance; reduces toolbar clutter for the editor surface).
  - **Editor toolbar** (slim strip below topbar, `surface.soft` bg, `border-bottom 1px border`, padding `12px 28px`, auto-layout HORIZONTAL with space-between):
    - **Left:** just the save-state pill in `font.small text.muted` (`Saved 3s ago` / `Saving…` / `Save failed — retry`). **No title input here** — the title is the first H1 block in the editor itself (Notion convention). On save the first H1's text becomes the `title` column on the `documents` row.
    - **Right:** primary `Publish` button (`accent` per design system §6.1).
  - **Editor surface** (main content area below toolbar):
    - `bg` background, padding `40px 0`. Inner content max-width 720px, horizontally centered (prose density).
    - On first load (empty doc): renders a single placeholder block at `font.h1`/`text.subtle` reading `Untitled` followed by a paragraph block at `font.body`/`text.subtle` reading `Type / for commands…`. Both clear on first keystroke.
    - **Each block row:**
      - On hover, the left margin (24px outside the prose column) reveals a small `+` add button (`text.subtle`, `radius.sm`, click → adds an empty block below) AND a `⋮⋮` drag handle (`text.subtle`, drag → reorders). Neither is visible at rest — keeps the page calm.
      - The block content itself renders in the appropriate spec token: `font.h1` for h1 blocks, `font.h2` for h2, `font.body` for paragraphs, `font.mono` 13px on `surface.soft` rounded `radius.sm` for inline code, multi-line `font.mono` on `surface.soft` `radius.md` 10px padding for fenced code, `border.strong` left rule + 16px padding-left for blockquote.
    - **Slash command popover** (mocked open in the static frame): triggered by typing `/` in any empty paragraph block. Floating card (`surface`, `radius.md`, `shadow.pop`, ~280px wide) positioned to the right of the caret. Lists block types: `Heading 1`, `Heading 2`, `Heading 3`, `Bulleted list`, `Numbered list`, `Quote`, `Code block`, `Divider`, `Image` (the Image item is M2.1 — render as disabled in M2). Each row shows an icon glyph + label + small `font.mono` keyboard hint right-aligned (e.g., `h1`, `h2`, `>`, `\`\`\``, `---`). Active row highlighted `accent.soft` bg + `accent` label. Keyboard navigation: ↑↓ to move, Enter to insert, Esc to dismiss.
- **Interactions:**
  - Typing renders blocks as you type — what you see IS the prose; no preview re-render to wait for.
  - `/` in an empty block opens the slash menu. Typing a few letters filters (`/h1` jumps to Heading 1).
  - Hovering a block reveals its side menu (`+` and `⋮⋮`).
  - Drag the handle to reorder the block; drop indicators on adjacent blocks.
  - `Publish` (toolbar right) opens the Publish modal (visual deferred per M2 Open questions).
  - `⌘+S` or autosave triggers `PATCH /api/docs/{id}`; the save-state pill updates.
- **Empty / error / loading states:**
  - **First-load (empty doc):** `Untitled` h1 placeholder + `Type / for commands…` paragraph placeholder, both in `text.subtle`, both clear on first keystroke.
  - **Save error (4xx/5xx on PATCH):** save-state pill swaps to `danger`-fg `Save failed — retry`; editor stays editable; `Publish` button disables until the next successful save.
  - **413 (body too large per spec §6.5):** save-state pill swaps to `danger`-fg `Document too large — trim`; cursor stays in the editor.
  - **`.md` upload failure:** deferred — upload affordance visual is a separate brainstorm round (see Open questions).

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Documents / New                          [(JL) JeekLee ▾]          │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │┌───────────────────────────────────────────────────────────────────┐│
│  [⌕ Search]  ││ Saved 3s ago                                       [ Publish ]   ││
│              │└───────────────────────────────────────────────────────────────────┘│
│  APPS        │                                                                     │
│  ⌂ Home      │                  ┌─────────────────────────────────┐                │
│  ▤ Docs 4/12 │                  │                                 │                │
│  💬 Chat M4🔒│                  │  Untitled                       │                │
│  📊 Stat M5🔒│                  │                                 │                │
│              │                  │  Type / for commands…           │                │
│              │                  │           │                     │                │
│              │                  │           │   ┌───────────────┐ │                │
│              │                  │           └──▶│ /             │ │                │
│              │                  │               │───────────────│ │                │
│              │                  │               │ ▤ Heading 1 h1│ │                │
│              │                  │               │ ▤ Heading 2 h2│ │                │
│              │                  │               │ ▤ Heading 3 h3│ │                │
│              │                  │               │ • Bulleted    │ │                │
│              │                  │               │ 1. Numbered   │ │                │
│              │                  │               │ ❝ Quote     > │ │                │
│              │                  │               │ ⌗ Code  ``` ` │ │                │
│              │                  │               │ ─ Divider --- │ │                │
│              │                  │               │ 🖼 Image (M2.1)│ │                │
│              │                  │               └───────────────┘ │                │
│              │                  └─────────────────────────────────┘                │
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  │ JeekLee │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Edit document (`/docs/{id}`)

- **Purpose:** edit an existing document via the same single-pane BlockNote editor as `/docs/new`, with three additions for the published-document case: a `→ View public` deep-link above the toolbar (visible only when published), an `Unpublish` outline button and a `Delete` ghost button in the toolbar (before the primary), and a primary CTA reframed from `Publish` to `Publish changes`. The first H1 block in the editor body is the saved title (in this mock, `Building an agent team`).
- **Spec trace:** M2 spec §6.2 (`PATCH /api/docs/{id}`, `POST /api/docs/{id}/publish`, `POST /api/docs/{id}/unpublish`, `DELETE /api/docs/{id}`), §7.2 row 5 (`/docs/{id}`), §11 Q3 (DECIDED: BlockNote).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Edit document  /docs/{id}` (node `14:508`).
- **Key elements:**
  - **Sidebar + topbar:** identical to `/docs/new` — `Docs` active with `4/12` badge, account pill on the right. Breadcrumb changes to `Documents / Building an agent team` (full title; ellipsis at 320px).
  - **`→ View public: /docs/public/building-an-agent-team`** accent text-link (`font.small`/500) rendered between the topbar and the editor toolbar (4px-padded strip on `bg`). Shown only when the doc is currently published; in the mock the doc is `Published` so the link is present.
  - **Editor toolbar** (same `surface.soft` strip, padding `12px 28px`, auto-layout HORIZONTAL with space-between):
    - **Left:** save-state pill `Saved 3s ago` in `font.small text.muted`. **No title input** — the title is the first H1 block in the editor body.
    - **Right (button row, `spacing.sm` gap):** ghost `🗑 Delete` button (`surface.soft` bg, `text.muted` fg, becomes `danger` on hover per the implementer's CSS — not visible in this static mock) → outline `Unpublish` button (`surface` bg + `border.strong` stroke, `radius.md`) → primary `Publish changes` button (`accent`).
  - **Editor surface:** identical BlockNote layout to `/docs/new` — single pane, `bg` background, padding `40px 0`, 720px-wide inner column. In this mock the body is populated (not empty placeholder): the first H1 block reads `Building an agent team`, followed by a paragraph, an H2 `What worked`, a bulleted list with three items, an H2 `What didn't`, and a paragraph with inline code. The slash-menu popover is NOT shown (the populated state is at-rest; the popover only opens on demand).
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
│              ││ Saved 3s ago               [🗑 Delete] [Unpublish] [Publish chg.] ││
│  APPS        │└───────────────────────────────────────────────────────────────────┘│
│  ⌂ Home      │                                                                     │
│  ▤ Docs 4/12 │                  ┌─────────────────────────────────┐                │
│  💬 Chat M4🔒│                  │  Building an agent team         │                │
│  📊 Stat M5🔒│                  │                                 │                │
│              │                  │  Most days I write more prompts │                │
│              │                  │  than I write code. The team    │                │
│              │                  │  is a PM, an architect, a       │                │
│              │                  │  designer, a reviewer.          │                │
│              │                  │                                 │                │
│              │                  │  What worked                    │                │
│              │                  │                                 │                │
│              │                  │  •  each agent has a narrow     │                │
│              │                  │     brief                       │                │
│              │                  │  •  the orchestrator never      │                │
│              │                  │     improvises                  │                │
│              │                  │  •  every cycle ends in a       │                │
│              │                  │     checkpoint                  │                │
│              │                  │                                 │                │
│              │                  │  What didn't                    │                │
│              │                  │                                 │                │
│              │                  │  Anything that touched          │                │
│              │                  │  `.claude/agents/` mid-cycle    │                │
│              │                  │  cost me a re-run.              │                │
│              │                  └─────────────────────────────────┘                │
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Publish modal (overlay)

- **Purpose:** confirm the publish-time slug + excerpt before flipping `visibility` to `public`. Opens when the `Publish` button is clicked on `/docs/new` (first-time publish) or `Publish changes` on `/docs/{id}` (when re-publishing after edits).
- **Spec trace:** M2 spec §6.2 `POST /api/docs/{id}/publish` (`PublishRequest = { slug?, excerpt? }`), §4.3 field rules for `slug` (kebab-case, collision-resolved with `-2`, `-3`, …) and `excerpt` (~140 chars default from body).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Publish modal  global` (node `29:816`).
- **Key elements:**
  - **Backdrop:** full-frame rect, `color.text` (`#2A2C20`) at `0.30` alpha — same scrim treatment as the ⌘K palette overlay (derived value, not a new token).
  - **Modal card** (centered horizontally, anchored 200px from frame top, 480 wide, `surface` bg + `border` 1px stroke + `radius.lg` 14px, auto-layout VERTICAL with 32px padding + 20px gap). `shadow.pop` is **not** authored in the Figma mock — the Talk to Figma plugin's `create_frame` does not expose a drop-shadow / effect setter; the implementer applies `shadow.pop` from the design system §5.3 at impl time. Flagged in Open questions.
  - **Title:** `font.h1` 28/700/-0.02em `color.text` — `Publish this document`.
  - **Body:** `font.body` 15px `color.text.muted` — `Pick a public URL slug and a one-line excerpt. The slug becomes this document's permanent URL — even if you unpublish and re-publish later.`
  - **Slug field:** label `Public URL slug` (`font.small` 13/600 `color.text`) → input (`surface` bg + `border.strong` 1px stroke + `radius.sm` 6px, `9px 12px` padding) containing a `/docs/public/` prefix in `color.text.subtle` followed by the editable value `building-an-agent-team` in `color.text` (the implementer renders the prefix as a non-editable input slot) → helper `Lowercase, hyphens between words. Existing slugs collide-resolve with -2, -3, …` in `font.small color.text.subtle`.
  - **Excerpt field:** label `One-line excerpt` → 80px-tall textarea with mock content `Four agents, one human gate. The seams surprised me more than the agents did.` → helper `Shown on /docs/public and as the og:description meta. ~140 chars max.`
  - **Footer button row** (auto-layout HORIZONTAL, right-aligned `primaryAxisAlignItems=MAX`, 8px gap): **Cancel** secondary (`surface` bg, `color.text` fg, `border.strong` 1px stroke, `radius.md` 10px, padding `8px 14px`, `font.small` weight 500) → **Publish** primary (`color.accent` bg, `#FFFFFF` fg, `color.accent` border, `radius.md`, padding `8px 14px`, `font.small` weight 500).
- **Interactions:** Enter inside slug/excerpt commits the form; Esc dismisses; clicking the backdrop dismisses; on successful POST the modal closes and the editor toolbar shows `Saved 1s ago · Published`. The `→ View public: …` strip on `/docs/{id}` appears (or updates) after the modal closes.
- **Empty / error / loading states:**
  - **Loading:** `Publish` button shows an inline spinner (replaces the label text); both buttons disable.
  - **409 slug collision** (per spec §6.5 — explicit collision when the user typed a slug already taken; also `400` with `availableSuggestions` per §6.5 when the user left slug blank and the server-derived slug collided): helper text under slug input swaps to `color.danger` with `That slug is taken. Try building-an-agent-team-2.` The first suggestion from the server's `availableSuggestions` is offered inline; clicking the suggestion populates the field.
  - **413 body too large** (per spec §6.5): inline `color.danger` banner above the footer row `Document body exceeds the size cap. Trim before publishing.`
  - **4xx/5xx other:** inline `color.danger` banner above the footer row with the server message, retry available via clicking `Publish` again.

### Unpublish modal (overlay)

- **Purpose:** confirm the visibility flip from `public` to `private`. Opens when the `Unpublish` button is clicked on `/docs/{id}`.
- **Spec trace:** M2 spec §6.2 `POST /api/docs/{id}/unpublish`, §4.4 state machine (re-publish reuses `publish_meta` so slug survives).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Unpublish modal  global` (node `29:817`).
- **Key elements:**
  - **Backdrop:** identical to Publish modal — full-frame `color.text` at `0.30` alpha.
  - **Modal card:** same shell as Publish (480 wide, centered, `surface` bg + `border` + `radius.lg` 14px, 32px padding, 20px gap, no form fields — body content is shorter so the card hugs to ~220px).
  - **Title:** `font.h1` — `Unpublish this document?`
  - **Body:** `font.body color.text.muted` — `It becomes private — only you can see it. The slug is retained, so re-publishing later reuses the same public URL (no broken links).`
  - **Footer button row** (right-aligned, 8px gap): **Cancel** secondary → **Unpublish** secondary (`surface` bg, `color.text` fg, `border.strong` stroke, `radius.md`). Unpublishing is not destructive enough to warrant `danger` but it is reductive, so `secondary` (not `primary` accent — accent is reserved for affirmative actions per design system §6.1). See Open questions for the alternative reading.
- **Interactions:** Esc or backdrop click dismisses; on successful POST the modal closes, the editor's `→ View public` link strip disappears, and the `Unpublish` button on the toolbar hides (reverts to just `Publish changes` to re-publish later).
- **Empty / error / loading states:**
  - **Loading:** `Unpublish` button shows a spinner; both buttons disable.
  - **4xx/5xx:** inline `color.danger` banner above the footer row with the server message; retry available via clicking `Unpublish` again.

### Delete modal (overlay)

- **Purpose:** destructive confirmation before `DELETE /api/docs/{id}`. Opens when the `🗑 Delete` ghost button is clicked on `/docs/{id}` or (post-M2) from the `⋯` overflow menu on a `/docs` list row.
- **Spec trace:** M2 spec §6.2 `DELETE /api/docs/{id}`, §5 events (`docs.document.deleted` emitted on commit), §10 (RAG chunks cascade-removed by M3+ consumers when they ship).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Delete modal  global` (node `29:818`).
- **Key elements:**
  - **Backdrop:** identical to the other two modals — `color.text` at `0.30` alpha.
  - **Modal card:** same shell (480 wide, centered, `surface` bg + `border` + `radius.lg` 14px, 32px padding, 20px gap, body content longer so the card hugs to ~270px).
  - **Title:** `font.h1` — `Delete this document?`
  - **Body:** `font.body color.text.muted` — explicit "can't be undone" + public-URL 404 + RAG chunk removal: `This can't be undone. If the document is currently published, its public URL (/docs/public/<slug>) will return 404. Vector chunks created by the RAG pipeline (M3+) are also removed.`
  - **Footer button row** (right-aligned, 8px gap): **Cancel** secondary → **Delete** `danger` (`color.danger` `#B14B3B` bg, `#FFFFFF` fg, `color.danger` border, `radius.md`, padding `8px 14px`, `font.small` weight 500). Per design system §6.1 danger variant.
- **Interactions:** Esc or backdrop click dismisses; on successful DELETE the modal closes, the user is navigated to `/docs` (the My documents index), and a `success`-fg toast in the topbar reads `Deleted "<title>".` for 4 seconds with an `Undo` link. **UX caveat:** the `Undo` link is non-functional in M2 P0 — DELETE is committed, the cascade has already run, and reviving the document + its (future M3+) RAG chunks via DELETE reversal is non-trivial. M2.1 adds a 30s tombstone column on `docs.documents` so DELETE is soft and `Undo` actually flips the tombstone before the cascade fires. See spec §11 row 14 (added with this design pass).
- **Empty / error / loading states:**
  - **Loading:** `Delete` button shows a spinner; both buttons disable.
  - **4xx/5xx:** inline `color.danger` banner above the footer row with the server message; retry available via clicking `Delete` again.

### Search results (`/docs/search`)

- **Purpose:** full-page full-text search against the OpenSearch projection (`GET /api/docs/search?q=…&scope={mine|public}`). Companion to the global ⌘K palette (also M2 P0 — see the new "⌘K search palette" section below). Reached from: (a) the per-page search input on `/docs`, (b) `⌘+Enter` on a query from the ⌘K palette, (c) direct URL with `?q=…&scope=…`.
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

### ⌘K search palette (global overlay)

- **Purpose:** keyboard-fastest entry into search. Triggered with `⌘K` (Mac) / `Ctrl+K` (Windows/Linux) from any authenticated page. Overlay-style modal — does not navigate; closing with `Esc` returns the user exactly where they were.
- **Spec trace:** M2 spec §2 P0 (new "Global `⌘K` search palette" bullet), §6.1 (`GET /api/docs/search` — palette consumes the same endpoint as the full page, with `scope=mine` as default), §11 (the richer command palette is M2.1 P1 — this section only covers search).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — ⌘K search palette (overlay)  global` (node `27:704`).
- **Key elements:**
  - **Backdrop:** the page underneath dims to `rgba(42,44,32,.30)` (taken from `color.text` with .30 alpha — no new token needed). The backdrop captures clicks: clicking outside the palette closes it (`Esc` does the same).
  - **Palette card** (centered horizontally, anchored 96px from the top of the viewport, 560px wide, `surface` bg, `radius.lg` 14px, `border` 1px stroke, `shadow.pop` shadow):
    - **Input strip** (top, padding `12px 14px`, `border-bottom 1px border`): a `⌕` glyph (`text.muted`) + the live search input (`font.body` 15px, `text` color, transparent bg, no border, focus state shows no extra ring — the palette card itself is the focus container) + a small right-aligned scope hint in `font.mono` (`Mine` / `Public`) in `text.subtle`.
    - **Results list** (max 6 rows visible at a time; scrollable beyond — though for M2's expected volume this rarely scrolls). Each row (padding `8px 14px`, `font.small` for title, `font.eyebrow`-like 10px `text.subtle` for meta):
      - **Title** with `<mark>`-equivalent highlighted matches (`accent.soft` bg, `text` fg) — same convention as the `/docs/search` page. In the static Figma mock this inline `<mark>` is simplified: the active row's whole title renders in `accent` (`#6E7A3A` weight 600) without the inline span, since the Talk to Figma plugin's TEXT node primitives don't support per-character background fills. The implementer reinstates the inline span treatment at impl time using the spec's standard `<mark>` rule (see Open questions below).
      - **Meta line:** visibility chip (Draft/Published) inline as text + `· slug or path · relative time`.
      - **Active row:** `accent.soft` bg + `accent` title color, weight 600. Default active = first row.
    - **Footer strip** (padding `6px 14px`, `border-top 1px border`, `font.mono` 10px in `text.subtle`): keyboard hints — `↑↓ navigate` · `↵ open` · `⌘↵ open in /docs/search` · `Tab toggle scope` · `Esc close`. Right-aligned: `Mine / Public` current scope indicator (active scope in `accent`).
  - **No empty-state graphic:** when the query is empty the list shows "Recent documents" (last 5 viewed/edited from local storage); when the query has no matches the list shows a single row reading `No matches. Press ⌘↵ to open /docs/search.` in `text.muted`.
- **Interactions:**
  - `⌘K` / `Ctrl+K` from any authenticated page opens the palette. The trigger is `keydown` at the document level; works even when focus is in the BlockNote editor (the editor's `⌘K` is reserved for the global palette, not for inline link formatting — the implementer must check BlockNote's default keymap and override accordingly).
  - Typing live-queries `GET /api/docs/search?q=<query>&scope=<current>` debounced 150ms.
  - `↑↓` moves the active row. `Enter` opens the active row's document (`/docs/{id}` for own docs, `/docs/public/{slug}` for public docs). `⌘+Enter` opens `/docs/search?q=<query>&scope=<current>` (the full page).
  - `Tab` toggles the scope between `Mine` and `Public`. The query persists across the toggle.
  - `Esc` or clicking the backdrop closes the palette without navigation.
- **Empty / error / loading states:**
  - **Loading (query in flight):** the right side of the input strip shows a small spinner (`text.subtle`); the active row count freezes during the in-flight request to avoid flashing.
  - **Error (503 OpenSearch down — spec §6.5):** results list swaps to a single row reading `Search is offline. Try again in a moment.` in `text.subtle` + a small retry icon. The user can keep typing; the next query auto-retries.
  - **Empty (no query yet):** shows "Recent documents" header (`font.eyebrow text.subtle`) followed by up to 5 recent rows (sourced from local storage of recent visits).
  - **Empty (query returns nothing):** single row `No matches. Press ⌘↵ to open /docs/search.` — gives the user a fallback to the full page (which may surface results the palette doesn't if the API's facet handling differs).

```
              (page underneath dimmed at rgba(42,44,32,.30))
              ┌──────────────────────────────────────────────────────┐
              │ ⌕  agent team                                   Mine │
              ├──────────────────────────────────────────────────────┤
              │ Building an agent team for my personal playground    │ ← active
              │ Published · /docs/public/building-an-agent-team · 2h │
              │ Why a single-developer agent playground?             │
              │ Draft · /docs/9c1d2e · 3d ago                         │
              │ Spark cluster: 4 workers, 12 cores                    │
              │ Published · /docs/public/spark-cluster-… · 1d ago    │
              │ Notes on Spring Modulith outbox                       │
              │ Draft · /docs/12fe9c · 1w ago                         │
              ├──────────────────────────────────────────────────────┤
              │ ↑↓ navigate  ↵ open  ⌘↵ open in /docs/search  Tab… │
              │                                       Mine / Public  │
              └──────────────────────────────────────────────────────┘
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
| §6.1 — `GET /api/docs/search?scope=public` | Search results (via the scope toggle = `Public`); ⌘K search palette (via `Tab` to toggle scope to Public) |
| §2 P0 — Global `⌘K` search palette | ⌘K search palette (global overlay) — same API endpoint as Search results, different UX surface (keyboard-fastest vs. depth-fastest) |
| §6.1 — `POST /api/docs/public/{slug}/view` | Document detail (fired on page load; the displayed `viewCount` reflects the post-increment value) |
| §6.2 — `GET /api/docs/mine` | My documents |
| §6.2 — `GET /api/docs/search?scope=mine` | Search results (default `Mine` scope in the mock); ⌘K search palette (default `Mine` scope) |
| §6.2 — `POST /api/docs` (in-app create) | New document (editor) — the in-app create path (`Blank document` from the dropdown lands here). |
| §2 P0 — `.md` file upload (multipart variant of `POST /api/docs`) | + New document dropdown (overlay) — the `↑ Import .md…` row triggers the native file picker; Drag-drop import overlay (active) — the drag-and-drop path. Both POST `multipart/form-data` to `POST /api/docs` per spec §6.2. |
| §6.2 — `GET /api/docs/{id}` | Edit document |
| §6.2 — `PATCH /api/docs/{id}` | New document + Edit document (the save-state pill is the user-facing artifact) |
| §6.2 — `POST /api/docs/{id}/publish` | Edit document (Publish-changes button — opens modal) and New document (Publish button — opens modal); **Publish modal** (slug + excerpt form, footer Publish CTA fires the POST) |
| §6.2 — `POST /api/docs/{id}/unpublish` | Edit document (Unpublish button — opens modal); **Unpublish modal** (footer Unpublish CTA fires the POST) |
| §6.2 — `DELETE /api/docs/{id}` | Edit document (🗑 Delete ghost button — opens modal); **Delete modal** (footer Delete `danger` CTA fires the DELETE) |
| §6.2 — `POST /api/docs/{id}/like` / `DELETE /api/docs/{id}/like` | Document detail (inline like button — disabled for anonymous in the mock, fully active in the authenticated-viewer interaction) |
| §6.3 — Owner resolution (`PLAYGROUND_OWNER_GOOGLE_SUB`) | N/A — deployment-time concern (the env var resolves before any of these screens render; no in-UI affordance) |
| §6.4 — DTOs (PublicDocListItem, PublicDocDetail, MyDocListItem, MyDocDetail, SearchHit) | All 6 screens consume one of these shapes — each "Key elements" section above names the data fields actually rendered |
| §6.5 — Error semantics (400/401/404/409/413/503) | Per-screen "Empty / error / loading states" entries cover the user-visible variants. 401 redirects to `/login` per M1 design. 503 from OpenSearch surfaces only on Search results (per spec, the rest of M2 still works). |
| §7.1 — Sidebar (Apps section, `Docs` row active/badged when shipped) | All 6 screens (every sidebar mock shows the `Docs` row active with `accent.soft` bg + `accent` label) |
| §7.2 — Client routes | Each route maps to one screen (1:1, 6 rows = 6 screens) |
| §7.3 — Home composition deltas (rename + meta extension) | Deltas-only section above (no new M2 frame); applied to existing M1 home frames at implementation time |
| §8 — RAG handoff trace | N/A — backend-only (the docs BC's responsibility is publishing accurate events; M3 + M4 own the user-facing chat surface) |
| §9 — Markdown feature scope (GFM, code highlighting, fenced code, blockquote, list, inline code, external-URL images) | Document detail (the public reader renders the full set through the `unified` pipeline); New document + Edit document (the BlockNote block editor authors the same MD set on the storage layer, so every block type in the slash menu round-trips through `tryParseMarkdownToBlocks` ⇄ `blockToMarkdownLossy` and survives the public render unchanged) |
| §10 — Non-functional requirements (tenant isolation, search lag tolerance, view dedup, like idempotency) | Surfaces only through correct behavior; tenant-isolation 404 explicitly mocked in Edit document's 404 state. Search lag and view dedup are invisible when working correctly. |

Every §6 / §7 row that has a user-facing surface is mapped above. Backend-only rows are explicitly tagged.

## Design tokens used

Every value below is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer mirrors them into `client/src/shared/ui/tokens/` (per ADR-06) — no hex value in this document is invented, every appearance maps to a spec entry.

| Token | Value | Where used |
|---|---|---|
| `color.bg` | `#FAF7EF` | App background on all six frames; topbar bg; editor surface bg in `/docs/new` and `/docs/{id}` |
| `color.surface` | `#FFFFFF` | Document cards, doc list card, account footer card, like button, search input, account pill, secondary-button bg, segment-switcher inactive bg |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; editor toolbar bg; slash-menu icon-glyph bg; inline-code bg; fenced-code-block bg; segment-switcher container bg; Draft chip bg; sand thumbnail gradient on document cards; Delete-ghost-button bg |
| `color.border` | `#E6E0CB` | Card strokes; topbar `border-bottom`; editor-toolbar `border-bottom`; list-row dividers; slash-menu popover stroke; account-pill stroke; account-footer card stroke |
| `color.border.strong` | `#D6CFB3` | Search input border; large search-bar border; secondary `Load more` button stroke; Unpublish-outline button stroke; blockquote left rule |
| `color.khaki` | `#C2B88A` | Khaki thumbnail gradient on document cards; sidebar-footer avatar; topbar account-pill avatar |
| `color.text` | `#2A2C20` | Page titles, document titles, doc titles in list rows, hit titles, account name, body MD text, editor block content fg (h1/h2/h3/paragraph), highlight `<mark>` fg |
| `color.text.muted` | `#6F6A55` | Hero subtitle, document excerpts, breadcrumb, neutral chip fg, Draft chip fg, all "updated …" meta, blockquote body, save-state pill, hit chip-Draft fg, account email, list-row meta numbers, sidebar wordmark line 2, Sign-in-to-like tooltip hint |
| `color.text.subtle` | `#8B8670` | Sidebar `APPS` label, sidebar search-pill placeholder, locked Apps row labels and milestone badges (Chat M4, System status M5), editor `Untitled` placeholder, search-bar meta fg in hit-card meta rows |
| `color.accent` | `#6E7A3A` | All primary CTA fills (`Sign in with Google`, `+ New document`, `Publish`, `Publish changes`), active nav fg (Docs `▤ Docs` active label), active-segment label (All in `/docs`, Mine in `/docs/search`), all `→` and `←` text-links, tag-chip fg, Published-chip fg, hit-Published-chip fg, sidebar `4/12` Docs badge, glyph J fill, `→ View public` link |
| `color.accent.hover` | `#5C6730` | Primary-button hover treatment (applied at impl time per spec §6.1); also the 1px chevron-divider inside the `+ New document` button on the dropdown-overlay frame (separates the label click target from the chevron click target). |
| `color.accent.soft` | `#E9E8D1` | Active nav bg (`▤ Docs` active row bg), active-segment bg, tag-chip bg, Published-chip bg, search-result highlight `<mark>` bg |
| `color.success` | `#4F6B2E` | `● Signed in` topbar chip fg (signed-in screens) |
| `success` chip bg | `#E5EBD9` | `● Signed in` chip bg; sage thumbnail gradient on document cards (this is the spec's `success` chip bg from §6.3, reused for the sage decorative gradient — same value, no new token) |
| `font.h1` | 28px / 1.2 / 700 / -0.02em | Page titles: `Documents by JeekLee`, document article titles, editor h1 block (and the `Untitled` placeholder in `text.subtle`) |
| `font.h2` | 20px / 1.3 / 600 / -0.01em | Section titles (`Latest`, `My documents`), document article h2, editor h2 block |
| `font.h3` | 16px / 1.4 / 600 / 0 | Document card titles, doc list row titles, search hit titles |
| `font.body` | 15px / 1.6 / 400 / 0 | Hero subtitle, document article body paragraphs, editor paragraph blocks, search hit snippets, search input text |
| `font.small` | 13px / 1.5 / 400 / 0 | Document card excerpts, breadcrumb, all button labels (13px / 500 per spec §6.1), all `→` accent links, account-pill name, list-row meta text, segment labels, hit-meta chip-row text |
| `font.eyebrow` | 11px / 1.2 / 600 / +0.14em / uppercase | Sidebar `APPS` label |
| `font.mono` | 13px / 400 | All inline code (`.claude/agents/`), all fenced code blocks (document detail + the editor's code block type), slash-menu keyboard hints (`h1`, `h2`, `>`, `\`\`\``, `---`) |
| `spacing.xs` | 4px | Intra-element micro-gaps (chip dot to label, view-public link to toolbar, search-input icon to text) |
| `spacing.sm` | 8px | Eyebrow → title gap, tile internal gap, save-state to button gap, toolbar button-row gap |
| `spacing.md` | 16px | Card internal padding, hero spacing, blockquote padding-left, fenced-code-block padding |
| `spacing.lg` | 24px | Section vertical rhythm, hero → grid gap, page-header → segment-switcher gap, list-card internal padding-y, editor pane padding-y |
| `spacing.xl` | 40px | Editor surface padding-y (`40px 0`); topbar → editor-toolbar offset |
| `radius.sm` | 6px | Inline code rounded background; search input (`/docs` per-page narrow search); kbd pill |
| `radius.md` | 10px | Buttons (primary + secondary + outline + ghost), cards, list card, segment-switcher container, fenced code blocks, sidebar nav-item active bg, account footer card |
| `radius.lg` | 14px | Modal card corner radius on all three M2 modal frames (Publish, Unpublish, Delete). Was reserved in v4; now in active use. |
| `color.danger` | `#B14B3B` | Delete button bg on the Delete modal (`danger` variant per design system §6.1); reserved for the `color.danger`-fg inline banners on all three modals' 4xx/5xx error states (rendered in implementation, not visible at rest in the mocks). |
| `color.text` @ 0.30α (derived, not a new token) | `rgba(42,44,32,.30)` | Backdrop / scrim behind the Publish, Unpublish, and Delete modal cards. Same derivation already used by the ⌘K palette overlay — no new token introduced. |
| `radius.pill` | 999px | Sidebar search pill, all chips, account pill, avatars, like button, large search bar in `/docs/search`, scope-toggle active segment |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | All cards at rest (document thumbnail cards, list card, account footer) |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | Hover-as-link card lift per spec §6.4 (applied on hover by the implementer); also the modal card elevation on all three M2 modals (Publish, Unpublish, Delete) — not authored in the Figma mock because the Talk to Figma plugin's `create_frame` doesn't expose a drop-shadow / effect setter (see Open questions); the implementer applies it from tokens. |

**Verification note:** every hex value above appears in the design system spec at §3.1 / §3.2 / §3.3 / §6.3 or in §5.3 elevation. No new tokens. The thumbnail gradients explicitly reuse `khaki`, `surface.soft`, and the spec §6.3 `success` chip bg `#E5EBD9` — no fourth thumbnail color is introduced. The modal scrim is `color.text` at 0.30 alpha — a derived value, not a new token (same derivation already in use on the ⌘K palette overlay).

## Out of scope (this milestone)

Items the M2 spec defers to M2.1, plus the P2 list:

- **Image / attachment upload** — M2.1 P1 (presigned to local volume or Postgres `bytea`, decided in M2.1 ADR). The editor toolbar in `/docs/new` and `/docs/{id}` does NOT show an image-upload button.
- **Editor auto-save** — M2.1 P1. The mock shows a manual `Saving…` save-state pill; an "Auto-save on" indicator is deferred.
- **Richer `⌘K` command palette** — M2 ships a search-only palette (see new "⌘K search palette" section). M2.1 P1 covers expanding the palette to non-search commands (Quick: New document, Quick: Switch to drafts, jumping to non-doc surfaces, etc.). The visual treatment of the search-only palette is already defined here and is forward-compatible — M2.1 additions append rows/sections, no shell change.
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

- **`.md` upload affordance — RESOLVED in this design round.** Spec §6.2 says `POST /api/docs` accepts `multipart/form-data` with a `.md` file plus optional `title`. Two affordances ship in M2 P0 (see the new "+ New document dropdown + .md import affordance" section above): (a) the `+ New document` button's chevron dropdown row `↑ Import .md…` (opens native file picker), and (b) drag-and-drop of a `.md` file onto the viewport (overlay accepts the drop and POSTs multipart). The earlier "add an upload button to the editor toolbar" suggestion is replaced — keeping the import path off the editor toolbar reduces toolbar clutter and groups both create paths under one entry point (the page-header `+ New document` button). M2 spec §12 acceptance criteria amended to make both affordances explicit.

- **Dashed border on drop card not authored in Figma.** The Talk to Figma plugin's `create_rectangle` doesn't expose a stroke-style setter (solid / dashed / dotted), so the 400×200 drop card in `M2 — Drag-drop import overlay (active)  /docs` uses a solid 2px `border.strong` stroke instead of dashed. The implementer applies `border-style: dashed` (CSS) at impl time — the dashed treatment is the standard "drop here" affordance and reads more clearly than solid. Same plugin limitation already noted for `shadow.pop` on modal cards; the maintainer could extend the bridge to expose `setStrokeStyle` if this becomes a recurring need.

- **Publish modal visual — RESOLVED in this design round.** The Publish modal frame (node `29:816`) now exists with the slug + excerpt form fields, helper text per spec §4.3 field rules, and primary/secondary footer button row per design system §6.1. The same modal is reused for `Publish changes` (pre-populated with the document's current `publish_meta.slug` and `publish_meta.excerpt`). Carried-forward sub-question: should the slug helper text live above or below the input? Working default: below (matches Material / shadcn / most form libraries). The mock shows below.

- **Delete confirm modal visual — RESOLVED in this design round.** The Delete modal frame (node `29:818`) now exists with `Cancel` (secondary) + `Delete` (`danger` per §6.1) footer. Body copy explicitly names the 404 + RAG-chunk-removal consequences. Sub-question resolved: the modal does **not** require typing the document title to confirm (GitHub-style). For a single-author personal site that's overkill; the `Cancel` button + the explicit "can't be undone" copy are sufficient friction. The implementer can revisit if user testing shows accidental deletes.

- **Unpublish-button variant — `secondary` vs. `primary` accent.** The Unpublish modal's CTA is rendered as `secondary` per design system §6.1 (`surface` bg, `color.text` fg, `border.strong` stroke) on the grounds that (a) unpublishing is reductive, not affirmative, and (b) `color.accent` is reserved for affirmative actions per spec §3.2. The alternative reading: it's still the modal's primary CTA and the user expects it to look "primary" — in which case use `color.accent` to draw the eye even though the action is reductive. Working default: `secondary` (current mock). Flagging for the reviewer in case the design system author disagrees; the swap is a one-line token change for the implementer either way.

- **Modal `shadow.pop` not authored in Figma.** The Talk to Figma plugin's `create_frame` doesn't expose a drop-shadow / effect setter, so the three modal cards (Publish, Unpublish, Delete) lack the `shadow.pop` elevation in the static mock — the cards sit on the scrim with only their `border` stroke for separation. The implementer applies `shadow.pop` (`0 10px 30px rgba(60,50,20,.10)` per design system §5.3) at impl time. The Talk to Figma plugin maintainer could extend the bridge to expose `setEffects` if this becomes a recurring need (the ⌘K palette frame `27:704` has the same gap).

- **Delete `Undo` toast — non-functional in M2 P0.** The Delete modal's success toast carries an `Undo` link but in M2 P0 the link does nothing — DELETE is committed at the SQL level and the cascade has already run. M2.1 adds a 30s tombstone column on `docs.documents` so DELETE is soft and `Undo` flips the tombstone before the cascade fires. Tracked in M2 spec §11 row 14 (added with this design pass). Working default for M2 P0: render the toast and the `Undo` link as visual affordance, but make the `Undo` click a no-op with a small `Couldn't undo — that's an M2.1 feature.` follow-up toast. Flagging for the implementer to confirm during Stage 3 — the alternative is to hide the `Undo` link entirely in M2 P0 (cleaner but breaks the visual contract with M2.1).

- **Account pill dropdown contents.** Same open question as M1's design doc — gets answered here at M2 since `My documents` is now a second item the menu can carry. Recommendation: `My documents` (links to `/docs`), divider, `Sign out` (calls `/logout` per ADR-07). Frontend-implementer can ship this as an M2 visual at no extra cost since the chevron is already mocked.

- **Empty-state CTA wording in `/docs`.** When the user has zero documents, the empty-state card says `No documents yet. Start writing.` with a `+ New document` button. Open: should the empty state also surface the `.md` file upload affordance (if Open question 1 lands) so first-time users can either type fresh or import? Working default: yes, with a small "or upload a .md file" secondary link below the primary button. Flagging for the implementer.

- **Search-result row click target — full row vs. title only.** The list rows in `/docs` are hover-as-link on the whole row. The search hits in `/docs/search` look similar but the meta chip + slug span feel like they could be separate targets. Working default: whole hit-block is the click target (matches the `/docs` pattern); the slug `/docs/{id-prefix}` text in the meta is non-interactive copy. Flagging in case the implementer disagrees.

- **Inline PNG capture.** Same Figma MCP base64-intercept blocker as M1 — the Figma file is canonical, ASCII wireframes are the inline reference. Manual one-call-per-frame export from Figma (`File → Export selected → PNG @ 2x`) drops the 10 M2 frames (6 page screens + ⌘K palette + 3 modals) into `assets/M2/{documents-list,document-detail,my-documents,new-document,edit-document,search-results,kbd-search-palette,publish-modal,unpublish-modal,delete-modal}.png` when the human reviewer wants them inlined here.

- **⌘K palette inline `<mark>` highlight simplification.** The static Figma mock for the ⌘K palette renders the active row's title in full `accent` (weight 600) instead of wrapping just the matched substring in an `accent.soft` `<mark>` span — the Talk to Figma plugin's TEXT node primitives don't support per-character background fills, so the inline highlight can't be authored declaratively in Figma here. The implementer reinstates the `<mark>` treatment at impl time using the same convention used on `/docs/search` (search result snippets already use the inline span), so there's no new rule to invent. The `accent`-on-title fallback in the mock is intentionally close enough that an at-a-glance design review still reads as "this is the active row with matched highlights." Resolution: implementer ships the proper inline `<mark>` span; the static mock retains the simplification. No spec change.
