# Spec: M2 — Docs BC (design)

**Date:** 2026-05-17 (v5)
**Status:** Draft (brainstorming output) — v5 (incorporates: **multi-author** authorship (any signed-in user writes/manages), `/docs/public` becomes community-wide (was owner-filtered) but home stays owner-curated, **UUID-only URLs** with `slug` and `publish_meta` table eliminated, **single `/docs` namespace** with `/public` prefix dropped, **authorization-based access control** on single-doc endpoint, **directory hierarchy P0** (per-user `path` column on documents), **OpenGraph share preview** via Next.js `generateMetadata`, **derived excerpt** (no DB column; strong-strip MD + 160 chars), **no Publish modal** (instant publish + toast). Supersedes v4 in its entirety.)
**Audience:** the PM agent who will write `docs/prd/M2-docs.md` when the M2 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/NN-m2-docs.md`; the human reviewer.
**Relationship to other docs:**
- Supersedes the M2 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- **Partially supersedes** the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` §8.1 (sidebar) and §9 (home composition) — see §7 below.
- Will be the canonical input for the M2 PRD (Stage-1 PM run, future cycle).
- References, does not supersede: ADR-09 (`09-public-route-policy.md`). ADR-09's public allowlist needs amending in the M2 per-milestone ADR to cover the new `/api/docs` (no `/public` prefix) anonymous-OK routes.
- **Will amend ADR-05** (`05-data-store.md`, which currently pins Postgres + pgvector only) to add OpenSearch as a second-tier search store — formal amendment happens in the M2 per-milestone ADR.

## 0. Terminology (single vocabulary)

The site uses **one noun** for user-authored Markdown content across every layer:

- **Engineering / API / DB / events / frontend routes / UI copy:** always `docs` (BC, schema, route prefix) and `Document` (entity, DTO root). Public-vs-private is a `visibility` modifier on the same noun, not a different noun.
- **No "essay", "post", or "blog"** anywhere.
- **One exception:** the roadmap and the agent-team design spec mention the user's pre-existing external "git blog" — that's a literal reference to a different external system and is unaffected by this rule.

## 1. Purpose

Pin the bounded context, data model, public surface, search surface, and event contract for M2 (Docs) so that:
1. The PM agent writes a PRD that does not re-litigate decisions already made here.
2. The frontend Stage-2 designer knows what screens exist and what data each one expects.
3. M3 (RAG-Ingestion) can be planned in parallel against a stable event contract.

This spec **does not** describe the M2 PRD's user-story list, the architect's port assignment, the per-milestone ADR's library choices, or the Stage-2 visual design. Those land in their canonical homes when the M2 cycle opens.

## 2. Scope summary

