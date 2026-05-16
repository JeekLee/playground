# Spec: M2 — Docs BC (design)

**Date:** 2026-05-16
**Status:** Draft (brainstorming output) — v4 (incorporates: Apps-only sidebar grouping with locked-row treatment, OpenSearch full-text, owner-filtered document feed, explicit RAG handoff trace, view/like counters as P0 + comments promoted to M2.1 P1, single-vocabulary terminology `docs`/`Docs`/`Document` with `essay`/`blog` removed)
**Audience:** the PM agent who will write `docs/prd/M2-docs.md` when the M2 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/NN-m2-docs.md`; the human reviewer.
**Relationship to other docs:**
- Supersedes the M2 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- **Partially supersedes** the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` §8.1 (sidebar) and §9 (home composition) — see §7 below.
- Will be the canonical input for the M2 PRD (Stage-1 PM run, future cycle).
- References, does not supersede: ADR-09 (`09-public-route-policy.md`).
- **Will amend ADR-05** (`05-data-store.md`, which currently pins Postgres + pgvector only) to add OpenSearch as a second-tier search store — formal amendment happens in the M2 per-milestone ADR.

## 0. Terminology (single vocabulary)

The site uses **one noun** for user-authored Markdown content across every layer:

- **Engineering / API / DB / events / frontend routes / UI copy:** always `docs` (BC, schema, route prefix) and `Document` (entity, DTO root). Public-vs-private is a `visibility` modifier on the same noun, not a different noun.
- **No "essay", "post", or "blog"** anywhere. Earlier drafts split into "essay" (public face) vs "docs" (private workshop) plus a separate "blog" label on the home — that split is dropped. Anyone writing future PRDs / Stage-2 designs / frontend code MUST resist re-introducing it. The home section is labeled `Latest documents`, the sidebar entry is `Docs` (short form; the spec uses `Docs` consistently — see §7.1), public URLs are `/docs/public/{slug}`, private URLs are `/docs/{id}`.
- **One exception:** the roadmap and the agent-team design spec mention the user's pre-existing external "git blog" — that's a literal reference to a different external system and is unaffected by this rule.

## 1. Purpose

Pin the bounded context, data model, public surface, search surface, and event contract for M2 (Docs) so that:
1. The PM agent writes a PRD that does not re-litigate decisions already made here.
2. The frontend Stage-2 designer knows what screens exist and what data each one expects.
3. M3 (RAG-Ingestion) can be planned in parallel against a stable event contract.

This spec **does not** describe the M2 PRD's user-story list, the architect's port assignment, the per-milestone ADR's library choices, or the Stage-2 visual design. Those land in their canonical homes when the M2 cycle opens.

## 2. Scope summary

### In scope (P0)
- Authoring of Markdown documents by an authenticated user (in-app Notion-style block editor with raw-MD I/O + `.md` file upload)
- A single `Document` entity with optional `PublishMeta` child for the publish-time fields
- `visibility` toggle (`private` → `public`) with safe re-publish (slug preserved)
- Public read surfaces: `/docs/public` (list) and `/docs/public/{slug}` (single)
- Private read/edit surfaces: `/docs` (my list), `/docs/new`, `/docs/{id}` (edit), `/docs/search`
- **Full-text search backed by OpenSearch** — `GET /api/docs/search` with `mine` and `public` scopes, fed by a Kafka consumer inside the docs service that mirrors writes to OpenSearch
- **Owner-filtered public feed** — `GET /api/docs/public` returns only documents authored by the platform owner (resolved via `PLAYGROUND_OWNER_GOOGLE_SUB` env var); home's "Latest documents" section consumes this
- **Reader engagement signals** — view counter (anonymous) and like toggle (login-required) on public documents. Counters live on the `docs.documents` row; no separate analytics BC for M2.
- **RAG handoff** — every `docs.document.uploaded` event reaches M3, which embeds the body and stores chunks scoped to the author's `user_id`. M4's `/api/rag/chat/private` then retrieves those chunks for the same user, giving the author a chat grounded in their own documents. M2 owns the contract; M3 and M4 own the behavior.
- Three Kafka events for downstream consumers
- Server returns raw MD + metadata; rendering happens in Next.js with `unified` + `remark` + `rehype` + `shiki`
- External-URL images only
- **Global `⌘K` search palette** — overlay launchable from any authenticated page; queries `GET /api/docs/search` live with `scope=mine` default and `Tab` to toggle `public`. Enter on a result opens the document; `⌘+Enter` opens the full `/docs/search` page with the same query. The palette is the keyboard-fastest entry into search; the `/docs/search` page is the depth-fastest (filters, scope toggle, pagination, deep-link).

