# ADR-12: M2 Docs — Implementation Decisions

## Status
Accepted

> Note on numbering — the original M2 task brief asked for slot `ADR-11`. By
> the time this ADR was authored, slot 11 had already been claimed by
> `11-shared-exception-hierarchy.md` (the `shared-kernel` exception ADR).
> M2 Docs decisions therefore land at the next free slot, **ADR-12**. ADR-00
> (`00-overview.md`) is updated accordingly. No content from the M2 task
> brief is lost — every numbered decision below corresponds to one of the
> M2 spec's §11 open questions or to an amendment the brief required.

## Context

The M2 docs spec (`docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` v5)
pins the Docs BC's bounded context, data model, public surface, search
surface, event contract, and home composition rules. It deliberately defers
14 implementation-shape questions to this per-milestone ADR (spec §11) plus a
formal amendment to three transverse ADRs (ADR-05, ADR-08, ADR-09).

ADR-10 already did the same job for M1 Identity: pin library versions, gateway
filter ordering, schema migrations, and the outbox decision. ADR-10 §8
explicitly states that the **Spring Modulith Events JPA** outbox decision is
**inherited by M2-M5 unless explicitly superseded**. This ADR honors that
inheritance and pins only the M2-specific additions on top — BlockNote
versions, OpenSearch image + client + topology, Korean analyzer, anonymous
read rate limits, the cross-BC body-fetch mechanism for M3, the owner-resolution
mechanism, and the `docs-api` JVM module wiring.

Like ADR-10, none of the decisions below supersede a transverse ADR; they fill
in implementation details inside the envelopes ADR-01, ADR-02, ADR-03, ADR-05,
ADR-08, and ADR-09 defined, plus three explicit amendments captured here and
appended inline to the respective transverse ADRs.

## Decision

### 1. Outbox library — Spring Modulith Events JPA (inherited from ADR-10 §8)

**Decision:** the docs BC publishes its three `docs.document.*` Kafka events
through **Spring Modulith Events JPA + Spring Modulith Events Kafka**, exactly
the same wiring ADR-10 §8 pinned for M1 Identity. No new outbox library is
introduced.

| Coordinate | Version source | Notes |
|---|---|---|
| `org.springframework.modulith:spring-modulith-events-jpa` | Spring Modulith **1.2.x** (latest 1.x line aligned with Spring Boot 3.3) | Same line as ADR-10 §8 |
| `org.springframework.modulith:spring-modulith-events-kafka` | Spring Modulith 1.2.x | Same line as ADR-10 §8 |

**Why not Debezium:** Debezium requires Kafka Connect + a logical replication
slot on Postgres + per-table monitoring — operational tax we don't need for
personal-scale event emission. ADR-10 rejected it for M1 for the same reason;
M2 inherits the rejection.

**Why not a hand-rolled outbox table + `@Scheduled` poller:** we'd be
re-implementing Modulith's at-least-once delivery and transactional
write+publish guarantees. The wiring (one starter + three `application.yml`
lines) is already proven in `identity-api`.

**Module placement:** the outbox `event_publication` table lives inside the
`docs` schema (Modulith's `spring.modulith.events.jdbc.schema-initialization.enabled=true`
creates it alongside `docs.documents`). The publishing call lives in
`docs-app` (application-service layer per ADR-02), via Spring's
`ApplicationEventPublisher`, inside the same `@Transactional` boundary as the
`documents` row write. The Kafka bridge is configured in `docs-infra`.

`application.yml` snippet (docs-api):

```yaml
spring:
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      externalization:
        enabled: true
      kafka:
        enabled: true
```

The domain event POJOs live in `docs-domain`
(`dev.jeeklee.playground.docs.domain.event.{DocumentUploaded,DocumentDeleted,DocumentVisibilityChanged}`).
No Spring imports in `domain.event` per ADR-02.

### 2. M3 body-fetch mechanism — gateway-internal HTTP on docs

**Decision:** `rag-ingestion` (M3) calls
**`GET http://docs-api:18082/internal/docs/public/{id}/body`** via WebClient.
A second route `GET /internal/docs/public/{id}` returns metadata when the
ingestion event is older than the current authoritative state.

| Option | Trade-off | Choice |
|---|---|---|
| (a) Gateway-internal HTTP on `docs-api` (`/internal/**` prefix not exposed by gateway) | Two services keep clean schema isolation; adds one HTTP hop; needs reliability discipline (timeout, retries, DLQ). | **Chosen** |
| (b) Read-only DB role on the `docs` schema for `rag-ingestion-infra` | Zero hop, no docs-api availability dependency; breaks ADR-05's "schema-per-BC" invariant; rag-ingestion learns the docs row layout (schema coupling). | Rejected |

Rationale:
- **ADR-05's schema-per-BC invariant is load-bearing.** Letting rag-ingestion
  read `docs.documents` directly couples the two BCs at the SQL layer; the
  next refactor of the docs table (renaming `body`, splitting it out to a
  blob store, etc.) silently breaks rag-ingestion.
- **The HTTP path is testable in isolation.** Schema-coupling failures only
  surface in cross-service integration tests; HTTP failures surface in the
  per-service contract test.
- The `/internal/**` prefix is **not** forwarded by the gateway (ADR-07's
  route table excludes it), so this is genuinely compose-internal traffic.

This is the **first justified exception to ADR-08's BC-to-BC HTTP ban**.
It is enumerated in the ADR-08 amendment appended below. The next exception
requires another ADR amendment row.

