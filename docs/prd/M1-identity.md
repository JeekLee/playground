# PRD: M1 — Identity

> Note on filename: the Stage 1 prompt referenced `M1-agent-task-queue.md`, but the pinned roadmap (and the project spec at `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`) defines M1 as **Identity**. Per the Stage 1 constraint to use the milestone list verbatim, this PRD is written for M1 = Identity at the canonical path `docs/prd/M1-identity.md`.

> Note on scope (added 2026-05-16): the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` establishes the site as **mixed public/authenticated** (the home, essays, public chat, and metrics are reachable without sign-in; see ADR-09). M1's job is still to ship the OAuth flow, the `/me` endpoint, and the gateway's `X-User-*` header injection **for authenticated routes only** — what changes is the **frontend treatment**: the home page is the public landing per the design system, not an auth-gated `/me` viewer. The `/me` payload renders in the sidebar account footer (signed-in state) and optionally on a dedicated `/me` route.
>
> On public routes (per ADR-09 §"Anonymous identity contract") `X-User-Id` / `X-User-Email` / `X-User-Sub` headers are **absent** — the gateway does not invent a sentinel like `anonymous` (sentinels would pollute downstream logs/analytics with non-existent user ids), and the gateway also **strips** any client-supplied `X-User-*` headers on every request so backends never trust attacker-injected identity. Backends MUST treat header absence as "anonymous reader" and not as an error. The anonymous-visitor identifier (when one is needed for rate-limit / view-dedup keys) is the `PLAYGROUND_ANON` cookie (ADR-09), set by the gateway on first public-page visit; `identity` itself does not consume it in M1.

## 한 줄 설명
사용자가 Google 계정으로 게이트웨이에서 로그인하고, 모든 백엔드 서비스가 동일한 사용자 식별 정보를 받아 동작할 수 있도록 하는 인증/식별 기반을 제공한다.

## 사용자 스토리
- As a user, I want to log in via Google so that the service knows who I am without me managing yet another password.
- As a user, I want my session to persist across page reloads so that I am not asked to log in on every navigation.
- As a user, I want to fetch my own profile (`/me`) so that the frontend can render my display name and avatar.
- As a backend developer (the operator), I want every downstream service to receive a trusted `X-User-Id` header so that BCs do not each re-implement OAuth.
- As a user, I want to hit a protected resource while logged out and be cleanly redirected/refused so that the auth boundary is obvious.

## Frontend treatment notes (M1 ship-state)

These notes carry the design-system spec's posture into the PRD so the frontend-implementer doesn't drift back to an auth-only home. They are not user stories; they constrain the M1 visual.

- **Sidebar locked rows are visual-only.** Per the design system spec §8.1 (as superseded by the M2 docs BC spec §7.1) and the M1 design doc, the sidebar's `Apps` section ships with four rows: `Home` (active, shipped), `Docs` (locked, badged `M2 🔒`), `Chat` (locked, `M4 🔒`), `System status` (locked, `M5 🔒`). Locked rows are **no-op on click** (cursor `default`, tooltip `Available when <milestone> ships`) — they do **not** route anywhere, do **not** trigger the login flow, and do **not** return 401. The M1 backend is not expected to serve the routes those rows will eventually point at (`/docs`, `/chat`, `/metrics`); each milestone enables its own row when it ships. The locked treatment is identical for logged-out and signed-in users — locks unlock per milestone, not per auth state.
- **Public Home is reachable logged-out.** Hitting `/` while logged out renders the Public Home, not `/login` and not `/401`. The `Sign in with Google` topbar button is the only auth entry point from the home. (See ADR-09; supersedes any earlier PRD reading that implied `/` was the `/me` viewer.)
- **`/me` payload renders in the sidebar account footer.** On the Signed-in Home the account-footer card (avatar + display name + email) is populated by `GET /me`. A dedicated `/me` route is **optional** and deferred — M1 covers only the footer rendering (per M1 design doc "Out of scope" §).

## 기능 범위

### In scope
- Google OAuth 2.0 Authorization Code flow handled at the gateway (Spring Cloud Gateway + Spring Security OAuth2 Client)
- Session cookie issued on successful auth (HttpOnly, Secure-where-applicable, SameSite policy decided in the architect's ADR)
- `/me` endpoint returning the current user (id, email, displayName, avatarUrl)
- `identity` service stores a user record on first login (`users` table in its own schema)
- `identity` service updates `lastLoginAt` on subsequent logins
- Gateway injects `X-User-Id`, `X-User-Email`, and `X-User-Sub` headers on every downstream request **on authenticated routes**; backend services trust these headers because the gateway is the only ingress (ADR-07)
- Gateway sets the `PLAYGROUND_ANON` cookie on first public-page visit (per ADR-09) — UUID v4, HttpOnly, SameSite=Lax, 30-day rolling, Secure outside dev. Backends do not consume it in M1 but the cookie's presence is required for M2+ rate-limit / view-dedup work.
- Gateway strips any client-supplied `X-User-*` headers on every request (authenticated and public) before forwarding (ADR-07 §"Header injection contract")
- `POST /logout` invalidates `PLAYGROUND_SESSION`, preserves the `identity.users` row, redirects to `/`
- `identity` service publishes `identity.user.registered` on first login and `identity.user.profile-updated` on profile change, using the shared-kernel `EventEnvelope<T>` (ADR-03)

### Out of scope (this milestone)
- Multi-provider auth (only Google for now — GitHub/Apple/etc. deferred)
- Roles / permissions / RBAC (single-user product for now)
- Account deletion, data export, GDPR-style flows
- Email/password fallback
- Refresh-token rotation strategy beyond what Spring Security gives by default
- Profile editing UI (the user has whatever Google says they have)
- Anonymous-visitor analytics and any UX that surfaces the `PLAYGROUND_ANON` cookie. The cookie itself **is** set by the gateway in M1 (P0 — required by ADR-09) but no backend consumes it in M1 and no UI mentions it.

## 수락 기준
- [ ] User clicks "Login with Google" → completes OAuth → lands back on the app authenticated (session cookie set, frontend renders logged-in state)
- [ ] `GET /me` returns the current user's `id`, `email`, `displayName`, `avatarUrl` behind auth; 401 when unauthenticated
- [ ] First-time login creates exactly one row in `identity.users` keyed by Google `sub`; a second login with the same Google account reuses the same row and updates `lastLoginAt`
- [ ] A downstream service receiving a forwarded request can read `X-User-Id` and `X-User-Email` headers (verified with a probe endpoint in `identity` itself, e.g. `GET /identity/_debug/whoami`)
- [ ] Unauthenticated request to an **authenticated-only** route (see ADR-09 for the classification) returns `401` from the gateway, or the frontend redirects to the login flow — the behavior is consistent and documented. Public routes (`/`, `/essays/**`, `/chat`, `/metrics`, `GET /api/docs/public/**`, `GET /api/metrics/**`) MUST NOT return 401 to logged-out callers; they render normally.
- [ ] Public routes carry **no** `X-User-*` headers — `X-User-Id`, `X-User-Email`, `X-User-Sub` are **absent** (the gateway does not invent a sentinel like `anonymous`, per ADR-09 §"Anonymous identity contract"). Verified by hitting a public route through the gateway and asserting the forwarded headers do not contain any `X-User-*` key. Any client-supplied `X-User-*` header is stripped by the gateway on every request (authenticated and public) before forwarding (per ADR-07 §"Header injection contract").
- [ ] On a logged-out browser's first visit to a public page, the gateway sets a `PLAYGROUND_ANON` cookie: random UUID v4 value, no PII, `HttpOnly`, `SameSite=Lax`, `Secure` only in non-`dev` profiles, 30-day rolling expiry. Subsequent public-page visits reuse the same cookie. Backends MAY trust this value as a stable per-browser identifier for rate-limit keys / view-dedup keys (ADR-09 §"Anonymous identity contract") — `identity` itself does not consume it in M1. (Detailed cookie generation location — gateway filter vs. SecurityWebFilterChain — is an M4 per-milestone ADR concern per ADR-09; M1 only requires the cookie is present.)
- [ ] `POST /logout` invalidates the `PLAYGROUND_SESSION` cookie (Spring Session deletes the Redis-backed session per ADR-07), responds `204`, and the frontend redirects to `/` (which is public, so the user lands on the Public Home). The `identity.users` row is **preserved** — logout is a session-end event, not an account-deletion event. The `PLAYGROUND_ANON` cookie is **not** cleared on logout (its lifecycle is independent — the user becomes an anonymous reader again, not a brand-new visitor).
- [ ] `identity.user.registered` event is published to Kafka on first login, using the shared-kernel envelope (ADR-03), and is observable on the topic

## Bounded Context: Identity

- **책임 (Responsibility):** Owns the authenticated user record and the current-user lookup. The OAuth dance itself is the gateway's responsibility; the identity service exposes user CRUD + `/me`, and is the source of truth for "who is user X."
- **핵심 엔티티 (Core entity):** `User`
  - `id` (UUID, PK)
  - `googleSub` (string, unique — Google's stable subject id)
  - `email` (string, unique at the application level)
  - `displayName` (string)
  - `avatarUrl` (string, nullable)
  - `createdAt` (timestamp)
  - `lastLoginAt` (timestamp)
- **발행 이벤트 (Published events):**
  - `identity.user.registered` — emitted on first login (new row inserted). `aggregateId = user.id` (the internal UUID, used as the Kafka message key).
  - `identity.user.profile-updated` — emitted when `displayName` / `avatarUrl` / `email` change on a subsequent login. `aggregateId = user.id`.
  - **Envelope:** every event uses the shared-kernel `EventEnvelope<T>` record pinned by ADR-03 (`docs/adr/03-kafka-conventions.md`). All six envelope fields are required on the wire:
    - `eventId` — UUID v4 per message
    - `eventType` — exact topic name (e.g., `"identity.user.registered"`)
    - `occurredAt` — ISO-8601 UTC `Instant`
    - `aggregateId` — `user.id` (also the Kafka message key)
    - `schemaVersion` — starts at `1`
    - `payload` — the BC-specific record (see below)
  - **Payload shape (P0, `schemaVersion = 1`)** — fields the architect's per-milestone ADR may refine but the PRD pins minimum content for downstream consumers:
    - `identity.user.registered.payload`: `{ userId, googleSub, email, displayName, avatarUrl, registeredAt }`
    - `identity.user.profile-updated.payload`: `{ userId, changedFields: ["displayName" | "avatarUrl" | "email"], newValues: { ... }, updatedAt }`
  - **Topic naming** follows ADR-03 `<bc>.<aggregate>.<event-past-tense>`; topic partitions/retention/DLQ behavior inherits ADR-03 defaults.
- **소비 이벤트 (Consumed events):** none in M1.
- **외부 인터페이스 (Public HTTP, via gateway):**
  - `GET /me` — current user profile. Behind auth (gateway requires session per ADR-07/ADR-09; this route is not in the public allowlist).
    - **200 response body** (JSON, `application/json`):
      ```json
      {
        "id": "01HXYZABCDEF...",
        "googleSub": "1234567890",
        "email": "jeeklee1120@gmail.com",
        "displayName": "JeekLee",
        "avatarUrl": "https://lh3.googleusercontent.com/..."
      }
      ```
      `id` is the internal user UUID (matches the `X-User-Id` the gateway injects on every authenticated request — same value). `googleSub` is the immutable OIDC `sub` claim. `avatarUrl` MAY be `null` if Google did not return a picture; the frontend MUST tolerate `null` (falls back to the khaki-initials avatar per the M1 design doc's Loading/Error states).
    - **401 Unauthorized** — session cookie missing, expired, or invalid. Returned by the gateway before the request reaches `identity`. Client behavior: route to `/login` (treat as session expiry per the M1 design doc's Signed-in Home error states).
    - **5xx** — `identity` service unavailable. Client behavior: keep the account pill rendered with `JL` initials and a `·` name placeholder, surface a non-blocking `info`-chip toast `Couldn't reach /me — retry` (per the M1 design doc). Do NOT log the user out.
  - (debug, gateable) `GET /identity/_debug/whoami` — echo of forwarded headers, used to verify gateway → service header injection. Disabled outside the `dev` profile.

## Non-functional requirements

- **Single ingress:** identity service is reachable only inside the docker network. Direct external access is not a supported configuration.
- **Header trust model:** identity (and every other BC) trusts `X-User-Id` / `X-User-Email` *only because* the gateway is the only path in. This trust assumption belongs in the architect's ADR — if it changes, every BC has to change.
- **Session storage:** session implementation (cookie-only vs Redis-backed) is decided in the architect's ADR for M1; PRD only requires that sessions survive a page reload.
- **Observability:** login success / failure / first-time-registration should each produce a structured log line at INFO with the Google `sub` (not the email) for correlation.

## 우선순위

- **P0** — required for the milestone to be considered done:
  - End-to-end Google OAuth flow at the gateway (ADR-07)
  - `/me` endpoint behind auth, returning the response schema pinned in §외부 인터페이스 above
  - User record creation on first login via `POST /users/bootstrap` gateway → identity call (ADR-07 §"Bootstrapping the user record")
  - `X-User-Id` / `X-User-Email` / `X-User-Sub` header injection to downstream services **on authenticated routes**
  - **Absence of `X-User-*` headers on public routes**, plus client-supplied `X-User-*` header stripping on every request (ADR-09 §"Anonymous identity contract" + ADR-07 §"Header injection contract") — this is gateway behavior, but M1 owns the contract and the verification (it is the milestone that introduces these headers)
  - `PLAYGROUND_ANON` cookie set by the gateway on first public-page visit (UUID v4, `HttpOnly`, `SameSite=Lax`, 30-day rolling, `Secure` outside dev) — promoted to **P0** because ADR-09 makes it the required anonymous identifier for any backend that needs a rate-limit / view-dedup key (M2 onward). M1's gateway cannot ship without it; backend consumption of the cookie is not in M1.
  - `PLAYGROUND_SESSION` cookie configuration (HttpOnly, SameSite=Lax, Secure outside dev, 8h sliding) per ADR-07
  - `POST /logout` invalidates the session, preserves the user row, redirects to `/`
  - 401 behavior on unauthenticated access to protected routes; public routes never return 401 to logged-out callers
  - `identity.user.registered` event published on first login, using the ADR-03 envelope (all six fields populated)
- **P1** — nice-to-have inside this cycle, can slip to a follow-up:
  - Avatar URL caching / proxying so the frontend never hits Google directly
  - `lastLoginAt` update + `identity.user.profile-updated` event when Google-provided fields drift between logins
  - `_debug/whoami` probe endpoint exposed only under a profile flag (used to verify P0 header injection)
  - Dedicated `/me` page route (the account-footer rendering in the sidebar is the P0 path)
- **P2** — explicitly deferred (do not implement in M1):
  - Multi-provider auth
  - Role / permission model
  - Account deletion / export
  - Anonymous-visitor analytics / UX around the `PLAYGROUND_ANON` cookie (the cookie is set in M1; surfacing or consuming it is later milestones' problem)