### Deferred to M2.1 (P1, same milestone bucket if cycle has slack)
- Image / attachment upload (presigned to local volume or Postgres `bytea`, decided in M2.1 ADR)
- Editor auto-save
- Richer `⌘K` command palette beyond search (e.g., `Quick: New document`, `Quick: Switch to drafts`, jumping to non-doc surfaces) — the M2 P0 palette ships search-only; non-search commands accrete in M3+.
- Cover image on documents
- **Comments on public documents** — login-required comments; owner has sole moderation authority (hard-delete any comment). Separate `Comment` entity, dedicated routes, no thread depth (flat list). Data model and routes defined in the M2.1 spec/PRD when that cycle opens.

### Out of scope (P2, separate future milestone)
- Tags / categories
- RSS / Atom feeds
- Version history / diff view
- Multi-author (the site stays single-author; owner is configured at deploy time)
- Engagement-driven ranking (using view/like signals to re-order the public feed or RAG retrieval — counters are stored, not yet consumed for ranking)

## 3. Bounded Context: Docs

- **책임 (Responsibility):** Owns user-authored Markdown content end-to-end — storage, lifecycle, visibility, **full-text indexing** (mirroring to OpenSearch), and **reader engagement signals** (view + like counters on public documents). Owns the `visibility` flag that ADR-09 made canonical for public-vs-private retrieval. Owns the **owner-filter** semantics for the public feed. Does **not** own embeddings, vector chunks, or any RAG concern — those are M3. Does **not** own comments — those land in M2.1.
- **외부 의존성 (External deps):**
  - `identity` (M1): only via `X-User-Id` / `X-User-Sub` headers on authenticated routes. No HTTP call from `docs` → `identity` at runtime.
  - `shared-kernel`: event envelope, common DTOs.
  - Postgres `docs` schema (source of truth).
  - **OpenSearch** (search projection) — pinned to a single index `docs-v1`. Concrete version + client library belong to the per-milestone ADR.
  - **Redis** — short-TTL dedup keys for anonymous view counting. Already present in compose per M0.
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
  view_count  BIGINT       NOT NULL DEFAULT 0,
  like_count  BIGINT       NOT NULL DEFAULT 0,
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

