# Playground Design System v1 — Docs Alignment Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring all existing documentation (ADRs, roadmap, M1 PRD, M1 design context, designer agent definition) into alignment with the newly-locked design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md`, so that every downstream agent (architect/pm/product-designer/frontend-implementer) reads a single coherent story.

**Architecture:** Docs-only plan. **No production code is written here.** The design system's code deliverables (`client/src/shared/ui/tokens/`, primitives, layout shell, home composition) are inputs for M1's Stage 4 `/build-client`, not for this plan. This plan ships everything that must be true *before* the next workflow stage (`/build-server M1` or a re-run of `/design M1`) starts.

**Tech Stack:** Markdown editing + one product-designer agent re-dispatch (Figma MCP). All work happens under `docs/` and `.claude/agents/`.

**Scope boundary:**
- ✅ In: ADR for public-route policy; ADR-07/ADR-00 cross-references; roadmap public-route notes for M2/M4/M5; M1 PRD note about home-screen change + public surfaces; product-designer agent updated to read the design system spec; re-generation of `docs/design/M1-identity.md` and its Figma assets against the new tokens.
- ❌ Out: any code under `client/`, `api/`, `infra/`. Any update to M2/M4/M5 PRDs (those PRDs do not yet exist; they will be authored by `pm` when each milestone is expanded). Implementation of token CSS files or React primitives.

---

## File Structure

Files this plan creates or modifies, with responsibility:

| Path | Action | Responsibility |
|---|---|---|
| `docs/adr/09-public-route-policy.md` | **create** | Transverse decision: which routes are public, anonymous identity contract, rate-limit principle, public-retrieval scope principle. |
| `docs/adr/07-gateway-oauth.md` | **modify** | Replace the speculative TBD on `/api/rag/ingest` route, link to ADR-09 for the public-route allowlist; clarify which routes never run auth. |
| `docs/adr/00-overview.md` | **modify** | Add ADR-09 to the index table. |
| `docs/roadmap.md` | **modify** | Append "Public surface" notes to M2, M4, M5 stating the four concerns from design-system spec §11. |
| `docs/prd/M1-identity.md` | **modify** | (a) Add a cross-reference to the design system spec. (b) Replace any "home screen shows /me JSON" framing — the home is now the public landing; `/me` is an endpoint only. (c) Note that the unauth contract from the gateway is no longer a hard 401 for `/` — the route is public. |
| `.claude/agents/product-designer.md` | **modify** | Add the design system spec as a mandatory input ordered before per-milestone PRD reads. |
| `docs/design/M1-identity.md` | **regenerate** | Output of Task 7 (product-designer re-dispatch). Replaces the existing file with one that applies the locked design system. |
| `docs/design/assets/M1/*.png` | **regenerate** | Output of Task 7. Replaces the existing screenshots. |

Each task is one focused docs change with its own commit. Tasks are independent except where noted (Task 2 references ADR-09 by number, so Task 1 must land first; Task 7 must run after Task 6).

---

## Task 1: Create ADR-09 — Public Route Policy

**Files:**
- Create: `docs/adr/09-public-route-policy.md`

This is a transverse ADR because the decision touches gateway routing, BC visibility contracts, retrieval scoping, and rate limiting — not any single service. The design system spec §11 enumerates the four concerns; this ADR turns them into pinned decisions.

- [ ] **Step 1: Confirm no ADR-09 exists yet**

Run:
```bash
ls /home/jeek_lee/work/personal/playground/docs/adr/ | grep -E '^09'
```
Expected: no output (file does not exist).

- [ ] **Step 2: Write the ADR**

Create `/home/jeek_lee/work/personal/playground/docs/adr/09-public-route-policy.md` with this exact content:

````markdown
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

