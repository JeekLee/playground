# ADR-10: M1 Identity — Implementation Decisions

## Status
Accepted

## Context

ADR-07 (Gateway OAuth) and ADR-09 (Public Route Policy) fix the high-level
posture for authentication and the public/authenticated route split, but they
leave Stage-3-buildable details deliberately blank — exact library versions on
top of Spring Boot, gateway filter ordering, the bootstrap call mechanics, the
`PLAYGROUND_ANON` cookie lifecycle, the `identity` schema shape, and which
outbox approach the project commits to for event emission. ADR-05 fixed the
Postgres image and the `identity` schema as the namespace, but did not pin the
`users` columns. ADR-08 permits gateway → identity internal HTTP but does not
specify retry/timeout discipline. ADR-09 §"Anonymous identity contract" pins
the `PLAYGROUND_ANON` cookie's existence and 30-day rolling expiry but defers
generation location (gateway filter vs `SecurityWebFilterChain`) and attribute
detail to a per-milestone ADR.

This ADR closes those holes for M1 (Identity) so backend-implementer,
infra-engineer, and code-reviewer have one document to read before Stage 3.
None of the decisions below supersede a transverse ADR; they fill in
implementation details inside the envelopes ADR-05, ADR-07, ADR-08, and ADR-09
defined.

## Decision

### 1. Library versions + dependency map

The `gateway` module pulls the following coordinates on top of the Spring Boot
3.3.x BOM (exact patch pinned in the root version catalog — same patch as the
rest of the modules per ADR-01) and the Spring Cloud 2023.0.x BOM:

| Coordinate | Version source | Why |
|---|---|---|
| `org.springframework.cloud:spring-cloud-starter-gateway` | Spring Cloud 2023.0.x BOM | Reactive ingress, route table |
| `org.springframework.boot:spring-boot-starter-oauth2-client` | Spring Boot 3.3.x BOM | Google OAuth2 Authorization Code |
| `org.springframework.boot:spring-boot-starter-security` | Spring Boot 3.3.x BOM | `SecurityWebFilterChain` DSL |
| `org.springframework.session:spring-session-data-redis` | Spring Boot 3.3.x BOM | `PLAYGROUND_SESSION` cookie backed by Redis |
| `org.springframework.boot:spring-boot-starter-data-redis-reactive` | Spring Boot 3.3.x BOM | Reactive Redis client for the bootstrap-mapping cache and Spring Session |
| `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot 3.3.x BOM | `/actuator/health` for compose healthcheck (ADR-08) |
| `org.springframework.boot:spring-boot-starter-webflux` (transitive via gateway) | Spring Boot 3.3.x BOM | Reactive stack — required by Spring Cloud Gateway |

The `identity` module pulls:

| Coordinate | Version source | Why |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-web` | Spring Boot 3.3.x BOM | Servlet-stack controllers (`POST /users/bootstrap`, `GET /me`) — identity has no reactive requirement |
| `org.springframework.boot:spring-boot-starter-data-jpa` | Spring Boot 3.3.x BOM | `identity.users` aggregate persistence |
| `org.postgresql:postgresql` | Spring Boot 3.3.x BOM | JDBC driver for `postgres-playground` (ADR-05) |
| `org.flywaydb:flyway-core` | Spring Boot 3.3.x BOM | Per-service migrations (ADR-05) |
| `org.springframework.kafka:spring-kafka` | Spring Boot 3.3.x BOM | `identity.user.registered` / `identity.user.profile-updated` producer (ADR-03) |
| `org.springframework.modulith:spring-modulith-events-jpa` | Spring Modulith **1.2.x** (latest 1.x line aligned with Spring Boot 3.3) | Transactional outbox table (see §8) |
| `org.springframework.modulith:spring-modulith-events-kafka` | same | Bridges modulith event publication to Spring Kafka |
| `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot 3.3.x BOM | `/actuator/health` for compose healthcheck |

Spring AI 1.0.0 GA (pinned in ADR-04 for chat/embedding) is **not** a
dependency of `gateway` or `identity` — M1 does not touch the LLM gateway, so
Spring AI's reactive dependency tree does not conflict with the Spring Cloud
Gateway reactive stack here.

### 2. Gateway filter chain ordering

The gateway runs the following ordered pipeline. Numeric `Ordered` values are
indicative — the contract is the *relative* sequence, not the literal numbers.

| # | Stage | Implementation | Notes |
|---|---|---|---|
| 1 | Strip client-supplied `X-User-*` headers | A `GlobalFilter` (`StripUserHeadersFilter implements GlobalFilter, Ordered`) at order `Ordered.HIGHEST_PRECEDENCE` — `exchange.getRequest().mutate().headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Email"); h.remove("X-User-Sub"); }).build()` | Runs before everything else (auth, public-route check). ADR-07 mandate. Applies to **all** requests, authenticated and public. |
| 2 | OAuth2 authentication | `ServerHttpSecurity` default — `oauth2Login()` plus `oauth2Client()` from `spring-boot-starter-oauth2-client`. Spring Security places its `AuthenticationWebFilter` at its own fixed position in the WebFlux chain. | Drives the Google redirect on protected routes; transparent on public routes (see step 3). |
| 3 | Public-route allowlist | `SecurityWebFilterChain` config (see §3 below) — `.authorizeExchange { ... .pathMatchers(...).permitAll(); .anyExchange().authenticated() }` | Implemented as Spring Security's standard `AuthorizationWebFilter` — public-route rules `permitAll()`, everything else `authenticated()`. Anonymous traffic passes through here without redirect. |
| 4 | `POST /users/bootstrap` on first-seen Google `sub` | A custom `GlobalFilter` (`UserBootstrapFilter`) ordered *after* Spring Security's authentication filter but *before* the header-injection filter (step 5). Reads `ReactiveSecurityContextHolder.getContext().map(ctx -> ctx.getAuthentication())`; if authenticated and the Google `sub` is not present in the Redis cache, calls `POST http://identity-api:18081/users/bootstrap` via `WebClient`, caches the resulting `userId`. See §4. | Skipped for unauthenticated traffic. |
| 5 | Inject `X-User-Id`, `X-User-Email`, `X-User-Sub` | A `GlobalFilter` (`UserHeaderInjectionFilter`) ordered immediately before the gateway's `NettyRoutingFilter`. Reads `OAuth2User` from the security context and the cached `userId` from step 4; sets the three headers via `exchange.getRequest().mutate().headers(...)`. | Only runs when an authentication is present. On public routes the security context is anonymous and this filter no-ops — headers are absent, per ADR-09. |
| 6 | Forward to upstream | Spring Cloud Gateway's built-in `NettyRoutingFilter` at `Ordered.LOWEST_PRECEDENCE - 1`. | Routes per ADR-07's forwarding map. |

