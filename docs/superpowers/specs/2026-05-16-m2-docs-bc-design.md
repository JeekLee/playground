# Spec: M2 — Docs BC (design)

**Date:** 2026-05-16
**Status:** Draft (brainstorming output)
**Audience:** the PM agent who will write `docs/prd/M2-docs.md` when the M2 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/NN-m2-docs.md`; the human reviewer.
**Relationship to other docs:**
- Supersedes the M2 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- Will be the canonical input for the M2 PRD (Stage-1 PM run, future cycle).
- References, does not supersede: ADR-09 (`09-public-route-policy.md`), the design system spec (`2026-05-16-playground-design-system.md`).

## 1. Purpose

Pin the bounded context, data model, public surface, and event contract for M2 (Docs) so that:
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
- Private read/edit surfaces: `/docs` (my list), `/docs/new`, `/docs/{id}` (edit)
- Three Kafka events for downstream consumers
- Server returns raw MD + metadata; rendering happens in Next.js with `unified` + `remark` + `rehype` + `shiki`
- External-URL images only

### Deferred to M2.1 (P1, same milestone bucket if cycle has slack)
- Image / attachment upload (presigned to local volume or Postgres `bytea`, decided in M2.1 ADR)
- Editor auto-save
- `⌘K` global search across the user's documents
- Cover image on essays

### Out of scope (P2, separate future milestone)
- Tags / categories
- Comments
- RSS / Atom feeds
- Version history / diff view
- Multi-author (the site is single-author; ADR-09 §"Anonymous identity contract" remains the public side)

## 3. Bounded Context: Docs

- **책임 (Responsibility):** Owns user-authored Markdown content end-to-end — storage, lifecycle, and visibility. Owns the `visibility` flag that ADR-09 made canonical for public-vs-private retrieval. Does **not** own embeddings, chunks, or any RAG concern — those are M3.
- **외부 의존성 (External deps):**
  - `identity` (M1): only via `X-User-Id` header on authenticated routes. No HTTP call from `docs` → `identity` at runtime.
  - `shared-kernel`: event envelope, common DTOs.
  - Postgres `docs` schema.
  - Kafka.
- **누가 docs를 호출하나:**
  - Gateway → `docs` for all `/api/docs/**` traffic (per ADR-07/08).
  - `rag-ingestion` (M3) — **read-only** access to fetch raw MD body when an event arrives. The exact mechanism (HTTP via gateway-internal route, or read-only DB role on `docs` schema) is an M2 per-milestone ADR decision. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception and must be reflected in either a superseding ADR or a per-milestone ADR.

## 4. Data model

### 4.1 Tables (Postgres, `docs` schema)

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

### 4.2 Field rules
- **`title`** is required at create time. Editor enforces non-empty; the API rejects empty/whitespace-only.
- **`body`** can be empty at create (drafting). Must be non-null (use `''`).
- **`visibility`** starts as `private`. The only state transitions are `private → public` (publish) and `public → private` (unpublish). No direct UPDATE of this column is exposed; only the publish/unpublish endpoints mutate it.
- **`slug`** is derived from `title` at first publish (kebab-case, lowercased ASCII, non-ASCII chars stripped, then `-2`, `-3`, … suffix on collision). User-editable on the publish modal before confirming. **Once set, the slug is the document's permanent public URL** — re-publish after unpublish reuses the existing `publish_meta` row. The user may rename the slug via a separate explicit action (P1).
- **`excerpt`** at first publish defaults to the first 200 characters of the rendered body with Markdown stripped, plus an ellipsis. User-editable.
- **`cover_image_url`** is nullable in M2; the UI does not surface it until M2.1.
- **`created_at` / `updated_at`** are wall-clock UTC. `updated_at` bumps on any column change (DB trigger or app-level — architect's call).

### 4.3 State machine

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

Hard delete is reachable from any state and cascades `publish_meta`.

## 5. Event surface

All envelopes follow the shared-kernel contract (ADR-03). Topic names use `<bc>.<aggregate>.<verb-past-tense>`.

| Topic | Trigger | Payload keys | Idempotency key |
|---|---|---|---|
| `docs.document.uploaded` | Document created **or** `body` changed on PATCH | `documentId`, `userId`, `visibility`, `title`, `bodyChecksum` | `documentId + bodyChecksum` |
| `docs.document.deleted` | Hard delete committed | `documentId`, `userId` | `documentId` (terminal event) |
| `docs.document.visibility-changed` | publish or unpublish committed | `documentId`, `userId`, `oldVisibility`, `newVisibility`, `slug` (present when `newVisibility='public'`) | `documentId + newVisibility` |

**Rules:**
- Payload **never** contains the raw `body`. Consumers fetch the body separately (see §3 external deps).
- `bodyChecksum` is SHA-256 of the raw MD; lets M3 short-circuit when an edit didn't actually change content (e.g., user re-saved without changes).
- A `title`-only or `visibility`-only change does **not** emit `uploaded` (no re-chunk needed). Title changes that affect the public face are caught by the next `visibility-changed` if re-published; for M2 we accept that an in-place title edit does not trigger a re-index — the chunk text is what matters for RAG.
- All three events are published transactionally with the DB write (outbox pattern recommended; the architect's per-milestone ADR picks library — Debezium vs. Spring Modulith outbox vs. a hand-rolled outbox table).

## 6. HTTP surface

### 6.1 Public routes (no auth — allowlisted per ADR-09)

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/docs/public` | — | `{ items: PublicEssayListItem[], nextCursor: string? }`. Cursor pagination; page size 20. Sort: `published_at DESC`. |
| GET | `/api/docs/public/{slug}` | — | `PublicEssayDetail` (see §6.3). 404 if no row has that slug **and** `visibility='public'`. |

Public routes carry no `X-User-Id` header (ADR-09). Backend treats absence as "anonymous reader."

### 6.2 Authenticated routes

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/docs/mine` | — | `{ items: MyDocListItem[], nextCursor: string? }`. Cursor pagination; sort `updated_at DESC`. |
| POST | `/api/docs` | `CreateDocRequest` (JSON) **or** `multipart/form-data` with a `.md` file + optional `title` | `MyDocDetail` (201). |
| GET | `/api/docs/{id}` | — | `MyDocDetail`. 404 if not owned by caller (do not leak existence). |
| PATCH | `/api/docs/{id}` | `PatchDocRequest` (`title?`, `body?`) | `MyDocDetail`. |
| POST | `/api/docs/{id}/publish` | `PublishRequest` (`slug?`, `excerpt?`) | `MyDocDetail` (now with PublishMeta). |
| POST | `/api/docs/{id}/unpublish` | — | `MyDocDetail` (visibility=private, PublishMeta retained). |
| DELETE | `/api/docs/{id}` | — | 204. |

Ownership check on every authenticated route: `WHERE user_id = X-User-Id`. A user fetching another user's doc gets the same 404 as a missing doc.

### 6.3 DTOs (sketch — final shapes belong to the per-milestone ADR / OpenAPI)

```ts
// Sketch only — final field names mirror Java/Kotlin record conventions per ADR-02.
PublicEssayListItem  = { slug, title, excerpt, publishedAt }
PublicEssayDetail    = { slug, title, body /*raw MD*/, excerpt, publishedAt, updatedAt }
MyDocListItem        = { id, title, visibility, slug?, updatedAt }
MyDocDetail          = { id, title, body, visibility, publishMeta?: { slug, excerpt, publishedAt }, createdAt, updatedAt }
CreateDocRequest     = { title: string, body?: string }
PatchDocRequest      = { title?: string, body?: string }
PublishRequest       = { slug?: string, excerpt?: string }
```

### 6.4 Error semantics

- `400` — empty title on create, empty/whitespace title on PATCH, slug-collision on publish (with `{ availableSuggestions: [...] }`)
- `401` — authenticated route with no `X-User-Id`
- `404` — doc not found OR not owned by caller (intentionally indistinguishable)
- `409` — slug collision when user explicitly chose the colliding slug
- `413` — body exceeds size cap (TBD by per-milestone ADR; sensible default ~1MB raw MD)

## 7. UX surfaces (M2 adds these client routes)

| Route | Auth | Purpose | Notes |
|---|---|---|---|
| `/essays` | public | Public essay list | Renders `GET /api/docs/public`. Loops on cursor. |
| `/essays/{slug}` | public | Single public essay | Renders `GET /api/docs/public/{slug}` with the `unified` pipeline. |
| `/docs` | auth | My documents list | Renders `GET /api/docs/mine`. Sidebar entry "My documents." |
| `/docs/new` | auth | New document editor | Empty state of the split-view editor. Sidebar entry "Write essay." |
| `/docs/{id}` | auth | Edit existing document | Same editor, pre-filled. |

The sidebar `Essays` count badge reflects the caller's `visibility='public'` document count, fetched as part of `GET /api/users/me` or a lightweight `GET /api/docs/mine/_stats` (architect's call).

Visual design lives in `docs/design/M2-docs.md`, written by `product-designer` in Stage 2 of the M2 cycle. This spec does not prescribe layout — only what each page needs to render.

## 8. Markdown feature scope (M2)

- **GFM:** tables, code fences with language, task lists, strikethrough, autolinks.
- **Code highlighting:** `shiki`, theme matches design system tokens (the design system spec already pins olive accent — code blocks use a complementary muted theme; design-time decision).
- **Images:** `![alt](https://…)` external URLs only. The renderer rejects `data:` URLs and unscoped relative paths.
- **Math / Mermaid:** out of scope for M2. P2.
- **HTML in MD:** sanitized out (rehype-sanitize). No raw `<script>`, no event handlers.

## 9. Non-functional requirements

- **Tenant isolation:** every authenticated query MUST include `WHERE user_id = X-User-Id`. A repository-level guard (e.g., a `@WithCurrentUser` interceptor that injects the predicate) is preferred over relying on every query author.
- **Outbox correctness:** events and DB writes succeed atomically. If outbox is not implemented in M2, the architect's ADR must document the alternative (and accept the at-most-once risk window in writing).
- **Public retrieval correctness (ADR-09 invariant):** `GET /api/docs/public/**` MUST never return `visibility='private'` rows. Integration test mandatory.
- **Observability:** every state transition (create, publish, unpublish, delete, body-edit) emits a structured log line at INFO with `documentId`, `userId`, `event`, and `bodyChecksum` where relevant.
- **Body size cap:** enforced at the API and via DB column constraint or trigger.
- **Slug stability:** once published, the public URL of an essay must survive unpublish/republish cycles unchanged. Test mandatory.

## 10. Open questions for the per-milestone ADR (M2)

These intentionally land in the architect's per-milestone ADR, not here:

1. **Outbox library / pattern** — Spring Modulith Events vs Debezium vs hand-rolled outbox table.
2. **M3 body fetch mechanism** — gateway-internal HTTP route vs. read-only DB role on the `docs` schema. ADR-08 currently says BC-to-BC is Kafka-only; this is the first justified exception, so either ADR-08 is superseded for this one read path, or the per-milestone ADR formally records the exception.
3. **Editor library** — `@uiw/react-md-editor` vs. a thin CodeMirror 6 + preview combo vs. plain textarea + preview. Affects bundle size and accessibility.
4. **Body size cap** — concrete number (default ~1MB) and where it's enforced.
5. **Slug rename action (P1)** — does renaming the slug 301-redirect from the old slug? If yes, do we need a `publish_meta_aliases` table?
6. **Rate limit for unauthenticated public reads** — `/api/docs/public/**` is cheap (no LLM) so probably no per-IP cap, but the gateway should still get a soft cap to deter scraping. Concrete numbers belong to the ADR.

## 11. Acceptance criteria (refinement of `roadmap.md` M2)

Replaces the M2 bullet list in `docs/roadmap.md` when the M2 cycle opens. The original five bullets are preserved; new ones expand on what the design system and ADR-09 already promised.

- [ ] Authenticated user can create a document via the in-app editor and via `.md` file upload, both producing a stable document id.
- [ ] `GET /api/docs/mine` returns the caller's documents (all visibilities), `GET /api/docs/{id}` returns a single doc — both **404 when the caller is not the owner**.
- [ ] `GET /api/docs/public` and `GET /api/docs/public/{slug}` work **without an auth header** and only return `visibility='public'` rows.
- [ ] `POST /api/docs/{id}/publish` sets `visibility='public'`, creates `publish_meta` with a slug derived from the title (collision-resolved), and the essay is immediately reachable at `/essays/{slug}`.
- [ ] `POST /api/docs/{id}/unpublish` flips `visibility='private'`, retains `publish_meta`, and re-publishing later returns the **same slug**.
- [ ] Hard delete removes the document, cascades `publish_meta`, and emits `docs.document.deleted`.
- [ ] `docs.document.uploaded` is emitted on create and on body change (verified by checking the topic during an integration test) using the shared-kernel envelope.
- [ ] `docs.document.visibility-changed` is emitted on publish and unpublish with the correct `oldVisibility` / `newVisibility`.
- [ ] Public read endpoint integration test proves no `visibility='private'` row can leak through `/api/docs/public/**` under any query.
- [ ] Tenant isolation test proves user A cannot read or mutate user B's document via any authenticated route.
- [ ] `/essays/{slug}` renders MD with GFM + syntax highlighting in the design-system prose treatment; the body comes from `GET /api/docs/public/{slug}`, rendering is client-side SSR.
- [ ] The in-app editor at `/docs/new` and `/docs/{id}` is a split-view: raw MD on the left, live prose preview on the right, both using the same `unified` pipeline that renders `/essays/{slug}`.

## 12. What this spec deliberately leaves out

- The **PRD** itself (`docs/prd/M2-docs.md`) — PM agent writes it in Stage 1 of the M2 cycle, using this spec as the source of truth for entity model, events, and route surface.
- The **per-milestone ADR** (`docs/adr/NN-m2-docs.md`) — architect writes it in Stage 1 of the M2 cycle, picking libraries, ports, and the outbox pattern.
- The **Stage-2 design doc** (`docs/design/M2-docs.md`) — `product-designer` writes it after the PRD, using the design system spec as the visual source of truth.
- Implementation plan and tasks — written by `writing-plans` after this spec is approved.
