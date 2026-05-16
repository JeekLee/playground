# ADR-06: Frontend Stack — Next.js + Feature-Sliced Design

## Status
Accepted

## Context
The client is a single Next.js app behind the gateway; it must:
- Use the same domain for HTTP calls so the gateway's session cookie applies.
- Scale architecturally as new BCs add new pages and widgets without becoming
  a tangle of cross-imports.
- Be authored fast by a single agent (later: humans) without bikeshedding the
  folder layout each cycle.

Alternatives considered:
- Plain Next.js with ad-hoc folders — rejected: every milestone would re-litigate
  where things go.
- Atomic Design — rejected: focuses on UI primitives, says nothing about
  feature/business slicing.
- Nx / Turborepo monorepo for client — rejected: one app, one team, no need.

## Decision

### Framework + tooling
- **Next.js 15.x** (App Router) — currently 15.3.x on the GA channel. Pin the
  exact patch in `frontend/package.json` when M0 / M1 lands.
- **TypeScript 5.6.x**, `"strict": true`, `"noUncheckedIndexedAccess": true`.
- **React 19.x** (matches Next 15 default).
- **Package manager: pnpm 9.x** (workspaces not used; single `frontend/`).
- **Node: 22 LTS** (declared in `frontend/package.json` `engines`).
- Linting: **ESLint 9.x** flat config + Prettier 3.x.

### Architecture: Feature-Sliced Design (FSD) v2 — 7 layers

Source root: `frontend/src/`.

```
frontend/src/
├── app/        # Next.js App Router routes + global providers (top of dep chain)
├── pages/      # Composition of widgets into route-level page components
│              # (FSD "pages" layer; not Next.js Pages Router)
├── widgets/    # Self-contained UI blocks (header, document viewer, chat panel)
├── features/   # User-facing interactions (upload-document, send-chat-message)
├── entities/   # Business entities + their UI/model (User, Document, ChatMessage)
└── shared/
    ├── ui/     # Design tokens + primitive components (Button, Input)
    ├── api/    # Generated/typed HTTP clients
    ├── lib/    # Framework-agnostic helpers
    └── config/ # Constants, env access
```

### Strict unidirectional dependency rule

A layer may import only from layers **below** it (lower in the list above).
A slice (e.g., `features/upload-document`) MUST NOT import from a sibling
slice; cross-slice composition happens one layer up.

Enforcement:
- **`@feature-sliced/eslint-config`** (or `eslint-plugin-boundaries` if FSD's
  config is not yet published for ESLint 9 flat config at implementation time).
- Lint failures **fail the build** (`next build` runs `eslint` via
  `next lint --fix=false --strict`).

### Design tokens
- All design tokens (colors, spacing, typography scale) live under
  `frontend/src/shared/ui/tokens/` as TypeScript objects, exported into Tailwind's
  config. Tailwind itself is allowed but not required; if used, tokens are the
  single source of truth.
- The Stage 2 product-designer agent produces tokens in Figma; the
  frontend-implementer mirrors them here.

### State / data fetching
- Server components by default; use Server Actions for mutations where the
  gateway tolerates them (CSRF token threading TBD per ADR-07).
- Client-side data fetching: **`@tanstack/react-query` 5.x** when interactivity
  requires it.
- No global state library (Zustand/Redux) until a milestone proves the need.

### Tests (covered by test-writer agent later)
- Unit / component: **Vitest 2.x** + Testing Library.
- E2E: **Playwright 1.4x**.

## Consequences
- Positive: New milestone = new slice under `features/` + maybe new entity;
  existing code is untouched.
- Positive: Lint-enforced layering means the architecture survives agent churn.
- Negative: FSD has a learning curve; first-time contributors must read the
  short FSD spec before committing.
- Negative: Two "pages" concepts (FSD `pages/` vs Next.js App Router) — solved
  by reading FSD `pages/` as "page composition" and keeping route files thin
  in `app/`.
