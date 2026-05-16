# Spec: M2 — Docs BC (design)

**Date:** 2026-05-16
**Status:** Draft (brainstorming output) — v2 (incorporates: Apps-only sidebar grouping, OpenSearch full-text, owner-filtered blog feed, explicit RAG handoff trace)
**Audience:** the PM agent who will write `docs/prd/M2-docs.md` when the M2 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/NN-m2-docs.md`; the human reviewer.
**Relationship to other docs:**
- Supersedes the M2 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- **Partially supersedes** the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` §8.1 (sidebar) and §9 (home composition) — see §7 below.
- Will be the canonical input for the M2 PRD (Stage-1 PM run, future cycle).
- References, does not supersede: ADR-09 (`09-public-route-policy.md`).
- **Will amend ADR-05** (`05-data-store.md`, which currently pins Postgres + pgvector only) to add OpenSearch as a second-tier search store — formal amendment happens in the M2 per-milestone ADR.

## 1. Purpose

Pin the bounded context, data model, public surface, search surface, and event contract for M2 (Docs) so that:
1. The PM agent writes a PRD that does not re-litigate decisions already made here.
2. The frontend Stage-2 designer knows what screens exist and what data each one expects.
3. M3 (RAG-Ingestion) can be planned in parallel against a stable event contract.

This spec **does not** describe the M2 PRD's user-story list, the architect's port assignment, the per-milestone ADR's library choices, or the Stage-2 visual design. Those land in their canonical homes when the M2 cycle opens.

## 2. Scope summary

### In scope (P0)
- Authoring of Markdown documents by an authenticated user (in-app split-view editor + `.md` file upload)
- A single `Document` entity with optional `PublishMeta` child for the publish-time fields
- `visibility` toggle (`private` → `public`) with safe re-publish (slug preserved)
- Public read surfaces: `/essays` (list) and `/essays/{slug}` (single)
- Private read/edit surfaces: `/docs` (my list), `/docs/new`, `/docs/{id}` (edit), `/docs/search`
- **Full-text search backed by OpenSearch** — `GET /api/docs/search` with `mine` and `public` scopes, fed by a Kafka consumer inside the docs service that mirrors writes to OpenSearch
- **Owner-filtered public feed** — `GET /api/docs/public` returns only documents authored by the platform owner (resolved via `PLAYGROUND_OWNER_GOOGLE_SUB` env var); home's "Latest from JeekLee's blog" section consumes this
- **RAG handoff** — every `docs.document.uploaded` event reaches M3, which embeds the body and stores chunks scoped to the author's `user_id`. M4's `/api/rag/chat/private` then retrieves those chunks for the same user, giving the author a chat grounded in their own documents. M2 owns the contract; M3 and M4 own the behavior.
- Three Kafka events for downstream consumers
- Server returns raw MD + metadata; rendering happens in Next.js with `unified` + `remark` + `rehype` + `shiki`
- External-URL images only

### Deferred to M2.1 (P1, same milestone bucket if cycle has slack)
- Image / attachment upload (presigned to local volume or Postgres `bytea`, decided in M2.1 ADR)
- Editor auto-save
- `⌘K` global palette wiring `/api/docs/search` into a keyboard-driven UI (the search API is P0; the keyboard palette is P1)
- Cover image on essays

### Out of scope (P2, separate future milestone)
- Tags / categories
- Comments
- RSS / Atom feeds
- Version history / diff view
- Multi-author (the site stays single-author; owner is configured at deploy time)

## 3. Bounded Context: Docs

- **책임 (Responsibility):** Owns user-authored Markdown content end-to-end — storage, lifecycle, visibility, and **full-text indexing** (mirroring to OpenSearch). Owns the `visibility` flag that ADR-09 made canonical for public-vs-private retrieval. Owns the **owner-filter** semantics for the public feed. Does **not** own embeddings, vector chunks, or any RAG concern — those are M3.
- **외부 의존성 (External deps):**
  - `identity` (M1): only via `X-User-Id` / `X-User-Sub` headers on authenticated routes. No HTTP call from `docs` → `identity` at runtime.
  - `shared-kernel`: event envelope, common DTOs.
  - Postgres `docs` schema (source of truth).
  - **OpenSearch** (search projection) — pinned to a single index `docs-v1`. Concrete version + client library belong to the per-milestone ADR.
  - Kafka.
