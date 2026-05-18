# Design: M2 — Docs (Markdown authoring + community feed + UUID URLs + search)

> Spec: `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` (v5, 2026-05-17)
> Design system: `docs/superpowers/specs/2026-05-16-playground-design-system.md` (canonical token vocabulary)
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN (M2 frames live below the M1 row at y ≥ 1000; M1 frames are unchanged except for owner-author rows added on the home cards)
> Builds on: `docs/design/M1-identity.md` (sidebar shell, topbar, account pill — all reused verbatim except the `Docs` Apps row, which is unlocked + active on every M2 route)

Stage 2 output (v5 refresh, 2026-05-17) for the Docs (M2) bounded context. The M2 spec was rewritten v4 → v5 on 2026-05-17 to land four conceptual shifts that ripple through every Docs surface: (1) the site is **multi-author** — every authenticated user is a first-class author; the `PLAYGROUND_OWNER_GOOGLE_SUB` env var is removed and the public feed is community-wide; (2) public document URLs use the row UUID (`/docs/{id}`) — no slugs, no `publish_meta`; (3) routes collapse to a single `/docs` namespace with `/docs/mine` as the author's private workspace; (4) the Publish modal is deleted — publish is instant + toast. The directory P0 work (folder tree + folder picker) carries over from v4. **16 1440-wide frames** total: 7 desktop page screens + 1 global ⌘K palette overlay + 2 modal overlays (Unpublish + Delete) + 3 contextual overlays (+ New document dropdown, drag-drop import overlay, account-pill dropdown) + 3 M1 frames re-touched only at the home-card author-row level. The Publish modal frame (`29:816`) is deleted. Built strictly against the design system spec (tokens, layout shell, chip vocabulary) with the M2 spec's §7.1 sidebar override applied: the `Docs` Apps row is **shipped/active** on every M2 route (`accent.soft` bg + `accent` label, weight 600). Chat (M4) and System status (M5) remain locked. Tokens table below is sourced verbatim from the design system spec — frontend-implementer mirrors them into `client/src/shared/ui/tokens/`; no new tokens are introduced by this milestone.

> Terminology note (v5): the user-facing noun is **`Document`** / **`Documents`** everywhere — unchanged from v4. What changed in v5 is the *route prefix*: there is no longer a `/docs/public/` namespace. Public documents and the author's own workspace both live under `/docs`, distinguished by route shape (`/docs` = community feed, `/docs/{id}` = single document, `/docs/mine` = author's private list, `/docs/new` = new doc, `/docs/search` = search). The "by JeekLee" framing on the community feed is gone — that section now reads "Latest published docs" and surfaces work by any author who has published.

> Asset note: PNG export from the Figma MCP returns base64 that the harness intercepts as inline visual content rather than passing through as text, same blocker as M1. The Figma file is the canonical visual reference; ASCII wireframes inline here are retained only where they aid comprehension (and are dropped where v5 changes them past usefulness).

## Frame mapping (v5)

| # | Frame name (current in Figma) | Node ID | Route | Status |
|---|---|---|---|---|
| 1 | `M2 — Documents (public list)  /docs/public` | `31:2` | `/docs` (community feed) | **Stale Figma name** — should be `M2 — Documents  /docs`. Content is v5-correct. User-manual rename required. |
| 2 | `M2 — Document detail (public)  /docs/public/{slug}` | `31:66` | `/docs/{id}` | **Stale Figma name** — should be `M2 — Document  /docs/{id}`. Content is v5-correct (UUID URL pill, author block). User-manual rename required. |
| 3 | `M2 — My documents  /docs` | `37:363` | `/docs/mine` | **Stale Figma name** — should be `M2 — My documents  /docs/mine`. Content (tree pane + list pane) is unchanged from v4 and v5-compatible. User-manual rename required. |
| 4 | `M2 — New document (editor)  /docs/new` | `36:191` | `/docs/new` | Frame name is v5-correct. Folder picker pill added in v5. |
| 5 | `M2 — Edit document  /docs/{id}` | `36:246` | `/docs/{id}` (edit) | Frame name is v5-correct. Publish modal trigger removed (instant publish + toast); folder picker pill (read-only) + publish toast added in v5. |
| 6 | `M2 — Search results  /docs/search` | `37:289` | `/docs/search` | Frame name is v5-correct. Author rows added per hit in v5. |
| 7 | `M2 — ⌘K search palette (overlay)  global` | `27:704` | global overlay | Frame name is v5-correct. Author display added per result in v5. |
| 8 | `M2 — Unpublish modal  global` | `29:817` | global overlay | Unchanged. Friction is kept on the reductive action. |
| 9 | `M2 — Delete modal  global` | `29:818` | global overlay | Unchanged. Friction is kept on the destructive action. |
| 10 | `M2 — + New document dropdown (overlay)  /docs` | `30:859` | contextual overlay | Unchanged. |
| 11 | `M2 — Drag-drop import overlay (active)  /docs` | `30:860` | contextual overlay | Unchanged. |
| 12 | `M2 — Account pill dropdown (overlay)  global` | `30:892` | global overlay | Unchanged. |
| — | `M2 — Publish modal  global` | ~~`29:816`~~ | — | **DELETED in v5.** Publish is now instant + toast. |
| — | `M1 — Home (public)  /` | `14:2` | `/` | M1 frame. v5 added owner-author rows to the 3 home cards (since home is owner-curated per spec §7.3). Sidebar + tile composition unchanged. |
| — | `M1 — Home (signed-in)  /` | `14:135` | `/` (signed-in) | M1 frame. Same author-row additions on the home cards. |
| — | `M1 — Login  /login` | `14:114` | `/login` | M1 frame. Untouched in v5. |
| — | `M1 — Unauthorized (401)  /401` | `14:246` | `/401` | M1 frame. Untouched in v5. |

> **Frame-name limitation (unchanged from v4):** the Talk to Figma plugin allowlist exposes `set_text_content` for TEXT nodes but no node-rename tool. Three v4-era frames carry stale `name` fields that point at v4 routes. The frames' internal content has been migrated to v5; the human reviewer renames them in Figma (right-click → Rename) before exporting PNG assets — same workflow used in the v4 round. The four other M2 frames already had v5-compatible names.

## Screens

### Documents (`/docs`) — community feed

- **Purpose:** the public reader's entry into the platform's *community-wide* published documents. Surfaces every author's published work in reverse-chronological order; v5 dropped the owner filter entirely. The first user-visible reading of the multi-author shift is here — every card shows its author next to the meta row.
- **Spec trace:** M2 spec v5 §6.1 (`GET /api/docs`, community feed), §6.4 `PublicDocListItem` (now carries `authorDisplayName` + `authorAvatarUrl` per multi-author), §7.2 row 1 (`/docs`), §7.3 (community feed framing). ADR-09 (public route, no `X-User-*` headers).
- **Auth state:** public (logged-out lands here; logged-in users land here too — chrome adapts but the page itself is identical in either state; signed-in users get the account pill instead of the `Sign in with Google` button).
- **Figma frame:** `M2 — Documents (public list)  /docs/public` (node `31:2`; **stale name** — should be `M2 — Documents  /docs`. Content is v5-correct.)
- **Key elements:**
  - **Sidebar (232px, `surface.soft`):** brand row → `⌘K` search pill → Apps section with `Home` (inactive), `Docs` (**active**, `accent.soft` bg + `accent` label, weight 600), `Chat M4 🔒`, `System status M5 🔒` → spacer → account footer (logged-out variant: `Not signed in / Sign in to write/chat privately.` per M1 pattern).
  - **Slim topbar:** breadcrumb `Documents`; right side **adapts to auth**: anon shows `Viewing publicly` neutral chip + `Sign in with Google` primary button; signed-in shows `● Signed in` success chip + account pill (same as `/docs/mine` etc.).
  - **Hero:** `font.h1` title **`Latest published docs`** (v5 reframe — was "Documents by JeekLee" in v4) + one-line subtitle in `text.muted` ("Notes from the community on agents, infra, design, and the spaces in between."). The community framing is the visual cue that this is no longer an owner-only feed.
  - **Section header `Latest`** + accent text-link `View archive →` (`accent`, `font.small`/500). Cursor pagination per spec §6.1.
  - **3-column thumbnail grid (6 cards, 2 rows):** each card per design system §9 — 124px gradient thumbnail (alternating `khaki #C2B88A` / `surface.soft #F4EFDF` / chip-success-soft `#E5EBD9` sage), `font.h3` title, 2-line excerpt in `font.small text.muted`, meta row `<accent tag chip> · N min · {date} · 👁 viewCount · ♥ likeCount`, then a **new author row** below the meta: 16px khaki avatar + display name in `font.small`/500 `text.muted`. The 6 mock cards show 6 different authors (`JeekLee`, `alice-kim`, `jun-park`, `sungwoo-lee`, `dahye-jeong`, `minsoo-han`) so the multi-author intent reads at a glance.
  - **Pagination row:** centered `Load more →` secondary button. Cursor pagination per spec §6.1.