Backends needing rate-limit keys for anonymous traffic use the gateway-injected `X-Forwarded-For` IP (already present from Spring Cloud Gateway's default forwarding) plus, for browser sessions, an anonymous cookie `PLAYGROUND_ANON` (UUID, set on first public-page visit, no PII, 30-day rolling expiry).

### Rate-limit and cost protection (public RAG chat)

Public RAG chat dispatches against `spark-inference-gateway` (Qwen3-32B generation + BGE-M3 retrieval), which is real compute. The gateway enforces:

- **Per-IP burst limit:** 10 chat completions / 5 minutes, then 429.
- **Per-anon-cookie burst limit:** 30 chat completions / day, then 429.
- **Global circuit breaker:** if `spark-inference-gateway` returns 5xx on >50% of public-chat requests in the last 60 seconds, public chat returns a 503 with a friendly "the model is resting — try a logged-in chat" message; logged-in chat keeps working until the breaker opens for it too.
- **Token cap per completion:** public chat is capped at `max_tokens=512` and one retrieved chunk window (no multi-turn context for anonymous sessions). Logged-in chat has higher limits.

Exact algorithm (token bucket vs sliding window) and the breaker library belong to M4's per-milestone ADR. This ADR fixes only the numbers and the principle.

### Public retrieval scoping

Public RAG chat retrieves **only** against `docs.documents` rows where `visibility = 'public'`. Private documents are stored in the same pgvector table but their chunks are excluded by a `WHERE visibility = 'public'` predicate added to every public-route retrieval query.

The `visibility` column is added to the `docs` schema. It is an **M2 (Docs) schema concern**, not an M3 (RAG-ingestion) one — chunks inherit the parent document's visibility at ingestion time and re-inherit on visibility changes (the ingestion service consumes a `docs.document.visibility-changed` event and re-tags chunks).

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
- Future M4 per-milestone ADR — concrete rate-limit algorithm + circuit-breaker library
````

- [ ] **Step 3: Verify the file is well-formed**

Run:
```bash
head -3 /home/jeek_lee/work/personal/playground/docs/adr/09-public-route-policy.md && echo "---" && wc -l /home/jeek_lee/work/personal/playground/docs/adr/09-public-route-policy.md
```
Expected: first 3 lines are `# ADR-09: Public Route Policy — Mixed Public / Authenticated Site`, blank, `## Status`. Line count between 60 and 100.

- [ ] **Step 4: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/adr/09-public-route-policy.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
adr: ADR-09 public route policy

Introduces the allowlist, anonymous identity contract (header-absent, not
sentinel), public RAG chat rate limits, and the docs.visibility column that
public retrieval is scoped against. Supersedes ADR-07's implicit "all /api/**
requires auth" assumption.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Update ADR-07 to defer the public allowlist to ADR-09

**Files:**
- Modify: `docs/adr/07-gateway-oauth.md`

ADR-07 currently says "For unauthenticated requests to public routes, these headers are stripped" (around line 64) without defining which routes are public, and lists routes (lines 67-76) without classifying them. After Task 1 lands, ADR-07 must point to ADR-09 for the allowlist and clean up the speculative `TBD per M3` comment.

- [ ] **Step 1: Confirm the lines we're editing**

Run:
```bash
sed -n '60,76p' /home/jeek_lee/work/personal/playground/docs/adr/07-gateway-oauth.md
```
Expected: shows the header-injection contract paragraph and the Routes section ending with `/** -> http://client:3000`.

- [ ] **Step 2: Replace the "Routes" section with a route-classification reference**

In `/home/jeek_lee/work/personal/playground/docs/adr/07-gateway-oauth.md`, replace exactly:

```
### Routes
- `/api/identity/**` -> `http://identity:18081`
- `/api/docs/**` -> `http://docs:18082`
- `/api/rag/ingest/**` -> `http://rag-ingestion:18083` (likely admin-only — TBD per M3)
- `/api/rag/chat/**` -> `http://rag-chat:18084`
- `/api/metrics/**` -> `http://metrics:18086`
- `/**` -> `http://client:3000` (Next.js SSR)

Path stripping: `/api/<bc>` is stripped before forwarding so backends see
`/docs/public`, `/users/me`, etc.
```

with:

```
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
`/docs/public`, `/users/me`, etc.

### Route classification (auth required vs public)

The forwarding map above is **transport-level**. Which of those routes require
an authenticated session and which are public is defined in **ADR-09 (Public
Route Policy)** and MUST stay in sync with that ADR. The header-injection
rule below applies only to authenticated routes; on public routes, `X-User-*`
headers are absent (not a sentinel value).
```

- [ ] **Step 3: Also update the header-injection paragraph to match ADR-09's "absent" semantics**

In the same file, replace exactly:

```
For unauthenticated requests to public routes, these headers are **stripped**
(the gateway must not pass through client-supplied versions).
```

with:

```
For requests to public routes (see ADR-09), these headers are **absent** — the
gateway does not invent a sentinel like `anonymous`. The gateway also strips
any client-supplied `X-User-*` headers on every request so backends never
trust attacker-injected identity.
```

- [ ] **Step 4: Verify both edits landed**

Run:
```bash
grep -n "ADR-09" /home/jeek_lee/work/personal/playground/docs/adr/07-gateway-oauth.md
```
Expected: two lines containing `ADR-09`.

- [ ] **Step 5: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/adr/07-gateway-oauth.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
adr-07: defer public route allowlist to ADR-09

Routes table now lists transport only; classification (which routes need auth,
which are public) lives in ADR-09. Drops the speculative "TBD per M3" comment
on rag-ingest. Header-absence semantics on public routes now match ADR-09.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add ADR-09 to the ADR-00 index

**Files:**
- Modify: `docs/adr/00-overview.md`

- [ ] **Step 1: Confirm the index table**

Run:
```bash
sed -n '21,32p' /home/jeek_lee/work/personal/playground/docs/adr/00-overview.md
```
Expected: index table from ADR 00 through ADR 08.

- [ ] **Step 2: Append the ADR-09 row**

In `/home/jeek_lee/work/personal/playground/docs/adr/00-overview.md`, replace exactly:

```
| 08 | `08-inter-service-comms.md` | Gateway-to-BC HTTP, BC-to-BC Kafka only |
```

with:

```
| 08 | `08-inter-service-comms.md` | Gateway-to-BC HTTP, BC-to-BC Kafka only |
| 09 | `09-public-route-policy.md` | Public route allowlist, anonymous identity, public RAG chat rate limits |
```

- [ ] **Step 3: Verify**

Run:
```bash
grep -c "^| 0" /home/jeek_lee/work/personal/playground/docs/adr/00-overview.md
```
Expected: `10` (rows 00 through 09).

- [ ] **Step 4: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/adr/00-overview.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
adr-00: index ADR-09 in the overview table

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add per-milestone public-route notes to the roadmap

**Files:**
- Modify: `docs/roadmap.md`

Design system spec §11 lists four concerns that ripple into M2, M4, M5. The roadmap is the right place to surface them so that when `pm` later expands each milestone's stub into a full PRD, the concerns are not forgotten.

- [ ] **Step 1: Confirm the M2 section structure**

Run:
```bash
sed -n '49,63p' /home/jeek_lee/work/personal/playground/docs/roadmap.md
```
Expected: M2 — Docs section with Goal / Acceptance / Dependencies / Notes.

- [ ] **Step 2: Append a "Public surface" line to M2's Notes**

In `/home/jeek_lee/work/personal/playground/docs/roadmap.md`, replace exactly:

```
**Notes:** The `document.uploaded` event contract is the hard interface between M2 and M3 and must be frozen in the shared-kernel before M3 starts. Schema lands in architect's per-milestone ADR for M2.
```

with:

```
**Notes:** The `document.uploaded` event contract is the hard interface between M2 and M3 and must be frozen in the shared-kernel before M3 starts. Schema lands in architect's per-milestone ADR for M2.

**Public surface (per ADR-09 / design system spec §11):** M2 introduces the `visibility` column on `docs.documents` (default `private`). Public list/read endpoints (`GET /api/docs/public/**`) serve only documents where `visibility = 'public'`. A `docs.document.visibility-changed` Kafka event is published when an author toggles visibility, so the RAG pipeline can re-tag chunks (consumed in M3).
```

- [ ] **Step 3: Append a "Public surface" line to M3's Notes**

Replace exactly:

```
**Notes:** Depends on M2's `document.uploaded` event from the shared kernel. No direct HTTP from M3 to M2 — Kafka only. Embedding model + endpoint are external (spark-inference-gateway); failure modes (gateway down, timeout) need explicit retry/backoff policy in the per-milestone ADR.
```

with:

```
**Notes:** Depends on M2's `document.uploaded` event from the shared kernel. No direct HTTP from M3 to M2 — Kafka only. Embedding model + endpoint are external (spark-inference-gateway); failure modes (gateway down, timeout) need explicit retry/backoff policy in the per-milestone ADR.

**Public surface (per ADR-09):** Chunks inherit the parent document's `visibility` at ingestion time. On `docs.document.visibility-changed` (public ↔ private), the consumer re-tags every chunk of that document so the visibility filter on the retrieval side stays correct.
```

- [ ] **Step 4: Append a "Public surface" line to M4's Notes**

Replace exactly:

```
**Notes:** Retrieval scope must respect M1's `X-User-Id` (a user can never retrieve from someone else's chunks). This is the first milestone that exercises both LLM endpoints of `spark-inference-gateway` end-to-end.
```

with:

```
**Notes:** Retrieval scope must respect M1's `X-User-Id` (a user can never retrieve from someone else's chunks). This is the first milestone that exercises both LLM endpoints of `spark-inference-gateway` end-to-end.

**Public surface (per ADR-09 / design system spec §11):** This milestone ships **two** chat endpoints: `POST /api/rag/chat/public` (anonymous, retrieves only `visibility = 'public'` chunks, capped at `max_tokens=512`, one retrieved chunk, 10 completions/5min/IP + 30 completions/day/anon-cookie) and `POST /api/rag/chat/private` (authenticated, scoped by `X-User-Id`, full limits). A circuit breaker on `spark-inference-gateway` 5xx rate must be implemented; concrete algorithm and library land in the per-milestone ADR.
```

- [ ] **Step 5: Append a "Public surface" line to M5's Notes**

Replace exactly:

```
**Notes:** Infra-heavy: needs the docker socket (or an exporter) mounted into the metrics container; that constraint goes into `docs/infra-requirements/be.md` for the M5 cycle. Polling interval is a tunable env var.
```

with:

```
**Notes:** Infra-heavy: needs the docker socket (or an exporter) mounted into the metrics container; that constraint goes into `docs/infra-requirements/be.md` for the M5 cycle. Polling interval is a tunable env var.

**Public surface (per ADR-09):** The metrics dashboard is a **public** page. We accept that this exposes container/cluster health to anyone — it's an intentional "live workshop" signal, not a leak. The endpoint is read-only (no mutation surface).
```

- [ ] **Step 6: Verify all four edits landed**

Run:
```bash
grep -c "Public surface" /home/jeek_lee/work/personal/playground/docs/roadmap.md
```
Expected: `4`.

- [ ] **Step 7: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/roadmap.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
roadmap: thread ADR-09 public-surface concerns into M2/M3/M4/M5

M2 gets the visibility column; M3 re-tags chunks on visibility change; M4
ships separate public/private chat endpoints with rate limits + breaker;
M5 metrics dashboard is intentionally public. Each milestone's PM will carry
these into the corresponding PRD when the milestone is expanded.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Update M1 PRD to reflect the public-home pivot

**Files:**
- Modify: `docs/prd/M1-identity.md`

Two changes:
1. Add a top-of-doc note referencing the design system spec.
2. Adjust the "Out of scope" / "Notes" framing so the home page is no longer framed as an auth-only landing showing `/me` JSON. `/me` remains a backend endpoint; the home is now public.

The endpoint contract (`GET /me`, user record creation, `X-User-Id` injection) does not change — those are still M1's job. Only the *frontend rendering* of `/me` moves: instead of a "Home" view dominated by a JSON card, the signed-in user sees `/me` content in the sidebar account footer and (optionally) on a dedicated `/me` route.

- [ ] **Step 1: Confirm the file structure**

Run:
```bash
head -10 /home/jeek_lee/work/personal/playground/docs/prd/M1-identity.md
```
Expected: existing top-of-doc note plus the `## 한 줄 설명` section.

- [ ] **Step 2: Replace the top-of-doc note**

In `/home/jeek_lee/work/personal/playground/docs/prd/M1-identity.md`, replace exactly:

```
> Note on filename: the Stage 1 prompt referenced `M1-agent-task-queue.md`, but the pinned roadmap (and the project spec at `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`) defines M1 as **Identity**. Per the Stage 1 constraint to use the milestone list verbatim, this PRD is written for M1 = Identity at the canonical path `docs/prd/M1-identity.md`.
```

with:

```
> Note on filename: the Stage 1 prompt referenced `M1-agent-task-queue.md`, but the pinned roadmap (and the project spec at `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`) defines M1 as **Identity**. Per the Stage 1 constraint to use the milestone list verbatim, this PRD is written for M1 = Identity at the canonical path `docs/prd/M1-identity.md`.

> Note on scope (added 2026-05-16): the design system spec `docs/superpowers/specs/2026-05-16-playground-design-system.md` establishes the site as **mixed public/authenticated** (the home, documents, public chat, and metrics are reachable without sign-in; see ADR-09). M1's job is still to ship the OAuth flow, the `/me` endpoint, and the gateway's `X-User-*` header injection for authenticated routes — what changes is the **frontend treatment**: the home page is the public landing per the design system, not an auth-gated `/me` viewer. The `/me` payload renders in the sidebar account footer (signed-in state) and optionally on a dedicated `/me` route.
```

- [ ] **Step 3: Update the "Out of scope" section to add a clarifier**

Replace exactly:

```
### Out of scope (this milestone)
- Multi-provider auth (only Google for now — GitHub/Apple/etc. deferred)
- Roles / permissions / RBAC (single-user product for now)
- Account deletion, data export, GDPR-style flows
- Email/password fallback
- Refresh-token rotation strategy beyond what Spring Security gives by default
- Profile editing UI (the user has whatever Google says they have)
```

with:

```
### Out of scope (this milestone)
- Multi-provider auth (only Google for now — GitHub/Apple/etc. deferred)
- Roles / permissions / RBAC (single-user product for now)
- Account deletion, data export, GDPR-style flows
- Email/password fallback
- Refresh-token rotation strategy beyond what Spring Security gives by default
- Profile editing UI (the user has whatever Google says they have)
- Anonymous-visitor analytics, anonymous-cookie management UX — anonymous cookie itself is set by the gateway (ADR-09) but M1 does not surface it
```

- [ ] **Step 4: Update acceptance criterion 5 (the 401 behavior)**

The current criterion says "Unauthenticated request to a protected route returns `401`". Since `/` is now public, the criterion needs a small clarifier so the implementer doesn't accidentally gate the home page.

Replace exactly:

```
- [ ] Unauthenticated request to a protected route returns `401` from the gateway, or the frontend redirects to the login flow — the behavior is consistent and documented
```

with:

```
- [ ] Unauthenticated request to an **authenticated-only** route (see ADR-09 for the classification) returns `401` from the gateway, or the frontend redirects to the login flow — the behavior is consistent and documented. Public routes (`/`, `/docs/public/**`, `/chat`, `/metrics`, `GET /api/docs/public/**`, `GET /api/metrics/**`) MUST NOT return 401 to logged-out callers; they render normally.
```

- [ ] **Step 5: Verify all three edits landed**

Run:
```bash
grep -cE "design system spec|ADR-09|anonymous-cookie" /home/jeek_lee/work/personal/playground/docs/prd/M1-identity.md
```
Expected: at least `4` matches (one design-system reference, two ADR-09 references, one anonymous-cookie reference).

- [ ] **Step 6: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/prd/M1-identity.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
prd-m1: reflect public-home pivot from design system spec

Home is no longer auth-gated. /me stays as a backend endpoint; the rendering
moves to the sidebar account footer (and an optional /me route). 401 behavior
clarified to apply to authenticated-only routes per ADR-09; public routes must
render for logged-out callers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Update product-designer agent to read the design system spec first

**Files:**
- Modify: `.claude/agents/product-designer.md`

The agent's "Inputs" list currently reads the project spec and per-milestone PRD. It must read the **design system spec** before the per-milestone PRD so every Stage 2 starts from the locked tokens, not from a clean slate.

- [ ] **Step 1: Confirm the current inputs list**

Run:
```bash
sed -n '9,16p' /home/jeek_lee/work/personal/playground/.claude/agents/product-designer.md
```
Expected: the numbered Inputs list referencing `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`, the PRD, the roadmap, and ADRs 06/07.

- [ ] **Step 2: Replace the Inputs section**

In `/home/jeek_lee/work/personal/playground/.claude/agents/product-designer.md`, replace exactly:

```
## Inputs (read these in this order)
1. `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` — overall framing (skim, only on first cycle)
2. `docs/prd/<Mx>-<slug>.md` — the PRD for the requested milestone (this is your spec)
3. `docs/roadmap.md` — confirm the milestone's bounded context and acceptance criteria
4. `docs/adr/06-frontend-stack.md` — frontend stack constraints (Next.js + FSD + design tokens location)
5. `docs/adr/07-gateway-oauth.md` — auth boundary (matters for any screen behind login)
6. Existing `docs/design/*.md` if present — reuse design tokens/patterns; do not contradict
```

with:

```
## Inputs (read these in this order)
1. `docs/superpowers/specs/2026-05-16-playground-design-system.md` — **the design system. Tokens, layout shell, home composition, public-vs-auth posture. Apply verbatim — do not invent alternative colors, fonts, or layouts.**
2. `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` — overall framing (skim, only on first cycle)
3. `docs/prd/<Mx>-<slug>.md` — the PRD for the requested milestone (this is your per-feature spec)
4. `docs/roadmap.md` — confirm the milestone's bounded context, acceptance criteria, and public-surface notes
5. `docs/adr/06-frontend-stack.md` — frontend stack constraints (Next.js + FSD + design tokens location)
6. `docs/adr/07-gateway-oauth.md` — auth boundary
7. `docs/adr/09-public-route-policy.md` — which screens are public, which are authenticated-only, anonymous-cookie surface
8. Existing `docs/design/*.md` if present — reuse layouts/patterns; do not contradict
```

- [ ] **Step 3: Add a constraint about not introducing new tokens**

In the same file, find the `## Constraints` section. Replace exactly:

```
- Reuse design tokens across cycles. Do not introduce a new accent color in M2 if M1 already set one — read prior `docs/design/*.md` first.
```

with:

```
- **Never introduce new design tokens.** The design system spec is the closed token set. If a screen needs something not in that token set, stop and add an "Open question" to the design doc proposing the new token; do not silently invent one.
- Reuse layouts and patterns from prior `docs/design/*.md`. If you change one, say so in the design doc with a one-line rationale.
```

- [ ] **Step 4: Verify**

Run:
```bash
grep -n "design system spec\|2026-05-16-playground-design-system\|Never introduce new design tokens" /home/jeek_lee/work/personal/playground/.claude/agents/product-designer.md
```
Expected: at least 3 lines matching.

- [ ] **Step 5: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add .claude/agents/product-designer.md
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
agent: product-designer reads design system spec as primary input

Adds the locked spec as input #1 (above the per-milestone PRD), adds ADR-09 to
the input set, and tightens the "no new tokens" constraint — anything outside
the spec's token set goes to Open questions, not silent invention.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Re-dispatch product-designer for M1 against the locked spec

**Files:**
- Regenerate: `docs/design/M1-identity.md`
- Regenerate: `docs/design/assets/M1/*.png`

The existing `docs/design/M1-identity.md` was generated against an ad-hoc token set (`#3D63DD` accent, slate text, white surfaces). With the design system locked and the agent definition updated (Task 6), we re-run the designer so the M1 deliverable matches the spec.

**Important harness note:** the `product-designer` agent file was created in a previous session. The harness loads its agent list at session start, so:
- If `product-designer` shows in the available agent list when this task runs, dispatch via `Agent(subagent_type: "product-designer", ...)`.
- If it does NOT show (fresh session that booted before the file was created), dispatch via `Agent(subagent_type: "claude", ...)` with the agent's job description embedded in the prompt — same pattern used the first time.

The executor should check available agent types first, then pick the right path. The prompt itself is the same.

- [ ] **Step 1: Confirm tasks 1–6 have landed**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground log --oneline -7
```
Expected: top of log contains commits for Tasks 1–6 (ADR-09, ADR-07, ADR-00, roadmap, PRD M1, designer agent).

- [ ] **Step 2: Verify the design system spec is readable**

Run:
```bash
test -s /home/jeek_lee/work/personal/playground/docs/superpowers/specs/2026-05-16-playground-design-system.md && echo OK
```
Expected: `OK`.

- [ ] **Step 3: Check available agent types**

Look at the available agent types in the current session (system prompt or harness listing). If `product-designer` is listed, use it; otherwise fall back to `claude`.

- [ ] **Step 4: Dispatch the agent**

Use the `Agent` tool with this exact prompt (replace `<subagent_type>` with `product-designer` if available, otherwise `claude`):

```
Stage 2 re-run for M1 (Identity) against the newly-locked design system.

You are the product-designer. Read these inputs IN ORDER:
1. docs/superpowers/specs/2026-05-16-playground-design-system.md — the design system. Tokens, layout shell, home composition, public-vs-auth posture. Apply verbatim.
2. docs/prd/M1-identity.md — the M1 PRD (recently updated with the public-home pivot; see the top-of-doc note added 2026-05-16).
3. docs/adr/06-frontend-stack.md, docs/adr/07-gateway-oauth.md, docs/adr/09-public-route-policy.md — auth boundary and public-route classification.
4. docs/design/M1-identity.md — the existing design doc you will REPLACE. Read it first so you know what's changing.

Your task:
- REPLACE docs/design/M1-identity.md with a new version that applies the design system tokens (olive accent #6E7A3A, cream surfaces, Inter typography, 232px sidebar per spec §8, Genspark-style home per spec §9).
- REPLACE all PNGs under docs/design/assets/M1/ with new screenshots that reflect the new system.
- M1 screens you must produce (changes from the previous design doc):
  • PUBLIC HOME (/) — replaces the previous auth-only Home screen. Apply spec §9 verbatim: 232px sidebar (only "Home" in the Apps section since no other milestone has shipped), slim topbar with "Viewing publicly" chip + "Sign in with Google" primary button, compact hero ("What would you like to do today?"), 1 active tile + 3 locked tiles (locked tiles name their unlocking milestone explicitly: "M2 — Documents", "M4 — Chat", "M5 — System status"), and an empty-state card under "Latest documents" pointing to the M2 GitHub milestone.
  • LOGIN (/login) — keep the screen but apply the new tokens. Same layout, new colors. Use spec §2.2 wordmark and spec §6.1 primary button styling for "Continue with Google".
  • SIGNED-IN HOME — same /  route as PUBLIC HOME, but topbar shows the account pill (avatar + name + chevron) instead of the public chip + Sign in button. Sidebar footer shows avatar + email. The locked "Workspace" tiles are STILL locked at M1 (Workspace tiles unlock per their own milestones), but the topbar reads "Signed in" instead of "Viewing publicly".
  • UNAUTHORIZED (/401) — keep the screen for hits to authenticated-only routes (see ADR-09 for which routes those are). Apply the new tokens. Note: hitting / while logged out NO LONGER redirects to 401 — / is public now.

Traceability matrix:
- Every PRD user story must map to a screen OR be marked "N/A — backend-only" with a one-line reason.
- Add a new row in the matrix for the public-home posture: trace it to the design system spec §2.4 (not a PRD story, but the source of the home redesign).

Design tokens:
- Use ONLY tokens defined in spec §3, §4, §5. If a screen needs something missing, add it to "Open questions" — do not invent.

Figma:
- Call mcp__claude_ai_Figma__whoami first.
- If authenticated, create a new file named "playground — M1 Identity v2 (design system)" via create_new_file, generate the four frames at 1440 wide, save screenshots under docs/design/assets/M1/{login,home-public,home-signedin,unauthorized}.png.
- If not authenticated, fall back to ASCII wireframes per the agent spec; mark the design doc with the wireframes-only note and proceed.

When done, output:
1. Path to the new docs/design/M1-identity.md.
2. Figma URL (or "N/A — wireframes only").
3. Screen count + traceability matrix coverage (X of N PRD stories mapped).
4. Confirmation that no tokens outside spec §3/§4/§5 were introduced.
5. Any open questions.

CONSTRAINTS:
- Documentation + Figma only. NO code under api/, client/, infra/. Do not modify files outside docs/design/ (and only those listed above).
- If running in an isolated worktree, ensure final files are copied or merged into the main working tree before reporting done.
```

- [ ] **Step 5: After the agent finishes, verify the artifacts in the main working tree**

Run:
```bash
ls /home/jeek_lee/work/personal/playground/docs/design/assets/M1/
test -s /home/jeek_lee/work/personal/playground/docs/design/M1-identity.md && head -5 /home/jeek_lee/work/personal/playground/docs/design/M1-identity.md
```
Expected: directory contains the new PNG set (matches what the agent reported); the design doc starts with `# Design: M1 — Identity` and the second line references the design system spec.

If the agent ran in an isolated worktree, copy artifacts from `.claude/worktrees/agent-*/docs/design/M1-identity.md` + `.claude/worktrees/agent-*/docs/design/assets/M1/*` into the main tree before continuing.

- [ ] **Step 6: Verify no new tokens were introduced**

Run:
```bash
grep -oE "#[0-9A-Fa-f]{6}" /home/jeek_lee/work/personal/playground/docs/design/M1-identity.md | sort -u
```
Expected: every hex appearing here must also appear in `docs/superpowers/specs/2026-05-16-playground-design-system.md`. Spot-check by running:
```bash
comm -23 \
  <(grep -oE "#[0-9A-Fa-f]{6}" /home/jeek_lee/work/personal/playground/docs/design/M1-identity.md | sort -u) \
  <(grep -oiE "#[0-9A-Fa-f]{6}" /home/jeek_lee/work/personal/playground/docs/superpowers/specs/2026-05-16-playground-design-system.md | sort -u)
```
Expected: empty output. If any hex appears that is NOT in the spec, the agent invented a token — re-dispatch it with the failure noted, or open-question it manually.

- [ ] **Step 7: Commit**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground add docs/design/M1-identity.md docs/design/assets/M1/
git -C /home/jeek_lee/work/personal/playground commit -m "$(cat <<'EOF'
design-m1: regenerate against locked design system v1

Replaces the ad-hoc M1 tokens (#3D63DD blue, slate text) with the spec'd
olive/cream system. Adds the public-home screen and the signed-in-home
variant; keeps Login and 401. New Figma file linked from the design doc.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Final sanity check and PR-readiness summary

**Files:**
- (read-only inspection)

- [ ] **Step 1: Confirm all expected commits**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground log --oneline -10
```
Expected: top 7 commits are this plan's commits (ADR-09, ADR-07, ADR-00, roadmap, PRD M1, designer agent, design M1). The 8th and below are the design-system spec commit (`1c3f62c`) and earlier work.

- [ ] **Step 2: Confirm working tree is clean**

Run:
```bash
git -C /home/jeek_lee/work/personal/playground status --short
```
Expected: empty output (apart from `?? .superpowers/` if the brainstorm session left files; that directory is gitignored).

- [ ] **Step 3: Verify cross-reference integrity**

Run:
```bash
grep -l "ADR-09" /home/jeek_lee/work/personal/playground/docs/
```
Won't work directly — run instead:
```bash
grep -rl "ADR-09\|design system spec" /home/jeek_lee/work/personal/playground/docs/ /home/jeek_lee/work/personal/playground/.claude/agents/
```
Expected at minimum: `docs/adr/09-public-route-policy.md`, `docs/adr/07-gateway-oauth.md`, `docs/adr/00-overview.md`, `docs/roadmap.md`, `docs/prd/M1-identity.md`, `docs/design/M1-identity.md`, `.claude/agents/product-designer.md`.

- [ ] **Step 4: Stop the brainstorm server (optional cleanup)**

If the brainstorm visual-companion server is still running and we're done with it, stop it. Find session dir:
```bash
ls /home/jeek_lee/work/personal/playground/.superpowers/brainstorm/ 2>/dev/null
```
If present, stop:
```bash
/home/jeek_lee/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0/skills/brainstorming/scripts/stop-server.sh \
  /home/jeek_lee/work/personal/playground/.superpowers/brainstorm/<session-dir>
```
The server auto-exits after 30 minutes of inactivity, so this step is optional.

- [ ] **Step 5: Report to the user**

Post a summary message:

> Design system v1 documentation alignment is complete. Seven commits landed:
> 1. ADR-09 (public route policy)
> 2. ADR-07 (defers public allowlist to ADR-09)
> 3. ADR-00 (indexes ADR-09)
> 4. roadmap.md (M2/M3/M4/M5 public-surface notes)
> 5. M1 PRD (public-home pivot, 401 clarification, anonymous-cookie note)
> 6. product-designer agent (reads design system spec first; no-new-tokens constraint)
> 7. M1 design context (regenerated against locked tokens; new PUBLIC HOME and SIGNED-IN HOME screens)
>
> What was deliberately NOT done in this plan (deferred):
> - Any code under `client/`, `api/`, `infra/`. The design system's code deliverables (tokens CSS, primitive components, Sidebar/Topbar widgets, home page composition) belong to M1 Stage 4 `/build-client M1`, which runs after M0 bootstrap and after `/build-server M1` lands the backend.
> - PRDs for M2/M4/M5. Those will be authored by `pm` when each milestone is expanded; the roadmap notes from Task 4 are the seed material.
>
> Recommended next actions, in order:
> - Human runs M0 bootstrap manually (per the roadmap), then `/design M1` (if needed for further tweaks) or `/build-server M1` to start Stage 3.

---

## Self-Review

Run through the spec section-by-section to confirm each is covered:

| Spec section | Covered by task |
|---|---|
| §1 Purpose | Not actionable — context only |
| §2 Brand (identity, wordmark, tonal anchors, public-vs-personal posture) | Task 7 applies wordmark + tonal anchors in the regenerated M1 design |
| §3 Color tokens | Task 6 (constraint), Task 7 (verified by hex comparison in Step 6) |
| §4 Typography | Task 7 (applied in regenerated screens) |
| §5 Spacing / radius / elevation | Task 7 (applied) |
| §6 Component primitives | Deferred to M1 Stage 4 (out of scope of this plan, explicitly noted) |
| §7 Iconography | Task 7 (emoji placeholders maintained per spec §7's M2 swap note) |
| §8 Layout shell (sidebar + topbar) | Task 7 (applied) |
| §9 Home page composition | Task 7 (PUBLIC HOME screen) |
| §10 M1 supersede mapping | Task 5 (PRD update) + Task 7 (design regen) |
| §11 Architectural ripple (public routes) | Task 1 (ADR-09 covers all four sub-items) + Task 4 (roadmap per-milestone notes) + Task 2 (ADR-07 cross-reference) |
| §12 Deliverables that belong to the next plan | Explicitly out of scope; Task 8 step 5 mentions deferral |
| §13 Out of scope | N/A — already declared in spec |
| §14 Reopening this spec | N/A — meta-process |

No gaps.

**Placeholder scan:** Searched for "TBD", "TODO", "fill in", "implement later", "add appropriate", "Similar to Task". Only legitimate uses are: Task 2 step 2 removes a pre-existing `TBD per M3` comment from ADR-07 (good — it's being deleted, not added); ADR-09 contains "Future M2 per-milestone ADR" / "Future M4 per-milestone ADR" lines which are deliberate cross-references to future work, not placeholders.

**Type / name consistency:** Cross-checked token names between spec §3 and the new constraints in Task 6 — match. Cross-checked route patterns between ADR-09 (Task 1), ADR-07 (Task 2), and M1 PRD (Task 5) — all use `/api/docs/public/**`, `/api/rag/chat/public`, `/api/metrics/**`. The PRD update in Task 5 step 4 includes the exact same pattern list as ADR-09. Consistent.

**Path verification:** Every `git add` path corresponds to a file that the prior step in the same task creates or modifies. Every `Read` / `Run` command targets a path that exists at that point in the plan.
