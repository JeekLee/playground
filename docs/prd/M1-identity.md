# PRD: M1 — Identity

> Note on filename: the Stage 1 prompt referenced `M1-agent-task-queue.md`, but the pinned roadmap (and the project spec at `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`) defines M1 as **Identity**. Per the Stage 1 constraint to use the milestone list verbatim, this PRD is written for M1 = Identity at the canonical path `docs/prd/M1-identity.md`.

> Note on scope (added 2026-05-16): the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` establishes the site as **mixed public/authenticated** (the home, essays, public chat, and metrics are reachable without sign-in; see ADR-09). M1's job is still to ship the OAuth flow, the `/me` endpoint, and the gateway's `X-User-*` header injection for authenticated routes — what changes is the **frontend treatment**: the home page is the public landing per the design system, not an auth-gated `/me` viewer. The `/me` payload renders in the sidebar account footer (signed-in state) and optionally on a dedicated `/me` route.

## 한 줄 설명
사용자가 Google 계정으로 게이트웨이에서 로그인하고, 모든 백엔드 서비스가 동일한 사용자 식별 정보를 받아 동작할 수 있도록 하는 인증/식별 기반을 제공한다.

## 사용자 스토리
- As a user, I want to log in via Google so that the service knows who I am without me managing yet another password.
- As a user, I want my session to persist across page reloads so that I am not asked to log in on every navigation.
- As a user, I want to fetch my own profile (`/me`) so that the frontend can render my display name and avatar.
- As a backend developer (the operator), I want every downstream service to receive a trusted `X-User-Id` header so that BCs do not each re-implement OAuth.
- As a user, I want to hit a protected resource while logged out and be cleanly redirected/refused so that the auth boundary is obvious.

## 기능 범위

### In scope
- Google OAuth 2.0 Authorization Code flow handled at the gateway (Spring Cloud Gateway + Spring Security OAuth2 Client)
- Session cookie issued on successful auth (HttpOnly, Secure-where-applicable, SameSite policy decided in the architect's ADR)
- `/me` endpoint returning the current user (id, email, displayName, avatarUrl)
- `identity` service stores a user record on first login (`users` table in its own schema)
- `identity` service updates `lastLoginAt` on subsequent logins
- Gateway injects `X-User-Id` and `X-User-Email` headers on every downstream request; backend services trust these headers because the gateway is the only ingress
- `identity` service publishes `identity.user.registered` on first login and `identity.user.profile-updated` on profile change, using the shared-kernel event envelope

### Out of scope (this milestone)
- Multi-provider auth (only Google for now — GitHub/Apple/etc. deferred)
- Roles / permissions / RBAC (single-user product for now)
- Account deletion, data export, GDPR-style flows
- Email/password fallback
- Refresh-token rotation strategy beyond what Spring Security gives by default
- Profile editing UI (the user has whatever Google says they have)
- Anonymous-visitor analytics, anonymous-cookie management UX — anonymous cookie itself is set by the gateway (ADR-09) but M1 does not surface it

## 수락 기준
- [ ] User clicks "Login with Google" → completes OAuth → lands back on the app authenticated (session cookie set, frontend renders logged-in state)
- [ ] `GET /me` returns the current user's `id`, `email`, `displayName`, `avatarUrl` behind auth; 401 when unauthenticated
- [ ] First-time login creates exactly one row in `identity.users` keyed by Google `sub`; a second login with the same Google account reuses the same row and updates `lastLoginAt`
- [ ] A downstream service receiving a forwarded request can read `X-User-Id` and `X-User-Email` headers (verified with a probe endpoint in `identity` itself, e.g. `GET /identity/_debug/whoami`)
- [ ] Unauthenticated request to an **authenticated-only** route (see ADR-09 for the classification) returns `401` from the gateway, or the frontend redirects to the login flow — the behavior is consistent and documented. Public routes (`/`, `/essays/**`, `/chat`, `/metrics`, `GET /api/docs/public/**`, `GET /api/metrics/**`) MUST NOT return 401 to logged-out callers; they render normally.
- [ ] `identity.user.registered` event is published to Kafka on first login, using the shared-kernel envelope, and is observable on the topic

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
  - `identity.user.registered` — emitted on first login (new row inserted)
  - `identity.user.profile-updated` — emitted when `displayName` / `avatarUrl` / `email` change on a subsequent login
  - Envelope schema follows the shared-kernel contract pinned in the architect's transverse ADR for events.
- **소비 이벤트 (Consumed events):** none in M1.
- **외부 인터페이스 (Public HTTP, via gateway):**
  - `GET /me` — current user profile
  - (debug, gateable) `GET /identity/_debug/whoami` — echo of forwarded headers, used to verify gateway → service header injection

## Non-functional requirements

- **Single ingress:** identity service is reachable only inside the docker network. Direct external access is not a supported configuration.
- **Header trust model:** identity (and every other BC) trusts `X-User-Id` / `X-User-Email` *only because* the gateway is the only path in. This trust assumption belongs in the architect's ADR — if it changes, every BC has to change.
- **Session storage:** session implementation (cookie-only vs Redis-backed) is decided in the architect's ADR for M1; PRD only requires that sessions survive a page reload.
- **Observability:** login success / failure / first-time-registration should each produce a structured log line at INFO with the Google `sub` (not the email) for correlation.

## 우선순위

- **P0** — required for the milestone to be considered done:
  - End-to-end Google OAuth flow at the gateway
  - `/me` endpoint behind auth
  - User record creation on first login
  - `X-User-Id` / `X-User-Email` header injection to downstream services
  - 401 behavior on unauthenticated access to protected routes
- **P1** — nice-to-have inside this cycle, can slip to a follow-up:
  - Avatar URL caching / proxying so the frontend never hits Google directly
  - `lastLoginAt` update + `identity.user.profile-updated` event when Google-provided fields drift
  - `_debug/whoami` probe endpoint exposed only under a profile flag
- **P2** — explicitly deferred (do not implement in M1):
  - Multi-provider auth
  - Role / permission model
  - Account deletion / export
