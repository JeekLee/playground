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

### Hosting model (single environment)

There is **no `dev` vs `prod` Spring profile split**. The service runs on one
host server; once `docker compose up` is running, the same gateway is
simultaneously reachable from `http://localhost:18080` (host-direct, for
operator debugging) and from `https://playground.jeeklee.com` (Cloudflare
Tunnel → `localhost:18080` ingress). All security defaults are therefore set
to the production posture (HTTPS, `Secure` cookies, forward-headers trusted).

`server.forward-headers-strategy=framework` is required on the gateway so the
Cloudflare-Tunnel-injected `X-Forwarded-Proto: https` and `X-Forwarded-Host:
playground.jeeklee.com` are honored. Without it, `OAuth2AuthorizationRequest`
defaults to the inbound scheme/host (`http://gateway:18080`) and the OAuth
callback redirect breaks under the tunnel.

### OAuth provider
- **Google** is the only configured provider for the foreseeable future.
- Registration ID: `google`.
- Scopes: `openid`, `profile`, `email`.
- **Authorized redirect URIs (register both in Google Cloud Console):**
  - `https://playground.jeeklee.com/login/oauth2/code/google` — public-facing through Cloudflare Tunnel.
  - `http://localhost:18080/login/oauth2/code/google` — operator-only loopback for debugging Spring Security.
- Authorized JavaScript origin: `https://playground.jeeklee.com`.
- Client ID / secret: `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`
  env vars consumed by the gateway. **Never committed.** `.env.example`
  carries empty placeholders.

### Session
- Backed by **Redis** (separate container, see below).
- Cookie name: **`PLAYGROUND_SESSION`**.
- Cookie attributes: `HttpOnly`, `SameSite=Lax`, `Secure=true` (always —
  see "Hosting model" above; the public surface is HTTPS through the
  tunnel, host-loopback debugging still works because `Secure=true` cookies
  flow on localhost in Chrome/Firefox developer mode).
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
| `/api/identity/**` | `http://identity-api:18081` |
| `/api/docs/**` | `http://docs-api:18082` |
| `/api/rag/ingest/**` | `http://rag-ingestion-api:18083` |
| `/api/rag/chat/**` | `http://rag-chat-api:18084` |
| `/api/metrics/**` | `http://metrics-api:18086` |
| `/oauth2/**`, `/login/**`, `/logout` | gateway-local (Spring Security; no forwarding) |
| `/**` | `http://frontend:3000` (Next.js SSR; compose service `frontend`) |

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
  seen, the gateway calls `POST /users/bootstrap` on `identity-api`
  (compose-internal, not exposed). `identity-api` mints the `X-User-Id` UUID,
  stores the `sub` mapping, and returns it. The gateway caches the mapping in
  Redis for the session lifetime.

## Consequences
- Positive: Backends remain trivially simple — one filter reads three headers,
  done. No JWT verification, no JWKS.
- Positive: Single point to revoke/expire sessions (delete Redis key).
- Negative: Trust boundary collapses if a backend port is ever exposed to the
  host or a service is moved out of the compose network. ADR-08 doubles down
  on this restriction.
- Negative: CSRF threading through the SPA adds a small ergonomic tax —
  acceptable.