-- Per-user like (login required; toggle semantics)
CREATE TABLE docs.document_likes (
  document_id  UUID         NOT NULL REFERENCES docs.documents(id) ON DELETE CASCADE,
  user_id      UUID         NOT NULL,
  liked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (document_id, user_id)
);
```

`view_count` / `like_count` are denormalized counters maintained alongside the row mutation (single transaction). For 1-user-traffic load, a periodic re-sync job (`COUNT(*) FROM document_likes …`) reconciles drift; the per-milestone ADR picks the cadence (default: nightly).

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
| GET | `/api/docs/public` | — | `{ items: PublicDocListItem[], nextCursor: string? }`. **Owner-filtered:** only documents where `user_id` matches the resolved `PLAYGROUND_OWNER_GOOGLE_SUB` AND `visibility='public'`. Cursor pagination; page size 20. Sort: `published_at DESC`. |
| GET | `/api/docs/public/{slug}` | — | `PublicDocDetail` (see §6.4). 404 if no row has that slug **and** `visibility='public'`. **Not** owner-filtered — anyone's published document is reachable by direct slug if it exists; the owner filter only governs the discovery feed. |
| GET | `/api/docs/search?q=...&scope=public` | — | OpenSearch-backed full-text search over `visibility='public'` + `isOwnerDoc=true`. Returns `{ items: SearchHit[], nextCursor: string? }` with highlight snippets. Rate-limited at the gateway (anti-scrape, per-IP soft cap). |
| POST | `/api/docs/public/{slug}/view` | — | `204`. Increments `view_count` on the row matching `slug` AND `visibility='public'`. Deduplicated via Redis key `view:{slug}:{PLAYGROUND_ANON}` (TTL 24h) — repeat hits within the window are silently dropped. Missing `PLAYGROUND_ANON` cookie falls back to `view:{slug}:ip:{X-Forwarded-For}` with the same TTL. 404 if slug is private or unknown. |

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
| POST | `/api/docs/{id}/like` | — | `204`. Upserts `(document_id, X-User-Id)` into `document_likes` and increments `like_count` if the row didn't already exist (idempotent — repeat calls succeed without re-incrementing). Allowed on any `visibility` for now; the UI only surfaces it on public documents. |
| DELETE | `/api/docs/{id}/like` | — | `204`. Removes `(document_id, X-User-Id)` from `document_likes` and decrements `like_count`. Idempotent — removing a non-existent like succeeds without going below zero. |

Ownership check on every authenticated route: `WHERE user_id = X-User-Id`. A user fetching another user's doc gets the same 404 as a missing doc.

### 6.3 Owner resolution

- `PLAYGROUND_OWNER_GOOGLE_SUB` is set in the gateway and docs service environments at deploy time.
- On boot, the docs service resolves `owner_user_id = SELECT id FROM identity.users WHERE google_sub = $env` (or via a gateway-internal call to identity if cross-schema reads are disallowed by ADR-08 amendment). Result is cached in memory.
- If the env var is unset or the lookup returns no row, public-feed endpoints return an empty list (fail-closed); a startup log line at WARN flags the misconfiguration.
- This env var is the system's **only** notion of "owner." No DB column, no role.

### 6.4 DTOs (sketch — final shapes belong to the per-milestone ADR / OpenAPI)

```ts
// Sketch only — final field names mirror Java/Kotlin record conventions per ADR-02.
PublicDocListItem  = { slug, title, excerpt, publishedAt, viewCount, likeCount }
PublicDocDetail    = { slug, title, body /*raw MD*/, excerpt, publishedAt, updatedAt,
                         viewCount, likeCount, likedByMe? /* present only when X-User-Id forwarded */ }
MyDocListItem        = { id, title, visibility, slug?, updatedAt, viewCount, likeCount }
MyDocDetail          = { id, title, body, visibility, publishMeta?: { slug, excerpt, publishedAt },
                         viewCount, likeCount, likedByMe, createdAt, updatedAt }
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

The Apps / Workspace split is dropped. The sidebar has exactly one navigation section — `APPS` — containing every top-level destination the site will ever expose, visible from M1 onward:

```
APPS
⌂ Home                                  (M1, active when shipped)
▤ Docs              M2 🔒               (M2 — locked until shipped)
💬 Chat             M4 🔒               (M4 — locked until shipped)
📊 System status    M5 🔒               (M5 — locked until shipped)
```

The Workspace section is removed entirely. Per-document actions (Write, Publish, Search, View public) live inside the Docs surface — not in the sidebar.

**Locked-item treatment** (when a row's milestone hasn't shipped):
- Row is visible, opacity ~0.72, label text in `text.subtle`.
- Right-side milestone badge (e.g., `M2`) in `text.muted` on `surface.soft`, plus a small 🔒 glyph.
- Hover: cursor `default` (not `pointer`); no `accent.soft` activation; tooltip `Available when <milestone-title> ships`.
- Click: no-op. The row is informational, not actionable.

**Shipped-item treatment** (when the milestone is shipped — milestone closed on GitHub):
- Full opacity, label in `text`, no badge, no 🔒.
- Hover: `surface` bg.
- Active: `accent.soft` bg + `accent` label, weight 600.

**`Docs` row behavior (once M2 ships):**
- **Logged out:** click lands on `/docs/public` (public-read list).
- **Logged in:** click lands on `/docs` (my list). Small numeric badge shows `published / total` (e.g., `4/12`).
- Active state lights up `accent.soft` for any route under `/docs/**` or `/docs/public/**`.