The `PLAYGROUND_ANON` cookie management filter (§6) lives in parallel with
steps 1–5 — it is a separate `WebFilter` (not a `GlobalFilter`) at
`Ordered.HIGHEST_PRECEDENCE + 1` (after the strip-user-headers filter, before
Spring Security) and short-circuits to a no-op for authenticated requests.

### 3. Public-route allowlist as code

The allowlist from ADR-09 maps to a single `SecurityWebFilterChain` bean.
Spec sketch (Java; Kotlin DSL equivalent acceptable):

```java
@Bean
SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
  return http
    .csrf(csrf -> csrf
      .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
      // CSRF applies only to state-changing methods on /api/** — default behavior
    )
    .authorizeExchange(ex -> ex
      // System routes Spring Security owns
      .pathMatchers("/oauth2/**", "/login/**", "/logout").permitAll()
      // Public SSR routes — Next.js owns the rendering, gateway permits the proxy
      .pathMatchers(HttpMethod.GET, "/", "/docs/public/**", "/chat", "/metrics").permitAll()
      // Public API routes per ADR-09's allowlist table
      .pathMatchers(HttpMethod.GET, "/api/docs/public/**").permitAll()
      .pathMatchers(HttpMethod.POST, "/api/rag/chat/public").permitAll()
      .pathMatchers(HttpMethod.GET, "/api/metrics/**").permitAll()
      // Next.js static assets (no PII, must be cacheable without session)
      .pathMatchers("/_next/**", "/favicon.ico", "/static/**").permitAll()
      // Default: authenticated. Anything not listed above requires a session.
      .anyExchange().authenticated()
    )
    .oauth2Login(Customizer.withDefaults())
    .logout(logout -> logout.logoutUrl("/logout"))
    .exceptionHandling(eh -> eh
      // For API routes, return 401 JSON; for SSR routes, redirect to /login
      .authenticationEntryPoint(new DelegatingServerAuthenticationEntryPoint(
        new ServerWebExchangeMatcherEntryPointMapping(
          ServerWebExchangeMatchers.pathMatchers("/api/**"),
          new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)),
        new ServerWebExchangeMatcherEntryPointMapping(
          ServerWebExchangeMatchers.anyExchange(),
          new RedirectServerAuthenticationEntryPoint("/login"))
      ))
    )
    .build();
}
```