- **누가 docs를 호출하나:**
  - Gateway → `docs` for all `/api/docs/**` traffic (per ADR-07/08).
  - `rag-ingestion` (M3) — **read-only** access to fetch raw MD body when an event arrives. The exact mechanism (HTTP via gateway-internal route, or read-only DB role on `docs` schema) is an M2 per-milestone ADR decision. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception and must be reflected in either a superseding ADR or a per-milestone ADR.

## 4. Data model

### 4.1 Postgres tables (`docs` schema — source of truth)

```sql
CREATE TABLE docs.documents (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL,                          -- identity.users.id (app-level FK; no cross-schema constraint)
  title       TEXT         NOT NULL,
  body        TEXT         NOT NULL,                          -- raw Markdown, GFM-flavored
  visibility  TEXT         NOT NULL DEFAULT 'private'
              CHECK (visibility IN ('private', 'public')),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_docs_user_updated      ON docs.documents (user_id, updated_at DESC);
CREATE INDEX ix_docs_public_published  ON docs.documents (visibility, updated_at DESC)
  WHERE visibility = 'public';

CREATE TABLE docs.publish_meta (
  document_id     UUID         PRIMARY KEY
                  REFERENCES docs.documents(id) ON DELETE CASCADE,
  slug            TEXT         NOT NULL,
  excerpt         TEXT         NOT NULL,
  cover_image_url TEXT,                                       -- P1; nullable for M2
  published_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_publish_meta_slug ON docs.publish_meta (slug);
```

### 4.2 OpenSearch index (`docs-v1` — search projection)

```jsonc
{
  "settings": { "analysis": { /* English + Korean analyzer; nori or seunjeon — ADR call */ } },
  "mappings": {
    "properties": {
      "documentId":   { "type": "keyword" },
      "userId":       { "type": "keyword" },
      "title":        { "type": "text", "fields": { "raw": { "type": "keyword" } } },
      "body":         { "type": "text" },
      "visibility":   { "type": "keyword" },
      "slug":         { "type": "keyword" },
      "isOwnerDoc":   { "type": "boolean" },              // pre-computed at index time
      "updatedAt":    { "type": "date" }
    }
  }
}
```

Rules:
- `isOwnerDoc = (userId == PLAYGROUND_OWNER_GOOGLE_SUB resolved to user_id)`. Indexed pre-computed so the public-feed query is a single boolean filter.
- The index is a **projection**, not a second source of truth. If it diverges from Postgres, Postgres wins. A `docs reindex` admin command (P1) rebuilds from Postgres.
- Body is mirrored verbatim (raw MD). Highlight queries can strip MD syntax client-side or via a `keep_only_text` analyzer chain (ADR call).

