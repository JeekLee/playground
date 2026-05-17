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
| `GET /api/docs/public/**` | public | Read-only access to documents the author marked public. |
| `POST /api/rag/chat/public` | public | Anonymous RAG chat against the public corpus only. |
| `GET /api/metrics/**` | public | Read-only system status. Polling endpoint; no mutation. |
| `/api/identity/**`, `/api/docs/mine/**`, `/api/docs/{id}` (write methods), `/api/rag/chat/private`, `/api/users/me`, `POST/PUT/DELETE /api/docs/**` | **authenticated** | Default. Anything that mutates user-owned data or reveals private content. |
| `/oauth2/**`, `/login/**`, `/logout` | system | Owned by Spring Security; not classified. |

The allowlist lives in the gateway's `SecurityWebFilterChain` config. Adding a public route is an explicit ADR change, not a code-only change — write a superseding ADR or a per-milestone ADR that supersedes the relevant row.

### Anonymous identity contract

Public routes receive an **absent** `X-User-Id` header — the gateway does not invent a sentinel value. Backends MUST treat absence as "anonymous reader" and MUST NOT crash on missing user. The rationale: a sentinel like `anonymous` would create a real-looking user id in logs/analytics that doesn't exist in `identity.users`, polluting joins.

`X-User-Email` and `X-User-Sub` are similarly absent on public routes.

Backends needing rate-limit keys for anonymous traffic use the gateway-injected `X-Forwarded-For` IP (already present from Spring Cloud Gateway's default forwarding) plus, for browser sessions, an anonymous cookie `PLAYGROUND_ANON` (UUID, set on first public-page visit, no PII, 30-day rolling expiry, `HttpOnly`, `Secure=true`, `SameSite=Lax`). The `Secure=true` setting matches ADR-07 §"Hosting model" — the public surface is HTTPS through Cloudflare Tunnel; `Secure` cookies still flow on localhost in modern browsers for operator debugging.

### Rate-limit and cost protection (public RAG chat)

Public RAG chat dispatches against `spark-inference-gateway` (Qwen3-32B generation + BGE-M3 retrieval), which is real compute. The gateway enforces:

- **Per-IP burst limit:** 10 chat completions / 5 minutes, then 429.
- **Per-anon-cookie burst limit:** 30 chat completions / day, then 429.
- **Global circuit breaker:** if `spark-inference-gateway` returns 5xx on >50% of public-chat requests in the last 60 seconds, public chat returns a 503 with a friendly "the model is resting — try a logged-in chat" message; logged-in chat keeps working until the breaker opens for it too.
- **Token cap per completion:** public chat is capped at `max_tokens=512` and one retrieved chunk window (no multi-turn context for anonymous sessions). Logged-in chat has higher limits.

Exact algorithm (token bucket vs sliding window) and the breaker library belong to M4's per-milestone ADR. This ADR fixes only the numbers and the principle.

### Public retrieval scoping

Public RAG chat retrieves **only** against `docs.documents` rows where `visibility = 'public'`. Private documents are stored in the same pgvector table but their chunks are excluded by a `WHERE visibility = 'public'` predicate added to every public-route retrieval query.

The `visibility` column is owned by **M2 (the Docs BC)** and lives in the `docs` schema. The `rag-ingestion` service (M3) does not own the column; it consumes the `docs.document.visibility-changed` Kafka event and re-tags its chunks accordingly. Chunks inherit the parent document's visibility at ingestion time.

Default visibility on document creation is `private`. The author publishes by an explicit toggle.

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
