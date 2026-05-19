# Design: M1 — Identity (v2, design-system aligned)

> PRD: `docs/prd/M1-identity.md`
> Figma: https://www.figma.com/design/NOe1YyQ3NxzgcuYlAVeooN/playground-%E2%80%94-M1-Identity — `playground — M1 Identity` (v2, built via Talk to Figma MCP)
> Supersedes: the previous Stage-2 output (auth-only home, `#3D63DD` accent, slate text). See PRD top-of-doc note (2026-05-16) and design system spec §10 for the change rationale.

Stage 2 (v2, re-run #2 + M2-spec sidebar realignment) output for the Identity milestone. Four desktop frames at 1440 wide, built strictly against the tokens, layout shell, and home composition pinned in `docs/superpowers/specs/2026-05-16-playground-design-system.md`. Sidebar follows `2026-05-16-m2-docs-bc-design.md` §7.1: single Apps section, no Workspace section, locked Apps rows for milestones not yet shipped (`Docs M2`, `Chat M4`, `System status M5`). Tokens table below is sourced verbatim from the design system spec — the frontend-implementer mirrors them into `client/src/shared/ui/tokens/` and never invents new ones.

> Asset note: the Figma file is fully assembled at the URL above (all four frames rendered correctly under live verification). The `mcp__TalkToFigma__export_node_as_image` calls succeed inside Figma but the harness intercepts the returned base64 as inline visual content rather than passing it through as text — so the PNGs cannot be programmatically saved under `docs/design/assets/M1/` from this agent. ASCII wireframes below remain accurate; the canonical visual reference is the Figma file. Manual one-call-per-frame export (File → Export selected, PNG @ 2x) drops them straight into `docs/design/assets/M1/{home-public,login,home-signedin,unauthorized}.png` when the human reviewer wants them inlined here.

> Note on frame state (added by M2 design round 7): the Figma frames `14:2` (Public Home) and `14:135` (Signed-in Home) now visualize the **M2-shipped state**, not the M1-ship state. Two deltas: (1) the sidebar `Docs` row is shipped/inactive (full opacity, icon + label in `color.text`, no `M2 🔒` badge); (2) the `Latest documents` section is populated with a 3-column grid of 3 mock document cards (replaces the empty-state card; same mock entries as `docs/design/M2-docs.md` §Documents (public list) — "Building an agent team…", "Why I rebuilt my blog…", "Spark cluster…"). The ASCII wireframes and per-screen prose in this doc still describe the **M1-ship state** — accurate for the M1 frontend implementation; for the M2-shipped UI state see `docs/design/M2-docs.md` "Home composition deltas" plus the Figma frames.

## Screens

### Public Home (`/`) — logged-out landing

- **Purpose:** the public reader's entry into the platform — shows what the site is, what's shipped (Home), and what's coming (Documents, Chat, System status) with explicit milestone names.
- **PRD user story (trace):** none directly — this screen serves the **design system spec §2.4 public-vs-personal posture** (logged-out visitors are first-class readers). See the new traceability row added below.
- **Auth state:** logged-out.
- **Figma frame:** `M1 — Home (public)  /` (node `14:2` in the Figma file; PNG export pending — see Asset note)
- **Key elements:**
  - **Sidebar (232px, `surface.soft`):** brand row (glyph `J` + `JeekLee's` / `PLAYGROUND` stacked wordmark per spec §2.2) → `⌘K` search pill → single Apps section per M2 spec §7.1 with four rows: `Home` (active, `accent.soft` bg + `accent` label), `Docs` (locked, 0.72 opacity, `text.subtle` label, `M2 🔒` badge), `Chat` (locked, `M4 🔒` badge), `System status` (locked, `M5 🔒` badge). Locked rows are no-op on click (cursor `default`, tooltip `Available when <milestone> ships`) → flex spacer → account footer card reading `Not signed in / Sign in to write/chat privately.`
  - **Slim topbar:** breadcrumb `Home` on the left; on the right, a neutral `Viewing publicly` chip + primary `Sign in with Google` button (spec §2.4 + §6.1).
  - **Compact hero (no display type):** eyebrow `A PERSONAL PLATFORM · OPEN TO READ` (`accent`, `font.eyebrow`), title `What would you like to do today?` (`font.h1`, `text`), subtitle in `text.muted` describing the dual-mode posture.
  - **`Things you can try`** section header with `See all →` link in `accent` → 4-column tile grid (4 × 276px wide tiles, 16px gap):
    - **Tile 1 — Home** (active, no opacity): 36×36 `accent.soft` icon box, title `Home`, desc "You're here. The dashboard for everything else as it ships.", meta chip `● shipped` (`success` chip).
    - **Tile 2 — Documents** (locked, 0.72 opacity): icon box on `surface.soft`, desc one-liner, **`M2 — Documents`** locked-meta chip + `🔒 sign in to write` chip.
    - **Tile 3 — Chat** (locked, 0.72 opacity): same treatment, **`M4 — Chat`** locked-meta chip + `PUBLIC when ready` accent chip.
    - **Tile 4 — System status** (locked, 0.72 opacity): same treatment, **`M5 — System status`** locked-meta chip + `PUBLIC when ready` accent chip.
  - **`Latest documents`** section header with `All documents →` link → single **empty-state card** (full row, centered content): eyebrow `M2 — DOCUMENTS`, h3 `Documents will appear here when the document is online.`, body `Read-only for visitors; sign in to write. Track progress on GitHub.`, accent text-link `→ Track the M2 milestone on GitHub`.
- **Interactions:**
  - `Sign in with Google` (topbar) and the implicit CTA inside the empty-state are both routed to `/oauth2/authorization/google` per ADR-07. Spring Security's `savedRequest` brings the user back to `/` after success.
  - Clicking the `M2 milestone` link opens the GitHub milestone page in a new tab.
  - Hovering a locked tile keeps the locked treatment — they are not clickable yet (their click target lands when the unlocking milestone ships).
- **Empty / error / loading states:**
  - **Empty:** this *is* the empty state for the pre-M2 site. Copy explicitly names which milestone unlocks each future surface (spec §9 mandates this).
  - **Loading:** none — the public home is fully SSR/static and ships zero blocking data fetches in M1.
  - **Error:** N/A at the page level. The `Sign in with Google` button error path (OAuth denied / network) reuses the Login screen's banner pattern (see Login below).

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Home                       [Viewing publicly]  [Sign in with Google]│  ← topbar (border-bottom)
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │                                                                     │
│  [⌕ Search ⌘K]│  A PERSONAL PLATFORM · OPEN TO READ                                 │
│              │  What would you like to do today?                                   │
│  APPS        │  Read documents, ask the model questions, or peek at how the system   │
│  ⌂ Home   ●  │  is feeling. Sign in to write your own.                            │
│  ▤ Docs M2 🔒│                                                                     │
│  💬 Chat M4 🔒│  Things you can try                                  See all →     │
│  📊 Stat M5 🔒│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │
│              │  │ [⌂]        │ │ [✍]  0.72  │ │ [💬] 0.72  │ │ [📊] 0.72  │      │
│              │  │ Home       │ │ Documents     │ │ Chat       │ │ System     │      │
│              │  │ You're here│ │ Long-form  │ │ Ask the    │ │ status     │      │
│              │  │ ● shipped  │ │ M2-Documents  │ │ M4-Chat    │ │ M5-Status  │      │
│              │  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │
│              │                                                                     │
│              │  Latest documents                              All documents →    │
│              │  ┌─────────────────────────────────────────────────────────────┐  │
│              │  │   M2 — DOCUMENTS                                               │  │
│              │  │   Documents will appear here when the document is online.          │  │
│              │  │   Read-only for visitors; sign in to write.                 │  │
│              │  │   → Track the M2 milestone on GitHub                        │  │
│              │  └─────────────────────────────────────────────────────────────┘  │
│              │                                                                     │
│  ┌─────────┐ │                                                                     │
│  │ Not     │ │                                                                     │
│  │ signed  │ │                                                                     │
│  │ in …    │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Login (`/login`)

- **Purpose:** unauthenticated entry point reached *only* when the user explicitly chooses to sign in (from the topbar `Sign in with Google` CTA on any sidebar+topbar shell route, or from the Unauthorized screen's primary CTA). Not a redirect target for the public home. Locked Apps rows in the sidebar are no-op and do NOT route here.
- **PRD user story (trace):** "As a user, I want to log in via Google so that the service knows who I am without me managing yet another password."
- **Auth state:** logged-out.
- **Figma frame:** `M1 — Login  /login` (node `14:114` in the Figma file; PNG export pending — see Asset note)
- **Key elements:**
  - **Header bar (full-width, no sidebar):** brand row (glyph `J` + stacked `JeekLee's / PLAYGROUND` wordmark, spec §2.2) on the left; `Not signed in` neutral chip on the right. `border-bottom: 1px solid color.border`.
  - **Centered card** (440×420, `surface` bg, radius `lg` 14px, border `color.border`, shadow `shadow.card`):
    - Eyebrow `JEEKLEE'S PLAYGROUND` (`accent`, `font.eyebrow`).
    - Headline `Sign in to continue` (`font.h1`).
    - Subtitle in `text.muted`: "Sign in lets you write documents, save chats, and see your own documents. Reading the site doesn't require an account." (`font.body`, makes the public/auth split unambiguous).
    - Primary button `Continue with Google` (`accent` bg, white text, radius `md`, full-card width, with a small `G` glyph rendered in white before the label).
    - Footnote in `text.subtle`: "We only read your name, email, and avatar. A session cookie keeps you signed in for 8 hours." (matches ADR-07's 8h sliding session).
  - **Below-card tip** in `text.subtle`: "Tip: hitting an authenticated page while logged out brings you here. Reading the site doesn't." — anchors the new public/auth boundary.
- **Interactions:**
  - `Continue with Google` → `/oauth2/authorization/google` (ADR-07). On success the gateway sets `PLAYGROUND_SESSION` (HttpOnly, SameSite=Lax, 8h sliding) and redirects to the saved request (or `/` if none).
  - On callback failure, the footnote area swaps to an inline danger banner: bg `danger.soft`, fg `danger`, copy `Sign-in failed — please try again.` Button re-enables.
- **Empty / error / loading states:**
  - **Loading:** the button's `G` glyph is replaced with a 14px spinner in `#FFFFFF`; the rest of the card disables (pointer-events none, opacity 0.6).
  - **Error (OAuth denied / network):** danger banner as described above.
  - **Already authenticated:** route guards redirect to `/` (the signed-in home), not back to this screen.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's                                                  [Not signed in]  │
│      PLAYGROUND                                                                  │
│──────────────────────────────────────────────────────────────────────────────────│
│                                                                                  │
│                          ┌─────────────────────────────────┐                    │
│                          │  JEEKLEE'S PLAYGROUND           │                    │
│                          │  Sign in to continue            │                    │
│                          │  Sign in lets you write documents, │                    │
│                          │  save chats, and see your own…  │                    │
│                          │  ┌────────────────────────────┐ │                    │
│                          │  │  G   Continue with Google  │ │                    │
│                          │  └────────────────────────────┘ │                    │
│                          │  We only read your name, email, │                    │
│                          │  and avatar. 8h session cookie. │                    │
│                          └─────────────────────────────────┘                    │
│                          Tip: hitting an authenticated page lands you here.     │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Signed-in Home (`/`) — same route, authenticated state

- **Purpose:** the signed-in user's entry point — proves the session round-trip succeeded and renders the same composition as the public home, with the only deltas being the topbar status, the topbar control, the sidebar account footer, and the hero subtitle. Identical tokens, identical layout (spec §2.4 mandate: same visual everywhere, posture changes only).
- **PRD user story (trace):** "As a user, I want my session to persist across page reloads so that I am not asked to log in on every navigation." AND "As a user, I want to fetch my own profile (`/me`) so that the frontend can render my display name and avatar."
- **Auth state:** logged-in.
- **Figma frame:** `M1 — Home (signed-in)  /` (node `14:135` in the Figma file; PNG export pending — see Asset note)
- **Key elements (deltas from Public Home only):**
  - **Topbar right side:** `Signed in` success chip (`success.soft` bg, `success` fg, dot) **replaces** `Viewing publicly`; account pill (24px khaki avatar with `JL` initials + `JeekLee` display name + chevron, `surface` fill, `border` stroke, radius `pill`) **replaces** the `Sign in with Google` primary button.
  - **Sidebar footer:** card swaps from `Not signed in` copy to a horizontal row: 28px khaki avatar + stacked `JeekLee` (`font.h3`-ish 12px Semi Bold) and `jeeklee1120@gmail.com` (10.5px `text.muted`). `Sign out` is reachable from the account pill's chevron menu (menu interactions out of M1 visual scope per the previous design doc's deferral).
  - **Hero subtitle:** rewritten to a signed-in voice: "Welcome back. Pick a surface — the locked Apps rows unlock as each milestone ships." (same `font.body`, `text.muted`.)
  - **Sidebar Apps section:** locked rows (`Docs`, `Chat`, `System status`) remain locked even when signed in — they unlock per their own milestones, not on sign-in (`Docs` unlocks at M2, etc.). Visually identical to public.
  - **Tiles, document empty-state, and the rest of the page:** unchanged from the Public Home composition.
- **Interactions:**
  - On page load the client calls `GET /api/identity/me` (gateway strips `/api/identity` → `/me` per ADR-07). On 200, the account pill and sidebar footer render the live display name + email; the `JL` initials shown in the mock are the loading-state fallback.
  - Clicking the account pill chevron opens a dropdown (visual out of scope for M1; the M2 design cycle picks the dropdown spec — see Open questions).
  - On 401 from `/me` the client treats it as session expiry and routes to `/login`.
- **Empty / error / loading states:**
  - **Loading (`/me` in flight):** account pill renders `JL` khaki avatar + a 64px skeleton bar in `surface.soft` where the name goes; no layout shift.
  - **Error (5xx from `/me`):** account pill remains visible with `JL` initials and an "·" placeholder for the name; a non-blocking `info`-chip toast appears in the topbar reading `Couldn't reach /me — retry`. The rest of the home is unaffected (it's static).
  - **Error (401 from `/me`):** redirect to `/login` (treat as session expiry).

```
┌──────────────┬─────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's│ Home                          [● Signed in]  [(JL) JeekLee ▾]      │
│      PLAYGRD │─────────────────────────────────────────────────────────────────────│
│              │  (same hero, tiles, and empty-state document card as Public Home —     │
│  [⌕ Search]  │   only the subtitle text changes to "Welcome back. Pick a surface  │
│              │   — the locked Apps rows unlock as each milestone ships.")         │
│  APPS        │                                                                     │
│  ⌂ Home   ●  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐      │
│  ▤ Docs M2 🔒│  │ Home       │ │ Documents  🔒 │ │ Chat    🔒 │ │ Status  🔒 │      │
│  💬 Chat M4 🔒│  │ ● shipped  │ │ M2-Documents  │ │ M4-Chat    │ │ M5-Status  │      │
│  📊 Stat M5 🔒│  └────────────┘ └────────────┘ └────────────┘ └────────────┘      │
│              │                                                                     │
│              │  Latest documents … (same empty-state card)                    │
│              │                                                                     │
│  ┌─────────┐ │                                                                     │
│  │(JL)     │ │                                                                     │
│  │ JeekLee │ │                                                                     │
│  │ jee…@   │ │                                                                     │
│  └─────────┘ │                                                                     │
└──────────────┴─────────────────────────────────────────────────────────────────────┘
```

### Unauthorized (`/401`)

- **Purpose:** explicit refusal state when a logged-out request hits an **authenticated-only** route (per ADR-09 classification). Critically, hitting `/` while logged out **does not** land here — `/` is public. This screen is reached only via, e.g., attempting to open `/me` or another authenticated route directly without a session. Locked Apps rows in the sidebar are no-op (not deep links), so they do not route here either.
- **PRD user story (trace):** "As a user, I want to hit a protected resource while logged out and be cleanly redirected/refused so that the auth boundary is obvious."
- **Auth state:** logged-out (rendered when the gateway returns 401 for an authenticated route).
- **Figma frame:** `M1 — Unauthorized (401)  /401` (node `14:246` in the Figma file; PNG export pending — see Asset note)
- **Key elements:**
  - **No sidebar, no topbar.** The 401 is a hard refusal — wrapping it in the regular app chrome (sidebar + locked Apps rows + topbar) competes with the actual message. The screen is intentionally a single centered card on `color.bg` with the brand row floated top-left. Matches the Login screen's bare layout, deliberately.
  - **Brand row (top-left, no header bar):** glyph `J` + stacked `JeekLee's / PLAYGROUND` wordmark per spec §2.2, positioned at `spacing.lg` (24px) from the top-left of the viewport.
  - **Centered card** (560×360, `surface` bg, radius `lg` 14px, border `color.border`, shadow `shadow.card`):
    - `401 · UNAUTHORIZED` chip in `danger.soft` bg + `danger` fg.
    - Headline `You need to sign in for this one` (`font.h1`).
    - Body in `text.muted`: "This page needs an account — writing documents, private chats, or your own documents all require sign-in. Reading the site (home, documents, public chat, system status) doesn't." (`font.body`) — reinforces the public/auth split.
    - Button row: primary `Continue with Google` (`accent` per spec §6.1) + **secondary** `Go home` button (per spec §10 row 6: "401 screen … use the secondary button for `Go home`").
    - Footnote in `text.subtle`: "After signing in we'll bring you back to the page you tried to open."
- **Interactions:**
  - Primary CTA: navigates to `/oauth2/authorization/google`; Spring Security `savedRequest` returns the user to the original authenticated URL.
  - `Go home` (secondary): navigates to `/` (public). Since `/` is public, this does NOT loop back here — that loop existed in the v1 design and is explicitly broken by ADR-09.
- **Empty / error / loading states:** N/A — single state. Button loading state matches the Login screen (G glyph swaps to a white spinner).

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  [J] JeekLee's                                                                   │
│      PLAYGROUND                                                                  │
│                                                                                  │
│                                                                                  │
│                          ┌─────────────────────────────────────────┐             │
│                          │  401 · UNAUTHORIZED                     │             │
│                          │  You need to sign in for this one       │             │
│                          │  This page needs an account — writing   │             │
│                          │  documents, private chats, or your own     │             │
│                          │  documents all require sign-in.         │             │
│                          │  Reading the site doesn't.              │             │
│                          │  ┌────────────────────────┐ ┌────────┐  │             │
│                          │  │ G  Continue w/ Google  │ │Go home │  │             │
│                          │  └────────────────────────┘ └────────┘  │             │
│                          │  After signing in we'll bring you back. │             │
│                          └─────────────────────────────────────────┘             │
│                                                                                  │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Traceability matrix

| PRD user story / posture row | Screen(s) |
|---|---|
| **Design system spec §2.4** — public-vs-personal posture: logged-out visitors are first-class readers of the home, with the same tokens and layout as signed-in. (Not a PRD story; tracked here per the Stage-2 re-run brief.) | Public Home, Signed-in Home (identical chrome; deltas only in topbar status/control and sidebar footer) |
| As a user, I want to log in via Google so that the service knows who I am without me managing yet another password. | Login; also reachable via the topbar `Sign in with Google` on Public Home and Unauthorized |
| As a user, I want my session to persist across page reloads so that I am not asked to log in on every navigation. | Signed-in Home (the persistent account pill in the topbar and the avatar in the sidebar footer are the visible artifacts of an active session) |
| As a user, I want to fetch my own profile (`/me`) so that the frontend can render my display name and avatar. | Signed-in Home (the account pill and the sidebar footer both populate from `GET /me`; `JL` initials shown in the mock are the skeleton/fallback state) |
| As a backend developer (the operator), I want every downstream service to receive a trusted `X-User-Id` header so that BCs do not each re-implement OAuth. | N/A — backend-only (verified at runtime via the P1 `GET /identity/_debug/whoami` probe; no user-facing screen) |
| As a user, I want to hit a protected resource while logged out and be cleanly redirected/refused so that the auth boundary is obvious. | Unauthorized (`/401`). Hitting `/` while logged out does NOT land here — `/` is public per ADR-09; only authenticated routes return 401. |

5 of 5 PRD user stories mapped (4 to screens, 1 to `N/A — backend-only`) + 1 posture row from design system spec §2.4.

## Design tokens used

Every value is sourced verbatim from `docs/superpowers/specs/2026-05-16-playground-design-system.md`. The frontend-implementer must mirror this into `client/src/shared/ui/tokens/` (per ADR-06) and downstream code MUST NOT hardcode any of these hexes — they read through the token names.

| Token | Value | Where used |
|---|---|---|
| `color.bg` | `#FAF7EF` | App background on all four frames; topbar background |
| `color.surface` | `#FFFFFF` | Login card, Unauthorized card, document-empty-state card, tile cards, account-footer card, search pill, secondary button, account pill |
| `color.surface.soft` | `#F4EFDF` | Sidebar background; locked tiles' icon-box bg; `kbd` pill bg; `Viewing publicly` neutral chip bg; locked-meta chips bg |
| `color.border` | `#E6E0CB` | Card strokes, topbar `border-bottom`, login-bar `border-bottom`, account-footer card stroke, account-pill stroke, search-pill stroke |
| `color.border.strong` | `#D6CFB3` | Secondary `Go home` button stroke (spec §6.1 secondary variant border) |
| `color.khaki` | `#C2B88A` | Sidebar-footer avatar fill, topbar account-pill avatar fill |
| `color.text` | `#2A2C20` | Headings (`What would you like to do today?`, `Sign in to continue`, `You need to sign in for this one`), nav labels, tile titles, account-pill name, sidebar footer name, secondary-button label |
| `color.text.muted` | `#6F6A55` | Hero subtitle, tile descriptions, login-card subtitle, unauthorized-card body, breadcrumb, neutral chip fg, sidebar wordmark line 2, sidebar account-footer email, kbd label |
| `color.text.subtle` | `#8B8670` | Search-pill placeholder, login footnote ("We only read your name…"), below-card tip, unauthorized footnote, sidebar section label (`APPS`), locked Apps row labels and milestone badges |
| `color.accent` | `#6E7A3A` | Primary button fill (`Sign in with Google`, `Continue with Google`), active nav (`Home`) fg, hero eyebrow, document-empty-state eyebrow, all `→` accent links (`See all →`, `All documents →`, `Track the M2 milestone on GitHub`), PUBLIC accent chips fg, accent-tile icon glyph |
| `color.accent.soft` | `#E9E8D1` | Active nav (`Home`) bg, active-tile icon-box bg, `PUBLIC when ready` accent chip bg |
| `color.success` | `#4F6B2E` | `Signed in` chip fg (topbar, signed-in home); `● shipped` chip fg on the active Home tile; chip dot fill |
| `color.danger` | `#B14B3B` | `401 · UNAUTHORIZED` chip fg |
| `color.danger.soft` | `#F4E1DA` | `401 · UNAUTHORIZED` chip bg |
| `font.h1` | 28px / 1.2 / 700 / -0.02em | Page titles: `What would you like to do today?` (both homes), `Sign in to continue` (Login), `You need to sign in for this one` (Unauthorized) |
| `font.h2` | 20px / 1.3 / 600 / -0.01em | Section titles `Things you can try`, `Latest documents` |
| `font.h3` | 16px / 1.4 / 600 / 0 | Tile titles (`Home`, `Documents`, `Chat`, `System status`); document-empty-state h3 |
| `font.body` | 15px / 1.6 / 400 / 0 | Hero subtitle, login-card subtitle, unauthorized-card body |
| `font.small` | 13px / 1.5 / 400 / 0 | Tile descriptions, breadcrumb, button labels (13px / 500 per spec §6.1), nav labels, accent text-links, account-pill name |
| `font.eyebrow` | 11px / 1.2 / 600 / +0.14em / uppercase | `A PERSONAL PLATFORM · OPEN TO READ`, `JEEKLEE'S PLAYGROUND` on Login, `M2 — DOCUMENTS` document-empty-state eyebrow, sidebar section label (`APPS`) |
| `font.mono` | 13px / 400 | `⌘K` glyph inside the sidebar search-pill `kbd` |
| `spacing.xs` | 4px | Intra-element micro-gaps (chip dot to label, sidebar nav vertical gap) |
| `spacing.sm` | 8px | Hero eyebrow → title gap; tile internal gap; button content gap |
| `spacing.md` | 16px | Tile internal padding, document-empty-state card padding, card-row gap in tile grid; main content top padding stem |
| `spacing.lg` | 24px | Login card vertical rhythm; unauthorized card vertical rhythm; main-area vertical rhythm between hero / `Things you can try` / `Latest documents` (spec §8.3 "22–32px") |
| `spacing.xl` | 40px | Login card outer padding; unauthorized card outer padding |
| `radius.sm` | 6px | `kbd` pill corner; (reserved for inputs in next milestones) |
| `radius.md` | 10px | Buttons (primary + secondary), tile cards, document-empty-state card, sidebar nav-item active bg, sidebar account-footer card, search-pill kbd inset |
| `radius.lg` | 14px | Login card, Unauthorized card (modal-scale surfaces) |
| `radius.pill` | 999px | Sidebar search pill, all chips, account pill, avatar (sidebar + topbar) |
| `shadow.card` | `0 4px 14px rgba(60,50,20,.05)` | Tile cards, document-empty-state card, Login card, Unauthorized card |
| `shadow.pop` | `0 10px 30px rgba(60,50,20,.10)` | (Reserved — tile-hover treatment per spec §6.4 hover-as-link variant; not used at rest in these static mocks but specified so the implementer applies it on hover) |

**Verification note:** the only hex values in this document are the ones listed above. Each appears exactly in the design system spec at §3.1 / §3.2 / §3.3 / §5.3. No new tokens were invented; no spec hex was substituted with a near-miss.

## Post-ship amendments

- **Favicon (added 2026-05-20):** browser tab icon rendered from `frontend/src/app/icon.tsx` via Next 14 `ImageResponse` — a 32×32 mirror of the §2.2 brand glyph (`accent` bg, `surface` fg, proportional ~9px corner radius, bold `J` at 22px). No `public/favicon.ico` and no `metadata.icons` override — the file-based icon convention auto-injects `<link rel="icon">` at build time. Sources the accent + surface hexes from `frontend/src/shared/ui/tokens/color.ts` so the favicon stays in lockstep with the brand color if it ever shifts.

## Out of scope (this milestone)

Same deferrals as the v1 design doc, plus the changes implied by the new public-home composition:

- **Profile editing UI** — the user has whatever Google says they have.
- **Account deletion / data export** — no UI.
- **Multi-provider auth** — only the Google button exists; no provider picker, no GitHub/Apple/email.
- **Email/password fallback** — no password field anywhere.
- **Roles / permissions** — no role badge on the account pill.
- **Account-pill dropdown menu** — visually present (chevron) but the menu contents were deferred at M1 ship. **Resolved in M2 Stage 2:** see `docs/design/M2-docs.md` "Account-pill dropdown (overlay)" section + Figma frame `30:892`. Menu contains an identity header row (name + email), divider, `My documents` → `/docs`, `Sign out` → `POST /logout` per M1 PRD.
- **Avatar URL caching/proxying** — P1 in PRD; the mocks show the khaki-initials avatar fallback so M1 ships without a hard dependency on Google's CDN.
- **Mobile / responsive layouts** — desktop only at 1440 wide. Spec §13 defers mobile breakpoints below 768px; sidebar collapse modes (768–1023 icon rail, <768 hamburger drawer) are specified in §8.1 but visual mocks for them are deferred to M4 (the first read-on-the-phone use case).
- **Dark mode** — single light theme. Token names are reserved for the swap per spec §3.4.
- **Real tile content / document thumbnails** — at M1 the only shipped `Apps` row in the sidebar is `Home` (the rest render as locked previews per M2 spec §7.1), and the document section is the empty-state card. Real document thumbnails (`128px gradient` per spec §9) ship with M2.
- **The "all documents" / "see all" overflow routes** — those screens live with M2.
- **`/me` route (dedicated page)** — PRD top-of-doc note says the `/me` payload renders in the sidebar account footer (signed-in state) "and optionally on a dedicated `/me` route." M1 covers the footer rendering; the dedicated route is optional and deferred.

## Open questions for the next cycle

- **Active-tile click target.** Spec §9 describes the tile grid and the locked treatment, but doesn't say what clicking the active `Home` tile does — it's the current page. Proposal: render it as a non-link tile at M1 (no anchor, no hover lift), and re-frame it as an anchor row to whatever the M2/M3 "Today" section becomes when the home gains real-time content. Flagging for human pick.
- **Account-pill dropdown contents — RESOLVED in the M2 Stage 2 design cycle.** See `docs/design/M2-docs.md` → "Account-pill dropdown (overlay)" section for the menu spec (header row with name + email, divider, `My documents` row, `Sign out` row). Figma frame `M2 — Account pill dropdown (overlay)  global` (node `30:892`) is the canonical visual reference. Frontend-implementer applies the resolved spec when wiring the topbar's account pill click behavior in M2 Stage 3.
- **Saved-request behavior on Unauthorized.** ADR-07 documents Spring Security's saved-request default; design assumes a logged-out hit on, e.g., `/me` lands on `/401` *and* returning from OAuth puts the user back on `/me`. Frontend-implementer should confirm during Stage 3 (`/build-server`); if not, the `/401` card needs to thread a `?redirect=` param through the Google button.
- **Bare-layout routes (`/login`, `/401`).** Both intentionally skip the sidebar+topbar shell — Login because there's no nav context yet, Unauthorized because the refusal message reads stronger uninterrupted. Both use the same brand-row-top-left layout for visual continuity. Open (carried from previous cycles): should either adopt the full shell later? Argument for shell: spec §2.4 says "same tokens everywhere." Argument against: no sidebar items are actionable on Login (anonymous user can't act on locked rows), and the shell-on-401 was tried in v1 and felt like a regular page hiding an error. Recommendation: keep both bare as drawn here.
- **GitHub milestone URL.** The document empty-state links to the M2 GitHub milestone, but the GitHub-issue/milestone URLs aren't pinned yet (the `/milestones` Stage 1 run produces them). Until they're pinned the link is a placeholder `→ Track the M2 milestone on GitHub`; the implementer wires the real URL during M2 Stage 3.
- **Inline PNG capture.** The Figma file is fully assembled and all four frames render correctly under live verification via `mcp__TalkToFigma__export_node_as_image` (proof: the agent visually confirmed each frame during this run). The blocker is that the harness intercepts the export's returned base64 as inline visual content and does not pass it through as text the agent can write to disk. The path to inlined PNGs in this doc is therefore a manual one-call-per-frame export from Figma (`File → Export selected → PNG @ 2x`) producing `assets/M1/{home-public,login,home-signedin,unauthorized}.png`. Not blocking for `frontend-implementer` — the Figma URL is the canonical visual.