Adding a public route is an ADR-09 change — code-reviewer rejects PRs that
extend the `permitAll()` list without a superseding ADR row.

### 4. `POST /users/bootstrap` mechanics

Path: `POST /users/bootstrap` on `identity` (compose-internal URL
`http://identity-api:18081/users/bootstrap`, **not** routed through any
gateway-exposed prefix — the gateway uses its own `WebClient` bean keyed on
this URL, not the public route table).

Request body (JSON):
```json
{
  "googleSub": "1234567890",
  "email": "jeeklee1120@gmail.com",
  "displayName": "JeekLee",
  "avatarUrl": "https://lh3.googleusercontent.com/..."
}
```

Response body (JSON, 200 or 201):
```json
{ "id": "01HXYZABCDEF..." }
```

The `id` is the internal `identity.users.id` UUID, which becomes the
`X-User-Id` value the gateway injects on every subsequent forwarded request
from this session.

Reliability discipline:
- **Timeout:** 1 second per attempt (`WebClient` `responseTimeout`).
- **Retries:** up to 3 attempts, exponential backoff with jitter (100ms,
  200ms, 400ms base; `Retry.backoff(3, Duration.ofMillis(100)).jitter(0.5)`).
- **Failure:** if all attempts fail, the gateway returns **HTTP 502** to the
  browser with body `{"error":"identity_unavailable"}`. The user can retry
  by reloading. The session cookie is **not** invalidated — a transient
  identity outage does not log the user out.
- **Idempotency:** `identity`'s handler treats the call as upsert-by-`googleSub`.
  Repeated calls with the same `googleSub` return the same `id` (handler
  detail in §7).

Cache key in `redis-playground`:
- Key format: `bootstrap:google-sub:{sub}` (where `{sub}` is the OIDC `sub`
  string verbatim — Google subs are numeric strings ≤ 21 chars, no escaping
  needed).