**Sidebar at M1:** Apps contains the same four rows. `Home` is active/shipped; `Docs`, `Chat`, `System status` render with the locked-item treatment above. This is the same preview-with-locks paradigm the home tile grid already uses (design system §9), giving both surfaces a single visual story. Supersedes the previous "strict grow rule" interim text and the "M1 design must be re-issued v3" requirement — the M1 design (`docs/design/M1-identity.md`) only needs the Workspace section dropped and its 3 locked items re-keyed to the Apps section rows above; it does not need a v3.

### 7.2 Client routes (Docs surface)

| Route | Auth | Purpose |
|---|---|---|
| `/docs/public` | public | Owner-filtered public document list (consumes `GET /api/docs/public`). |
| `/docs/public/{slug}` | public | Single public document (consumes `GET /api/docs/public/{slug}`). |
| `/docs` | auth | My documents list. Has a tab/segment switcher: `All / Drafts / Published`. Top-right `Search` input + `New document` button. |
| `/docs/new` | auth | New document editor — single-pane Notion-style **block editor** (BlockNote), "/" command for block insertion, drag-handles per block. Body roundtrips raw MD via BlockNote's `blockToMarkdownLossy` / `tryParseMarkdownToBlocks`. See §11 Q3 for library decision rationale. |
| `/docs/{id}` | auth | Edit existing document. Top-right has `Publish` / `Unpublish` / `Delete` and a `View public` link if published. |
| `/docs/search` | auth | Full-page search results (scope toggle: `mine / public`, filters, cursor pagination). Companion to the global `⌘K` palette: pressing `⌘+Enter` from the palette opens this page with the current query pre-applied; pressing `Enter` opens a result directly. |

### 7.3 Home composition (supersedes design system §9 item 3)

The home's `Latest documents` section (label finalized in this spec — earlier drafts called it "Latest from the blog") sources only owner-authored public documents (`GET /api/docs/public`, already owner-filtered). Visual treatment (3-column thumbnail grid) is unchanged from §9 except the **card meta row**, which extends to: `· N min · {date} · 👁 {viewCount} · ♥ {likeCount}`. Icon glyphs are placeholders — the frontend-implementer swaps to Lucide `Eye` and `Heart` (matching the spec §3 emoji-to-icon migration rule). Empty-state copy (pre-M2) is unchanged.

On the document detail page (`/docs/public/{slug}`), the same view/like counts render directly under the title in `text.muted`; the like control is an inline button (filled `accent` when `likedByMe`, outline otherwise) that toggles the like via `POST` / `DELETE /api/docs/{id}/like`. Anonymous readers see the button in a disabled "sign in to like" state.

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
- **Owner-filter correctness:** `GET /api/docs/public` (the list) MUST never return rows where `user_id != owner_user_id`, even if other users have public documents. Integration test mandatory.
- **Observability:** every state transition (create, publish, unpublish, delete, body-edit) emits a structured log line at INFO with `documentId`, `userId`, `event`, and `bodyChecksum` where relevant. Search projector emits separate INFO/WARN on each batch.
- **Body size cap:** enforced at the API and via DB column constraint or trigger.
- **Slug stability:** once published, the public URL of an document must survive unpublish/republish cycles unchanged. Test mandatory.
- **View dedup correctness:** a single anonymous visitor (same `PLAYGROUND_ANON` cookie) hitting the same slug N times within 24h increments `view_count` exactly once. Integration test required. Authors viewing their own documents are **not** excluded from the count — symmetric treatment, no special-casing in M2.
- **Like idempotency:** `POST /api/docs/{id}/like` and `DELETE /api/docs/{id}/like` are both idempotent against repeat invocation. Concurrent like/unlike from the same user is serialized at the DB level (PK contention on `(document_id, user_id)`); the final `like_count` must equal `COUNT(*) FROM document_likes WHERE document_id=?`.
- **Counter drift tolerance:** if `view_count` / `like_count` drift from their source-of-truth queries due to a partial failure, the nightly re-sync job repairs them; max acceptable drift between syncs is informational, not contractual.

## 11. Open questions for the per-milestone ADR (M2)

These intentionally land in the architect's per-milestone ADR, not here:

1. **Outbox library / pattern** — Spring Modulith Events vs Debezium vs hand-rolled outbox table.
2. **M3 body fetch mechanism** — gateway-internal HTTP route vs. read-only DB role on the `docs` schema. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception, so either ADR-08 is superseded for this one read path, or the per-milestone ADR formally records the exception.
3. **Editor library — DECIDED: BlockNote** (https://www.blocknotejs.org). Brainstorm shifted from MD-source editor to Notion-style block editor; rationale captured in the M2 design doc (`docs/design/M2-docs.md` Open Questions). BlockNote chosen over alternatives:
   - **vs. Novel** — Novel bundles AI-completion features unnecessary for M2 (RAG chat is M4's domain), making it dead weight here.
   - **vs. TipTap + tiptap-markdown** — requires assembling Notion UX (slash menu, drag handles, block reordering) from scratch; BlockNote ships it.
   - **vs. former candidates (`@uiw/react-md-editor`, CodeMirror 6, plain textarea + preview)** — superseded; those were all MD-source editors, the brainstorm explicitly rejected the split-view UX paradigm.
   Per-milestone ADR pins exact `@blocknote/core` + `@blocknote/react` versions, the MD-roundtrip adapter configuration, and the SSR strategy under Next.js App Router. Bundle size and accessibility audit also belong to the per-milestone ADR.
4. **Body size cap** — concrete number (default ~1MB) and where it's enforced.
5. **Slug rename action (P1)** — does renaming the slug 301-redirect from the old slug? If yes, do we need a `publish_meta_aliases` table?
6. **Rate limit for unauthenticated public reads** — `/api/docs/public/**` is cheap (no LLM); `/api/docs/search?scope=public` is expensive enough to merit a soft per-IP cap. Concrete numbers belong to the ADR.
7. **OpenSearch version + client** — OpenSearch 2.x vs. 1.x; Spring Data OpenSearch vs. the native low-level client. Affects docker-compose tag and dependency tree. Also: single-node vs. 3-node minimum (single-node for dev is fine; prod is still single-host).
8. **Korean analyzer** — `nori` (built-in) vs `seunjeon`; affects search quality on Korean documents.
9. **Metadata-only event topic** — do we add a `docs.document.metadata-changed` event for title/visibility-only edits so the search projector can keep title in sync without re-firing `uploaded`? Trade-off vs. accepting stale titles in search.
10. **Owner resolution path** — cross-schema SELECT from `docs` into `identity.users` (simple, breaks schema isolation) vs. an HTTP call to identity at boot (adds a startup-time dependency). Affects ADR-08 amendment scope.
11. **View dedup TTL** — 24h is the working default. Should authenticated views dedup against `X-User-Id` (longer TTL, more accurate) instead of the anon cookie? For now: same anon-cookie path regardless of auth state, accepted for simplicity.
12. **Counter sync strategy** — denormalized column + nightly re-sync (current default) vs. trigger-based maintenance vs. event-sourced rebuild from a `docs.document.engagement-*` topic. The trade-off is correctness vs. read latency; default favors read latency since the home tile is a hot path.
13. **Anonymous viewer ergonomics** — should the like button render at all for anonymous readers, or should it be hidden entirely? Working default: render disabled with "sign in to like" tooltip. Confirmed during M2 Stage-2 design.

## 12. Acceptance criteria (refinement of `roadmap.md` M2)

Replaces the M2 bullet list in `docs/roadmap.md` when the M2 cycle opens. The original five bullets are preserved; new ones expand on what the design system, ADR-09, and this spec promise.

- [ ] Authenticated user can create a document via the in-app editor and via `.md` file upload, both producing a stable document id.
- [ ] `GET /api/docs/mine` returns the caller's documents (all visibilities), `GET /api/docs/{id}` returns a single doc — both **404 when the caller is not the owner**.
- [ ] `GET /api/docs/public` and `GET /api/docs/public/{slug}` work **without an auth header** and only return `visibility='public'` rows.
- [ ] `GET /api/docs/public` (list) returns **only owner-authored** documents — non-owner public documents are excluded even if they exist. Verified by integration test seeding a non-owner public document and asserting it is absent.
- [ ] `POST /api/docs/{id}/publish` sets `visibility='public'`, creates `publish_meta` with a slug derived from the title (collision-resolved), and the document is immediately reachable at `/docs/public/{slug}`.
- [ ] `POST /api/docs/{id}/unpublish` flips `visibility='private'`, retains `publish_meta`, and re-publishing later returns the **same slug**.
- [ ] Hard delete removes the document, cascades `publish_meta`, removes the OpenSearch entry, and emits `docs.document.deleted`.
- [ ] `docs.document.uploaded` is emitted on create and on body change (verified by checking the topic during an integration test) using the shared-kernel envelope.
- [ ] `docs.document.visibility-changed` is emitted on publish and unpublish with the correct `oldVisibility` / `newVisibility`.
- [ ] Public read endpoint integration test proves no `visibility='private'` row can leak through `/api/docs/public/**` under any query.
- [ ] Tenant isolation test proves user A cannot read or mutate user B's document via any authenticated route.
- [ ] `/docs/public/{slug}` renders MD with GFM + syntax highlighting in the design-system prose treatment; the body comes from `GET /api/docs/public/{slug}`, rendering is client-side SSR.
- [ ] The in-app editor at `/docs/new` and `/docs/{id}` is a single-pane **BlockNote** block editor (Notion-style "/" menu + drag-handles). On load, body MD parses to blocks via `tryParseMarkdownToBlocks`; on save, blocks serialize back via `blockToMarkdownLossy`. The public render at `/docs/public/{slug}` continues to use the `unified` + `remark` + `rehype` + `shiki` pipeline against the raw MD body — the editor changes the **authoring** UX, not the **reading** pipeline.
- [ ] `GET /api/docs/search?q=...&scope=mine` returns OpenSearch-backed hits scoped to the caller; `scope=public` returns hits scoped to owner-authored public docs.
- [ ] OpenSearch projection eventually-consistent: after any `docs.document.*` event, the search index reflects the change within ≤ 2s P95.
- [ ] OpenSearch unavailability returns `503` from search routes but **does not block** writes, reads, or other M2 routes.
- [ ] The sidebar's `Docs` row is reachable from logged-out state and lands on `/docs/public`; from logged-in state it lands on `/docs`.
- [ ] Home renders the section labeled **"Latest documents"** sourced from `GET /api/docs/public` (already owner-filtered).
- [ ] `POST /api/docs/public/{slug}/view` increments `view_count` and the same anon cookie repeating the call within 24h does **not** increment it again. Verified by integration test.
- [ ] `POST /api/docs/{id}/like` is idempotent; calling it twice from the same user leaves `like_count` at +1, and `DELETE /api/docs/{id}/like` returns it to 0. Verified by integration test.
- [ ] `GET /api/docs/public/{slug}` returns `viewCount` + `likeCount`; if the caller is authenticated, also returns `likedByMe` reflecting the user's like state.
- [ ] Home tile cards and the document detail page both render view + like counts; the like button is disabled with a tooltip when the reader is anonymous.
- [ ] Manual end-to-end check: as the owner, upload a private document, wait for M3 to embed it (after M3 ships), start a private chat in M4, ask a question grounded in that document — the answer cites it. (Cross-milestone test, captured here for traceability; not a blocker for closing M2 alone.)

## 13. What this spec deliberately leaves out

- The **PRD** itself (`docs/prd/M2-docs.md`) — PM agent writes it in Stage 1 of the M2 cycle, using this spec as the source of truth for entity model, events, route surface, and search semantics.
- The **per-milestone ADR** (`docs/adr/NN-m2-docs.md`) — architect writes it in Stage 1 of the M2 cycle, picking libraries, ports, OpenSearch version, outbox pattern, and the ADR-05/ADR-08 amendments.
- The **Stage-2 design doc** (`docs/design/M2-docs.md`) — `product-designer` writes it after the PRD, using the design system spec as the visual source of truth (with this spec's §7 overrides).
- The **design system spec update** — design system spec stays at v1; this M2 spec records the §8.1 and §9 partial supersedes inline. If the supersede surface grows in M3+, promote it to a design-system v1.1.
- Implementation plan and tasks — written by `writing-plans` after this spec is approved.