### In scope (P0)
- **Multi-author authorship.** Any authenticated user (anyone who completed Google OAuth in M1) can create, edit, publish, unpublish, and delete their own documents. Tenant isolation enforced at the repository layer.
- Authoring of Markdown documents (in-app Notion-style block editor with raw-MD I/O + `.md` file upload).
- A single `Document` entity. No separate `PublishMeta` table — publish-time fields fold into `docs.documents`.
- `visibility` toggle (`private` → `public`) with safe re-publish (URL stable since URL is UUID).
- **Single `/docs` URL namespace.** The `/public` path prefix is removed entirely. Every document has a single canonical URL: `/docs/{id}` where `id` is the document's UUID.
- **Authorization-based access** on single-doc endpoint: `(visibility='public') OR (X-User-Id == doc.user_id)` returns 200; anything else returns 404 (do not leak existence of private docs).
- **Community-wide public document list** (`GET /api/docs`) — every author's public documents, sorted `published_at DESC`. Owner-filter is removed from this list.
- **Community-wide home (revised 2026-05-18).** The home page's `Latest published docs` section sources the community-wide latest feed (`GET /api/docs?limit=6`, no author filter). v5 originally kept this owner-curated; that constraint was dropped after the multi-author shift made the section read as a hole when the owner hadn't published recently. See §7.3 for the current treatment.
- Private read/edit surfaces: `/docs/mine` (my list with directory hierarchy), `/docs/new`, `/docs/{id}` (edit when owner; read when not).
- **Directory hierarchy.** Each document has a `path TEXT NOT NULL DEFAULT '/'` column on `docs.documents`. Folders are **implicit** — derived from distinct path prefixes. No separate `folders` table. UI exposes a left tree pane on `/docs/mine` that lists status filters + folder tree with counts. Move action and folder ops are per-author scoped.
- **Full-text search backed by OpenSearch** — `GET /api/docs/search` with `mine` and `public` scopes, fed by a Kafka consumer inside the docs service that mirrors writes to OpenSearch.
- **Reader engagement signals** — view counter (anonymous + dedup'd) and like toggle (login-required) on public documents. Counters live on the `docs.documents` row; no separate analytics BC for M2.
- **Author identity on public surfaces.** Public docs surface their author's display name + avatar (sourced from `identity.users`). Author name is also indexed in OpenSearch for search highlighting.
- **OpenGraph share preview.** `/docs/{id}` pages render `og:title`, `og:description` (= derived excerpt), `og:url`, `og:type=article`, `og:image` (M2.1), Twitter Card (`summary_large_image`) via Next.js App Router `generateMetadata`. Server-side rendering required for unfurlers (Slack, KakaoTalk, X, Discord).
- **Derived excerpt.** No `excerpt` column. Computed at response serialization: `strip_markdown(body, strong) |> trim |> take(160) |> append('…' if truncated)`. Strong-strip removes heading marks, code blocks, inline code, link URLs (keeping link text), bold/italic markers.
- **No Publish modal.** Clicking `Publish` issues the request immediately and returns a toast (`✓ Published as /docs/{id}` + `[Copy link]` + `[View public]`). Friction is reserved for Unpublish (modal) and Delete (modal).
- **RAG handoff** — every `docs.document.uploaded` event reaches M3, which embeds the body and stores chunks scoped to the author's `user_id`. M4's `/api/rag/chat/private` then retrieves those chunks for the same user.
- Three Kafka events for downstream consumers.
- Server returns raw MD + metadata; rendering happens in Next.js with `unified` + `remark` + `rehype` + `shiki`.
- External-URL images only.
- **Global `⌘K` search palette** — overlay launchable from any authenticated page; queries `GET /api/docs/search` live with `scope=mine` default and `Tab` to toggle `public` (community-wide). Enter on a result opens the document; `⌘+Enter` opens the full `/docs/search` page with the same query.

### Deferred to M2.1 (P1, same milestone bucket if cycle has slack)
- Image / attachment upload (presigned to local volume or Postgres `bytea`).
- `cover_image_url` column on `docs.documents` (drives `og:image` for share previews).
- Editor auto-save.
- Move-to-folder action (`POST /api/docs/{id}/move`) emitting `docs.document.moved` event. M2 P0 ships the read-only directory tree; documents can only be filed at creation time via the editor's path picker.
- Richer `⌘K` command palette beyond search.
- **Comments on public documents** — login-required, owner has sole moderation authority on comments (hard-delete any comment, regardless of which doc they live on).
- **Owner moderation** — owner can hard-delete any public document authored by another user (off-by-default; takedown surface deferred to M2.1+). M2 P0: owner has no special privilege over other users' documents.
- **Undo-after-delete** — 30s tombstone column + soft delete cycle.

### Out of scope (P2, separate future milestone)
- Tags / categories (folder hierarchy is the only organizational primitive).
- RSS / Atom feeds.
- Version history / diff view.
- Engagement-driven ranking (using view/like signals to re-order the public feed or RAG retrieval — counters are stored, not yet consumed for ranking).
- Per-author public landing page (`/by/{author}`).

## 3. Bounded Context: Docs

- **책임 (Responsibility):** Owns user-authored Markdown content end-to-end — storage, lifecycle, visibility, **per-user directory placement**, **full-text indexing** (mirroring to OpenSearch), and **reader engagement signals** (view + like counters on public documents). Owns the `visibility` flag that ADR-09 made canonical for public-vs-private retrieval. Does **not** own embeddings, vector chunks, or any RAG concern — those are M3. Does **not** own comments or owner-moderation surfaces — those land in M2.1.
- **외부 의존성 (External deps):**
  - `identity` (M1): only via `X-User-Id` / `X-User-Sub` headers on authenticated routes. Plus a startup-time / lazy lookup of `display_name + avatar_url` for author info on public surfaces (cached). Mechanism (cross-schema SELECT vs. internal HTTP call to identity) is a per-milestone ADR decision.
  - `shared-kernel`: event envelope, common DTOs.
  - Postgres `docs` schema (source of truth).
  - **OpenSearch** (search projection) — pinned to a single index `docs-v1`. Concrete version + client library belong to the per-milestone ADR.
  - **Redis** — short-TTL dedup keys for anonymous view counting. Already present in compose per M0.
  - Kafka.
- **누가 docs를 호출하나:**
  - Gateway → `docs` for all `/api/docs/**` traffic (per ADR-07/08).
  - `rag-ingestion` (M3) — **read-only** access to fetch raw MD body when an event arrives. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception and must be reflected in either a superseding ADR or a per-milestone ADR.

## 4. Data model

### 4.1 Postgres tables (`docs` schema — source of truth)

```sql
CREATE TABLE docs.documents (
  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID         NOT NULL,                          -- identity.users.id (app-level FK; no cross-schema constraint)
  title         TEXT         NOT NULL,
  body          TEXT         NOT NULL,                          -- raw Markdown, GFM-flavored
  visibility    TEXT         NOT NULL DEFAULT 'private'
                CHECK (visibility IN ('private', 'public')),
  path          TEXT         NOT NULL DEFAULT '/'
                CHECK (path ~ '^(/|(/[a-z0-9][a-z0-9-]*)+/)$'), -- implicit folder hierarchy, per-user namespaced
  view_count    BIGINT       NOT NULL DEFAULT 0,
  like_count    BIGINT       NOT NULL DEFAULT 0,
  published_at  TIMESTAMPTZ,                                    -- NULL while private; set on first publish; retained across unpublish/republish
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_docs_user_updated      ON docs.documents (user_id, updated_at DESC);
CREATE INDEX ix_docs_user_path         ON docs.documents (user_id, path);                            -- folder listing
CREATE INDEX ix_docs_public_published  ON docs.documents (visibility, published_at DESC)
  WHERE visibility = 'public';                                                                       -- community feed
CREATE INDEX ix_docs_author_public     ON docs.documents (user_id, visibility, published_at DESC)
  WHERE visibility = 'public';                                                                       -- owner-filtered home feed + per-author public listing

-- Per-user like (login required; toggle semantics)
CREATE TABLE docs.document_likes (
  document_id  UUID         NOT NULL REFERENCES docs.documents(id) ON DELETE CASCADE,
  user_id      UUID         NOT NULL,
  liked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (document_id, user_id)
);
```

**Eliminated from v4:** `docs.publish_meta` table, `slug` column, `excerpt` column, `cover_image_url` column, `uq_publish_meta_slug` unique index.

`view_count` / `like_count` are denormalized counters maintained alongside the row mutation (single transaction). For low-traffic load, a periodic re-sync job (`COUNT(*) FROM document_likes …`) reconciles drift; the per-milestone ADR picks the cadence (default: nightly).

`path` rules:
- Always starts and ends with `/`. Always lowercase ASCII with hyphens.
- Root is the literal `/`. Nested paths look like `/agents/build-log/`.
- Per-user: user A's `/agents/` is independent from user B's `/agents/`. Folder listings are always scoped by `user_id`.
- Folders are **implicit** — derived from `SELECT DISTINCT path FROM docs.documents WHERE user_id = ?`. No `folders` table. No empty folders (a folder exists only if at least one document lives in it).
- Move action (`POST /api/docs/{id}/move`) is **deferred to M2.1**. M2 P0: path is set at create time only (via the editor's folder picker before first save), and edits to existing docs do not move them. A user wanting to reorganize in M2 P0 must DELETE + re-create at the new path.

### 4.2 OpenSearch index (`docs-v1` — search projection)

```jsonc
{
  "settings": { "analysis": { /* English + Korean analyzer; nori or seunjeon — ADR call */ } },
  "mappings": {
    "properties": {
      "documentId":   { "type": "keyword" },
      "userId":       { "type": "keyword" },
      "authorName":   { "type": "text", "fields": { "raw": { "type": "keyword" } } },  // NEW (v5): for multi-author display + filtering
      "title":        { "type": "text", "fields": { "raw": { "type": "keyword" } } },
      "body":         { "type": "text" },
      "visibility":   { "type": "keyword" },
      "path":         { "type": "keyword" },                                            // NEW (v5): for in-folder mine-scope search
      "publishedAt":  { "type": "date" },
      "updatedAt":    { "type": "date" }
    }
  }
}
```

**Removed from v4:** `slug` field, `isOwnerDoc` field.

Rules:
- The index is a **projection**, not a second source of truth. If it diverges from Postgres, Postgres wins. A `docs reindex` admin command (P1) rebuilds from Postgres.
- Body is mirrored verbatim (raw MD). Highlight queries can strip MD syntax client-side or via a `keep_only_text` analyzer chain (ADR call).
- `authorName` is denormalized from `identity.users.display_name` at index time. A `display_name` change in identity does NOT auto-update existing OpenSearch docs in M2 P0 (drift acceptable for low-update profile). M2.1 may add a `user.display-name-changed` event consumer if drift becomes user-visible.

### 4.3 Field rules
- **`title`** is required at create time. Editor enforces non-empty; the API rejects empty/whitespace-only.
- **`body`** can be empty at create (drafting). Must be non-null (use `''`).
- **`visibility`** starts as `private`. The only state transitions are `private → public` (publish) and `public → private` (unpublish). No direct UPDATE of this column is exposed; only the publish/unpublish endpoints mutate it.
- **`path`** is required (`/` default). Validated against the CHECK regex. Set at create time; not editable in M2 P0 (M2.1 adds `POST /api/docs/{id}/move`).
- **`published_at`** is set automatically on first publish. Retained across unpublish + republish cycles. Never updated by the user. Used to sort the community feed (`/api/docs`), the home feed (`/api/docs?author={owner}`), and as `<meta property="article:published_time">`.
- **`excerpt`** has no column. Computed at every response serialization from `body`. The derivation is canonical and the only acceptable algorithm in M2 P0:
  ```
  excerpt(body) =
    strip_markdown(body, strong)    # Remove: heading marks (#, ##, ...), code blocks ``` ... ```, inline code `...`, link URLs (keep link text), bold/italic markers (**...**, *...*, __..._, _..._), blockquote markers (>), list markers (-, *, 1.). Keep plain text content.
    |> normalize_whitespace          # Collapse consecutive whitespace to single space; trim ends.
    |> take(160)                     # First 160 characters.
    |> append('…' if truncated)      # If original was longer than 160.
  ```
  This produces the field `excerpt: string` in every DTO that includes document metadata (list items, single detail, search hits). 160 chars matches OpenGraph + Twitter Card + Google snippet conventions.
- **`created_at` / `updated_at`** are wall-clock UTC. `updated_at` bumps on any column change (DB trigger or app-level — architect's call).

### 4.4 State machine

```
                   ┌────────────────────────────────┐
                   v                                │
   (none) ──create──▶ private (published_at=NULL)   │
                              │                     │
                              │ publish             │ edit (title/body)
                              ▼                     │
                       public (published_at=now())──┘
                              │
                              │ unpublish
                              ▼
                       private (published_at retained)
                              │
                              │ publish again
                              ▼
                       public (same published_at)
```

Hard delete is reachable from any state and cascades `document_likes` + OpenSearch document.

URL stability: the document's URL is `/docs/{id}` where `id` is the immutable UUID — so URL stability across visibility/publish cycles is automatic (no slug to preserve).

## 5. Event surface

All envelopes follow the shared-kernel contract (ADR-03). Topic names use `<bc>.<aggregate>.<verb-past-tense>`.

| Topic | Trigger | Payload keys | Idempotency key | Consumed by (M2 ships) |
|---|---|---|---|---|
| `docs.document.uploaded` | Document created **or** `body` changed on PATCH | `documentId`, `userId`, `visibility`, `title`, `path`, `bodyChecksum` | `documentId + bodyChecksum` | docs-search-projector (in-service), rag-ingestion (M3, after M3 ships) |
| `docs.document.deleted` | Hard delete committed | `documentId`, `userId` | `documentId` (terminal event) | docs-search-projector, rag-ingestion (M3+) |
| `docs.document.visibility-changed` | publish or unpublish committed | `documentId`, `userId`, `oldVisibility`, `newVisibility`, `publishedAt` (present when `newVisibility='public'`) | `documentId + newVisibility` | docs-search-projector, rag-ingestion (M3+) |

**Rules:**
- Payload **never** contains the raw `body`. Consumers fetch the body separately (see §3 external deps).
- `bodyChecksum` is SHA-256 of the raw MD; lets consumers short-circuit when an edit didn't actually change content.
- A `title`-only or `visibility`-only change does **not** emit `uploaded` (the embedding chunks don't need re-computation). The search projector handles title/visibility deltas via the `visibility-changed` event or by listening on a dedicated `docs.document.metadata-changed` (decision deferred to per-milestone ADR).
- All three events are published transactionally with the DB write (outbox pattern — Spring Modulith Events JPA per the working ADR direction).
- **No `docs.document.moved` event in M2 P0** (move action is M2.1).

### 5.1 In-service search projector (new module, M2 P0)

The `docs` service hosts a Kafka consumer (`docs-search-projector`) that subscribes to all three `docs.document.*` topics and mirrors changes into OpenSearch. Lives in the same deployable but is a separate Spring bean / module per ADR-02 layering. Failure to write to OpenSearch must not block the original DB transaction — search lag is acceptable, ghost rows in OpenSearch are not.

## 6. HTTP surface

### 6.1 Unified `/api/docs` namespace

There is no `/api/docs/public/*` prefix. Every route lives under `/api/docs/*` and uses **per-request authorization** based on the auth header + the document's `visibility` + ownership.

| Method | Path | Auth | Body | Returns |
|---|---|---|---|---|
| GET | `/api/docs` | optional | — | Community feed: documents with `visibility='public'`, all authors. Sort `published_at DESC`. Cursor pagination, page size 20. Returns `{ items: DocListItem[], nextCursor: string? }`. |
| GET | `/api/docs?author={userId}` | optional | — | All public documents by a specific author. Sort `published_at DESC`. Available for future per-author profile views; the home no longer uses it (revised 2026-05-18 — see §7.3). |
| GET | `/api/docs?scope=mine` | required | — | Caller's own documents, all visibilities. Cursor pagination; sort `updated_at DESC`. May combine with `&path={folder}` to filter to a folder. |
| GET | `/api/docs?scope=mine&path={folder}` | required | — | Caller's own documents in a given folder path. |
| GET | `/api/docs/folders` | required | — | Caller's folder tree: `{ items: [{ path: '/agents/', count: 8 }, ...] }`. Computed as `SELECT path, COUNT(*) FROM docs.documents WHERE user_id=? GROUP BY path`. |
| GET | `/api/docs/{id}` | optional | — | Single document. **Authorization rule:** `(doc.visibility='public') OR (X-User-Id == doc.user_id)` → 200 returns `DocDetail`. Anything else → 404. |
| POST | `/api/docs` | required | `CreateDocRequest` (JSON: `title`, `body?`, `path?`) **or** `multipart/form-data` with a `.md` file + optional `title` + optional `path` | `DocDetail` (201). Document starts as `visibility='private'`, `path` defaults to `/`. |
| PATCH | `/api/docs/{id}` | required + owner | `PatchDocRequest` (`title?`, `body?`) | `DocDetail`. Owner-only. 404 if not owner (do not leak existence). |
| POST | `/api/docs/{id}/publish` | required + owner | — (empty body) | `DocDetail` with `visibility='public'` + `publishedAt` set. **No request fields** — slug and excerpt are gone. |
| POST | `/api/docs/{id}/unpublish` | required + owner | — | `DocDetail` with `visibility='private'`, `publishedAt` retained. |
| DELETE | `/api/docs/{id}` | required + owner | — | 204. |
| POST | `/api/docs/{id}/like` | required | — | 204. Upserts `(document_id, X-User-Id)` and increments `like_count` if the row didn't already exist (idempotent). Allowed on any `visibility`; UI only surfaces it on public docs. |
| DELETE | `/api/docs/{id}/like` | required | — | 204. Removes the like; idempotent (no-op if absent). |
| POST | `/api/docs/{id}/view` | optional | — | 204. Increments `view_count` if `visibility='public'`; otherwise no-op (still 204, no leak). Deduplicated via Redis key `view:{id}:{PLAYGROUND_ANON}` (TTL 24h). Missing `PLAYGROUND_ANON` cookie falls back to `view:{id}:ip:{X-Forwarded-For}` with the same TTL. |
| GET | `/api/docs/search?q=...&scope=public` | optional | — | OpenSearch-backed full-text search over all `visibility='public'` docs (community-wide). Returns `{ items: SearchHit[], nextCursor: string? }` with highlight snippets. Rate-limited at the gateway (anti-scrape, per-IP soft cap). |
| GET | `/api/docs/search?q=...&scope=mine` | required | — | OpenSearch over `userId = X-User-Id` (any visibility). Returns highlighted hits. May combine with `&path={folder}` to scope to a folder. |

**Authorization model summary:**
- Authenticated routes (`PATCH`, `POST /publish`, `POST /unpublish`, `DELETE`) require **both** `X-User-Id` present **and** `X-User-Id == doc.user_id`. Mismatch → 404 (not 403; do not leak existence of other users' docs).
- `GET /api/docs/{id}` is the only single-doc read endpoint and applies the visibility-OR-ownership rule (see table).
- The `like`/`view` writes are intentionally lenient: any authenticated user can like any doc (UI gates this to public docs); view is anonymous-OK and only increments for public docs.

### 6.2 Removed routes (vs. v4)

These v4 routes are **deleted**:
- `GET /api/docs/public` (replaced by `GET /api/docs` — no `/public` prefix, no owner filter)
- `GET /api/docs/public/{slug}` (replaced by `GET /api/docs/{id}` — single namespace, UUID identifier)
- `GET /api/docs/mine` (replaced by `GET /api/docs?scope=mine`)
- `POST /api/docs/public/{slug}/view` (replaced by `POST /api/docs/{id}/view`)

### 6.3 Owner resolution (no longer consumed by the home; retained for future per-author surfaces)

> Revised 2026-05-18: the home no longer uses the owner feed (§7.3). The resolution + `GET /api/docs/owner` endpoint stay in place so future surfaces (per-author profile page, attribution lines, etc.) have one consistent source of truth.

- `PLAYGROUND_OWNER_GOOGLE_SUB` is set in the gateway and docs service environments at deploy time.
- On boot, the docs service resolves `owner_user_id = SELECT id FROM identity.users WHERE google_sub = $env` (or via a gateway-internal call to identity). Result is cached in memory.
- `GET /api/docs/owner` → `{ ownerUserId: UUID | null }` is still exposed for callers that need the owner id; it returns `null` when the env var is unset or the lookup misses (fail-closed). A startup log line at WARN flags the misconfiguration.
- This env var is the system's **only** notion of "owner." No DB column, no role. All other surfaces are author-agnostic.

### 6.4 DTOs (sketch — final shapes belong to the per-milestone ADR / OpenAPI)

```ts
// Sketch only — final field names mirror Java/Kotlin record conventions per ADR-02.

Author = {
  id: UUID,                        // identity.users.id
  displayName: string,             // identity.users.display_name
  avatarUrl: string?               // identity.users.avatar_url (nullable)
}

DocListItem = {
  id: UUID,
  title: string,
  excerpt: string,                 // derived per §4.3 (160 chars, strong strip)
  visibility: 'public',            // list endpoints only ever return public for community/owner feeds
  path: string,                    // present but typically '/' for community feed
  author: Author,
  publishedAt: string,
  viewCount: number,
  likeCount: number,
  likedByMe?: boolean              // present when authenticated
}

DocDetail = {
  id: UUID,
  title: string,
  body: string,                    // raw MD
  excerpt: string,                 // derived per §4.3
  visibility: 'private' | 'public',
  path: string,
  author: Author,
  viewCount: number,
  likeCount: number,
  likedByMe?: boolean,             // present when authenticated
  publishedAt?: string,            // present when visibility='public'
  createdAt: string,
  updatedAt: string
}

MyDocListItem = {
  id: UUID,
  title: string,
  excerpt: string,                 // derived
  visibility: 'private' | 'public',
  path: string,
  updatedAt: string,
  publishedAt?: string,            // present when public
  viewCount: number,
  likeCount: number
  // No `author` block — by definition the caller is the author.
}

FolderListItem = {
  path: string,                    // e.g., '/agents/', '/agents/build-log/'
  count: number                    // number of caller's docs at this exact path
}

SearchHit = {
  documentId: UUID,
  title: string,
  visibility: 'private' | 'public',
  path: string?,
  author: Author?,                 // present for public hits; the caller's own hits omit it
  snippet: string,                 // highlighted (OpenSearch highlight)
  publishedAt?: string,
  updatedAt: string
}

CreateDocRequest = { title: string, body?: string, path?: string }
PatchDocRequest  = { title?: string, body?: string }
// PublishRequest deleted — no body fields needed.
```

### 6.5 Error semantics
- `400` — empty title on create, empty/whitespace title on PATCH, invalid `path` format
- `401` — authenticated route with no `X-User-Id`
- `404` — doc not found OR not authorized to view/mutate (intentionally indistinguishable — private docs are not leaked by their endpoints)
- `413` — body exceeds size cap (TBD by per-milestone ADR; sensible default ~1MB raw MD)
- `503` — OpenSearch unreachable on search routes (the rest of M2 still works; only search degrades)

(No `409` slug-collision — slugs are gone.)

## 7. UX surfaces (supersedes design system §8.1 + §9 in part)

### 7.1 Sidebar (replaces design system §8.1 items 3–4)

The Apps section has exactly one navigation list — `APPS` — containing every top-level destination the site will ever expose, visible from M1 onward:

```
APPS
⌂ Home                                  (M1, active when shipped)
▤ Docs              M2 🔒               (M2 — locked until shipped)
💬 Chat             M4 🔒               (M4 — locked until shipped)
📊 System status    M5 🔒               (M5 — locked until shipped)
```

**Locked-item treatment** (when a row's milestone hasn't shipped):
- Row is visible, opacity ~0.72, label text in `text.subtle`. Right-side milestone badge in `text.muted` on `surface.soft`, plus a small 🔒 glyph. Hover: cursor `default`. Click: no-op.

**Shipped-item treatment** (when the milestone is shipped):
- Full opacity, label in `text`, no badge, no 🔒. Hover: `surface` bg. Active: `accent.soft` bg + `accent` label, weight 600.

**`Docs` row behavior (once M2 ships):**
- **Logged out:** click lands on `/docs` (community feed — all authors' public docs).
- **Logged in:** click lands on `/docs/mine` (my list with directory tree). A small numeric badge shows `published / total` for the caller's own docs (e.g., `4/12`).
- Active state lights up `accent.soft` for any route under `/docs` or `/docs/mine` or `/docs/{id}` or `/docs/new` or `/docs/search`.

### 7.2 Client routes (Docs surface)

| Route | Auth | Purpose |
|---|---|---|
| `/` | optional | Home. The `Latest published docs` section sources from `GET /api/docs?limit=6` (community-wide, no author filter — see §7.3 2026-05-18 revision). |
| `/docs` | optional | **Community feed.** All authors' public documents (consumes `GET /api/docs`). Anonymous OK. |
| `/docs/mine` | required | **My documents.** Caller's docs in a directory-tree + list layout (left tree pane: status filters + folder tree from `GET /api/docs/folders`; right list pane: docs in selected folder from `GET /api/docs?scope=mine&path=...`). Has a Search input + `New document` button. |
| `/docs/{id}` | optional | **Single document view/edit.** Read-only for non-owner viewers (if public). Editor for the owner (Notion-style **BlockNote** block editor, "/" command, drag-handles per block). Body roundtrips raw MD via BlockNote's `blockToMarkdownLossy` / `tryParseMarkdownToBlocks`. Top-right toolbar (owner only): `Publish` / `Unpublish` / `Delete` + a `View public` link if published. **No edit-vs-view URL split** — the route is one and the same; the surface adapts based on `X-User-Id == doc.user_id`. |
| `/docs/new` | required | New document editor. On first save, redirects to `/docs/{id}`. Includes a folder picker (defaults to current `/docs/mine` selection or `/` if entered directly). |
| `/docs/search` | required | Full-page search results. Scope toggle (`mine / public`), optional folder filter when scope=mine, cursor pagination. Companion to the global `⌘K` palette. |

### 7.3 Home composition (supersedes design system §9 item 3)

> Revised 2026-05-18: the section was originally owner-curated; in production it now sources the community-wide latest feed (no author filter), matching the `/docs` scope but trimmed by recency to the first 6.

The home's `Latest published docs` section sources the community-wide latest feed (`GET /api/docs?limit=6`, no author filter). Every public document by every author can land here in reverse-chronological order. The `All documents →` link still navigates to `/docs` (the same feed, paginated).

Visual treatment (3-column thumbnail grid) is unchanged from design system §9 except the card meta row, which extends to: `· N min · {date} · 👁 {viewCount} · ♥ {likeCount}`. Icon glyphs are placeholders — the frontend-implementer swaps to Lucide `Eye` and `Heart` (matching the spec §3 emoji-to-icon migration rule).

On the document detail page (`/docs/{id}`, when `visibility='public'`), the same view/like counts render directly under the title in `text.muted`; the like control is an inline button (filled `accent` when `likedByMe`, outline otherwise) that toggles the like via `POST` / `DELETE /api/docs/{id}/like`. Anonymous readers see the button in a disabled "sign in to like" state. **Author row** (avatar + display name) is rendered next to the meta on every card — both on `/` and `/docs` — showing the document's actual author.

### 7.4 OpenGraph + share preview

`/docs/{id}` pages must render the following meta tags via Next.js App Router `generateMetadata({ params })`:

```ts
// Sketch
export async function generateMetadata({ params }) {
  const doc = await fetchDoc(params.id);    // GET /api/docs/{id}; returns 404 if not visible
  if (!doc) return { title: 'Not found · JeekLee\'s playground' };
  return {
    title: `${doc.title} · JeekLee's playground`,
    description: doc.excerpt,
    openGraph: {
      title: doc.title,
      description: doc.excerpt,
      url: `https://playground.jeeklee.../docs/${doc.id}`,
      type: 'article',
      publishedTime: doc.publishedAt,
      authors: [doc.author.displayName],
      images: doc.coverImageUrl ? [{ url: doc.coverImageUrl }] : []   // M2.1
    },
    twitter: {
      card: 'summary_large_image',
      title: doc.title,
      description: doc.excerpt
    }
  };
}
```

Server-side rendering required — unfurlers (Slack, KakaoTalk, X, Discord) don't execute client-side JS. For private docs that 404 to non-owners, `generateMetadata` must also return a 404-shaped object so unfurlers don't index the existence of a private doc title.

## 8. RAG handoff trace (informational — confirms ADR-09)

> **Amended 2026-05-18 (M3 PRD cycle):** earlier draft described M4 with two endpoints (`/api/rag/chat/public` + `/api/rag/chat/private`) and an owner-only public corpus. Superseded by the single-endpoint, header-switched model below.
>
> **Re-amended 2026-05-18 (M4 ADR cycle, ADR-14):** the immediately prior "anonymous or signed-in" framing is itself superseded — `/api/rag/chat` is **authenticated-only**. Anonymous callers receive 401 at the gateway. The anonymous-corpus bullet is dropped from the M4 retrieval contract sub-list below. Canonical statement of the M4 retrieval contract lives in `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract" + ADR-14 §G.

M2's only RAG responsibility is publishing accurate `docs.document.*` events. The downstream chain that gives the caller a chat grounded in documents they're allowed to see:

```
M2 (docs)                      M3 (rag-ingestion)              M4 (rag-chat)
─────────────                  ──────────────────              ─────────────
user uploads/edits doc
  │
  └─ writes docs.documents row
  └─ emits docs.document.uploaded ───▶ consumes event
                                       fetches body from docs (internal HTTP)
                                       chunks + embeds (BGE-M3)
                                       writes to pgvector with
                                         (document_id, chunk_index,
                                          user_id, visibility,
                                          embedding, text)
                                                                       │
an authenticated user starts chat in M4                                │
  └─ POST /api/rag/chat                                                │
        (X-User-Id always present — gateway 401 on absence)  ────────▶ │
                                                                       │
                                                                       retrieves chunks
                                                                       WHERE visibility = 'public'
                                                                          OR (user_id = X-User-Id
                                                                              AND visibility = 'private')
                                                                       ORDER BY cosine_distance
                                                                       LIMIT K
                                                                       │
                                                                       generates answer (Qwen3-32B)
                                                                       citing the matched docs
                                                                       │
                                       ◀────── stream tokens to caller ◀┘
```

If the author later toggles visibility, `docs.document.visibility-changed` re-tags the chunks (ADR-09 §"Public retrieval scoping") — M3 updates the `visibility` column on every chunk row of that document without re-embedding. No M2 work needed beyond emitting the event.

### M4 retrieval contract (canonical — supersedes any earlier reading)

- **Single endpoint**: `/api/rag/chat`, **authenticated-only**. Anonymous callers receive 401 at the gateway. The legacy `/api/rag/chat/public` and `/api/rag/chat/private` split is removed; the interim "anonymous or signed-in" framing is also superseded (re-revised 2026-05-18 by ADR-14).
- **Authenticated caller** (`X-User-Id` present): retrieval corpus = `WHERE visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`. All public docs from every author, plus the caller's own private docs.
- **Never visible**: other users' `private` docs, AND the entire chat surface is closed to unauthenticated callers (no anonymous retrieval corpus exists at all).
- M3's job is to keep the `(user_id, visibility)` pair on every chunk row accurate at all times so the WHERE clause above is the only filter M4 needs. No additional M3 API surface for retrieval.

`/api/rag/chat`'s public-route policy in ADR-09 is updated via the ADR-14 amendment block — the public allowlist row is removed and the rate-limit section is rewritten for authenticated traffic only.

## 9. Markdown feature scope (M2)

- **GFM:** tables, code fences with language, task lists, strikethrough, autolinks.
- **Code highlighting:** `shiki`, theme matches design system tokens.
- **Images:** `![alt](https://…)` external URLs only. The renderer rejects `data:` URLs and unscoped relative paths.
- **Math / Mermaid:** out of scope for M2. P2.
- **HTML in MD:** sanitized out (rehype-sanitize). No raw `<script>`, no event handlers.

## 10. Non-functional requirements

- **Tenant isolation:** every authenticated mutation (`PATCH`, `POST /publish`, `POST /unpublish`, `DELETE`, `POST /move` (M2.1)) MUST verify `X-User-Id == doc.user_id` before any write. Mismatch → 404. A repository-level guard (e.g., a `@WithCurrentUser` interceptor that injects the predicate) is preferred over relying on every query author.
- **Outbox correctness:** events and DB writes succeed atomically. Spring Modulith Events JPA outbox is the working direction per existing ADRs; the per-milestone ADR pins it formally.
- **Search projection lag tolerance:** acceptable lag from DB write to OpenSearch visibility is ≤ 2 seconds (P95). Slower than that, the docs service emits a WARN log per delayed projection.
- **Search projection failure isolation:** OpenSearch unavailability MUST NOT block API writes. Failed projections retry via Kafka redelivery; the per-milestone ADR pins the retry/backoff policy.
- **Authorization correctness (ADR-09 invariant):** `GET /api/docs/{id}` MUST never return a `visibility='private'` row when the caller is not the owner. Integration test mandatory.
- **Community feed correctness:** `GET /api/docs` MUST never return rows where `visibility != 'public'`. Integration test mandatory.
- **Folder listing scoping:** `GET /api/docs/folders` MUST never return a row where `user_id != X-User-Id`. Integration test mandatory.
- **Observability:** every state transition (create, publish, unpublish, delete, body-edit) emits a structured log line at INFO with `documentId`, `userId`, `event`, and `bodyChecksum` where relevant. Search projector emits separate INFO/WARN on each batch.
- **Body size cap:** enforced at the API and via DB column constraint or trigger.
- **URL stability:** the document's URL is `/docs/{id}` where `id` is the immutable UUID — URL stability across visibility/publish/unpublish cycles is automatic. Test mandatory: a published-then-unpublished-then-republished doc keeps the same UUID and is reachable at the same URL.
- **View dedup correctness:** a single anonymous visitor (same `PLAYGROUND_ANON` cookie) hitting the same doc N times within 24h increments `view_count` exactly once. Integration test required. Authors viewing their own documents are **not** excluded from the count — symmetric treatment, no special-casing in M2.
- **Like idempotency:** `POST /api/docs/{id}/like` and `DELETE /api/docs/{id}/like` are both idempotent against repeat invocation. Concurrent like/unlike from the same user is serialized at the DB level (PK contention on `(document_id, user_id)`); the final `like_count` must equal `COUNT(*) FROM document_likes WHERE document_id=?`.
- **Counter drift tolerance:** if `view_count` / `like_count` drift from their source-of-truth queries due to a partial failure, the nightly re-sync job repairs them.
- **OpenGraph rendering:** `/docs/{id}` MUST emit `og:title`, `og:description`, `og:url`, `og:type=article`, `og:publishedTime`, `og:authors[]`, and Twitter Card meta tags on every server-rendered response for `visibility='public'` docs. For non-visible docs (private to non-owner), the page returns 404 and the meta tags are not generated.
- **Excerpt derivation determinism:** the excerpt algorithm in §4.3 is the only acceptable derivation. Integration test mandatory: given a fixed body input, the excerpt output is byte-stable.

## 11. Open questions for the per-milestone ADR (M2)

These intentionally land in the architect's per-milestone ADR, not here:

1. **Outbox library / pattern** — Spring Modulith Events vs Debezium vs hand-rolled outbox table. Working direction: Spring Modulith Events JPA.
2. **M3 body fetch mechanism** — gateway-internal HTTP route vs. read-only DB role on the `docs` schema. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception.
3. **Editor library — DECIDED: BlockNote** (https://www.blocknotejs.org). Per-milestone ADR pins exact `@blocknote/core` + `@blocknote/react` versions, the MD-roundtrip adapter configuration, and the SSR strategy under Next.js App Router. Bundle size and accessibility audit also belong to the per-milestone ADR.
4. **Body size cap** — concrete number (default ~1MB) and where it's enforced.
5. **Rate limit for unauthenticated public reads** — `/api/docs` (community feed) is cheap (no LLM); `/api/docs/search?scope=public` is expensive enough to merit a soft per-IP cap. Concrete numbers belong to the ADR.
6. **OpenSearch version + client** — OpenSearch 2.x vs. 1.x; Spring Data OpenSearch vs. the native low-level client. Affects docker-compose tag and dependency tree. Also: single-node vs. 3-node minimum (single-node for dev is fine; prod is still single-host).
7. **Korean analyzer** — `nori` (built-in) vs `seunjeon`; affects search quality on Korean documents.
8. **Metadata-only event topic** — do we add a `docs.document.metadata-changed` event for title/visibility/path-only edits so the search projector can keep them in sync without re-firing `uploaded`? Trade-off vs. accepting stale titles/paths in search.
9. **Owner resolution path** — cross-schema SELECT from `docs` into `identity.users` (simple, breaks schema isolation) vs. an HTTP call to identity at boot (adds a startup-time dependency). Affects ADR-08 amendment scope.
10. **View dedup TTL** — 24h is the working default. Should authenticated views dedup against `X-User-Id` (longer TTL, more accurate) instead of the anon cookie? For now: same anon-cookie path regardless of auth state, accepted for simplicity.
11. **Counter sync strategy** — denormalized column + nightly re-sync (current default) vs. trigger-based maintenance vs. event-sourced rebuild from a `docs.document.engagement-*` topic.
12. **Anonymous viewer ergonomics** — should the like button render at all for anonymous readers, or should it be hidden entirely? Working default: render disabled with "sign in to like" tooltip.
13. **Undo-after-delete UX.** M2 P0: toast appears but `Undo` link is non-functional. M2.1: tombstone column on `docs.documents` so DELETE is soft for 30s and `Undo` flips the tombstone before the cascade fires.
14. **Author display name drift in OpenSearch.** Spec §4.2 accepts drift in M2 P0 (`authorName` denormalized at index time, no auto-update on identity `display_name` change). The ADR can decide whether to subscribe to a `user.display-name-changed` event for keeping it fresh, or accept drift.
15. **Folder picker UX at create time.** M2 P0 lets the user set `path` only on create. Where in the editor does the path picker live (a small breadcrumb-style picker in the editor header? a modal on first save? a sidebar field?). Visual decision belongs to Stage-2 designer; data contract is `path?` in `CreateDocRequest`.
16. **Owner moderation surface (M2.1+).** When M2.1 introduces owner takedown of other users' public docs, the route shape and audit trail belong to that spec amendment. M2 P0 simply omits the route and the UI affordance.

## 12. Acceptance criteria (refinement of `roadmap.md` M2)

Replaces the M2 bullet list in `docs/roadmap.md` when the M2 cycle opens.

### Authorship + tenant isolation
- [ ] Any authenticated user can create a document via the in-app editor and via `.md` file upload, both producing a stable document id.
- [ ] The `.md` upload path is reachable from two affordances on `/docs/mine`: (a) the `+ New document` button's chevron dropdown row `↑ Import .md…` (opens native file picker), and (b) drag-and-drop of a `.md` file onto the viewport (overlay accepts the drop and POSTs multipart). Non-`.md` files dropped are rejected with a `danger` toast.
- [ ] `GET /api/docs?scope=mine` returns the caller's documents (all visibilities); `GET /api/docs/{id}` returns a single doc.
- [ ] Tenant isolation test: user A cannot read user B's private doc via `GET /api/docs/{id}` (returns 404). User A cannot PATCH / publish / unpublish / delete user B's doc via the respective routes (each returns 404).

### Public surface (community-wide)
- [ ] `GET /api/docs` (no params) works **without an auth header** and returns documents from all authors where `visibility='public'`. Cursor pagination works.
- [ ] `GET /api/docs?author={userId}` works without an auth header and returns only that user's public documents. Verified by integration test seeding two users' public docs and asserting the filter is honored.
- [ ] `GET /api/docs/{id}` for a `visibility='public'` doc works without an auth header. Same call for a `visibility='private'` doc returns 404 to non-owners; returns 200 to the owner.
- [ ] `GET /api/docs` (list) never returns rows where `visibility != 'public'`.
- [ ] Community feed integration test: seed three users' public docs + each user's private doc; assert `GET /api/docs` returns only the public ones and none of the private ones.

### Owner-curated home
- [ ] Home renders `Latest published docs` sourced from the community-wide `GET /api/docs?limit=6` feed (no author filter). Every author's public docs appear in reverse-chronological order. (Revised 2026-05-18 — was owner-only.)

### Publish lifecycle
- [ ] `POST /api/docs/{id}/publish` (with empty body) sets `visibility='public'`, sets `published_at` to `now()` if NULL (else retains existing), and the document is immediately reachable at `/docs/{id}` for anonymous readers.
- [ ] `POST /api/docs/{id}/unpublish` flips `visibility='private'`, retains `published_at`, and `/docs/{id}` then 404s for anonymous readers but 200s for the owner.
- [ ] Re-publish keeps the same URL (URL is the UUID `id`; never changes). Verified by integration test: publish → unpublish → publish → assert URL stable.
- [ ] Hard delete removes the document, cascades `document_likes`, removes the OpenSearch entry, and emits `docs.document.deleted`.

### Events
- [ ] `docs.document.uploaded` is emitted on create and on body change (verified by checking the topic during an integration test) using the shared-kernel envelope.
- [ ] `docs.document.visibility-changed` is emitted on publish and unpublish with the correct `oldVisibility` / `newVisibility` and `publishedAt` (when applicable).
- [ ] `docs.document.deleted` is emitted on hard delete.

### Editor + rendering
- [ ] `/docs/{id}` for the owner renders the **BlockNote** block editor (Notion-style "/" menu + drag-handles). On load, body MD parses to blocks via `tryParseMarkdownToBlocks`; on save, blocks serialize back via `blockToMarkdownLossy`.
- [ ] `/docs/{id}` for non-owner viewers (when `visibility='public'`) renders the document body via `unified` + `remark` + `rehype` + `shiki`. The editor changes the authoring UX, not the reading pipeline.

### Search
- [ ] `GET /api/docs/search?q=...&scope=mine` returns OpenSearch-backed hits scoped to the caller's docs.
- [ ] `GET /api/docs/search?q=...&scope=public` returns hits scoped to community-wide public docs (any author).
- [ ] OpenSearch projection eventually-consistent: after any `docs.document.*` event, the search index reflects the change within ≤ 2s P95.
- [ ] OpenSearch unavailability returns `503` from search routes but **does not block** writes, reads, or other M2 routes.

### Engagement
- [ ] `POST /api/docs/{id}/view` increments `view_count` for `visibility='public'` docs; same anon cookie repeating the call within 24h does not increment it again. For `visibility='private'` docs, the call is a 204 no-op.
- [ ] `POST /api/docs/{id}/like` is idempotent; calling it twice from the same user leaves `like_count` at +1, and `DELETE /api/docs/{id}/like` returns it to 0.
- [ ] `GET /api/docs/{id}` returns `viewCount` + `likeCount`; if the caller is authenticated, also returns `likedByMe`.

### UX
- [ ] The sidebar's `Docs` row is reachable from logged-out state and lands on `/docs` (community feed); from logged-in state it lands on `/docs/mine`.
- [ ] `/docs/mine` renders a left tree pane (status filters + folder tree from `GET /api/docs/folders`) and a right list pane (docs in selected folder from `GET /api/docs?scope=mine&path=...`). Switching folders updates the right pane.
- [ ] Folder counts in `GET /api/docs/folders` equal `COUNT(*) FROM docs.documents WHERE user_id=? GROUP BY path`.
- [ ] `+ New document` dropdown on `/docs/mine` opens the editor at `/docs/new` with the current folder selection pre-applied as `path`.

### Excerpt + share preview
- [ ] Every DTO that includes document metadata returns `excerpt` derived per the §4.3 algorithm.
- [ ] `/docs/{id}` for a public doc emits the OpenGraph + Twitter Card meta tags per §7.4 in the server-rendered HTML. Verified by a `curl -A "Slackbot 1.0 (+...)" $URL | grep og:` integration check.
- [ ] `/docs/{id}` for a private doc viewed by a non-owner returns 404 and does NOT emit the meta tags.

### Cross-milestone
- [ ] Manual end-to-end check: as any authenticated user, upload a private document, wait for M3 to embed it (after M3 ships), start a private chat in M4, ask a question grounded in that document — the answer cites it. (Captured here for traceability; not a blocker for closing M2 alone.)

## 13. What this spec deliberately leaves out

- The **PRD** itself (`docs/prd/M2-docs.md`) — PM agent writes it in Stage 1 of the M2 cycle, using this spec as the source of truth.
- The **per-milestone ADR** (`docs/adr/NN-m2-docs.md`) — architect writes it in Stage 1 of the M2 cycle, picking libraries, ports, OpenSearch version, outbox pattern, and the ADR-05/ADR-08/ADR-09 amendments.
- The **Stage-2 design doc** (`docs/design/M2-docs.md`) — current contents reflect v4 model; needs refresh against this v5 spec (Publish modal removal, `/docs` vs `/docs/mine` split, UUID URLs, author identity surfacing, share-preview behavior, etc.). Performed in parallel with this spec update.
- The **design system spec update** — design system spec stays at v1; this M2 spec records the §8.1 and §9 partial supersedes inline.
- Implementation plan and tasks — written by `writing-plans` after this spec is approved.