**Reliability discipline** (mirrors ADR-10 §4's gateway → identity pattern):
- WebClient `responseTimeout`: **5 seconds** (body is larger than the identity
  bootstrap payload — wider window).
- Retries: up to **3 attempts**, exponential backoff with jitter (200ms,
  400ms, 800ms base; `Retry.backoff(3, Duration.ofMillis(200)).jitter(0.5)`).
- Permanent failure: route the in-flight ingestion event to
  `docs.document.uploaded.dlq` (per ADR-03 DLQ convention).
- **No `X-User-*` headers forwarded** — this is service-to-service traffic,
  no user identity to propagate. The `docs-api` internal handler does **not**
  consult visibility rules — rag-ingestion needs the body for both public
  and private documents (visibility filtering happens at retrieval time, per
  ADR-09).
- **Read-only.** rag-ingestion MUST NOT mutate any docs state via this route.
  Enforced by docs-api exposing only `GET` verbs under `/internal/**`.

The handler lives in `docs-api`
(`dev.jeeklee.playground.docs.api.internal.InternalDocumentController`),
guarded by:
- Path prefix `/internal/**` not in any `permitAll()` rule and not in the
  authenticated allowlist either — gateway just doesn't route there.
- A Spring profile / property check that refuses to serve `/internal/**`
  when bound to a host-exposed port (defense in depth; future change to
  ADR-08 that opens host ports would still fail closed).

### 3. BlockNote — versions, SSR strategy, MD-roundtrip adapter

**Library decision (already made by the M2 spec, §11 Q3):** **BlockNote**
(https://www.blocknotejs.org) is the in-app editor. This ADR pins the
mechanical details.

**Version pins** (latest stable on the 0.22 line at time of writing — pin
exact patch in `frontend/package.json` after a smoke build, but treat the
following as the contract for `pnpm i`):

| Coordinate | Pinned version | Why |
|---|---|---|
| `@blocknote/core` | `^0.22.0` | Block model + Markdown adapters (`tryParseMarkdownToBlocks`, `blockToMarkdownLossy`) |
| `@blocknote/react` | `^0.22.0` | React bindings + default UI |
| `@blocknote/mantine` | `^0.22.0` | Default theme; replaced with Inter-tokens skin per design system spec (frontend-implementer task) |

**Version-pin discipline:** ranges (`^0.22.0`) are acceptable in `package.json`
because BlockNote on the `0.x` line is pre-1.0; `pnpm-lock.yaml` is the source
of truth for the exact resolved patch. The frontend-implementer commits the
lockfile with the M2 PR and CI installs with `pnpm i --frozen-lockfile`. If
BlockNote ships a `0.23` line during the M2 cycle, sticking to `0.22` is
acceptable — bump in a follow-up.

**SSR strategy under Next.js App Router:**

BlockNote uses Tiptap, which uses ProseMirror, which **requires `window` /
`document` at import time**. Importing `@blocknote/react` from a server
component (or the top of a client component file that gets statically
analyzed) blows up `next build` with `ReferenceError: window is not defined`.
The mandatory pattern is **dynamic import with `{ ssr: false }`**:

```tsx
// frontend/src/features/docs-editor/ui/BlockNoteEditor.tsx
"use client";

import dynamic from "next/dynamic";

const BlockNoteEditorClient = dynamic(
  () => import("./BlockNoteEditorClient"),
  { ssr: false, loading: () => <EditorSkeleton /> }
);

export function BlockNoteEditor(props: BlockNoteEditorProps) {
  return <BlockNoteEditorClient {...props} />;
}
```

Rules:
- The dynamic wrapper file (`BlockNoteEditor.tsx`) is the **only** place that
  imports `next/dynamic` for the editor.
- The actual editor file (`BlockNoteEditorClient.tsx`) is a **`"use client"`**
  React component that imports `@blocknote/react` at the top level — that's
  fine because the wrapper guarantees it loads in the browser only.
- The skeleton (`EditorSkeleton`) is server-renderable and matches the
  editor's outer dimensions to avoid layout shift. Lives in `shared/ui/`.
- The `/docs/new` and `/docs/{id}` routes render `BlockNoteEditor` and never
  the client component directly.

**MD-roundtrip adapter configuration:**

```ts
// On load — server returns raw MD, client parses to BlockNote blocks
import { BlockNoteEditor as Editor } from "@blocknote/core";

const editor = Editor.create({
  // optional: schema overrides land here in M2.1 (cover image, etc.)
});

const blocks = await editor.tryParseMarkdownToBlocks(rawMarkdown);
editor.replaceBlocks(editor.document, blocks);

// On save — serialize back to raw MD before PATCH
const md = await editor.blocksToMarkdownLossy(editor.document);
await api.patch(`/api/docs/${id}`, { body: md });
```

Rules:
- `tryParseMarkdownToBlocks` is **lossy** for nested/unsupported constructs
  (e.g., HTML blocks, Mermaid). The spec §9 already pins HTML-in-MD as
  sanitized out, and Mermaid as P2 — so the lossy edges are acceptable for
  M2.
- `blocksToMarkdownLossy` MUST be the canonical save path. The editor's
  internal JSON document is **not persisted** — Postgres `docs.documents.body`
  remains GFM-flavored Markdown, exactly as the spec §4.3 pins.
- A round-trip property test (load → edit → save → reload → assert visual
  equivalence on a corpus of M2-supported MD features) lives in
  `frontend/src/features/docs-editor/__tests__/`. Run on every PR.

**Bundle size note:** BlockNote + Tiptap + ProseMirror pulls roughly
~350-450 KB gzipped into the editor route's chunk. Acceptable for M2 —
the editor route is authenticated-only and dynamic-imported, so the public
home / public document detail pages do not pay this cost. The frontend-
implementer verifies via `next build` output that the public chunks do
not include BlockNote.

### 4. Body size cap — 1 MB raw Markdown, enforced API + DB

**Decision:**

| Layer | Enforcement | Limit |
|---|---|---|
| API (docs-api Spring) | `@Size(max = 1_048_576)` on the `body` field of `CreateDocRequest` / `PatchDocRequest` DTOs; multipart upload checked via `Content-Length` filter | **1 MB** (1,048,576 bytes of raw MD) |
| DB (`docs.documents.body`) | `CHECK (octet_length(body) <= 1048576)` on the column | **1 MB** |
| HTTP response | `413 Payload Too Large` with `BadRequestException` ⇒ `DocsErrorCode.BODY_TOO_LARGE` (per ADR-11 hierarchy) | — |

Rationale:
- A typical long-form essay is ~10-50 KB of MD. 1 MB is 20-100× headroom.
- 1 MB keeps the body comfortably under Kafka's default 1 MB max message
  size, but that's incidental — events carry only `bodyChecksum`, not the
  body itself (per spec §5).
- The DB `CHECK` is defense in depth against a future migration script or
  an out-of-band write bypassing the API.
- M2.1 may revisit if the user uploads a large `.md` file (the `.md` upload
  affordance is in M2 P0 per spec §12). Reasonable next bump: 5 MB.

**Error response** (per ADR-11 unified shape):

```json
{
  "errorCode": "DOCS-VALIDATION-001",
  "message": "Document body exceeds maximum size (1 MB)",
  "timestamp": "2026-05-17T10:11:12.345Z",
  "path": "/api/docs/{id}",
  "traceId": "..."
}
```

### 5. OpenSearch — version, client, topology

**Decision matrix:**

| Concern | Options | Choice | Reason |
|---|---|---|---|
| Major version | OpenSearch 1.x (LTS, EoL 2024-Q4) vs 2.x (current LTS, EoL 2026+) | **2.x** (`opensearchproject/opensearch:2.18.0`) | 1.x is past EoL; 2.x is the supported line. The image tag is already pinned in ADR-05's amendment. |
| Client library | Spring Data OpenSearch (1.x line) vs native `opensearch-java` low-level/REST client | **`opensearch-java` (`org.opensearch.client:opensearch-java:2.10.x`)** | Spring Data OpenSearch is **not in the Spring Boot 3.3.x BOM**; pulling it in requires a separate BOM with its own version-resolution risk. The native client has a clean Java DSL, supports the 2.x server features the projector needs (highlight, painless scripting, bulk indexing), and stays out of the Spring Data abstraction (we don't need repository-style queries — we need a single `IndexRequest` + a single search query). |
| Topology — dev | Single-node vs 3-node minimum | **Single-node** (`discovery.type=single-node`, per ADR-05 amendment) | Personal-scale, one host, no replicas needed. |
| Topology — prod | Same as dev (single-host playground) | **Single-node** | Re-evaluate if the audience scope expands. |
| Auth | Security plugin enabled vs disabled | **Disabled in dev** (per ADR-05 amendment) | Compose-internal network, no external exposure. Re-enable for any future public deployment via a per-milestone ADR. |
| JVM heap | 512m vs 1g vs 2g | **512m** (`OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m`) | Dev sizing — bump on milestone if the index outgrows. |

**Compose service block (specification — infra-implementer transcribes
verbatim into `infra/docker-compose.yml` in Stage 3):**

```yaml
  # --- ADR-05 amendment + ADR-12: OpenSearch (search projection for docs BC) ---
  opensearch-playground:
    image: opensearchproject/opensearch:2.18.0
    container_name: opensearch-playground
    environment:
      cluster.name: opensearch-playground
      node.name: opensearch-playground
      discovery.type: single-node
      bootstrap.memory_lock: "true"
      DISABLE_SECURITY_PLUGIN: "true"
      OPENSEARCH_JAVA_OPTS: "-Xms512m -Xmx512m"
    ulimits:
      memlock:
        soft: -1
        hard: -1
    ports:
      - "10292:9200"
    volumes:
      - opensearch-playground-data:/usr/share/opensearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -s -f http://localhost:9200/_cluster/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
```

**Client wiring (docs-infra):**

| Coordinate | Version | Notes |
|---|---|---|
| `org.opensearch.client:opensearch-java` | `2.10.x` (compatible with OpenSearch server 2.18) | Native Java client; uses the Apache HttpClient 5 transport (already on the classpath via Spring's WebClient/HttpClient transitive deps). |
| `org.opensearch.client:opensearch-rest-client` | matched line | REST transport |

The `SearchIndexPort` interface lives in `docs-app`
(`dev.jeeklee.playground.docs.application.port.SearchIndexPort`); the adapter
`OpenSearchSearchIndexAdapter` lives in `docs-infra` and implements it. No
`docs-domain` reference to OpenSearch types (per ADR-02).

**Single OpenSearch endpoint URL** (compose-internal):
`http://opensearch-playground:9200` — configured via env var
`OPENSEARCH_BASE_URL` on the `docs-api` service.

**Index name + version suffix:** **`docs-v1`** (matches spec §4.2). Reindex
operations during a schema migration would create `docs-v2` and atomically
swap an alias `docs` — but no alias is configured in M2 because there is no
v2 yet. M2 reads/writes go directly to `docs-v1`.

### 6. Korean analyzer — Nori (built-in)

**Decision:** the `docs-v1` index uses the **Nori** Korean analyzer
(`analysis-nori` plugin, bundled with the OpenSearch 2.x image — no extra
install step).

| Option | Trade-off | Choice |
|---|---|---|
| **Nori** (built-in, maintained by OpenSearch upstream) | Solid tokenization for modern Korean; no extra install; mature with documented edge cases. | **Chosen** |
| **Seunjeon** (community plugin) | Slightly better recall on older / mixed-script text; requires manual plugin install on every container restart and an extra build step in the OpenSearch Dockerfile. | Rejected for M2; revisit if Nori's recall is empirically poor on the M2 corpus. |

**Index settings snippet** (specification for the projector to send on bootstrap
when the index doesn't exist):

```jsonc
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "nori_user_dict": {
          "type": "nori_tokenizer",
          "decompound_mode": "mixed",
          "discard_punctuation": "true"
        }
      },
      "analyzer": {
        "korean": {
          "type": "custom",
          "tokenizer": "nori_user_dict",
          "filter": ["lowercase", "nori_part_of_speech", "nori_readingform"]
        },
        "english": {
          "type": "standard"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "documentId":  { "type": "keyword" },
      "userId":      { "type": "keyword" },
      "title":       {
        "type": "text",
        "analyzer": "korean",
        "fields": {
          "en":  { "type": "text", "analyzer": "english" },
          "raw": { "type": "keyword" }
        }
      },
      "body":        {
        "type": "text",
        "analyzer": "korean",
        "fields": { "en": { "type": "text", "analyzer": "english" } }
      },
      "visibility":  { "type": "keyword" },
      "slug":        { "type": "keyword" },
      "isOwnerDoc":  { "type": "boolean" },
      "updatedAt":   { "type": "date" }
    }
  }
}
```

The `title.en` / `body.en` multi-fields cover English-only queries (where
Korean tokenization would over-decompound a Latin string into noise). Search
queries union both subfields via `multi_match` with `cross_fields` operator
(implementation detail for backend-implementer; not a contract).

### 7. Rate limit for unauthenticated public reads

**Decision (per-IP, enforced at the gateway):**

| Route | Cap | Window | Rationale |
|---|---|---|---|
| `GET /api/docs` (list, anonymous) | **60 requests** | per minute | Cheap (single Postgres SELECT with owner filter + cursor); the bot-scrape budget. |
| `GET /api/docs/{id}` (detail, anonymous) | **60 requests** | per minute | Same Postgres cost class as list. |
| `POST /api/docs/{id}/view` (anonymous) | **30 requests** | per minute | Redis dedup keeps the per-slug count flat; the rate limit is anti-abuse, not anti-honest-reader. |
| `GET /api/docs/search?scope=public` (anonymous) | **10 requests** | per minute | OpenSearch query + JSON marshaling; ~3-5× more expensive than the list query. |

**Implementation:**
- Reuses the **same Bucket4j-on-Redis** middleware the M4 per-milestone ADR
  will pin for public RAG chat (per ADR-09 §"Rate-limit and cost protection").
  M4 picks the exact library and config; M2 contributes only the per-IP cap
  numbers above and binds them via the gateway property
  `playground.rate-limit.docs-public.*`. **If M4 has not yet shipped when
  M2 lands**, the M2 implementation may stub the rate-limit filter to a
  no-op (the route works, the cap is unenforced) **on the explicit
  understanding** that M4 retro-fits enforcement. This is captured in
  ADR-09's M4-future amendment, not blocked by M2.
- Authenticated routes (`/api/docs/mine`, `GET /api/docs/{id}` when the
  caller is authenticated and owner-or-public, etc.) carry **no rate
  limit** in M2 — the single-user model makes that meaningless. M4's
  per-session limits cover the chat surface where the LLM cost lives.
- 429 response uses the ADR-11 error shape with `errorCode:
  "SHARED-RATE-LIMITED-001"`.

**Key choice:** IP-based (`X-Forwarded-For` from Cloudflare Tunnel, fallback
to gateway's `RemoteAddr`). The `PLAYGROUND_ANON` cookie is **not** the rate
key — anonymous cookies are trivially deleted, so they're useless as a cap
denominator. The cookie remains the dedup key for `POST /view` (per spec
§6.1), which is a separate concern.

### 8. Owner resolution path — cross-schema SELECT on boot, with health check fallback

**Decision matrix:**

| Option | Trade-off | Choice |
|---|---|---|
| (a) Cross-schema SELECT from `docs-infra` into `identity.users` at boot | Simple — one JDBC `SELECT id FROM identity.users WHERE google_sub = ?`. Result cached in memory for the JVM lifetime. **Breaks ADR-08's "cross-schema DB access forbidden" rule.** | Rejected |
| (b) Gateway-internal HTTP call to `identity-api` at boot — `GET /internal/users/by-google-sub/{sub}` | Adds a startup-time dependency (identity must be reachable). Symmetric with the §2 docs body-fetch exception — both are read-only internal HTTP. | **Chosen** |

**Why HTTP and not the cross-schema SELECT:**
- ADR-08's schema-isolation rule is the same load-bearing invariant that
  motivated §2's HTTP-not-DB choice. Consistency: every cross-BC read in M2
  is HTTP-with-internal-prefix, not direct schema access.
- The `identity-api` service is also reachable at boot for the gateway's
  bootstrap call (per ADR-10 §4) — the startup-time dependency is already
  baked in for the gateway. Adding it to `docs-api` mirrors the topology.
- This **does not** add a new ADR-08 exception — `gateway → identity-api`
  HTTP was already allowed (ADR-08's allowed-channels table). What's new is
  that `docs-api` joins the gateway in that allowance, **scoped to the
  read-only `/internal/users/by-google-sub/{sub}` route only**.

**Sanctioned route (identity-api side, to be implemented in M2 alongside docs):**

| Method | Path | Returns | Purpose |
|---|---|---|---|
| `GET` | `/internal/users/by-google-sub/{sub}` | `200 {"id": "...", "googleSub": "..."}` or `404` | Resolve the owner user id from the `PLAYGROUND_OWNER_GOOGLE_SUB` env var at docs-api boot. |

This route lives at `/internal/**` (same prefix as the docs body-fetch
route — not exposed by the gateway per ADR-07's route table). identity-api
ships it in M2 even though the boot caller is docs-api; it's a small
additive controller (`InternalUserController` in `identity-api`).

**Behavior at docs-api boot:**

```java
// docs-infra: OwnerResolverAdapter implements OwnerResolverPort (docs-app)
@Component
class OwnerResolverAdapter implements OwnerResolverPort {
  private final WebClient identityClient;
  private final String ownerGoogleSub;
  private final AtomicReference<Optional<UUID>> cachedOwnerId =
      new AtomicReference<>(Optional.empty());

  @PostConstruct
  void bootstrapOwner() {
    if (ownerGoogleSub == null || ownerGoogleSub.isBlank()) {
      log.warn("PLAYGROUND_OWNER_GOOGLE_SUB unset — public feed will return empty");
      return;
    }
    identityClient.get()
      .uri("/internal/users/by-google-sub/{sub}", ownerGoogleSub)
      .retrieve()
      .bodyToMono(UserDto.class)
      .timeout(Duration.ofSeconds(2))
      .retryWhen(Retry.backoff(3, Duration.ofMillis(100)).jitter(0.5))
      .doOnError(e -> log.warn("Owner resolution failed at boot; will retry on first request", e))
      .subscribe(u -> cachedOwnerId.set(Optional.of(u.id())));
  }

  @Override
  public Optional<UUID> ownerUserId() {
    return cachedOwnerId.get(); // empty until boot resolves; public feed returns [] in the gap
  }
}
```

- **Fail-closed:** if the lookup fails, public-feed endpoints return an
  empty list (per spec §6.3). A WARN log line flags the misconfiguration.
- **Lazy retry:** the boot-time call retries 3 times with backoff; if all
  fail, the next `GET /api/docs` will re-attempt resolution (idempotent,
  cached on success).
- **Cache invalidation:** the cached owner id is never invalidated during
  the JVM lifetime. If the operator changes `PLAYGROUND_OWNER_GOOGLE_SUB`,
  restart docs-api. Acceptable for personal scale — owner changes are rare.

**Env var contract** (spec §6.3 already pins the var; this ADR pins where
it's set):
- `PLAYGROUND_OWNER_GOOGLE_SUB` set on the `docs-api` compose service.
- Same value MAY also be set on `gateway` (unused in M2; reserved for future
  use such as a "this is the owner" banner). Not required.
- M2.1 may surface the value to the frontend via a server component (no API
  exposure).

### 9. Metadata-only event topic — NO

**Decision:** **do not** introduce `docs.document.metadata-changed` for M2.

| Option | Trade-off | Choice |
|---|---|---|
| Add a fourth topic `docs.document.metadata-changed` for title-only / visibility-only edits | Search projector keeps title in sync without re-firing `uploaded` (which re-triggers M3 embedding work). Adds one topic, one consumer wiring, one event payload definition. | Rejected for M2 |
| Re-use `docs.document.visibility-changed` for visibility flips; let title edits **not** propagate to OpenSearch in M2 | Accepts stale title in search until the next `body` edit (which fires `uploaded`). Zero new topics. | **Chosen** |

Rationale:
- M2 is a single-user platform — title edits are rare and the staleness
  window is the user's own next save. Acceptable in writing per spec §5
  rules.
- Adding a fourth event topic to support a P95 case that won't happen at
  personal scale is wrong-sized. If the audience expands, M2.1 introduces
  the topic with a superseding ADR.
- The search projector still listens to `docs.document.visibility-changed`
  (already in the M2 event surface) and re-indexes the row's title +
  visibility from Postgres on that signal. So visibility flips never see
  stale titles; only no-event title edits do.

Spec §5 already accepted this trade-off in writing. This ADR makes it
explicit.

### 10. View dedup TTL — 24h, same-cookie path regardless of auth state

**Decision:** the `view:{slug}:{anon-cookie-or-ip}` Redis key has a fixed
**24-hour TTL**. Authenticated viewers go through the **same dedup path**
(no `X-User-Id`-based dedup; spec §11 Q11 confirmed simplicity wins).

| Option | Trade-off | Choice |
|---|---|---|
| Per-anon-cookie dedup, 24h TTL, same path for authed users | Simple; one Redis key shape. Same anon-cookie may accumulate views across sign-in / sign-out cycles. | **Chosen** |
| Per-user dedup for authenticated viewers (longer TTL, e.g., 7 days) | More accurate uniqueness; two Redis key shapes; double the test matrix. | Rejected |

**Implementation note for docs-infra:**

```java
String dedupKey = anonCookie != null
    ? "view:" + slug + ":anon:" + anonCookie
    : "view:" + slug + ":ip:" + clientIp;
Boolean isFirst = redis.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(24));
if (Boolean.TRUE.equals(isFirst)) {
  documentRepository.incrementViewCount(slug);
}
return ResponseEntity.noContent().build();
```

The `setIfAbsent` is the atomic "did I just claim this view" check; the row
update follows only when the claim succeeded.

### 11. Counter sync strategy — denormalized columns + nightly resync

**Decision:** the `view_count` / `like_count` columns on `docs.documents` are
denormalized and maintained transactionally with the originating mutation
(view-increment Redis-claim, like-toggle). A **nightly resync job** repairs
drift.

| Option | Trade-off | Choice |
|---|---|---|
| Denormalized counters + nightly resync | Fast reads (home tile is a hot path; one row, two integers). Drift between mutation and resync is fine; the spec §10 already accepts it. | **Chosen** |
| Trigger-based maintenance | Tighter integrity; surprises in test setup (`document_likes` inserts via test fixture without going through the app would still fire the trigger, but the inverse — counters updated without a like row — would silently desync). | Rejected |
| Event-sourced rebuild from `docs.document.engagement-*` topic | Heaviest infrastructure; reads still go to the denormalized columns, so the topic is purely audit. Not justified for M2. | Rejected |

**Job wiring:**

- Library: **Spring `@Scheduled` annotation** (no Quartz, no ShedLock — single
  docs-api instance, so cross-instance scheduling isn't a concern).
- Cron: **`0 0 3 * * *`** (every day at 03:00 server time; quiet hour).
- Location: `docs-infra/scheduler/CounterResyncJob.java`.
- Behavior: full table scan of `docs.documents`, recompute `like_count` from
  `COUNT(*) FROM document_likes WHERE document_id = ?`, recompute
  `view_count` from the audit log if one exists (spec §10 mentions max
  acceptable drift is informational; M2 does **not** maintain a view audit
  table, so `view_count` is **resync'd from itself** — the resync only
  repairs `like_count` drift from the `document_likes` source-of-truth
  table).
- Failure: the job logs WARN and skips the offending row; the next night
  retries.

**If the audience scope expands and multiple docs-api instances run**, the
resync job needs ShedLock or equivalent. Out of scope for M2.

### 12. Author display name drift in OpenSearch — accepted P0 drift, flag for M2.1

**Decision:** the M2 OpenSearch index does **not** carry the author's
display name. Search hits include `documentId` + `title` + `slug?` +
`snippet` + `updatedAt` (per spec §6.4 `SearchHit` shape); the frontend
joins display name from `GET /me` or from a per-doc detail fetch.

Therefore: **no event subscription from docs to `identity.user.profile-updated`** in M2.
If a future surface displays the author's name **inline in search results**
(M2.1+), the docs BC subscribes to `identity.user.profile-updated` (per
ADR-10 §8's outbox event) and re-indexes the affected user's documents.

**Status:** accepted drift for M2 P0. Flagged for M2.1 if drift becomes
user-visible.

### 13. Undo-after-delete UX — toast appears, Undo non-functional in M2 P0

**Decision (confirms spec §11 Q14):**
- **M2 P0:** the design doc's Delete confirmation toast renders with an
  `Undo` link. Clicking the link **does nothing** (no API call; the link is
  visually present but server-side it has no undo path). A `data-testid`
  marks it as `undo-disabled` for the E2E test.
- **M2.1:** add a `deleted_at TIMESTAMPTZ` tombstone column on
  `docs.documents`. DELETE becomes soft for 30 seconds; the Undo link
  triggers `POST /api/docs/{id}/restore` which clears `deleted_at` before
  the cascade fires. After 30s a hard-delete worker cascades to
  `publish_meta`, `document_likes`, OpenSearch, and emits
  `docs.document.deleted` (per spec §5).

Rationale: cascading restore (publish_meta + document_likes + OpenSearch
re-index + RAG chunk revival) is non-trivial to ship inside M2's scope.
The toast's visual presence sets the right user expectation; the empty
link is honest enough for M2 P0. The M2.1 tombstone is a one-day change
once M3's RAG chunk cascade is mapped out.

### 14. Folder picker UX placement — design-doc decision, not this ADR

The spec §11 Q14 lists the folder picker UX as a brainstorm-bucket item.
**This ADR explicitly leaves it to the design doc** (`docs/design/M2-docs.md`),
which is the `product-designer`'s artifact. Backend-implementer reads the
design doc for the placement; no ADR-level constraint from this document.

### 15. Module layout + port assignment — quadruplet at port 18082

**Decision (matches ADR-01 v2):**

| Module | Type | Port | Notes |
|---|---|---|---|
| `docs-api` | runnable Spring Boot app | **18082** | Only host-internal; gateway forwards `/api/docs/**` here. Internal route `/internal/docs/public/**` reachable only on the compose network. |
| `docs-app` | Java library | n/a | Application services, use-case orchestration, port interfaces. Hosts the search projector as a separate Spring bean (`DocsSearchProjector` listening on `docs.document.*` topics). |
| `docs-domain` | Java library | n/a | Aggregate, value objects, domain events. No Spring imports per ADR-02. |
| `docs-infra` | Java library | n/a | JPA adapter, Kafka producer (via Modulith bridge), OpenSearch adapter, Redis adapter (for view dedup), WebClient adapter (for owner resolution call to identity-api). |

Port 18082 is already pinned in ADR-01 v2 — no change. The reservation
table there has 18085 as the next free slot for any future BC.

**Search projector placement (per spec §5.1):**
- `DocsSearchProjector` lives in **`docs-app`** as a Spring `@Service`
  bean. It depends on the `SearchIndexPort` (in `docs-app`) and consumes
  the BC's own three domain event POJOs (from `docs-domain`).
- The Kafka subscription wiring (`@KafkaListener` annotations, container
  factory) lives in **`docs-infra`** — the projector itself is a use-case
  orchestrator; the Kafka plumbing is an infrastructure concern.
- Failure to write to OpenSearch in the projector MUST NOT block the
  original DB transaction (per spec §5.1) — the projector runs **after**
  the outbox publishes the Kafka event, so the DB write is already
  committed by the time the projector reacts. OpenSearch failures retry
  via Kafka redelivery (per ADR-03 consumer settings).

**Compose service block (spec for infra-implementer):**

```yaml
  # --- M2: docs-api (Docs BC quadruplet runnable per ADR-01 v2) ---
  docs-api:
    build:
      context: ../backend
      dockerfile: docs/docs-api/Dockerfile
    container_name: docs-api
    depends_on:
      postgres-playground:
        condition: service_healthy
      kafka-playground:
        condition: service_healthy
      redis-playground:
        condition: service_healthy
      opensearch-playground:
        condition: service_healthy
      identity-api:
        condition: service_healthy   # for boot-time owner resolution per §8
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-default}
      POSTGRES_HOST: ${POSTGRES_HOST:-postgres-playground}
      POSTGRES_PORT: ${POSTGRES_PORT:-5432}
      POSTGRES_DB: ${POSTGRES_DB:-playground}
      POSTGRES_USER: ${POSTGRES_USER:-playground}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-playground}
      KAFKA_BOOTSTRAP_SERVERS: ${KAFKA_BOOTSTRAP_SERVERS:-kafka-playground:9092}
      REDIS_HOST: ${REDIS_HOST:-redis-playground}
      REDIS_PORT: ${REDIS_PORT:-6379}
      OPENSEARCH_BASE_URL: ${OPENSEARCH_BASE_URL:-http://opensearch-playground:9200}
      IDENTITY_BASE_URL: ${IDENTITY_BASE_URL:-http://identity-api:18081}
      PLAYGROUND_OWNER_GOOGLE_SUB: ${PLAYGROUND_OWNER_GOOGLE_SUB}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:18082/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
```

The volume `opensearch-playground-data` and the service entry
`opensearch-playground` are added per §5. No host port for `docs-api`
(ADR-08's "backends must not be host-exposed" rule).

## Amendments to transverse ADRs

This section enumerates the three amendments this ADR makes to existing
transverse ADRs. The amendment notes are appended **inline** to each affected
ADR's file (so a reader of ADR-05 / ADR-08 / ADR-09 sees the M2 change
without having to cross-reference here).

### Amendment to ADR-05 (Data Store)

> Amendment (2026-05-17, ADR-12): **OpenSearch is added as the second-tier
> search store for the Docs BC.** Postgres remains the source of truth;
> OpenSearch is a projection rebuilt-able from Postgres at any time. Image
> pin: `opensearchproject/opensearch:2.18.0`, single-node topology,
> compose service `opensearch-playground`, host port `10292`, JVM heap 512m,
> security plugin disabled in dev. Client: `org.opensearch.client:opensearch-java:2.10.x`.
> Index name: `docs-v1`. Korean analyzer: **Nori** (built-in plugin). See
> ADR-12 §5 + §6 for the full specification.

(Note: ADR-05 already had an OpenSearch amendment section appended pre-ADR-12;
ADR-12's amendment **supersedes the prior section** with the concrete client
+ analyzer pin, but does **not** invalidate the prior image + port pins
already there.)

### Amendment to ADR-08 (Inter-Service Communication)

> Amendment (2026-05-17, ADR-12): the M3 `rag-ingestion` service may read
> `docs.documents.body` via **gateway-internal HTTP** on the docs BC (route
> `GET /internal/docs/public/{id}/body` + `GET /internal/docs/public/{id}`).
> This is the **first justified exception to the Kafka-only rule**;
> future exceptions require explicit ADR amendment. Read-only; no `X-User-*`
> headers forwarded; 5s timeout, 3 retries, DLQ on permanent failure.
>
> Additional sanctioned route in the same amendment: `docs-api` may call
> `GET /internal/users/by-google-sub/{sub}` on `identity-api` at boot for
> owner resolution. Read-only; cached for the JVM lifetime; fail-closed
> (empty public feed) if identity is unreachable. This is **not** a new
> direction (gateway → identity HTTP was already allowed); it extends the
> allowance to docs-api scoped to that one internal route.
>
> See ADR-12 §2 + §8 for the full specification.

(Note: ADR-08 already has a "Superseding — M2 amendments" section that
captures Exception 1 (rag-ingestion → docs) and Exception 2 (rag-ingestion →
Redis). ADR-12's amendment **supersedes the prior section's Exception 1**
with the concrete route pin and adds the new identity lookup exception.)

### Amendment to ADR-09 (Public Route Policy)

> Amendment (2026-05-17, ADR-12): the public route allowlist is extended to
> cover the unified `/api/docs/**` namespace (the `/public` prefix that
> earlier drafts assumed is **removed** in M2 spec v5). Specifically:
>
> - `GET /api/docs` — public feed list (owner-filtered).
> - `GET /api/docs/{id}` — single document (per-doc visibility-OR-ownership
>   gate enforced at the docs service; returns 404 for private docs the
>   caller doesn't own, indistinguishable from missing).
> - `POST /api/docs/{id}/view` — view counter increment (anonymous OK,
>   24h Redis dedup).
> - `GET /api/docs/search?scope=public` — full-text search over the
>   owner-filtered public corpus.
>
> The boot-time `GET /internal/users/by-google-sub/{sub}` route on
> `identity-api` (per ADR-12 §8) is **not** in the public allowlist — it
> lives under `/internal/**` and is reachable only on the compose-internal
> network.
>
> **Per-IP rate limits** for the anonymous reads above are pinned in ADR-12 §7
> (60/min for list+detail, 30/min for view increment, 10/min for search).
> If the M4 rate-limit filter (per ADR-09 §"Rate-limit and cost protection")
> has not yet shipped at M2 closure, M2 may stub the filter to a no-op; M4
> retro-fits the enforcement.

(Note: ADR-09's original `GET /api/docs/public/**` row in the allowlist
table is **superseded** by this amendment — the namespace flattens to
`/api/docs/**` with per-route visibility gating.)

## Open questions deferred beyond M2

These are explicitly out of scope for M2 and noted here so the next
milestone's architect doesn't re-litigate them:

- **Slug rename action (P1)** — does renaming the slug 301-redirect from
  the old slug? Spec §11 Q5 lists this for an M2.1 or later cycle.
- **Anonymous viewer like-button ergonomics** — spec §11 Q13 confirmed
  "render disabled with sign-in tooltip" as the working default; the design
  doc finalizes the visual.
- **Author display name in OpenSearch hits** — spec §11 +ADR-12 §12 accept
  P0 drift; M2.1 can subscribe to `identity.user.profile-updated`.
- **Counter drift beyond nightly resync** — the nightly job repairs
  `like_count` from `document_likes`. `view_count` has no source-of-truth
  table in M2; drift is informational only. If the audience scope expands,
  a `docs.document_view_log` append-only table becomes the source of
  truth.
- **Multiple docs-api instances** — out of scope for M2 (single-instance);
  if M5 or beyond fans out, the `@Scheduled` resync job needs ShedLock,
  and the owner-resolution cache needs cluster-wide invalidation.

## Diagrams

### docs-api request flow (HTTP + Kafka + OpenSearch)

```
Browser (anonymous)                   Browser (authenticated)
    │                                     │
    └────── /api/docs[/{id}|/search] ─────┘
                  │ via gateway (ADR-07)
                  ▼
        ┌─────────────────────┐
        │   docs-api (18082)  │
        │  ──────────────     │
        │  HTTP controllers   │
        │  (docs-api module)  │
        └──┬──────────────┬───┘
           │              │
   read    │              │  write
           │              │
           ▼              ▼
    ┌──────────┐    ┌──────────────────────────┐
    │ Postgres │    │ Postgres (TX)            │
    │ docs.*   │    │   1. docs.documents      │
    │ (read)   │    │   2. event_publication   │
    └────┬─────┘    │      (Modulith outbox)   │
         │          └──────────────┬───────────┘
   join  │                         │
         │                         │  Modulith → Kafka bridge
         │                         ▼
         │                ┌───────────────────────┐
         │                │ Kafka                 │
         │                │  docs.document.*      │
         │                └────────┬──────────────┘
         │                         │
         │                         ▼
         │                ┌─────────────────────────────┐
         │                │ DocsSearchProjector         │
         │                │ (in docs-app; bean)         │
         │                │   1. read row from Postgres │
         │                │   2. PUT /docs-v1/_doc/{id} │
         │                └────────┬────────────────────┘
         │                         │
         │                         ▼
         │                ┌────────────────────┐
         │                │ OpenSearch         │
         │                │ docs-v1            │
         │                └────────┬───────────┘
         │                         │
         └─────────────────────────┘
            (search controller queries OpenSearch
             AND joins owner-filter at index time)


               ┌──────────────────────────────────────┐
               │ rag-ingestion-infra (M3, future)     │
               │ consumes docs.document.uploaded      │
               │  → GET docs-api/internal/...body    │
               │  → embeds + writes pgvector          │
               └──────────────────────────────────────┘
```

### Owner resolution at docs-api boot

```
docs-api startup
   │
   │ @PostConstruct OwnerResolverAdapter.bootstrapOwner()
   ▼
GET http://identity-api:18081/internal/users/by-google-sub/{sub}
   │
   ├── 200 {id, googleSub} ──▶ cache owner UUID in JVM memory
   │
   ├── 404 (no row) ─────────▶ log WARN, public feed returns []
   │
   └── timeout / 5xx ────────▶ retry 3x with backoff; on final fail,
                                lazy-retry on next /api/docs request
```

## Consequences

- Positive: the docs BC ships with the same outbox pattern M1 used —
  backend-implementer has one mental model across BCs, code-reviewer has
  one shape to check.
- Positive: OpenSearch joins the data layer without breaking ADR-05's
  "Postgres is the source of truth" invariant — every OpenSearch state is
  rebuilt-able from Postgres + Kafka.
- Positive: rate limits on the unauthenticated public reads are pinned
  with concrete numbers, not deferred to "M4 will figure it out". M4
  retro-fits enforcement on the M2 numbers; M2's contract is stable.
- Positive: owner resolution via HTTP (not cross-schema SELECT) preserves
  ADR-08's schema isolation; the next BC that needs to read identity data
  borrows the same pattern.
- Positive: BlockNote's SSR-unsafe import is contained to one dynamic
  wrapper file; the rest of the frontend stack stays SSR-compatible.
- Negative: the OpenSearch container adds ~600 MB RAM to the dev compose
  footprint. Acceptable on the host machine; flagged if the audience
  scope expands and we ever target laptops.
- Negative: BlockNote's ~400 KB gzipped editor chunk is the largest
  single-feature cost in the frontend. Acceptable because the chunk loads
  only on the authenticated editor routes.
- Negative: the nightly counter resync job is the simplest correctness
  mechanism, but the worst case (a Postgres failure during a like-toggle
  followed by 24 hours of drift) is invisible to the user — accepted
  trade-off per spec §10.
- Negative: choosing HTTP for both M3 → docs body-fetch and docs → identity
  owner-resolution introduces two new compile-time WebClient adapters that
  the test matrix must cover. Each is small; the test discipline is
  inherited from ADR-10 §4's identity bootstrap pattern.

## Related

- ADR-01 v2 — quadruplet module layout, docs-api port 18082
- ADR-02 — DDD layering rules the docs quadruplet inherits
- ADR-03 — Kafka envelope, topic naming, DLQ convention
- ADR-05 (amended below by this ADR) — Postgres + OpenSearch
- ADR-07 — gateway routing, OAuth, `/internal/**` not forwarded
- ADR-08 (amended below by this ADR) — inter-service comms, BC-to-BC HTTP
  exception (rag-ingestion → docs, docs → identity)
- ADR-09 (amended below by this ADR) — public route allowlist
- ADR-10 §8 — Spring Modulith Events outbox (inherited by M2)
- ADR-11 — shared exception hierarchy used in all docs-api responses
- `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` — M2 spec v5
  (the source of truth this ADR resolves §11 against)
- `docs/prd/M2-docs.md` — PM's PRD (parallel session, not yet landed)
- `docs/design/M2-docs.md` — designer's Stage-2 design (parallel session)
