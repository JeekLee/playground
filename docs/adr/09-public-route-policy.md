# ADR-09: Public Route Policy — Mixed Public / Authenticated Site

## Status
Accepted

## Context
The design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` §2.4 and §11 establishes that JeekLee's playground is a **dual-mode site**: parts of it (documents, public RAG chat, system metrics, the home landing) serve logged-out visitors as first-class readers, while parts of it (document authoring, private documents, private chats, `/me`) require Google sign-in.

ADR-07 originally treated the gateway as an OAuth-only ingress and assumed every `/api/**` path required authentication, with backends trusting injected `X-User-Id`/`X-User-Email` headers. That assumption no longer holds. This ADR pins the new policy.

Alternatives considered:
- **Two separate sites** (a marketing `playground.com` and an authenticated `app.playground.com`) — rejected: doubles infra, fractures the brand, and the design system's whole point is one tonal place.
- **All-private with a marketing-only marketing site** — rejected: public RAG chat is the most distinctive surface and losing the no-sign-in entry kills its value.
- **Per-BC public-route declaration** — rejected: scatters the policy; gateway is the only place every request flows through, so the allowlist lives there.

## Decision

### Route classification (the allowlist)

The gateway maintains a single allowlist of **public** route patterns. Everything else requires an authenticated session (per ADR-07).

| Pattern | Class | Reason |
|---|---|---|
| `/` and any non-`/api` SSR route the client serves as a public page | public | The home, documents index, individual document pages, public chat page, and metrics page are reachable without sign-in. |
| `GET /api/docs/public/**` | public | Read-only access to documents the author marked public. (Note: superseded by ADR-12 amendment — namespace flattened to `/api/docs/**`.) |
| `GET /api/metrics/**` | public | Read-only system status. Polling endpoint; no mutation. |
| `/api/identity/**`, `/api/docs/mine/**`, `/api/docs/{id}` (write methods), `/api/users/me`, `POST/PUT/DELETE /api/docs/**`, `POST /api/rag/chat`, `/api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/*/messages` | **authenticated** | Default. Anything that mutates user-owned data, reveals private content, OR streams LLM completions. RAG chat (the M4 single endpoint + session CRUD) is auth-only per ADR-14 amendment; anonymous callers receive 401. |
| `/oauth2/**`, `/login/**`, `/logout` | system | Owned by Spring Security; not classified. |

The allowlist lives in the gateway's `SecurityWebFilterChain` config. Adding a public route is an explicit ADR change, not a code-only change — write a superseding ADR or a per-milestone ADR that supersedes the relevant row.

### Anonymous identity contract

Public routes receive an **absent** `X-User-Id` header — the gateway does not invent a sentinel value. Backends MUST treat absence as "anonymous reader" and MUST NOT crash on missing user. The rationale: a sentinel like `anonymous` would create a real-looking user id in logs/analytics that doesn't exist in `identity.users`, polluting joins.

`X-User-Email` and `X-User-Sub` are similarly absent on public routes.

Backends needing rate-limit keys for anonymous traffic use the gateway-injected `X-Forwarded-For` IP (already present from Spring Cloud Gateway's default forwarding) plus, for browser sessions, an anonymous cookie `PLAYGROUND_ANON` (UUID, set on first public-page visit, no PII, 30-day rolling expiry, `HttpOnly`, `Secure=true`, `SameSite=Lax`). The `Secure=true` setting matches ADR-07 §"Hosting model" — the public surface is HTTPS through Cloudflare Tunnel; `Secure` cookies still flow on localhost in modern browsers for operator debugging.

### Rate-limit and cost protection (RAG chat — authenticated)

> **Re-scoped 2026-05-18 by ADR-14.** Originally this section governed anonymous public RAG chat with per-IP + per-anon-cookie caps and a `max_tokens=512` ceiling. The anonymous chat surface is permanently removed (the `POST /api/rag/chat/public` allowlist row is deleted; chat is auth-only). Rate-limit numbers below are per-authenticated-user, not per-IP.

RAG chat dispatches against `spark-inference-gateway` (Qwen3-32B generation + BGE-M3 retrieval), which is real compute. The cost protection enforced by `rag-chat-api` (not the gateway — the gateway only sees the auth header; the bucket is per-user) is:

- **Per-user token bucket:** 60 chat completions / hour / user AND 200 chat completions / day / user. Both buckets must have capacity for the turn to proceed; whichever depletes first → 429 with `Retry-After` set to the smaller refill ETA. Backing: Redisson `RRateLimiter`, key `rag-chat:bucket:user:{userId}:{hourly|daily}` (per ADR-14 §5).
- **Per-completion token cap:** Spring AI option `max_tokens=4000` (per ADR-14 §8). The retrieval slice is K=6 chunks (per ADR-14 §7).
- **Global circuit breaker:** Resilience4j 2.2.x `CircuitBreaker` named `spark-gateway`, shared by the `ChatClient` and `EmbeddingModel` adapters. Failure rate threshold 50% over a 60-second sliding window, minimum 10 calls, OPEN duration 30s, 1 HALF_OPEN probe (per ADR-14 §4). When OPEN, chat returns a 503 with a friendly "AI service is currently unavailable" body and `retryAfterSeconds: 30`.
- **Per-user concurrent stream cap:** 1. A second concurrent `POST /api/rag/chat` from the same user aborts the first (latest-wins) via a Redisson `RLock` keyed `rag-chat:lock:user:{userId}` with 35s TTL (per ADR-14 §G).
- **No per-IP rate limit** for authenticated chat — the per-user bucket is the relevant denominator. The M2 per-IP rate limits (ADR-12 §7's anonymous-read caps) remain in force for their own public surfaces (`GET /api/docs`, `GET /api/docs/search`, etc.).

Exact library / algorithm pins are in ADR-14 (Resilience4j 2.2 + Redisson 3.34). This section fixes the numbers, the principle, and the supersession of the original anonymous-chat framing.

### Public retrieval scoping

> **Re-scoped 2026-05-18 by ADR-14.** This section originally described an anonymous public RAG chat retrieving against `visibility='public'` chunks. With chat being auth-only post-M4, the scoping rule moves to the authenticated path:
>
> Authenticated RAG chat retrieves against `docs.documents` rows where `visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`. Other users' private docs are never visible to any caller.

The `visibility` column is owned by **M2 (the Docs BC)** and lives in the `docs` schema. The `rag-ingestion` service (M3) does not own the column; it consumes the `docs.document.visibility-changed` Kafka event and re-tags its chunks accordingly. Chunks inherit the parent document's visibility at ingestion time.

Default visibility on document creation is `private`. The author publishes by an explicit toggle.

The same `visibility = 'public'` predicate is still applied at the M2 public-read paths (`GET /api/docs`, `GET /api/docs/{id}`, `GET /api/docs/search?scope=public`) and at the M2 OpenSearch projector — those surfaces remain anonymous-OK per ADR-12 amendment.

## Consequences
- Positive: Backends keep ADR-07's "trust the header" simplicity for authenticated routes; the only new contract is "header may be absent, handle it."
- Positive: The cost-of-anonymous-chat is bounded by policy, not by hope.
- Positive: Adding/removing a public route is a visible ADR-level event — no silent permission creep.
- Negative: Doubles the test matrix for the rag-chat BC (public path + private path). Acceptable.
- Negative: Introduces an anonymous-cookie surface that the GDPR-future-self may need to reconsider. Acceptable for a personal project; flag if the audience scope ever grows.
- Negative: The metrics endpoint being public exposes infra health information. We accept this — it's a feature, not a leak.

## Diagrams
None. The allowlist table above is the diagram.

## Related
- ADR-07 (Gateway OAuth) — auth path, header injection
- Design system spec §2.4, §9, §11
- Future M2 per-milestone ADR — `visibility` column + migration
- Future M4 per-milestone ADR — concrete rate-limit algorithm, circuit-breaker library, and `PLAYGROUND_ANON` cookie attributes (domain, SameSite, HttpOnly, generation location)

## Amendment (2026-05-17, ADR-12)

ADR-12 (M2 Docs per-milestone) supersedes the **`/api/docs/public/**`** row in
the allowlist table above. The M2 spec v5 flattens the docs API namespace —
the `/public` prefix is **removed**, and per-route visibility gating moves
into the docs service.

### New public allowlist rows (post-ADR-12)

| Pattern | Class | Reason |
|---|---|---|
| `GET /api/docs` | public | Owner-filtered public feed (visibility=public AND user_id=owner). |
| `GET /api/docs/{id}` | public | Single document; **per-doc visibility-OR-ownership gate enforced at the docs service** — returns 404 for private docs the caller doesn't own (indistinguishable from missing). |
| `POST /api/docs/{id}/view` | public | View-counter increment; anonymous OK; 24h Redis dedup per `PLAYGROUND_ANON` cookie or `X-Forwarded-For` IP. |
| `GET /api/docs/search?scope=public` | public | OpenSearch-backed full-text search over the owner-filtered public corpus. |
| `~~GET /api/docs/public/**~~` | (removed) | Superseded by the four rows above. The `/public` prefix is dropped in M2 spec v5. |

### Per-IP rate limits (anonymous reads)

ADR-09's original §"Rate-limit and cost protection" section pinned numbers
for **public RAG chat** (M4) but not for **public document reads** (M2).
ADR-12 §7 fills that gap:

| Route | Cap | Window |
|---|---|---|
| `GET /api/docs` (list, anonymous) | 60 requests | per minute |
| `GET /api/docs/{id}` (detail, anonymous) | 60 requests | per minute |
| `POST /api/docs/{id}/view` (anonymous) | 30 requests | per minute |
| `GET /api/docs/search?scope=public` (anonymous) | 10 requests | per minute |

Key: per-IP (`X-Forwarded-For` from Cloudflare Tunnel; fallback to the
gateway's `RemoteAddr`). The `PLAYGROUND_ANON` cookie is **not** the rate
key — anonymous cookies are trivially deleted and therefore useless as a
cap denominator.

If the M4 rate-limit filter (the shared Bucket4j-on-Redis middleware ADR-09
defers to M4) has not yet shipped at M2 closure, M2 may stub the filter to
a no-op; M4 retro-fits enforcement on these numbers without re-litigating
the cap policy.

### Internal route — explicitly NOT in the allowlist

The boot-time owner-lookup route `GET /internal/users/by-google-sub/{sub}`
on `identity-api` (introduced in ADR-12 §8 / ADR-08 amendment) lives under
the `/internal/**` prefix and is **not** routable through the gateway. It is
reachable only on the compose-internal network. The allowlist above
governs **gateway-exposed** routes only; `/internal/**` is gateway-invisible
by construction.

### Public retrieval correctness — unchanged

ADR-09's "Public retrieval scoping" rule (public RAG chat retrieves only
against `visibility='public'` chunks) remains in force. The Docs BC enforces
the same predicate at every `/api/docs/**` read path; integration tests are
mandatory per M2 spec §10.

See `docs/adr/12-m2-docs.md` §7 + amendment block for the full
specification.

## Amendment (2026-05-18, ADR-14)

ADR-14 (M4 RAG-Chat per-milestone) narrows the chat surface to
**authenticated callers only**. This amendment composes with the prior
ADR-12 amendment additively — the M2 `/api/docs/**` allowlist rows and
the per-IP read caps remain in force; only the chat-related rows are
changed.

### Route classification — revised (post-ADR-14)

The row `| POST /api/rag/chat/public | public | Anonymous RAG chat against the public corpus only. |` is **removed permanently**. The single `POST /api/rag/chat` endpoint moves to the authenticated section:

| Pattern | Class | Reason |
|---|---|---|
| `POST /api/rag/chat`, `/api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/*/messages` | **authenticated** | RAG chat (single streaming endpoint + session CRUD). Anonymous callers receive 401 at the gateway. Per spec §5.1 + §5.3 and ADR-14 §C. |

### Rate-limit and cost protection — re-scoped (post-ADR-14)

The §"Rate-limit and cost protection" section above (renamed from "(public RAG chat)" to "(RAG chat — authenticated)") is the canonical statement. Summary of the new numbers:

- Per-user token bucket: 60/hour + 200/day (Redisson `RRateLimiter`, key `rag-chat:bucket:user:{userId}:{hourly|daily}`).
- Per-completion cap: `max_tokens=4000`, K=6.
- Circuit breaker: Resilience4j 2.2, name `spark-gateway`, 50% failure rate over 60s sliding window, OPEN 30s.
- Per-user concurrent stream cap: 1 (Redisson `RLock`, key `rag-chat:lock:user:{userId}`, 35s TTL, latest-wins).

The original anonymous-chat numbers (per-IP 10/5min, per-anon-cookie 30/day, `max_tokens=512`, K=1) are **permanently deleted** — no anonymous chat surface exists.

### Auth-lock vs milestone-lock badge convention

The sidebar `Apps` rows render with three distinct lock states. ADR-14 introduces the third (auth-lock) badge; the existing two are documented here for completeness so the frontend has a single canonical reference.

| Badge | When | Click behavior | Visual |
|---|---|---|---|
| (none) | The corresponding milestone has shipped AND the route is reachable for the current session | Navigates to the route | Active text, no badge |
| `🔒 Mx` (milestone-lock) | The milestone has NOT yet shipped (e.g., `🔒 M5` for Metrics before M5 ships) | No-op — click does nothing; hover tooltip "Coming in Mx" | Muted text |
| **`🔒 Sign in` (auth-lock)** | The milestone has shipped BUT the current session is anonymous AND the route is auth-only | Navigates to `/login?return=<target>` | Muted text + sign-in badge |

The two lock states are **mutually exclusive per row at any moment**:

- **Milestone-lock** = "no route exists" (the BC has not deployed yet — the click is a no-op because there is nothing to route to).
- **Auth-lock** = "route exists but you're anonymous" (the BC has deployed but only serves authenticated callers — the click goes to `/login` to lift the auth barrier).
- A fully-accessible route (shipped + reachable) shows no badge.

The frontend sidebar component derives the state from `(milestone shipped?, session authenticated?, route auth-required?)`. M4 ships the auth-lock visual for the `Chat` row in two states (`🔒 Sign in` when anon, no badge when signed in); future auth-only routes (e.g., M6+ Agents) reuse the same badge.

**Forward note — M5 Metrics is unaffected.** M5's `GET /api/metrics/**` remains in the public allowlist; the auth-lock convention does not apply to it. The convention is route-by-route, not row-by-row.

### Internal route — unchanged

The boot-time owner-lookup route on `identity-api` (`/internal/users/by-google-sub/{sub}` per ADR-12 §8 / ADR-08 amendment) is unaffected — still `/internal/**`, still gateway-invisible.

### Anonymous identity contract — narrowed, not removed

The `PLAYGROUND_ANON` cookie + per-IP fallback for anonymous identity (ADR-09 original §"Anonymous identity contract") still governs the remaining public routes: `/`, `GET /api/docs`, `GET /api/docs/{id}`, `POST /api/docs/{id}/view`, `GET /api/docs/search?scope=public`, `GET /api/metrics/**` when shipped. Chat is the only route where anonymous = 401; everything else still treats `X-User-Id` as optionally absent.

See `docs/adr/14-m4-rag-chat.md` §C + §G.4 and `docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md` §7.6 + §8 for the full specification.

## Amendment (2026-05-19, ADR-15)

ADR-15 (M5 Metrics per-milestone) splits the M5 surface into a public
dashboard + authenticated logs endpoint. The existing public
`GET /api/metrics/**` row STAYS — it continues to cover the dashboard
(`/api/metrics/dashboard`), services (`/api/metrics/services`), and
timeseries (`/api/metrics/timeseries`) routes. A new authenticated row
is added for `/api/metrics/logs/**`. The most-specific-match rule means
the new logs row wins for `/api/metrics/logs/**` without removing the
public row that still governs everything else under `/api/metrics/**`.

### Route classification — new row (post-ADR-15)

The authenticated section gains:

| Pattern | Class | Reason |
|---|---|---|
| `GET /api/metrics/logs/**` | **authenticated** | Logs may surface user PII, error stack traces, or content not appropriate for anon viewers. Per-user rate-limit 60/min via Redisson `RRateLimiter`. |

The existing public `GET /api/metrics/**` row is **unchanged in
wording**; its scope is now interpreted as "anything under
`/api/metrics/**` that isn't matched by a more-specific authenticated
row" per the most-specific-match-wins rule.

### Per-user rate limit on the logs endpoint

The `/api/metrics/logs/**` row carries a per-user rate limit of **60
requests/min/user**, enforced by `metrics-api` (not the gateway — the
gateway only sees the auth header; the bucket is per-user). Backing:
Redisson `RRateLimiter`, key `metrics:bucket:user:{userId}:logs` (per
ADR-15 §C + §18). The existing per-IP cap on `/api/metrics/dashboard`
(30/min/IP, per spec §8.2 and ADR-15 §18) is a separate denominator
and applies to the public route.

### Anonymous identity contract — unchanged

ADR-09's original §"Anonymous identity contract" remains in force.
`/api/metrics/dashboard`, `/api/metrics/services`,
`/api/metrics/timeseries` are anonymous-OK — `X-User-Id` may be absent,
and the metrics BC does not consult it for those routes (the dashboard
payload has no user-scope component). The `PLAYGROUND_ANON` cookie is
not consulted by the metrics BC; per-IP rate limiting on `/dashboard`
uses `X-Forwarded-For` directly (mirrors ADR-12 §7's anonymous-read
pattern).

### Auth-lock badge — does NOT apply to `/metrics`

ADR-14's auth-lock badge convention (`🔒 Sign in` for shipped-but-anon-locked
routes) does **not** apply to the sidebar `System status` row.
`/metrics` is public; the row goes from `🔒 M5` (milestone-lock,
pre-ship) directly to no-badge (active, unconditional click →
`/metrics`) when M5 ships. There is no middle state. This matches
ADR-14's forward note "M5 Metrics is unaffected".

See `docs/adr/15-m5-metrics.md` §14 + §18 + §C + §G.2 for the full
specification.