### 4.3 Field rules
- **`title`** is required at create time. Editor enforces non-empty; the API rejects empty/whitespace-only.
- **`body`** can be empty at create (drafting). Must be non-null (use `''`).
- **`visibility`** starts as `private`. The only state transitions are `private → public` (publish) and `public → private` (unpublish). No direct UPDATE of this column is exposed; only the publish/unpublish endpoints mutate it.
- **`slug`** is derived from `title` at first publish (kebab-case, lowercased ASCII, non-ASCII chars stripped, then `-2`, `-3`, … suffix on collision). User-editable on the publish modal before confirming. **Once set, the slug is the document's permanent public URL** — re-publish after unpublish reuses the existing `publish_meta` row. The user may rename the slug via a separate explicit action (P1).
- **`excerpt`** at first publish defaults to the first 200 characters of the rendered body with Markdown stripped, plus an ellipsis. User-editable.
- **`cover_image_url`** is nullable in M2; the UI does not surface it until M2.1.
- **`created_at` / `updated_at`** are wall-clock UTC. `updated_at` bumps on any column change (DB trigger or app-level — architect's call).

### 4.4 State machine

```
                   ┌────────────────────────────────┐
                   v                                │
   (none) ──create──▶ private (no PublishMeta)      │
                              │                     │
                              │ publish             │ edit (title/body)
                              ▼                     │
                       public (PublishMeta set)─────┘
                              │
                              │ unpublish
                              ▼
                       private (PublishMeta retained)
                              │
                              │ publish again
                              ▼
                       public (same PublishMeta, same slug)
```

Hard delete is reachable from any state and cascades `publish_meta` + OpenSearch document.

## 5. Event surface

All envelopes follow the shared-kernel contract (ADR-03). Topic names use `<bc>.<aggregate>.<verb-past-tense>`.

| Topic | Trigger | Payload keys | Idempotency key | Consumed by (M2 ships) |
|---|---|---|---|---|
| `docs.document.uploaded` | Document created **or** `body` changed on PATCH | `documentId`, `userId`, `visibility`, `title`, `bodyChecksum` | `documentId + bodyChecksum` | docs-search-projector (in-service), rag-ingestion (M3, after M3 ships) |
| `docs.document.deleted` | Hard delete committed | `documentId`, `userId` | `documentId` (terminal event) | docs-search-projector, rag-ingestion (M3+) |
| `docs.document.visibility-changed` | publish or unpublish committed | `documentId`, `userId`, `oldVisibility`, `newVisibility`, `slug` (present when `newVisibility='public'`) | `documentId + newVisibility` | docs-search-projector, rag-ingestion (M3+) |

**Rules:**
- Payload **never** contains the raw `body`. Consumers fetch the body separately (see §3 external deps).
- `bodyChecksum` is SHA-256 of the raw MD; lets consumers short-circuit when an edit didn't actually change content.
- A `title`-only or `visibility`-only change does **not** emit `uploaded` (the embedding chunks don't need re-computation). The search projector handles title/visibility deltas via the `visibility-changed` event or by listening on a dedicated `docs.document.metadata-changed` (decision deferred to per-milestone ADR — for M2 P0 the projector can accept that title edits don't re-index, with the trade-off accepted in writing).
- All three events are published transactionally with the DB write (outbox pattern recommended; the architect's per-milestone ADR picks library — Debezium vs. Spring Modulith outbox vs. a hand-rolled outbox table).

### 5.1 In-service search projector (new module, M2 P0)

The `docs` service hosts a Kafka consumer (`docs-search-projector`) that subscribes to all three `docs.document.*` topics and mirrors changes into OpenSearch. Lives in the same deployable but is a separate Spring bean / module per ADR-02 layering. Failure to write to OpenSearch must not block the original DB transaction — search lag is acceptable, ghost rows in OpenSearch are not.

## 6. HTTP surface

### 6.1 Public routes (no auth — allowlisted per ADR-09)

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/docs/public` | — | `{ items: PublicEssayListItem[], nextCursor: string? }`. **Owner-filtered:** only documents where `user_id` matches the resolved `PLAYGROUND_OWNER_GOOGLE_SUB` AND `visibility='public'`. Cursor pagination; page size 20. Sort: `published_at DESC`. |
| GET | `/api/docs/public/{slug}` | — | `PublicEssayDetail` (see §6.4). 404 if no row has that slug **and** `visibility='public'`. **Not** owner-filtered — anyone's published essay is reachable by direct slug if it exists; the owner filter only governs the discovery feed. |
| GET | `/api/docs/search?q=...&scope=public` | — | OpenSearch-backed full-text search over `visibility='public'` + `isOwnerDoc=true`. Returns `{ items: SearchHit[], nextCursor: string? }` with highlight snippets. Rate-limited at the gateway (anti-scrape, per-IP soft cap). |

Public routes carry no `X-User-Id` header (ADR-09). Backend treats absence as "anonymous reader."

### 6.2 Authenticated routes

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/docs/mine` | — | `{ items: MyDocListItem[], nextCursor: string? }`. Cursor pagination; sort `updated_at DESC`. |
| GET | `/api/docs/search?q=...&scope=mine` | — | OpenSearch over `userId = X-User-Id` (any visibility). Returns highlighted hits. |
| POST | `/api/docs` | `CreateDocRequest` (JSON) **or** `multipart/form-data` with a `.md` file + optional `title` | `MyDocDetail` (201). |
| GET | `/api/docs/{id}` | — | `MyDocDetail`. 404 if not owned by caller (do not leak existence). |
| PATCH | `/api/docs/{id}` | `PatchDocRequest` (`title?`, `body?`) | `MyDocDetail`. |
| POST | `/api/docs/{id}/publish` | `PublishRequest` (`slug?`, `excerpt?`) | `MyDocDetail` (now with PublishMeta). |
| POST | `/api/docs/{id}/unpublish` | — | `MyDocDetail` (visibility=private, PublishMeta retained). |
| DELETE | `/api/docs/{id}` | — | 204. |

Ownership check on every authenticated route: `WHERE user_id = X-User-Id`. A user fetching another user's doc gets the same 404 as a missing doc.

### 6.3 Owner resolution

- `PLAYGROUND_OWNER_GOOGLE_SUB` is set in the gateway and docs service environments at deploy time.
- On boot, the docs service resolves `owner_user_id = SELECT id FROM identity.users WHERE google_sub = $env` (or via a gateway-internal call to identity if cross-schema reads are disallowed by ADR-08 amendment). Result is cached in memory.
- If the env var is unset or the lookup returns no row, public-feed endpoints return an empty list (fail-closed); a startup log line at WARN flags the misconfiguration.
- This env var is the system's **only** notion of "owner." No DB column, no role.

### 6.4 DTOs (sketch — final shapes belong to the per-milestone ADR / OpenAPI)

```ts
// Sketch only — final field names mirror Java/Kotlin record conventions per ADR-02.
PublicEssayListItem  = { slug, title, excerpt, publishedAt }
PublicEssayDetail    = { slug, title, body /*raw MD*/, excerpt, publishedAt, updatedAt }
MyDocListItem        = { id, title, visibility, slug?, updatedAt }
MyDocDetail          = { id, title, body, visibility, publishMeta?: { slug, excerpt, publishedAt }, createdAt, updatedAt }
SearchHit            = { documentId, title, slug?, visibility, snippet /* highlighted */, updatedAt }
CreateDocRequest     = { title: string, body?: string }
PatchDocRequest      = { title?: string, body?: string }
PublishRequest       = { slug?: string, excerpt?: string }
```

### 6.5 Error semantics

- `400` — empty title on create, empty/whitespace title on PATCH, slug-collision on publish (with `{ availableSuggestions: [...] }`)
- `401` — authenticated route with no `X-User-Id`
- `404` — doc not found OR not owned by caller (intentionally indistinguishable)
- `409` — slug collision when user explicitly chose the colliding slug
- `413` — body exceeds size cap (TBD by per-milestone ADR; sensible default ~1MB raw MD)
- `503` — OpenSearch unreachable on search routes (the rest of M2 still works; only search degrades)

## 7. UX surfaces (supersedes design system §8.1 + §9 in part)

### 7.1 Sidebar (replaces design system §8.1 items 3–4)

The Apps / Workspace split is dropped. All Documents-related navigation lives in a single Apps section as one entry:

```
APPS
⌂ Home               (M1)
▤ Documents          (M2)
💬 Chat              (M4, when shipped)
📊 System status     (M5, when shipped)
```

The Workspace section is removed. Per-document actions (Write, Publish, Search, View public) live inside the Documents surface, not in the sidebar.

`Documents` row behavior:
- **Logged out:** entry visible, click lands on `/essays` (public-read list). No 🔒 — the public face of Documents is reachable.
- **Logged in:** entry visible, click lands on `/docs` (my list). A small numeric badge shows `published / total` (e.g., `4/12`).
- Active state lights up `accent.soft` for any route under `/docs/**` or `/essays/**`.

### 7.2 Client routes (Documents surface)

| Route | Auth | Purpose |
|---|---|---|
| `/essays` | public | Owner-filtered public essay list (consumes `GET /api/docs/public`). |
| `/essays/{slug}` | public | Single public essay (consumes `GET /api/docs/public/{slug}`). |
| `/docs` | auth | My documents list. Has a tab/segment switcher: `All / Drafts / Published`. Top-right `Search` input + `New document` button. |
| `/docs/new` | auth | New document editor (split-view). |
| `/docs/{id}` | auth | Edit existing document. Top-right has `Publish` / `Unpublish` / `Delete` and a `View public` link if published. |
| `/docs/search` | auth | Full-page search results (scope toggle: `mine / public`). `⌘K` palette also lands here when the user hits Enter. |

### 7.3 Home composition (supersedes design system §9 item 3)

The `Latest from the blog` section is renamed to **`Latest from JeekLee's blog`** and sources only owner-authored public documents (`GET /api/docs/public`, already owner-filtered). Visual treatment (3-column thumbnail grid) is unchanged from §9. Empty-state copy (pre-M2) is unchanged.

## 8. RAG handoff trace (informational — confirms ADR-09)

M2's only RAG responsibility is publishing accurate `docs.document.*` events. The downstream chain that gives the user a chat grounded in their own documents:

```
M2 (docs)                      M3 (rag-ingestion)              M4 (rag-chat)
─────────────                  ──────────────────              ─────────────
user uploads/edits doc
  │
  └─ writes docs.documents row
  └─ emits docs.document.uploaded ───▶ consumes event
                                       fetches body from docs
                                       chunks + embeds (BGE-M3)
                                       writes to pgvector with
                                         (chunk, user_id, visibility)
                                                                       │
user starts chat in M4                                                 │
  └─ POST /api/rag/chat/private (X-User-Id=alice) ──────────────────▶  │
                                                                       │
                                                                       retrieves chunks
                                                                       WHERE user_id=alice
                                                                       (returns alice's own docs as context)
                                                                       │
                                                                       generates answer
                                                                       (Qwen3-32B) citing alice's docs
                                                                       │
                                       ◀────── stream tokens to alice ◀┘
```

If the user later toggles visibility, `docs.document.visibility-changed` re-tags the chunks (ADR-09 §"Public retrieval scoping"). No M2 work needed beyond emitting the event.

## 9. Markdown feature scope (M2)

- **GFM:** tables, code fences with language, task lists, strikethrough, autolinks.
- **Code highlighting:** `shiki`, theme matches design system tokens (the design system spec already pins olive accent — code blocks use a complementary muted theme; design-time decision).
- **Images:** `![alt](https://…)` external URLs only. The renderer rejects `data:` URLs and unscoped relative paths.
- **Math / Mermaid:** out of scope for M2. P2.
- **HTML in MD:** sanitized out (rehype-sanitize). No raw `<script>`, no event handlers.

## 10. Non-functional requirements

- **Tenant isolation:** every authenticated query MUST include `WHERE user_id = X-User-Id`. A repository-level guard (e.g., a `@WithCurrentUser` interceptor that injects the predicate) is preferred over relying on every query author. The OpenSearch `mine`-scope query MUST filter on `userId` as a `term` filter, not as a should-clause.
- **Outbox correctness:** events and DB writes succeed atomically. If outbox is not implemented in M2, the architect's ADR must document the alternative (and accept the at-most-once risk window in writing).
- **Search projection lag tolerance:** acceptable lag from DB write to OpenSearch visibility is ≤ 2 seconds (P95). Slower than that, the docs service emits a WARN log per delayed projection.
- **Search projection failure isolation:** OpenSearch unavailability MUST NOT block API writes. Failed projections retry via Kafka redelivery; the per-milestone ADR pins the retry/backoff policy.
- **Public retrieval correctness (ADR-09 invariant):** `GET /api/docs/public/**` MUST never return `visibility='private'` rows. Integration test mandatory.
- **Owner-filter correctness:** `GET /api/docs/public` (the list) MUST never return rows where `user_id != owner_user_id`, even if other users have public essays. Integration test mandatory.
- **Observability:** every state transition (create, publish, unpublish, delete, body-edit) emits a structured log line at INFO with `documentId`, `userId`, `event`, and `bodyChecksum` where relevant. Search projector emits separate INFO/WARN on each batch.
- **Body size cap:** enforced at the API and via DB column constraint or trigger.
- **Slug stability:** once published, the public URL of an essay must survive unpublish/republish cycles unchanged. Test mandatory.

## 11. Open questions for the per-milestone ADR (M2)

These intentionally land in the architect's per-milestone ADR, not here:

1. **Outbox library / pattern** — Spring Modulith Events vs Debezium vs hand-rolled outbox table.
2. **M3 body fetch mechanism** — gateway-internal HTTP route vs. read-only DB role on the `docs` schema. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception, so either ADR-08 is superseded for this one read path, or the per-milestone ADR formally records the exception.
3. **Editor library** — `@uiw/react-md-editor` vs. a thin CodeMirror 6 + preview combo vs. plain textarea + preview. Affects bundle size and accessibility.
4. **Body size cap** — concrete number (default ~1MB) and where it's enforced.
5. **Slug rename action (P1)** — does renaming the slug 301-redirect from the old slug? If yes, do we need a `publish_meta_aliases` table?
6. **Rate limit for unauthenticated public reads** — `/api/docs/public/**` is cheap (no LLM); `/api/docs/search?scope=public` is expensive enough to merit a soft per-IP cap. Concrete numbers belong to the ADR.
7. **OpenSearch version + client** — OpenSearch 2.x vs. 1.x; Spring Data OpenSearch vs. the native low-level client. Affects docker-compose tag and dependency tree. Also: single-node vs. 3-node minimum (single-node for dev is fine; prod is still single-host).
8. **Korean analyzer** — `nori` (built-in) vs `seunjeon`; affects search quality on Korean essays.
9. **Metadata-only event topic** — do we add a `docs.document.metadata-changed` event for title/visibility-only edits so the search projector can keep title in sync without re-firing `uploaded`? Trade-off vs. accepting stale titles in search.
10. **Owner resolution path** — cross-schema SELECT from `docs` into `identity.users` (simple, breaks schema isolation) vs. an HTTP call to identity at boot (adds a startup-time dependency). Affects ADR-08 amendment scope.

## 12. Acceptance criteria (refinement of `roadmap.md` M2)

Replaces the M2 bullet list in `docs/roadmap.md` when the M2 cycle opens. The original five bullets are preserved; new ones expand on what the design system, ADR-09, and this spec promise.

- [ ] Authenticated user can create a document via the in-app editor and via `.md` file upload, both producing a stable document id.
- [ ] `GET /api/docs/mine` returns the caller's documents (all visibilities), `GET /api/docs/{id}` returns a single doc — both **404 when the caller is not the owner**.
- [ ] `GET /api/docs/public` and `GET /api/docs/public/{slug}` work **without an auth header** and only return `visibility='public'` rows.
- [ ] `GET /api/docs/public` (list) returns **only owner-authored** essays — non-owner public essays are excluded even if they exist. Verified by integration test seeding a non-owner public essay and asserting it is absent.
- [ ] `POST /api/docs/{id}/publish` sets `visibility='public'`, creates `publish_meta` with a slug derived from the title (collision-resolved), and the essay is immediately reachable at `/essays/{slug}`.
- [ ] `POST /api/docs/{id}/unpublish` flips `visibility='private'`, retains `publish_meta`, and re-publishing later returns the **same slug**.
- [ ] Hard delete removes the document, cascades `publish_meta`, removes the OpenSearch entry, and emits `docs.document.deleted`.
- [ ] `docs.document.uploaded` is emitted on create and on body change (verified by checking the topic during an integration test) using the shared-kernel envelope.
- [ ] `docs.document.visibility-changed` is emitted on publish and unpublish with the correct `oldVisibility` / `newVisibility`.
- [ ] Public read endpoint integration test proves no `visibility='private'` row can leak through `/api/docs/public/**` under any query.
- [ ] Tenant isolation test proves user A cannot read or mutate user B's document via any authenticated route.
- [ ] `/essays/{slug}` renders MD with GFM + syntax highlighting in the design-system prose treatment; the body comes from `GET /api/docs/public/{slug}`, rendering is client-side SSR.
- [ ] The in-app editor at `/docs/new` and `/docs/{id}` is a split-view: raw MD on the left, live prose preview on the right, both using the same `unified` pipeline that renders `/essays/{slug}`.
- [ ] `GET /api/docs/search?q=...&scope=mine` returns OpenSearch-backed hits scoped to the caller; `scope=public` returns hits scoped to owner-authored public docs.
- [ ] OpenSearch projection eventually-consistent: after any `docs.document.*` event, the search index reflects the change within ≤ 2s P95.
- [ ] OpenSearch unavailability returns `503` from search routes but **does not block** writes, reads, or other M2 routes.
- [ ] The sidebar's Documents row is reachable from logged-out state and lands on `/essays`; from logged-in state it lands on `/docs`.
- [ ] Home renders the section labeled **"Latest from JeekLee's blog"** sourced from `GET /api/docs/public` (already owner-filtered).
- [ ] Manual end-to-end check: as the owner, upload a private document, wait for M3 to embed it (after M3 ships), start a private chat in M4, ask a question grounded in that document — the answer cites it. (Cross-milestone test, captured here for traceability; not a blocker for closing M2 alone.)

## 13. What this spec deliberately leaves out

- The **PRD** itself (`docs/prd/M2-docs.md`) — PM agent writes it in Stage 1 of the M2 cycle, using this spec as the source of truth for entity model, events, route surface, and search semantics.
- The **per-milestone ADR** (`docs/adr/NN-m2-docs.md`) — architect writes it in Stage 1 of the M2 cycle, picking libraries, ports, OpenSearch version, outbox pattern, and the ADR-05/ADR-08 amendments.
- The **Stage-2 design doc** (`docs/design/M2-docs.md`) — `product-designer` writes it after the PRD, using the design system spec as the visual source of truth (with this spec's §7 overrides).
- The **design system spec update** — design system spec stays at v1; this M2 spec records the §8.1 and §9 partial supersedes inline. If the supersede surface grows in M3+, promote it to a design-system v1.1.
- Implementation plan and tasks — written by `writing-plans` after this spec is approved.