- Value: the `id` UUID returned by `identity`.
- TTL: 8 hours (matches ADR-07's session sliding window). On every cache hit
  the TTL is refreshed (`SET ... EX 28800`) so a long-lived session keeps the
  mapping warm without re-bootstrapping.
- Cache miss path: call `POST /users/bootstrap`, store the result, proceed.

### 5. Saved-request behavior

Spring Security's `WebSessionServerRequestCache` default is used **unchanged**.
It stores the pre-auth request URL in the WebSession (which is itself backed
by Redis via Spring Session — so saved requests survive the redirect dance
across gateway replicas if we ever fan out).

Concretely:
- Logged-out user hits `/me` (authenticated route) → `RedirectServerAuthenticationEntryPoint`
  fires (see §3) → user lands on `/login` → clicks `Continue with Google` → OAuth
  round-trip → on callback, Spring Security restores the saved request and
  302s to `/me`.
- Logged-out user hits a public route → no saved request is ever stored
  (because `permitAll()` short-circuits before the entry point). If the user
  then clicks `Sign in with Google` from the public home, the post-auth
  redirect target is `/` (Spring Security's default success URL when no saved
  request exists). The frontend-implementer relies on this — no custom
  `ServerAuthenticationSuccessHandler` is configured for M1.

This closes the design doc's open question about saved-request behavior:
**use the default, no custom handler**.

### 6. `PLAYGROUND_ANON` cookie management

Implementation location: a dedicated `WebFilter` in the gateway module
(`AnonCookieFilter implements WebFilter, Ordered`), ordered immediately after
`StripUserHeadersFilter` and before Spring Security. Not a `GlobalFilter` —
WebFilter sees the request before routing, which is the correct layer for
session-class cookies.

Cookie attributes (M1-pinned, deferred from ADR-09):

| Attribute | Value | Why |
|---|---|---|
| Name | `PLAYGROUND_ANON` | ADR-09 pin |
| Value | UUID v4, generated server-side via `UUID.randomUUID()` | Random, no PII, no client influence |
| `Path` | `/` | All routes share the same anon identity |
| `Domain` | unset (host-only) | Single-host deployment; no subdomain sharing |
| `Max-Age` | `2592000` (30 days) | ADR-09 "30-day rolling expiry" |
| `HttpOnly` | `true` | Defense in depth; no JS reads this cookie |
| `SameSite` | `Lax` | Mirrors `PLAYGROUND_SESSION` (ADR-07) — supports cross-site GET navigations (e.g., from Google search) but not cross-site POST tracking |
| `Secure` | `true` in non-`dev` profiles, `false` in `dev` | Mirrors ADR-07's `PLAYGROUND_SESSION` policy |

Filter behavior:
1. If the request carries `PLAYGROUND_ANON`, refresh the cookie with the same
   value and a fresh 30-day `Max-Age` (rolling). Pass through.
2. If absent **and** the request targets a public route (path-match against
   the same allowlist used in §3), generate a UUID, set the cookie via
   `exchange.getResponse().addCookie(ResponseCookie.from(...))`, pass through.
3. If absent and the request targets an authenticated route, **do not** set
   the cookie — Spring Security may immediately redirect to `/login`, and the
   anon cookie is meaningless for a user who is about to authenticate.
4. Authenticated requests that already have `PLAYGROUND_ANON` (because the
   user signed in mid-session): the cookie is **preserved untouched** — its
   lifecycle is independent of session state per ADR-09. The cookie is not
   cleared on `POST /logout`.

Backends do not consume `PLAYGROUND_ANON` in M1 (identity does not need it).
M4 (rag-chat) is the first consumer for rate-limit keys.

### 7. `identity` schema + Flyway migrations

Schema: `identity` (per ADR-05). Migrations live at
`backend/identity/identity-infra/src/main/resources/db/migration/`. Flyway is configured with
`flyway.schemas=identity`, `flyway.table=flyway_history`.

Migration `V202605160001__create_users.sql`:

```sql
SET search_path = identity, public;

CREATE TABLE identity.users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub      TEXT         NOT NULL UNIQUE,
    email           TEXT         NOT NULL,
    display_name    TEXT         NOT NULL,
    avatar_url      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_identity_users_email ON identity.users (email);
```

Notes:
- `id` defaults to `gen_random_uuid()` — requires the `pgcrypto` extension
  (already enabled in the `pgvector/pgvector:pg16` base image). If absent in
  any future image change, prepend `CREATE EXTENSION IF NOT EXISTS pgcrypto;`
  to this migration.
- `google_sub` is the natural key (Google's OIDC `sub`). Unique constraint
  enforces the "exactly one row per Google account" PRD acceptance criterion.
- `email` is **indexed but not unique** at the DB level — Google can in
  theory reassign an email between accounts, and the `googleSub` is the
  immutable identity. Application code may treat email collisions as
  notable but not fatal.
- `avatar_url` is nullable — Google may not return a picture (PRD pins
  frontend `null` tolerance).
- `last_login_at` is updated on every authenticated session — handler logic
  in §8.

Per ADR-02, `identity.domain.model.User` is a POJO record/class with no JPA
annotations; `identity.infrastructure.persistence.UserJpaEntity` is the JPA
entity that maps to this table, and `UserRepositoryImpl` translates between
the two. Repository interface `identity.domain.repository.UserRepository`
exposes `findByGoogleSub(String)`, `save(User)`, `updateLastLogin(UUID,
Instant)`.

Handlers:

- `POST /users/bootstrap` (`identity.infrastructure.web.UserBootstrapController`)
  - Inputs: `{ googleSub, email, displayName, avatarUrl }`.
  - Behavior: `findByGoogleSub`. If present, update mutable fields
    (`email`, `displayName`, `avatarUrl`) when they differ, update
    `lastLoginAt = now()`, return existing `id`. If absent, insert new row,
    return new `id`. The full handler runs in a single transaction so the
    outbox publication (§8) is atomic with the row write.
  - First-insert path publishes `identity.user.registered`.
  - Update-with-field-drift path publishes `identity.user.profile-updated`.
  - Update-without-field-drift path publishes nothing (last-login bump alone
    is not an event).
- `GET /me` (`identity.infrastructure.web.MeController`)
  - Reads `X-User-Id` header (injected by the gateway per §2).
  - Returns 200 with `{ id, googleSub, email, displayName, avatarUrl }` per
    PRD response shape.
  - If `X-User-Id` is missing (defense in depth — should never happen because
    `/me` is an authenticated route), returns 401.
- `GET /identity/_debug/whoami` (`identity.infrastructure.web.DebugController`)
  - Gated by `@ConditionalOnProperty(name="playground.profile", havingValue="dev")`.
  - Echoes the forwarded `X-User-*` headers. Verifies gateway → identity
    injection end-to-end.

### 8. Event publishing (transactional outbox)

**Decision: Spring Modulith Events with the JPA outbox implementation, bridged
to Spring Kafka via `spring-modulith-events-kafka`.** This is the project's
chosen outbox approach and is **inherited by M2, M3, M4, M5** — any future
milestone that publishes a Kafka event uses the same pattern unless that
milestone's per-milestone ADR explicitly supersedes this decision.

Why not Debezium: Debezium requires a Kafka Connect deployment, a logical
replication slot on Postgres, and per-table monitoring — operational tax that
buys us strict CDC fidelity we don't need for personal-scale event-emission.

Why not hand-rolled (outbox table + Spring `@Scheduled` poller): we'd be
re-implementing what Spring Modulith already provides — at-least-once
delivery, transactional write+publish, and a documented externalization
contract. Spring Modulith Events JPA is already a Spring-Boot-starter, one
dependency, one `@ApplicationModuleListener` or `@DomainEventPublisher` call.

Wiring (M1 baseline that all later milestones reuse):

- Add `spring-modulith-events-jpa` and `spring-modulith-events-kafka` to the
  `identity` module (and to any future event-producing module).
- `application.yml`:
  ```yaml
  spring:
    modulith:
      events:
        jdbc:
          schema-initialization:
            enabled: true       # Modulith creates its event-publication table in the service's schema
        externalization:
          enabled: true
        kafka:
          enabled: true
  ```
  Modulith creates an `event_publication` table inside the `identity` schema
  (alongside `identity.users`), backed by Flyway-managed columns (Modulith's
  schema init is idempotent and harmless to keep enabled).
- Domain event POJOs live in `identity.domain.event.UserRegistered` and
  `identity.domain.event.UserProfileUpdated` (per ADR-02 — no Spring imports
  in `domain.event`).
- The application service publishes them via Spring's
  `ApplicationEventPublisher` inside the same `@Transactional` boundary as
  the `users` row write — Modulith persists each publication to
  `event_publication` in the same DB transaction, guaranteeing the outbox
  invariant.
- A Modulith-managed background thread reads the outbox, wraps each event in
  the `shared-kernel` `EventEnvelope<T>` (per ADR-03), publishes to
  `identity.user.registered` or `identity.user.profile-updated` with the
  user's `id` as the Kafka message key, and marks the publication completed.
  Failed publications are retried with Modulith's default backoff; a
  permanent failure routes to `identity.user.registered.dlq` (per ADR-03 DLQ
  convention).

Topic payload schema (envelope per ADR-03; payload below):

```json
// identity.user.registered, schemaVersion 1
{
  "id": "01HXYZABCDEF...",
  "googleSub": "1234567890",
  "email": "jeeklee1120@gmail.com",
  "displayName": "JeekLee",
  "avatarUrl": "https://lh3.googleusercontent.com/...",
  "registeredAt": "2026-05-16T08:42:11.123Z"
}

// identity.user.profile-updated, schemaVersion 1
{
  "id": "01HXYZABCDEF...",
  "changedFields": ["email", "displayName"],
  "email": "jeeklee.new@gmail.com",
  "displayName": "Jeek L.",
  "avatarUrl": null,
  "updatedAt": "2026-05-17T10:11:12.000Z"
}
```

`changedFields` is informational only — consumers downstream still read the
full current state from the payload, not from a delta.

## Diagrams

### Filter ordering (gateway request path)

```
Browser request
    │
    ▼
[1] StripUserHeadersFilter  (HIGHEST_PRECEDENCE)
    │   removes X-User-Id / X-User-Email / X-User-Sub from incoming headers
    ▼
[2] AnonCookieFilter  (HIGHEST_PRECEDENCE + 1)
    │   if public route + no PLAYGROUND_ANON → set cookie (UUIDv4, 30d)
    │   if has PLAYGROUND_ANON → refresh Max-Age, pass through
    │   if authenticated route + no cookie → pass through (do not set)
    ▼
[3] Spring Security: AuthenticationWebFilter  (Spring-fixed order)
    │   loads OAuth2User from PLAYGROUND_SESSION (Redis)
    ▼
[4] Spring Security: AuthorizationWebFilter  (Spring-fixed order)
    │   public allowlist → permitAll
    │   else → authenticated (else → /login redirect or 401 JSON)
    ▼
[5] UserBootstrapFilter  (after auth, before injection)
    │   if authenticated and Redis cache miss on bootstrap:google-sub:{sub}
    │   → POST identity:18081/users/bootstrap, cache id (TTL 8h)
    │   if authenticated and cache hit → no-op
    │   if anonymous → no-op
    ▼
[6] UserHeaderInjectionFilter  (just before NettyRoutingFilter)
    │   if authenticated → set X-User-Id / X-User-Email / X-User-Sub
    │   if anonymous → no-op (headers absent)
    ▼
[7] NettyRoutingFilter  (LOWEST_PRECEDENCE - 1)
    │   forward to identity:18081 / docs:18082 / client:3000 / …
    ▼
Upstream service
```

## Consequences

- Positive: backend-implementer has one document that pins every Stage-3
  decision the gateway/identity build needs — no clarifying-question loops
  with code-reviewer over filter ordering, cookie attributes, or schema
  shape.
- Positive: committing to Spring Modulith Events here saves M2/M3/M4/M5 from
  re-litigating outbox choice; the dependency is already in the project's
  Spring-Boot-aligned dependency graph and the wiring is one starter +
  three `application.yml` lines.
- Positive: bootstrap caching in Redis (8h TTL matching the session) keeps
  the hot path to <1ms after the first request without inventing a new
  cache-invalidation protocol — the session expiry is the invalidation.
- Negative: choosing Spring Modulith for outbox now constrains M3+ to live
  inside its delivery semantics (at-least-once, in-process polling). If
  any later milestone needs Debezium-class CDC (e.g., emitting events from
  rows written by an external tool), that milestone must write a
  superseding per-milestone ADR. Acceptable — none of M2–M5 is currently
  expected to need CDC.
- Negative: the bootstrap cache TTL means a user who edits their Google
  profile (display name, avatar) mid-session sees the old values until the
  next bootstrap fires (worst case: 8h). Intentional — we treat Google
  profile drift as best-effort sync, not real-time. `identity.user.profile-updated`
  is still emitted on the next bootstrap, so downstream BCs eventually
  converge.
- Negative: `PLAYGROUND_ANON` is a per-browser identifier that survives
  logout — ADR-09 already flagged the GDPR-future implications; this ADR
  inherits the flag and adds no new exposure (no PII, HttpOnly, no
  third-party sharing). Re-evaluate if the audience expands beyond
  jeeklee1120@gmail.com's personal scope.
- Negative: filter ordering is enforced by `Ordered` values, not by a
  declarative DSL. A future contributor (or agent) adding a new
  `GlobalFilter` can accidentally break the contract by picking the wrong
  order. Mitigation: code-reviewer checks every new `GlobalFilter` /
  `WebFilter` against the table in §2.

## Related

- ADR-03 (Kafka conventions) — envelope, topic naming for the two events
- ADR-05 (Data store) — `identity` schema, Postgres image, Flyway config
- ADR-07 (Gateway OAuth) — session cookie, route table, header contract
- ADR-08 (Inter-service comms) — gateway → identity HTTP is the sanctioned
  exception this ADR exercises
- ADR-09 (Public route policy) — anonymous identity contract, route
  classification, `PLAYGROUND_ANON` 30-day rolling expiry
- `docs/prd/M1-identity.md` — acceptance criteria this ADR's decisions must
  satisfy
- `docs/design/M1-identity.md` — UI screens whose routing this ADR's filter
  chain serves
- Future M2 / M3 / M4 / M5 per-milestone ADRs — **inherit §8 (Spring
  Modulith Events outbox)** unless explicitly superseded