- **Interactions:**
  - Clicking any card navigates to `/docs/{id}` (a public route, no auth required, UUID URL).
  - `Sign in with Google` (topbar, anon variant) follows the M1 OAuth path (ADR-07).
  - `View archive →` opens the full archive (out of M2 visual scope; same component as `Load more` at a different state).
  - `Load more →` fetches the next cursor page; on completion the new cards append below.
- **Empty / error / loading states:**
  - **Empty (no published docs at all):** reuse the M1 empty-state card pattern with copy `No documents published yet. Sign in to be first.`
  - **Loading (initial / next page):** card skeletons matching the card geometry — 124px `surface.soft` thumb, 60% width title bar, two 90% width excerpt bars, plus a 12px width author-row skeleton. No layout shift.
  - **Error (5xx from feed):** non-blocking `danger`-chip toast in the topbar `Couldn't load documents — retry`; cards keep their last successful render if any, otherwise the empty-state copy.

### Document (`/docs/{id}`) — single document

- **Purpose:** render a single public document with full GFM + syntax-highlighted markdown body. Surfaces the author identity prominently below the title (32px avatar + display name + relative published date) and exposes the document's permanent UUID URL via a Copy-link pill — the new sharing surface that replaces v4's slug-based URL.
- **Spec trace:** M2 spec v5 §6.1 (`GET /api/docs/{id}` — UUID), §6.2 (`POST /api/docs/{id}/like` / `DELETE`), §7.2 row 2 (`/docs/{id}`), §7.3 (like button anonymous "sign in to like" state), §7.4 (OpenGraph + Twitter Card meta tags via Next.js `generateMetadata`), §9 (markdown feature scope — GFM, code highlighting, fenced code, blockquote, list, inline code, external-URL images only).
- **Auth state:** public; the like button changes state based on whether `X-User-Id` is forwarded.
- **Figma frame:** `M2 — Document detail (public)  /docs/public/{slug}` (node `31:66`; **stale name** — should be `M2 — Document  /docs/{id}`. Content is v5-correct: UUID URL pill + author block.)
- **Key elements:**
  - **Sidebar:** same as `/docs` — `Docs` row active, locked Chat/System status rows below.
  - **Topbar:** breadcrumb `Documents / Building an agent team` (with ellipsis at 320px). Right side adapts to auth (same logic as `/docs`).
  - **Article (max-width 720px for prose readability, but the author row + URL pill span wider since they're chrome, not prose):**
    - `font.h1` title `Building an agent team`.
    - **Author block** directly under the title (left-aligned): 32px khaki avatar with `JL` initials + a 2-line stack — line 1 display name `JeekLee` (`font.small`/600 `color.text`), line 2 `published 3 days ago` (`font.small` 11px `color.text.muted`).
    - **URL pill** (right-aligned in the same row as the author block): `surface` bg + `border` 1px stroke + `radius.pill` 999, containing `⎘  /docs/a3f2b9c1-7e5d-4abc-9def-1234567890ab` in 11px `color.text.subtle`. Click target is a Copy-link affordance — clicking copies the full URL to clipboard and surfaces a `success`-chip toast `Link copied.` for 2s (toast not authored in the static mock).
    - **Meta row** below the author block in `font.small text.muted`: `<accent tag chip> · N min read · 👁 viewCount` + **inline like button**. The like button is outline-`border.strong` + `font.small`/500 + `♥ likeCount` glyph; for anonymous viewers it renders disabled (`text.muted` fg) with a small `text.muted` tooltip-hint span next to it reading `Sign in to like` (spec §7.3 working default).
    - **MD body** (`font.body` 15px / 1.6 line height): one paragraph, one h2 (`font.h2` 20px/600), one paragraph that contains an inline-code span (`font.mono` 13px, `surface.soft` bg, `radius.sm` 6px, 4px x-padding), one fenced code block (`surface.soft` bg, `radius.md` 10px, 16px padding, `font.mono` 13px multi-line), one bulleted list (`font.body` with `•` markers indented 24px), one blockquote (3px-wide `border.strong` left rule, 16px padding-left, body in `text.muted`).
  - **Back link** at the bottom of the article: `← All documents` in `accent`, `font.small`/500. Returns to `/docs`.
- **Interactions:**
  - **View counter:** the page load fires `POST /api/docs/{id}/view` once; the API dedup uses the `PLAYGROUND_ANON` cookie (24h TTL per ADR-09 + M2 spec v5 §6.1). The counter rendered in the meta row reflects the post-increment value.
  - **Like button (anonymous viewer):** click opens a tooltip `Sign in to like` then routes to `/oauth2/authorization/google` via the topbar `Sign in with Google` control.
  - **Like button (authenticated viewer):** click toggles the like via `POST /api/docs/{id}/like` (idempotent — repeat clicks don't double-count per spec §10 like-idempotency). Optimistic update: heart fills `accent`, counter increments by 1 immediately; on 5xx the optimistic state rolls back with a `danger`-chip toast.
  - **URL pill click:** copies the UUID URL to clipboard; surfaces a transient toast.

#### Sharing + OpenGraph

`/docs/{id}` is the canonical share surface. Per M2 spec v5 §7.4, Next.js's `generateMetadata` exports per-document `title`, `description` (derived from the body's first ~200 chars with MD stripped — the same "derived excerpt" that v5 replaces the editable `publish_meta.excerpt` with), `og:title`, `og:description`, `og:type=article`, `og:url`, `og:image` (optional, generated server-side or supplied later in M2.1), `twitter:card=summary_large_image`. The implementer wires these in `app/docs/[id]/page.tsx`'s `generateMetadata` async export per Next.js App Router conventions. The static Figma mock does not visualize the share-preview card itself (browser/social UI, not playground UI); see spec §7.4 for the field-shape sketch and the implementer's responsibility list.

```tsx
// Sketch only — final shape lives in the implementer's code per spec §7.4
export async function generateMetadata({ params }) {
  const doc = await fetchDoc(params.id);
  return {
    title: doc.title,
    description: deriveExcerpt(doc.body, 200),
    openGraph: { title: doc.title, description: deriveExcerpt(doc.body, 200),
                 type: 'article', url: `/docs/${doc.id}`,
                 publishedTime: doc.publishedAt, authors: [doc.authorDisplayName] },
    twitter: { card: 'summary_large_image', title: doc.title,
               description: deriveExcerpt(doc.body, 200) },
  };
}
```

- **Empty / error / loading states:**
  - **404 (id unknown or `visibility != public`):** dedicated 404 card centered in main column (mirrors M1 `/401` card geometry but with `info.soft` chip "404 · NOT FOUND" instead of `danger.soft`) — copy `That document isn't published.` + `Go to all documents →` link in `accent`.
  - **Loading:** title skeleton (1 line 70% width), author-block skeleton, URL-pill skeleton, meta-row skeleton, 6 paragraph skeletons. SSR may pre-render so this is the spinner state only on client-side reload, not first paint.
  - **Error (5xx body):** the article frame renders title + author + meta normally; the body region shows a `danger`-bordered card with copy `Couldn't load this document — retry` + a `retry` ghost button.

### My documents (`/docs/mine`) — author's private workspace

- **Purpose:** the author's at-a-glance index of every document they've authored, mixed visibility, organized by folder. Tree pane on the left (folders + status filters), list pane on the right (rows for each doc in the selected folder/status). Top-right surfaces the per-screen search (complementary to the global ⌘K palette) and the primary `+ New document` CTA.
- **Spec trace:** M2 spec v5 §6.2 (`GET /api/docs/mine`), §7.2 row 3 (`/docs/mine` — the route renamed from `/docs` to `/docs/mine` in v5 to disambiguate from the community feed), §4.1 P0 directory work (folder tree, folder path on docs).
- **Auth state:** authenticated (401 from this route lands on `/login` per M1).
- **Figma frame:** `M2 — My documents  /docs` (node `37:363`; **stale name** — should be `M2 — My documents  /docs/mine`. The tree pane + list pane composition is unchanged from v4 since v5 kept the directory P0 work intact.)
- **Key elements:**
  - **Sidebar:** brand row → search pill → Apps section with `Home`, `Docs` (active, with `4/12` numeric badge in `accent`), `Chat M4 🔒`, `System status M5 🔒` → spacer → **signed-in account footer** (28px khaki avatar + stacked `JeekLee` / `jeeklee1120@gmail.com`).
  - **Topbar:** breadcrumb `My documents  /  agents  /  build-log` (the folder path is the breadcrumb so the user always sees where they're authoring); right side **signed-in chip** + account pill.
  - **Page header (top-left of main):** `font.h2` `My documents`. **Top-right of main:** 240px `Search my documents…` input + primary `+ New document` button (`accent`).
  - **Tree pane** (200px wide, `surface` card with `border` stroke, `radius.md`) — two sections:
    - **STATUS** (eyebrow) → `All  28` (active, `accent.soft` bg + `accent` label/count), `Drafts  11`, `Published  17`, `Private  11`.
    - **FOLDERS** (eyebrow) → folder tree with collapse/expand carets, indentation, and per-folder count: `▸ /`, `▾ agents` (expanded), `▾ build-log  5` (active row, `accent.soft` bg, `accent` label/count), `· m1-cycle  2`, `▸ spec-notes`, `▸ spark`, `▸ architecture`, `▸ misc`. Footer ghost link `+ New folder` at the bottom of the pane.
  - **List pane** (right of tree, fills remaining width, `surface` card with `border` stroke + `radius.md`) with 5 rows separated by 1px `border` dividers. Each row:
    - **Left:** title (`font.h3` 16px/600) + visibility chip on the same line. `Draft` chip uses `surface.soft` bg + `text.muted` fg; `Published` chip uses `accent.soft` bg + `accent` fg.
    - **Middle:** `Updated <relative time>` in `font.small text.muted`.
    - **Right:** `👁 viewCount · ♥ likeCount` in `font.small text.muted` — only present for Published rows. Draft rows show `—`. Overflow `⋯` at the right edge.
    - The whole row is the hover-as-link target (bg → `surface.soft`).
  - **5 mock rows** in `/agents/build-log/`: `Building an agent team for my personal playground` (Published, 2h ago, 1.2K/42), `Why a single-developer agent playground?` (Draft, 2d ago), `M2 spec brainstorm notes` (Draft, 5d ago), `Stage 2 design output — Docs BC` (Draft, 1w ago), `Architect ADR-10 review notes` (Draft, 2w ago).
- **Interactions:**
  - Clicking a row navigates to `/docs/{id}` (the editor / edit screen).
  - Clicking `+ New document` (label) navigates to `/docs/new` with the current folder path inherited. The chevron opens the New-document dropdown (see "+ New document dropdown" frame below).
  - Clicking a tree-pane folder filters the list to that folder; URL gets a `?folder=…` query param so deep-linking works.
  - Status filters (`All / Drafts / Published / Private`) filter by visibility on top of the folder filter.
  - **Drag a row** onto a different folder in the tree pane moves the doc; the row's right-side hint `drag rows to move folder` reminds the user the affordance exists.
  - Search input is a per-page narrow search (filters the visible list client-side; the full search experience lives at `/docs/search`).
- **Empty / error / loading states:**
  - **Empty (zero documents in the entire workspace):** centered empty-state card `No documents yet. Start writing.` + primary `+ New document` button.
  - **Empty (filter result is empty — e.g., the Drafts status filter with zero drafts in the current folder):** `No drafts in /agents/build-log/.` line in `text.muted` rendered inside the list pane.
  - **Loading:** 5 row skeletons (title bar 50% width + chip + meta-row skeleton); list pane frame visible immediately to prevent layout shift.
  - **Error (5xx from `/api/docs/mine`):** the list pane swaps to a `danger`-bordered variant with `Couldn't load your documents — retry` and a `retry` ghost button. The header (title + search + `+ New document`) stays interactive.

### + New document dropdown + .md import affordance

- **Purpose:** two parallel paths to bring content into the system — (a) write from scratch via the BlockNote editor, (b) import an existing `.md` file from disk. Both surface from `/docs/mine` and from any page where a `+ New document` action exists.
- **Spec trace:** M2 spec v5 §2 P0 (`.md` file upload bullet), §6.2 `POST /api/docs` (multipart variant with `.md` file), §7.2 row `/docs/mine`.
- **Auth state:** authenticated.
- **Figma frames:** `M2 — + New document dropdown (overlay)  /docs` (node `30:859`) + `M2 — Drag-drop import overlay (active)  /docs` (node `30:860`). Frame names retain `/docs` from v4 — they are overlays attached to the workspace; v5 renamed the workspace from `/docs` to `/docs/mine` but the overlay frames' name fields haven't been updated (same node-rename limitation).
- **Key elements (dropdown):** The page-header `+ New document` button gets a small `▾` chevron immediately to the right of the label. Clicking the label = default action (Blank document → `/docs/new`). Clicking the chevron opens a 200px dropdown card (`surface` bg, `border` 1px, `radius.md`) with two rows: `+ Blank document` (primary path) and `↑ Import .md…` (opens native file picker filtered to `.md`).
- **Key elements (drag-drop overlay):** Active on `/docs/mine` AND `/docs/new` AND `/docs/{id}` (edit). Triggered on `dragenter` of a file. Full-viewport `color.text @ 0.30α` backdrop + centered drop card (400×200, `surface`, dashed `border.strong` 2px, `radius.lg`). Card content: big `↑` accent glyph + title + subtitle. On `/docs/new` and `/docs/{id}` (edit) the title swaps to `Drop .md to replace this document's body` (`color.danger` accent glyph instead of `color.accent` — destructive overwrite signal).
- **Interactions:**
  - **Click + New document (label):** navigates to `/docs/new` with the current folder path inherited.
  - **Click "+ Blank document" in dropdown:** same as clicking the label.
  - **Click "↑ Import .md…" in dropdown:** opens native file picker filtered to `.md` extension. On select, `POST /api/docs` multipart (file body + `path` set to the current folder) → on success navigates to `/docs/{newId}` (the new doc opens in the editor for review).
  - **Drag a file over the viewport:** overlay appears within 100ms. Drag-leave with no drop dismisses. Drop on the card uploads via the same multipart path.
  - **Non-.md file dropped:** the drop is rejected; a `danger`-fg toast appears in the topbar reading `Only .md files are accepted.` for 3s.
- **Empty / error / loading states:**
  - **Loading (upload in progress):** drop card swaps to a single line `Uploading <filename>…` with a small spinner.
  - **Error (413 body too large per spec §6.5):** danger toast `<filename> is too large (>1MB). Trim and try again.`
  - **Error (multipart parse failure):** danger toast `Couldn't read <filename>. Make sure it's a UTF-8 Markdown file.`

### New document (editor) (`/docs/new`)

- **Purpose:** in-app block editor for creating a brand-new document. Notion-style single-pane: each line is a block, `/` summons a block-type picker, blocks are reorderable via a drag handle. Body roundtrips raw MD via BlockNote's `tryParseMarkdownToBlocks` / `blockToMarkdownLossy`. Public render at `/docs/{id}` still uses the `unified` + `remark` + `rehype` + `shiki` pipeline against the raw MD body — BlockNote changes the **authoring** UX, not the **reading** pipeline.
- **Spec trace:** M2 spec v5 §6.2 (`POST /api/docs`), §7.2 row `/docs/new` (BlockNote block editor), §9 (MD feature scope), §11 Q3 (DECIDED: BlockNote), §11 Q15 (folder picker UX — DECIDED in this design pass as a small pill in the editor toolbar; see below).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — New document (editor)  /docs/new` (node `36:191`).
- **Key elements:**
  - **Sidebar:** same signed-in shell as `/docs/mine` — `Docs` active with `4/12` badge, locked Chat/System status rows, signed-in account footer.
  - **Topbar:** breadcrumb `Documents / New`; right side = JL account pill only (no `Signed in` chip in the editor topbar — the editor toolbar's save-state already carries that affordance; reduces toolbar clutter for the editor surface).
  - **Editor toolbar** (slim strip below topbar, `surface.soft` bg, `border-bottom 1px border`, padding `12px 28px`, auto-layout HORIZONTAL with space-between):
    - **Left:** save-state pill in `font.small text.muted` (`Saved 3s ago` / `Saving…` / `Save failed — retry`).
    - **Middle (new in v5):** **folder picker pill** — `surface` bg + `border` 1px stroke + `radius.pill` 999, padding `8px 14px`, label `📁  /  build-log  ▾` (12px `text.muted`). Clicking it opens a folder picker overlay (the picker overlay itself is out of M2 P0 visual scope; the pill's presence + chevron is the affordance). The design decision (spec §11 Q15) lands on a small surface-bg pill rather than a full breadcrumb because: (1) the breadcrumb-as-folder-path metaphor is already taken by the topbar's breadcrumb in `/docs/mine`; (2) a single pill reads as a single click target; (3) the pill style matches the URL pill on `/docs/{id}` (single sharing surface), giving editor + reader chrome visual consistency.
    - **Right:** primary `Publish` button (`accent` per design system §6.1). On click, this **instantly** flips the doc to public and shows a success toast — there is no Publish modal in v5.
  - **Editor surface** (main content area below toolbar):
    - `bg` background, padding `40px 0`. Inner content max-width 720px, horizontally centered (prose density).
    - On first load (empty doc): renders a single placeholder block at `font.h1`/`text.subtle` reading `Untitled` followed by a paragraph block at `font.body`/`text.subtle` reading `Type / for commands…`. Both clear on first keystroke.
    - **Slash command popover** (mocked open in the static frame): triggered by typing `/`. Floating card (`surface`, `radius.md`) listing block types: `Heading 1`, `Heading 2`, `Heading 3`, `Bulleted list`, `Numbered list`, `Quote`, `Code block`, `Divider`, `Image` (M2.1 — disabled).
- **Interactions:**
  - Typing renders blocks as you type — what you see IS the prose; no preview re-render.
  - `/` in an empty block opens the slash menu. Typing filters (`/h1` jumps to Heading 1).
  - Hovering a block reveals its side menu (`+` and `⋮⋮` drag handle).
  - **Folder picker pill click:** opens a folder picker overlay (visual: M2.1; behavior: user picks a folder, pill label updates).
  - `Publish` (toolbar right) **instantly** calls `POST /api/docs/{id}/publish` and surfaces the publish toast (see Edit document below for the toast spec).
  - `⌘+S` or autosave triggers `PATCH /api/docs/{id}`; the save-state pill updates.
- **Empty / error / loading states:**
  - **First-load (empty doc):** `Untitled` h1 placeholder + `Type / for commands…` paragraph placeholder, both in `text.subtle`, both clear on first keystroke.
  - **Save error (4xx/5xx on PATCH):** save-state pill swaps to `danger`-fg `Save failed — retry`; editor stays editable; `Publish` button disables until the next successful save.
  - **413 (body too large per spec §6.5):** save-state pill swaps to `danger`-fg `Document too large — trim`; cursor stays in the editor.

### Edit document (`/docs/{id}` — author view)

- **Purpose:** edit an existing document via the same single-pane BlockNote editor as `/docs/new`. v5 simplifies the publish UX: `Publish changes` is **instant** + toast — the Publish modal is removed. `Unpublish` and `Delete` keep their confirm modals (friction on reductive/destructive actions). The folder picker pill in the toolbar is read-only in M2 P0 (Move action is M2.1 per spec §4.1). The `→ View public` link at the top displays the UUID URL.
- **Spec trace:** M2 spec v5 §6.2 (`PATCH /api/docs/{id}`, `POST /api/docs/{id}/publish` — now instant, `POST /api/docs/{id}/unpublish`, `DELETE /api/docs/{id}`), §7.2 row `/docs/{id}` (edit view, route shape is shared with the reader view — the page renders the editor when the caller is the author, the reader when not), §11 Q3 (DECIDED: BlockNote), §11 Q15 (folder picker pill — read-only in this view since path is not editable in M2 P0).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Edit document  /docs/{id}` (node `36:246`).
- **Key elements:**
  - **Sidebar + topbar:** identical to `/docs/new` — `Docs` active with `4/12` badge, account pill on the right. Breadcrumb changes to `Documents / Building an agent team` (full title; ellipsis at 320px).
  - **`→ View public: /docs/a3f2b9c1-7e5d-4abc-9def-1234567890ab`** accent text-link (`font.small`/500) rendered between the topbar and the editor toolbar (4px-padded strip on `bg`). Shown only when the doc is currently published; in the mock the doc is `Published` so the link is present. **v5 change:** the URL is the UUID — no more `/docs/public/{slug}`.
  - **Editor toolbar** (same `surface.soft` strip, padding `12px 28px`, auto-layout HORIZONTAL with space-between):
    - **Left:** save-state pill `Saved 3s ago` in `font.small text.muted`.
    - **Middle (new in v5):** folder picker pill `📁  /  build-log` — read-only (no chevron, slightly subtler `color.text.subtle` label) since Move action lands in M2.1. The same component as `/docs/new`'s editable pill; just a styling variant for the read-only state.
    - **Right (button row, `spacing.sm` gap):** ghost `🗑 Delete` button (`surface.soft` bg, `text.muted` fg) → outline `Unpublish` button (`surface` bg + `border.strong` stroke, `radius.md`) → primary `Publish changes` button (`accent`).
  - **Publish toast** (mocked at the top-right of the editor surface in the static frame, just below the toolbar): `success`-soft bg + `success` border + `radius.md`, padding `10px 16px`, copy `✓ Published as /docs/a3f2b9c1-...    [Copy link]    [View public]` in `success` fg. In the live UI this toast appears immediately after `Publish changes` succeeds, dismisses after 6s (or on explicit close), and the `Copy link` / `View public` affordances are clickable.
  - **Editor surface:** identical BlockNote layout to `/docs/new` — single pane, `bg` background, padding `40px 0`, 720px-wide inner column. In this mock the body is populated: first H1 block reads `Building an agent team`, followed by a paragraph, an H2 `What worked`, a bulleted list with three items, an H2 `What didn't`, and a paragraph with inline code.
- **Interactions:**
  - **`Publish changes`** (v5): calls `POST /api/docs/{id}/publish` instantly (no modal). On success the toast appears, the `→ View public` strip updates if the slug/UUID changed (shouldn't in v5 since UUIDs are stable), and the save-state pill briefly shows `Published`. On 4xx/5xx the toast turns danger-variant with the error and a retry affordance.
  - **`Unpublish`** opens a confirm modal `Unpublish this document? Its UUID is retained for re-publish.` (spec §4.4 state machine — re-publish reuses the same row, UUID intact).
  - **`Delete`** opens a destructive-action confirm modal `Delete this document? This cannot be undone.` with `Cancel` (secondary) + `Delete` (danger).
  - **`→ View public` link:** opens `/docs/{id}` in a new tab.
  - **Folder picker pill:** in M2 P0, no-op on click (read-only). In M2.1 it opens a Move-to-folder picker overlay (spec §4.1 Move action).
- **Empty / error / loading states:**
  - **404 (someone else's doc id — per spec §10 tenant isolation):** the editor doesn't render; instead a centered 404 card with copy `That document doesn't exist (or isn't yours).` + `Go to my documents →` link.
  - **Save error:** same `danger`-fg save-state pill behavior as `/docs/new`.
  - **Loading the initial doc:** editor surface shows skeletons.

### Unpublish modal (overlay)

- **Purpose:** confirm the visibility flip from `public` to `private`. Opens when the `Unpublish` button is clicked on `/docs/{id}` (edit view). Kept in v5 because Unpublish is reductive — friction is warranted to prevent accidental clicks.
- **Spec trace:** M2 spec v5 §6.2 `POST /api/docs/{id}/unpublish`, §4.4 state machine (re-publish reuses the row so UUID survives).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Unpublish modal  global` (node `29:817`).
- **Key elements:** Backdrop (`color.text @ 0.30α`); modal card (480 wide, centered, `surface` + `border` + `radius.lg` 14px, 32px padding); title `Unpublish this document?`; body `It becomes private — only you can see it. The UUID is retained, so re-publishing later reuses the same /docs/{id} URL (no broken links).` (v5 wording — replaces v4's "slug is retained"); footer row `Cancel` (secondary) → `Unpublish` (secondary outline, not `danger` — reductive, not destructive).
- **Interactions:** Esc or backdrop click dismisses; on successful POST the modal closes, the editor's `→ View public` link strip disappears, and the `Unpublish` button on the toolbar hides (reverts to just `Publish changes` to re-publish later).
- **Empty / error / loading states:** Loading shows a spinner on the `Unpublish` button; 4xx/5xx surface an inline `color.danger` banner above the footer row with the server message.

### Delete modal (overlay)

- **Purpose:** destructive confirmation before `DELETE /api/docs/{id}`. Opens when the `🗑 Delete` ghost button is clicked. Kept in v5.
- **Spec trace:** M2 spec v5 §6.2 `DELETE /api/docs/{id}`, §5 events (`docs.document.deleted` emitted on commit), §10 (RAG chunks cascade-removed by M3+ consumers when they ship).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Delete modal  global` (node `29:818`).
- **Key elements:** Backdrop (`color.text @ 0.30α`); modal card (480 wide, centered, `surface` + `border` + `radius.lg` 14px, 32px padding); title `Delete this document?`; body `This can't be undone. If the document is currently published, its public URL (/docs/{id}) will return 404. Vector chunks created by the RAG pipeline (M3+) are also removed.` (v5 wording — drops the slug reference); footer row `Cancel` (secondary) → `Delete` `danger` variant per design system §6.1 (`color.danger` bg, white fg).
- **Interactions:** Esc or backdrop click dismisses; on successful DELETE the modal closes, the user is navigated to `/docs/mine`, and a `success`-fg toast in the topbar reads `Deleted "<title>".` for 4 seconds with an `Undo` link (non-functional in M2 P0 per spec §11 row 14 — M2.1 adds a 30s tombstone column for soft-delete with undo).
- **Empty / error / loading states:** Loading shows a spinner on the `Delete` button; 4xx/5xx surface an inline `color.danger` banner with the server message.

### Search results (`/docs/search`)

- **Purpose:** full-page full-text search against the OpenSearch projection (`GET /api/docs/search?q=…&scope={mine|public}`). Companion to the global ⌘K palette. Reached from: (a) the per-page search input on `/docs/mine`, (b) `⌘+Enter` on a query from the ⌘K palette, (c) direct URL with `?q=…&scope=…`.
- **Spec trace:** M2 spec v5 §6.2 (`GET /api/docs/search?q=…&scope=mine`), §6.1 (`GET /api/docs/search?q=…&scope=public`), §7.2 row `/docs/search`, §10 (OpenSearch projection lag tolerance, search failure isolation).
- **Auth state:** authenticated (the `mine` scope requires `X-User-Id`; `public` scope is also reachable for the author via scope toggle).
- **Figma frame:** `M2 — Search results  /docs/search` (node `37:289`).
- **Key elements:**
  - **Sidebar + topbar:** signed-in shell. Breadcrumb `Documents / Search`. Right side = JL account pill.
  - **Sticky search bar** at the top of main content: large search input (placeholder `Search…`, `radius.pill`, `font.body` 15px, max-width 720px, `border.strong` stroke) — mock shows the query `agent team` typed in. Right of the input: a 2-segment scope toggle (`Mine` / `Public`) in a `surface.soft` rounded container. `Mine` is active in the mock.
  - **Result count** line: `6 results` in `font.small text.muted`.
  - **6 mock hits**, each a stacked block:
    - **Title** (`font.h3` 16px/600).
    - **Snippet** in `font.body text.muted` with the matched keyword wrapped in a `<mark>`-equivalent `accent.soft` background span. Hits 1-3 carry the full highlight treatment; hits 4-6 use a plain snippet line.
    - **Meta row** in `font.small text.subtle` (left): `<visibility chip — Published or Draft> · /docs/{id-prefix} · updated <relative time>`.
    - **Author row (new in v5)** on the right side of the same row: 14px khaki avatar + display name in `font.small`/500 `text.muted`. 6 different authors across the 6 hits (`JeekLee`, `alice-kim`, `jun-park`, `sungwoo-lee`, `dahye-jeong`, `minsoo-han`).
- **Interactions:**
  - Typing in the input fires the search on debounce (200ms default); the URL updates with `?q=…&scope=…` for share-ability.
  - Toggle scope to `Public` swaps the result set to the community-wide public search (v5: no owner filter); same component, different hits.
  - Clicking a hit navigates to `/docs/{id}` (always — `/docs/{id}` is the canonical single-document route).
- **Empty / error / loading states:**
  - **Empty query:** "Start typing to search your documents." in `text.muted`, no results region rendered.
  - **Empty result set:** `No matches for "<q>"` + a `text.muted` suggestion `Try a broader keyword or switch scope.`
  - **Loading:** 4 hit skeletons (title 70% width, two snippet bars, meta-row skeleton, author-row skeleton).
  - **503 (OpenSearch unavailable — per spec §6.5):** a danger-bordered card replaces the results region with `Search is unavailable right now. Other features still work.` Search input stays interactive; retry retries the same query.

### ⌘K search palette (global overlay)

- **Purpose:** keyboard-fastest entry into search. Triggered with `⌘K` / `Ctrl+K` from any authenticated page. Overlay-style — does not navigate; closing with `Esc` returns the user exactly where they were.
- **Spec trace:** M2 spec v5 §2 P0 (Global `⌘K` search palette), §6.1 (`GET /api/docs/search` — palette consumes the same endpoint as the full page, with `scope=mine` as default), §11 (richer command palette is M2.1 P1 — this section only covers search).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — ⌘K search palette (overlay)  global` (node `27:704`).
- **Key elements:**
  - **Backdrop:** page underneath dims to `rgba(42,44,32,.30)` (`color.text` @ 0.30α). Clicking outside the palette closes it (`Esc` does the same).
  - **Palette card** (centered horizontally, anchored 96px from the top of the viewport, 560px wide, `surface` bg, `radius.lg` 14px, `border` 1px stroke, `shadow.pop` shadow):
    - **Input strip** (top): a `⌕` glyph + the live search input + a small right-aligned scope hint (`Mine` / `Public`).
    - **Results list** (max 6 rows visible). Each row:
      - **Title** with highlighted matches (`accent.soft` bg on the match span in live UI; the static mock uses an active-row `accent` title-color fallback per the v4 plugin-limitation note).
      - **Meta line (v5 update)** — now includes the author at the front: `<author display name> · <visibility> · /docs/{slug-or-uuid} · <relative time>`. The 4 mock rows show 4 different authors (`JeekLee`, `alice-kim`, `jun-park`, `dahye-jeong`) — same multi-author intent as the community feed and search results page.
      - **Active row:** `accent.soft` bg + `accent` title color, weight 600. Default active = first row.
    - **Footer strip:** keyboard hints — `↑↓ navigate` · `↵ open` · `⌘↵ open in /docs/search` · `Tab toggle scope` · `Esc close`. Right-aligned: `Mine / Public` current scope indicator.
- **Interactions:**
  - `⌘K` / `Ctrl+K` from any authenticated page opens the palette.
  - Typing live-queries `GET /api/docs/search?q=<query>&scope=<current>` debounced 150ms.
  - `↑↓` moves active row. `Enter` opens the active row's document (`/docs/{id}`). `⌘+Enter` opens `/docs/search?q=<query>&scope=<current>`.
  - `Tab` toggles scope between `Mine` and `Public`.
  - `Esc` or backdrop click closes.
- **Empty / error / loading states:**
  - **Loading (query in flight):** small spinner on the input strip's right side.
  - **Error (503 OpenSearch down):** single row reading `Search is offline. Try again in a moment.`
  - **Empty (no query yet):** shows "Recent documents" header + up to 5 recent rows from local storage.
  - **Empty (query returns nothing):** `No matches. Press ⌘↵ to open /docs/search.`

### Account-pill dropdown (overlay)

- **Purpose:** the only menu surface attached to the topbar account pill. Surfaces (a) the signed-in user's identity and (b) M2 destinations and account actions accessible from anywhere.
- **Spec trace:** M2 spec v5 §7.1 (sidebar Apps row `Docs` shipped → `My documents` becomes a real destination at `/docs/mine`), M1 PRD `POST /logout` semantics, design system §2.4 (single chrome).
- **Auth state:** authenticated.
- **Figma frame:** `M2 — Account pill dropdown (overlay)  global` (node `30:892`). Unchanged in v5 except the `My documents` row navigates to `/docs/mine` (was `/docs` in v4) — the route name change is in the destination, not the menu copy.
- **Key elements:** Header row (display name + email, non-interactive); divider; `▤ My documents` row → navigates to `/docs/mine`; `↪ Sign out` row → `POST /logout` per M1 PRD. Future rows append above the divider (M3 `My chats`, M5 `System status`, M2.1+ `Account settings`).
- **Interactions:** Open via click pill or Enter on focused pill; close via Esc, click outside, or click any action row. `My documents` → `/docs/mine`. `Sign out` → `POST /logout` then `/`.
- **Empty / error / loading states:** Header skeleton during `/me` load; redirect to `/login` on 401; `danger` toast on logout 5xx.

## Home composition deltas (applied to M1 home frames)

M2 spec v5 §7.3 keeps the home as **owner-curated** — the `Latest documents` section on the home is the *owner's* recent published documents, NOT the community feed (that's `/docs`). This is the key conceptual delta worth restating: the multi-author shift opens up `/docs` to the community, but the home stays a personal-platform surface where the owner's voice leads. A non-owner author's published docs do NOT appear on the home; they appear on `/docs` (community feed). The `All documents →` link on the home navigates to `/docs` (which is *not* owner-filtered in v5) — so the user moves from the owner's voice to the community's by clicking that link.

Two deltas applied to the M1 home frames in this round:

1. **Section header rename (carried from v4):** `Latest documents` with `All documents →` right-aligned link. Unchanged in v5.
2. **Card meta + author row:** the 3-column thumbnail grid uses the same vocabulary as the `/docs` community feed for visual consistency. **Per-card meta row** carries `<tag-chip> · N min · {date} · 👁 viewCount · ♥ likeCount`. **Author row (new in v5)** below the meta on every home card shows `JeekLee · {date}` — explicitly the owner, since the home is owner-curated. The 3 mock entries are unchanged from v4: (a) `build-log · "Building an agent team…"`, (b) `architecture · "Why I rebuilt my blog…"`, (c) `infra · "Spark cluster…"`. All three show `JeekLee · today/yesterday/this week` author rows.

Both deltas are textual + data-source changes; no new layout, no new tokens.

## Traceability matrix (v5 routes)

Every M2 spec v5 subsection that has user-facing surface area is mapped to one or more screens, or to `N/A — backend-only` with a reason.

| Spec section (v5) | Screen(s) |
|---|---|
| §3 — Bounded context (`docs` owns content + visibility + counters + search projection) | N/A — backend-only |
| §4.1 — Postgres `documents` schema (visibility, view_count, like_count, folder_path) | Documents (community feed) (counts in card meta); Document (counts in meta + like control); My documents (counts in published-row meta; folder_path = tree pane); Edit document (visibility surfaced via Unpublish + `→ View public`) |
| §4.1 — `document_likes` (per-user like) | Document (inline like button with `likedByMe` state) |
| §4.2 — OpenSearch index (`docs-v1`) | Search results (entire screen); ⌘K palette |
| §4.4 — Document state machine (private → public ↔ private, UUID stable) | Edit document (Unpublish + Publish-changes + `→ View public` link expose every legal transition; UUID stability is implicit in the `→ View public` URL being stable) |
| §5 — Kafka events (`docs.document.uploaded` / `.deleted` / `.visibility-changed`) | N/A — backend-only |
| §5.1 — In-service search projector | N/A — backend-only |
| §6.1 — `GET /api/docs` (community feed) | Documents (community feed) |
| §6.1 — `GET /api/docs/{id}` (UUID URL) | Document; URL pill on Document is the visible artifact of the UUID-URL shift |
| §6.1 — `GET /api/docs/search?scope=public` | Search results (via Public scope toggle); ⌘K palette (via Tab to Public) |
| §6.1 — `POST /api/docs/{id}/view` | Document (fired on page load) |
| §6.2 — `GET /api/docs/mine` | My documents |
| §6.2 — `GET /api/docs/search?scope=mine` | Search results (default Mine scope); ⌘K palette (default Mine scope) |
| §6.2 — `POST /api/docs` (in-app create) | New document; **+ New document dropdown** (Blank document path) |
| §2 P0 — `.md` file upload (multipart variant) | + New document dropdown (Import .md row); Drag-drop import overlay |
| §6.2 — `GET /api/docs/{id}` (author view) | Edit document |
| §6.2 — `PATCH /api/docs/{id}` | New document + Edit document (save-state pill) |
| §6.2 — `POST /api/docs/{id}/publish` (**instant in v5**) | New document (Publish button — instant + toast); Edit document (Publish changes — instant + toast). **No Publish modal in v5** — the v4 modal frame `29:816` is deleted. |
| §6.2 — `POST /api/docs/{id}/unpublish` | Edit document (Unpublish button → modal); **Unpublish modal** |
| §6.2 — `DELETE /api/docs/{id}` | Edit document (🗑 Delete ghost button → modal); **Delete modal** |
| §6.2 — `POST /api/docs/{id}/like` / `DELETE /api/docs/{id}/like` | Document (inline like button) |
| §6.4 — DTOs (with author identity surfaced on every list/detail shape) | All 6 page screens consume one of these shapes — each "Key elements" section above names the data fields actually rendered, including the new `authorDisplayName` / `authorAvatarUrl` fields on `PublicDocListItem` / `PublicDocDetail` / `SearchHit` |
| §6.5 — Error semantics (400/401/404/409/413/503) | Per-screen "Empty / error / loading states" entries cover the user-visible variants |
| §7.1 — Sidebar (Apps section, `Docs` row active/badged when shipped) | All 6 page screens (every sidebar mock shows the `Docs` row active with `accent.soft` bg + `accent` label) |
| §7.2 — Client routes (`/docs`, `/docs/{id}`, `/docs/mine`, `/docs/new`, `/docs/search`) | Each route maps to one screen (1:1, 5 rows + the edit view on `/docs/{id}` = 6 screens) |
| §7.3 — Home composition (owner-curated, multi-author NOT applied to home) | Deltas-only section above (no new M2 frame); applied to existing M1 home frames `14:2` + `14:135` |
| §7.4 — OpenGraph + Twitter Card meta (`generateMetadata`) | Document (the `generateMetadata` sketch lives in the "Sharing + OpenGraph" subsection on the Document screen) |
| §8 — RAG handoff trace (informational) | N/A — backend-only |
| §9 — Markdown feature scope | Document (public reader pipeline); New + Edit document (BlockNote authoring) |
| §10 — Non-functional requirements | Surfaces only through correct behavior; tenant-isolation 404 explicitly mocked in Edit document's 404 state |

Every §6 / §7 row that has a user-facing surface is mapped above. Backend-only rows are explicitly tagged.

## Design tokens used

Every value below is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. **No token changes in v5** — the multi-author + UUID-URL shifts are content/data changes, not styling changes. The implementer mirrors them into `client/src/shared/ui/tokens/` (per ADR-06); no hex value in this document is invented, every appearance maps to a spec entry.

| Token | Value | Where used |
|---|---|---|
| `color.bg` | `#FAF7EF` | App background on all screen frames; topbar bg; editor surface bg in `/docs/new` and `/docs/{id}` |
| `color.surface` | `#FFFFFF` | Document cards, doc list card, account footer card, like button, search input, account pill, folder picker pill, URL pill, secondary-button bg, segment-switcher inactive bg |
| `color.surface.soft` | `#F4EFDF` | Sidebar bg; editor toolbar bg; slash-menu icon-glyph bg; inline-code bg; fenced-code-block bg; segment-switcher container bg; Draft chip bg; sand thumbnail gradient on document cards; Delete-ghost-button bg |
| `color.border` | `#E6E0CB` | Card strokes; topbar `border-bottom`; editor-toolbar `border-bottom`; list-row dividers; slash-menu popover stroke; account-pill stroke; account-footer card stroke; URL pill stroke; folder picker pill stroke |
| `color.border.strong` | `#D6CFB3` | Search input border; large search-bar border; secondary `Load more` button stroke; Unpublish-outline button stroke; blockquote left rule |
| `color.khaki` | `#C2B88A` | Khaki thumbnail gradient on document cards; sidebar-footer avatar; topbar account-pill avatar; **author avatars** on community feed cards, document detail, search results, ⌘K palette, home cards |
| `color.text` | `#2A2C20` | Page titles, document titles, doc titles in list rows, hit titles, account name, body MD text, editor block content fg, highlight `<mark>` fg, author display name on document detail |
| `color.text.muted` | `#6F6A55` | Hero subtitle, document excerpts, breadcrumb, neutral chip fg, Draft chip fg, all "updated …" meta, blockquote body, save-state pill, hit chip-Draft fg, account email, list-row meta numbers, sidebar wordmark line 2, Sign-in-to-like tooltip hint, author display name on community feed / search / ⌘K / home cards, published-date subline on document detail |
| `color.text.subtle` | `#8B8670` | Sidebar `APPS` label, sidebar search-pill placeholder, locked Apps row labels and milestone badges, editor `Untitled` placeholder, search-bar meta fg, URL pill text, folder picker pill (read-only variant) |
| `color.accent` | `#6E7A3A` | All primary CTA fills (`Sign in with Google`, `+ New document`, `Publish`, `Publish changes`), active nav fg (`▤ Docs` active label), active-segment label, all `→` and `←` text-links, tag-chip fg, Published-chip fg, sidebar `4/12` Docs badge, glyph J fill, `→ View public` link |
| `color.accent.hover` | `#5C6730` | Primary-button hover treatment |
| `color.accent.soft` | `#E9E8D1` | Active nav bg, active-segment bg, tag-chip bg, Published-chip bg, search-result highlight `<mark>` bg |
| `color.success` | `#4F6B2E` | `● Signed in` topbar chip fg (signed-in screens); **publish toast border + label fg on Edit document** |
| `color.success.soft` | `#E5EBD9` | `● Signed in` chip bg; sage thumbnail gradient on document cards; **publish toast bg on Edit document** |
| `color.danger` | `#B14B3B` | Delete button bg on the Delete modal; reserved for `color.danger`-fg inline banners on modal 4xx/5xx error states |
| `color.text` @ 0.30α (derived) | `rgba(42,44,32,.30)` | Backdrop / scrim behind the Unpublish, Delete, ⌘K palette, and drag-drop overlay frames |
| `font.h1` | 28px / 1.2 / 700 / -0.02em | Page titles: `Latest published docs`, `Building an agent team`, editor h1 block, `Untitled` placeholder |
| `font.h2` | 20px / 1.3 / 600 / -0.01em | Section titles (`Latest`, `My documents`), document article h2, editor h2 block |
| `font.h3` | 16px / 1.4 / 600 / 0 | Document card titles, doc list row titles, search hit titles |
| `font.body` | 15px / 1.6 / 400 / 0 | Hero subtitle, document article body paragraphs, editor paragraph blocks, search hit snippets, search input text |
| `font.small` | 13px / 1.5 / 400 / 0 | Document card excerpts, breadcrumb, all button labels (13px / 500), all `→` accent links, account-pill name, list-row meta text, segment labels, hit-meta chip-row text, author display name (community feed, document detail, search results) |
| `font.eyebrow` | 11px / 1.2 / 600 / +0.14em / uppercase | Sidebar `APPS` label, tree-pane `STATUS` / `FOLDERS` eyebrows |
| `font.mono` | 13px / 400 | All inline code, fenced code blocks, slash-menu keyboard hints, URL pill UUID display |
| `spacing.xs` | 4px | Intra-element micro-gaps |
| `spacing.sm` | 8px | Eyebrow → title gap, tile internal gap, save-state to button gap, toolbar button-row gap |
| `spacing.md` | 16px | Card internal padding, hero spacing, blockquote padding-left, fenced-code-block padding |
| `spacing.lg` | 24px | Section vertical rhythm, hero → grid gap, page-header → segment-switcher gap, list-card internal padding-y, editor pane padding-y |
| `spacing.xl` | 40px | Editor surface padding-y (`40px 0`); topbar → editor-toolbar offset |
| `radius.sm` | 6px | Inline code rounded background; search input (`/docs/mine` per-page narrow search); kbd pill |
| `radius.md` | 10px | Buttons (primary + secondary + outline + ghost), cards, list card, tree pane card, segment-switcher container, fenced code blocks, sidebar nav-item active bg, account footer card, publish toast |
| `radius.lg` | 14px | Modal card corner radius on Unpublish + Delete frames |
| `radius.pill` | 999px | Sidebar search pill, all chips, account pill, avatars, like button, large search bar, scope-toggle active segment, URL pill, folder picker pill |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | All cards at rest |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | Hover-as-link card lift; modal card elevation; ⌘K palette card; publish toast (applied at impl time — not authored in Figma per the Talk to Figma plugin's effect-setter limitation) |

**Verification note:** every hex value above appears in the design system spec at §3.1 / §3.2 / §3.3 / §6.3 or in §5.3 elevation. No new tokens, including for the v5-new chrome (author avatars use existing `color.khaki`; URL pill uses existing `surface` / `border` / `text.subtle` / `radius.pill`; publish toast uses existing `success.soft` / `success` / `radius.md`).

## Out of scope (this milestone — refreshed per v5 §2)

Items the M2 spec v5 defers to M2.1, plus the P2 list:

- **Image / attachment upload** — M2.1 P1.
- **Editor auto-save** — M2.1 P1.
- **Richer `⌘K` command palette** beyond search — M2.1 P1.
- **Cover image on documents** — M2.1 P1.
- **Comments on public documents** — M2.1 P1.
- **Move-to-folder action** — M2.1 P1 (the folder picker pill on `/docs/{id}` is read-only in M2 P0 for this reason).
- **Tags / categories** — P2. The mock tag chips on document cards (`agents`, `spark`, `design`, `infra`, `search`, `rag`) are visual-only — they are stored nowhere in M2 schema and are NOT clickable.
- **RSS / Atom feeds** — P2.
- **Version history / diff view** — P2.
- **Engagement-driven ranking** — P2 (view/like counters are stored, not yet used to re-order feeds).
- **Slug-based URLs** — **removed in v5**. The `publish_meta` table is gone; URLs are UUIDs only. Any slug-related UI (slug rename, slug helper text, slug collision handling) is gone with it.
- **Owner-filter on the public feed** — **removed in v5**. The community feed is everyone's published docs.
- **Mobile / responsive layouts** — desktop 1440 only. Sidebar collapse modes (768-1023 icon rail, <768 hamburger drawer) are specified in design system §8.1 but visual mocks are deferred to M4.
- **Dark mode** — token names are reserved per design system §3.4.

## Open questions for the next cycle

> **Frame refresh log (2026-05-17, v5).** This round refreshed the M2 design doc + Figma frames to match spec v5 (2026-05-17). What changed:
> - **Deleted:** `29:816` Publish modal frame (instant publish + toast in v5).
> - **Content-updated:** `31:2` Documents list — hero reframed to "Latest published docs" + community subtitle, 6 author rows on cards (6 different mock authors).
> - **Content-updated:** `31:66` Document detail — author block (32px avatar + display name + published-relative-date) under the title, UUID URL pill (Copy-link affordance) to the right of the author block, body content pushed down accordingly, meta row reframed to drop "published 3d ago" (now in author block).
> - **Content-updated:** `36:191` New document editor — folder picker pill `📁  /  build-log  ▾` added to the editor toolbar middle (between save-state and Publish button).
> - **Content-updated:** `36:246` Edit document — folder picker pill `📁  /  build-log` (read-only variant) added to the editor toolbar middle; `→ View public` URL updated to UUID; publish toast affordance added near the top-right of the editor surface (`✓ Published as /docs/a3f2b9c1-...  [Copy link]  [View public]`).
> - **Content-updated:** `37:289` Search results — 6 author rows added to the right side of each hit's meta row (6 different mock authors).
> - **Content-updated:** `27:704` ⌘K palette — each result row's meta line updated to prepend the author display name (4 different mock authors).
> - **Content-updated:** `14:2` + `14:135` M1 home frames — JeekLee author row added below each home card's meta row (3 cards each, all `JeekLee` since home is owner-curated per spec §7.3).
> - **Not changed (deliberately):** `29:817` Unpublish modal, `29:818` Delete modal, `30:859` New-doc dropdown, `30:860` Drag-drop overlay, `30:892` Account-pill dropdown, `37:363` My documents (tree + list panes are unchanged from v4 since v5 kept the directory P0 work intact). The M1 Login + Unauthorized frames are also untouched.
> - **Frame-name limitation persists** for three frames (`31:2`, `31:66`, `37:363`) that need user-manual rename in Figma since the Talk to Figma plugin allowlist doesn't expose `set_node_name`. Stale names documented in the Frame mapping table at the top of this doc.

> **S2 implementation log (2026-05-18).** Frontend Stage-3 S2 dispatch shipped the community feed (`/docs`), full-page search (`/docs/search`), the global ⌘K command palette, and the owner-curated `Latest published docs` section on `/`. Decisions made under implementer discretion (none deviate from the design doc, but worth capturing for the next round):
> - **`/docs` chip hint** is derived from the doc's first path segment (e.g. `agents`) and falls back to a deterministic per-index value when the path is `/`. Chips remain visual-only per "Out of scope" (no tag persistence in M2). The implementer didn't invent new visual chrome; it reuses the existing `accent` Chip variant.
> - **`N min` read-time meta** is approximated from the derived excerpt length (which is bounded at 160 chars per spec §4.3) since the list DTO does not carry the full body. Floor 1 min, cap 12 min — accurate enough for a meta-row hint and stays in sync with the design doc's "N min" placeholder.
> - **`/docs/search` debounce** is set to 300 ms per dispatch (the design doc said "default 200 ms"; the dispatch overrode to 300 ms for the full-page experience and kept 200 ms on the ⌘K palette where latency tolerance is lower).
> - **`/docs/search` empty-query screen** now shows two complementary cues: a "Start typing to search." live-region status line and a dashed-border affordance card with a `⌘K` hint, because the empty input on a search page is a moment the user is most receptive to learning the keyboard shortcut. Both copy strings stay within the design doc's stated empty-state vocabulary.
> - **⌘K palette open-handler** is exposed via a module-scoped imperative function (`openCommandPalette`) rather than a React Context. The topbar SearchPill (a `shared/ui` primitive) calls this from its onClick to open the global palette. FSD layering remains clean: the palette + handler live in `widgets/command-palette`, and the only cross-layer wiring happens in `app/(shell)/ShellChrome.tsx`, which mounts the palette and passes the handler down to the Topbar widget.
> - **⌘K Tab-toggle scope** is gated to authenticated callers only; anonymous callers see a static "Public" badge in both the input header and the footer hint row (since the `mine` scope requires `X-User-Id`).
> - **Home `Latest published docs` fallback** renders a friendly empty-state card (linking to `/docs` community feed) when owner-resolution fails or returns zero published docs, rather than hiding the section silently. The spec §6.3 "fail-closed" rule is honored — the data path stays closed, but the visual slot stays present so the home doesn't develop a hole that reads as broken.
> - **`SearchPill` (shared/ui primitive)** gained an optional `onClick` so the same component renders as a non-interactive label in pre-palette surfaces (e.g. `/login`) and as a button in shelled routes. No new tokens.

> **S3 implementation log (2026-05-18).** Frontend Stage-3 S3 dispatch shipped the directory tree on `/docs/mine`, the like button + view counter UI on `/docs/{id}` reader, the folder picker pill on `/docs/new`, the Docs sidebar badge, the Undo-disabled post-delete toast, and verified the OpenGraph metadata wiring. Decisions made under implementer discretion (none deviate from the design doc; flagging for the next round):
> - **`/docs/mine` left pane (220px) splits into STATUS + FOLDERS** as the design doc §"My documents" specifies. The tree is built client-side from the flat `GET /api/docs/folders` response; synthetic intermediate folders (`/agents/` when only `/agents/build-log/` is in the response) are materialized so indentation reads correctly, with count `0` and 60% opacity on the count badge. Active row uses `accent.soft` bg + `accent` count, matching the design doc's verbatim treatment.
> - **Status filter is client-side**, since the M2 list endpoint returns all visibilities (spec §6.1) and the filter is purely a UI overlay. Counts in the STATUS pane reflect the already-loaded list pane, so switching folders updates the counts in sync.
> - **Folder picker on `/docs/new` is a real interactive dropdown** even though spec §11 Q15 + design doc "Open questions" §"Folder picker overlay visual" both flag the overlay itself as M2.1 visual scope. Implemented per the design doc's permission: "the implementer may want to ship a minimal 'click → dropdown of folders' overlay in M2 P0." The dropdown reuses the existing `surface` / `border` / `radius.md` / `shadow.pop` token stack — no new visual chrome. Typeahead filters the caller's existing folders; a `Create '<path>'` row appears when the typed input parses as a valid path and isn't already in the list. Path is committed on first save; after that, the picker switches to read-only per ADR-12 §14 + spec §6.1 (PATCH carries only `title?` + `body?`).
> - **`/docs/{id}` editor folder picker is read-only** — same `FolderPicker` component as `/docs/new` but with `readOnly={true}`. The Move action lands in M2.1.
> - **LikeButton optimistic + anonymous behavior** matches design doc §"Document" §Interactions verbatim: filled `accent` when `likedByMe`, outline `border.strong` otherwise; rollback on API failure surfaces an inline `danger`-soft chip "Couldn't update like" for 3s. Anonymous viewers see an outline pill with `text-muted` fg; first click reveals an inline "Sign in to like →" accent link routing to `/oauth2/authorization/google?savedRequest=<current>` — a deliberately progressive disclosure (no aggressive popup on hover; the casual reader can ignore it).
> - **View beacon** fires on reader-view mount with a 200ms guard against React strict-mode double-invocation. Private docs viewed by the owner skip the round-trip entirely (the editor surface, not the reader, mounts when owner). The beacon never surfaces errors — view-counter failures are pure infrastructure noise per spec §10.
> - **Sidebar Docs badge** shows `published/total` (e.g. `4/12`) on the Docs row when the caller is signed in AND the current pathname is somewhere under `/docs/`. Computed once in the shell layout via the SSR `GET /api/docs?scope=mine` call (same fetch that drives any signed-in page), then passed through `ShellChrome` to `Sidebar`. The active row's badge uses `bg-accent text-surface` (inverted) for contrast against the `accent-soft` row bg; inactive uses `bg-accent-soft text-accent`.
> - **Undo-disabled toast** appears in the top-right of `/docs/mine` when the user lands via `?deleted=<title>` after a successful delete. Per ADR-12 §13 the toast renders a visually-present but non-functional `Undo` link; clicking it preventDefaults (no API call). The element carries `data-testid="undo-disabled"` for the E2E test that ADR-12 anticipates.
> - **OpenGraph metadata** in `app/(shell)/docs/[id]/page.tsx`'s `generateMetadata` already matched spec §7.4 verbatim from S1: `title: '<doc.title> · JeekLee's playground'`, `description: doc.excerpt`, `openGraph.{title,description,type:'article',url,publishedTime,authors:[displayName]}`, `twitter.card:'summary_large_image'`. For private docs viewed by a non-owner the route 404s upstream and the metadata function returns a `'Not found · JeekLee's playground'` shape so unfurlers don't see the title. No change required in S3 beyond verification.
> - **`fetchMyDocsServerSide` now accepts an optional `{ path }`** that maps to `?scope=mine&path={folder}`. Backward-compatible: omitting it preserves the S1/S2 behavior.

> **Post-S3 fix (2026-05-18).** `⌘+S` / `Ctrl+S` keyboard shortcut was missing from the editor surfaces — design doc line 184 says "⌘+S or autosave triggers PATCH" but only the autosave path shipped in S1/S3. Added a `useSaveShortcut` hook in `features/docs-editor/lib/` and wired it into both `DocNewPage` and `DocEditor`. The shortcut runs the same persist path as the 1.5s debounced loop (no behavior divergence); window-level keydown listener with `event.preventDefault()` to suppress the browser's "Save page" dialog. Cross-platform via `event.metaKey || event.ctrlKey`. Gated off when `saveState.kind === 'too-large'` or `publishing` (matches the debounced loop's guards). PRD Story 8's "수동 저장" line was simultaneously rewritten to acknowledge debounced auto-save + `⌘+S` as the M2 P0 contract.

> **Post-S3 width override (2026-05-18).** The spec'd 720px prose column on `/docs/new`, `/docs/{id}` editor, and the `/docs/{id}` public reader was widened to **1100px** per user UX preference ("Notion wide mode" feel). Trade-off: line length grows to ~95–100 characters, slightly past the textbook 50–75 prose-readability range, in exchange for less wasted lateral whitespace on 1080p+ viewports. Affects `DocNewPage`, `DocEditor`, `DocReader`, `EditorSkeleton`. The 720px figure in §"Document (/docs/{id})" item §"Article" and §"New document editor" / §"Edit document" item §"Editor surface" should be read as 1100px for the live UI.

The following open questions carry into the per-milestone ADR / M3+ cycles:

- **OpenGraph image generation.** Spec §7.4 includes `og:image` as an optional field. Three implementer options: (a) skip in M2 P0 (Twitter/Slack will fall back to the document title + description, which is already specified); (b) wire a Next.js OG image route (`app/docs/[id]/opengraph-image.tsx`) that renders a 1200×630 PNG server-side using the playground tokens; (c) defer to M2.1 with a static fallback image. Working default: (a) for M2 P0, with (b) tracked as an M2.1 task. Flagging for the implementer to confirm during Stage 3.

- **Toast geometry on Edit document.** The publish toast in the mock floats above the editor surface near the top-right. In the live UI, should it (a) attach to the toolbar (less layout shift), or (b) float as a global toast in the topbar (matches the existing toast pattern from M1 / Delete modal)? Working default: float as global toast for consistency; the in-frame mock is an illustrative placement, not a binding spec.

- **Author avatar source.** The mock uses khaki-initials placeholders (no Google avatar URL). The live UI should consume `authorAvatarUrl` from the DTO when present, falling back to khaki initials when not. The avatar-URL caching/proxying note from M1 carries over here — frontend-implementer applies the same fallback rule on every author-row surface (community feed, document detail, search results, ⌘K palette, home cards).

- **Folder picker overlay visual.** Spec §11 Q15 resolved the trigger pill style in this design pass; the overlay that opens *from* the pill is still M2.1 visual scope. Working default for M2 P0: the pill on `/docs/new` is a no-op for now (the doc's folder is inferred from the user's current view via `?folder=` URL param), and the read-only pill on `/docs/{id}` is decorative. Flagging because the implementer may want to ship a minimal "click → dropdown of folders" overlay in M2 P0 even though the spec defers it; the design system spec already covers the dropdown card pattern (see "+ New document dropdown" frame).

- **`/docs/{id}` route shape for the author view.** The author and the public reader hit the same URL `/docs/{id}`; the page renders the editor when the caller is the author, the reader when not. This is the v5 collapse — no separate `/docs/{id}/edit` route. Open: should the page redirect the author to a `?mode=read` query when they explicitly want to preview the reader view? Working default: no redirect; the author already has the `→ View public` link in the editor toolbar that opens the reader view in a new tab. Flagging in case the implementer disagrees.

- **Inline PNG capture.** Same Figma MCP base64-intercept blocker as v4 — the Figma file is canonical, ASCII wireframes are the inline reference where retained. Manual one-call-per-frame export (`File → Export selected → PNG @ 2x`) drops the M2 frames into `assets/M2/*.png` when the human reviewer wants them inlined here.
