# ADR-07: Gateway — OAuth2 Client (Google), Session, Header Injection

## Status
Accepted

## Context
The gateway is the single ingress. It is responsible for:
- Authenticating users via Google OAuth.
- Holding a session so the SPA does not deal with tokens.
- Forwarding requests to backend BCs with a short, stable contract about *who*
  is logged in.

Backend services are not exposed to the host network; they reside on the
compose-internal network and trust headers the gateway injects.

Alternatives considered:
- JWT-bearing requests to backends (resource-server pattern) — rejected for
  simplicity: no per-service token validation, no JWKS fetching.
- Stateless gateway (no session) with tokens in client-side storage — rejected:
  exposes tokens to XSS, complicates the SPA.

## Decision

### Gateway stack
- **Spring Cloud Gateway** (reactive, built into `org.springframework.cloud:spring-cloud-starter-gateway`).
- **Spring Security OAuth2 Client**: `spring-boot-starter-oauth2-client`.
- **Spring Session** with Redis store: `spring-session-data-redis`.
- All on Spring Boot 3.3.x / JDK 21 per ADR-01.

### OAuth provider
- **Google** is the only configured provider for the foreseeable future.
- Registration ID: `google`.
- Scopes: `openid`, `profile`, `email`.
- Redirect URI (dev): `http://localhost:18080/login/oauth2/code/google`.
- Client ID / secret: from `.env`, never committed.

### Session
- Backed by **Redis** (separate container, see below).
- Cookie name: **`PLAYGROUND_SESSION`**.
- Cookie attributes: `HttpOnly`, `SameSite=Lax`, `Secure` only in non-dev
  profiles (`dev` profile uses plain HTTP on localhost).
- Session timeout: **8 hours** sliding.
- Spring Session is mandated even though we run a single gateway replica today —
  this keeps horizontal scaling cost-free if we ever fan out.

### Redis
- Image: **`redis:7.4-alpine`**.
- Compose service name: **`redis-playground`**.
- Compose-internal port: `6379`.
- Host-exposed port: **`10279`** (clears `clic-*` and the `1023x` Postgres pin).
- No persistence configured (sessions are recreatable by re-login).

### Header injection contract

After successful authentication, the gateway adds the following headers to every
forwarded request, via a `GatewayFilter`:

| Header | Source | Example |
|---|---|---|
| `X-User-Id` | internal user UUID minted by `identity` service on first login | `01HXYZABCDEF...` |
| `X-User-Email` | OIDC `email` claim | `jeeklee1120@gmail.com` |
| `X-User-Sub` | OIDC `sub` claim (Google subject ID, immutable) | `1234567890...` |

For requests to public routes (see ADR-09), these headers are **absent** — the
gateway does not invent a sentinel like `anonymous`. The gateway also strips
any client-supplied `X-User-*` headers on every request so backends never
trust attacker-injected identity.

### Routes (forwarding map)

| Pattern | Upstream |
|---|---|
| `/api/identity/**` | `http://identity:18081` |
| `/api/docs/**` | `http://docs:18082` |
| `/api/rag/ingest/**` | `http://rag-ingestion:18083` |
| `/api/rag/chat/**` | `http://rag-chat:18084` |
| `/api/metrics/**` | `http://metrics:18086` |
| `/**` | `http://client:3000` (Next.js SSR) |

Path stripping: `/api/<bc>` is stripped before forwarding so backends see
`/documents`, `/users/me`, etc.

### Route classification (auth required vs public)

The forwarding map above is **transport-level**. Which of those routes require
an authenticated session and which are public is defined in **ADR-09 (Public
Route Policy)** and MUST stay in sync with that ADR. The header-injection
rule below applies only to authenticated routes; on public routes, `X-User-*`
headers are absent (not a sentinel value).

### CSRF
- **Enabled at the gateway** for state-changing methods on `/api/**`. Token
  exposed via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header (Spring Security
  default).
- Backends are **CSRF-exempt** (they trust the gateway-injected user headers
  and accept only requests originating from inside the compose network).
- Server Actions / mutations from the Next.js client must echo the
  `XSRF-TOKEN` cookie value as `X-XSRF-TOKEN`.

### Bootstrapping the user record (M1 contract)
- On the first authenticated request from a Google `sub` the gateway has not
  seen, the gateway calls `POST /users/bootstrap` on `identity` (compose-internal,
  not exposed). `identity` mints the `X-User-Id` UUID, stores the `sub` mapping,
  and returns it. The gateway caches the mapping in Redis for the session
  lifetime.

## Consequences
- Positive: Backends remain trivially simple — one filter reads three headers,
  done. No JWT verification, no JWKS.
- Positive: Single point to revoke/expire sessions (delete Redis key).
- Negative: Trust boundary collapses if a backend port is ever exposed to the
  host or a service is moved out of the compose network. ADR-08 doubles down
  on this restriction.
- Negative: CSRF threading through the SPA adds a small ergonomic tax —
  acceptable.
