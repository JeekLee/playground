# Post-M5 Roadmap Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Granularity note:** This is a **roadmap-wiring plan**, not a per-milestone implementation plan. Three tasks total — one PR + two pure GitHub API ops. Per-milestone deep cycles (brainstorm spec → PRD → ADR → design → implementation slices) for M6 / M7 / M8 happen later in their own sessions, each running the standard 9–12 task cycle (mirroring M4 / M5).

**Goal:** Wire the three new milestones (M6 PDF-in-docs / M7 tool-calling / M8 massing-gen) into the project structure — `docs/roadmap.md`, the agent-teams design spec, and GitHub milestones + stub issues — so future per-milestone cycles can pick them up cleanly.

**Architecture:** Pure documentation + GitHub structural changes. No code touched. The single PR amends two docs files atomically; two follow-up GitHub API ops restructure milestones + create stub issues.

**Tech Stack:** Markdown edits, `gh` CLI for milestone/issue management.

---

## Source spec + cross-references

- Spec: `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` (490 lines, merged via PR #159 at commit `9c2672f`).
- Spec §12 lists the supersession + downstream amendments — this plan encodes those changes.
- Spec §13 ("Migration from 'M6+ Agents TBD'") explains the placeholder resolution.

---

## File structure (what lands where)

### Doc amendments (Task 1 — one PR)
- Modify: `docs/roadmap.md` — replace the M6+ table row + the M6+ section with three new milestone entries (M6, M7, M8)
- Modify: `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` §"Features (initial roadmap)" — its M6+ row in the same fashion

### GitHub structural (Tasks 2 + 3 — no PR)
- Modify: GitHub milestone #7 (currently "M6+ — Agents") — rename + update description
- Create: GitHub milestone (M7 — Tool-calling) — new milestone #N
- Create: GitHub milestone (M8 — massing-gen) — new milestone #N+1
- Modify: GitHub issue #31 — close with cross-reference comment OR repurpose under new M6 milestone
- Create: 1 stub issue per new milestone (3 stubs total) — mirroring the M2-M5 pattern from earlier roadmap seeding

---

## Dependencies + execution order

```
Task 1 (doc amendments PR)
   │
Task 2 (GitHub milestone restructure) ← needs Task 1 (milestone names should match doc names)
   │
Task 3 (issue stubs + close #31) ← needs Task 2 (milestones must exist to attach issues)
```

All three tasks are short. The whole plan should take under 30 minutes to execute.

---

## Conventions used throughout this plan

- **Worktree-per-task** for Task 1: `EnterWorktree({ name: "m6-roadmap-wiring" })`. Per CLAUDE.md.
- **`gh pr merge --rebase`** without `--delete-branch`; then `git push origin --delete <branch>`.
- **`git fetch origin && git merge --ff-only origin/main`** to sync after `ExitWorktree`.
- Tasks 2 + 3 are pure GitHub API; no worktree needed.

---

### Task 1: Amend roadmap + agent-teams spec (single PR)

**Stage:** Doc wiring
**Agent:** orchestrator (direct edit — no subagent needed; pure mechanical replacement from a known spec)
**Branch:** `worktree-m6-roadmap-wiring`

**Files:**
- Modify: `docs/roadmap.md` (lines ~1–17 table + §M6+ block)
- Modify: `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` (§"Features (initial roadmap)" table M6+ row)

**Steps:**

- [ ] **Step 1: Enter worktree**

```
EnterWorktree({ name: "m6-roadmap-wiring" })
```

- [ ] **Step 2: Read current state of both files** (via `Read` tool — both are smallish):
  - `docs/roadmap.md` (read full file)
  - `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` (focus on §"Features (initial roadmap)")

- [ ] **Step 3: Amend `docs/roadmap.md` — replace the M6+ table row**

The top-of-file table currently includes this row:
```markdown
| M6+ | Agents | TBD (future cycles) | deferred |
```

Replace with three new rows:
```markdown
| M6 | Docs (PDF support) | M2 docs BC accepts PDF; Apache PDFBox text extraction feeds M3 unchanged | planned |
| M7 | RAG-Chat (tool-calling) | rag-chat invokes external tool BCs via Spring AI 1.0 function-calling; generic infra | planned |
| M8 | massing-gen | New BC: brief PDF → room program → basic massing → .3dm via rhino3dm sidecar | planned |
```

- [ ] **Step 4: Amend `docs/roadmap.md` — replace the M6+ section**

The current `## M6+ — Agents` block (and everything after it through end-of-file) should be replaced with three new sections. Use this exact replacement (note the trailing horizontal rule between sections):

```markdown
## M6 — Docs (PDF support)

**Goal:** Extend M2's docs BC to accept `.pdf` uploads. Server extracts text via Apache PDFBox and stores it in the existing `body` column; M3 RAG ingestion (Kafka consumer + chunking + embedding) processes the extracted text unchanged.

**Acceptance:**
- [ ] `POST /api/docs/upload` accepts `application/pdf` MIME type
- [ ] `docs.documents.mime_type` column added (default `text/markdown`); PDF uploads set `application/pdf`
- [ ] Extracted text appears in `body` and M3 ingests it identically to MD content
- [ ] Frontend `/docs/new` file picker accepts `.pdf`; doc detail page shows `(PDF)` indicator
- [ ] Apache PDFBox runs in-process (no new container needed)

**Dependencies:** M0, M2.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §4. Per-milestone ADR-16 closes open questions (PDFBox version pin, Korean PDF test corpus, corrupted-PDF error semantic, original-binary storage decision).

---

## M7 — RAG-Chat (tool-calling)

**Goal:** Give rag-chat (M4) the ability to invoke external tool BCs via LLM function-calling. Generic infrastructure — domain-neutral — so any future tool BC plugs in via the same pattern.

**Acceptance:**
- [ ] `ToolCatalog` constants class in `rag-chat-domain` lists registered tools (name, description, parameter schema, endpoint URL, timeout)
- [ ] `ToolDispatcher` adapter in `rag-chat-infra` invokes tool BCs via Spring WebClient with Resilience4j circuit breaker per tool
- [ ] `ChatTurnUseCase` extended with Spring AI 1.0 `ChatClient.tools(...)` — handles multi-turn tool_call → tool_result → token flow
- [ ] SSE event grammar gains `tool_call` / `tool_result` / `tool_error` event types (amends ADR-14 §5.2)
- [ ] ADR-08 Exception 4 added (rag-chat → tool BCs HTTP for LLM function-calling)
- [ ] Maximum tool-call depth (default 5) enforced; exceeded depth emits `tool_error` with code `MAX_DEPTH_EXCEEDED`
- [ ] WireMock-stubbed end-to-end test validates the full flow without a real tool BC

**Dependencies:** M0, M1, M2, M3, M4.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §5. Per-milestone ADR-17 closes open questions (Spring AI 1.0 API stability, tool error retry policy, max-depth default, tool result max size, SSE event ordering during multi-turn). M7 itself adds no user-visible feature — it enables M8.

---

## M8 — massing-gen

**Goal:** Ship the first domain-specific tool BC — given a brief PDF document ID, extract the room program via LLM, run a basic massing algorithm, and return a Rhino `.3dm` file URL.

**Acceptance:**
- [ ] New BC `massing-gen-{api,app,domain,infra}` quadruplet (port 18086 candidate, ADR-18 confirms)
- [ ] `POST /internal/tools/generate-massing` accepts `{ briefDocId, siteWidth?, siteDepth?, floorHeight? }` and returns `{ fileUrl, programJson, totalAreaM2, floorCount, summary }`
- [ ] Brief reading via M2's existing `/internal/docs/{id}/body` (ADR-08 Exception 1 widened — see ADR-18)
- [ ] LLM call (Qwen3-32B via spark-inference-gateway) extracts structured room program JSON from brief text
- [ ] `MassingAlgorithm` in `-domain` (Spring-free) implements rectangular first-fit + area balance
- [ ] `rhino3dm-bridge` Node sidecar container serializes box list → `.3dm` binary
- [ ] New Postgres schema `arch` + table `arch.outputs` stores file_bytes (BYTEA), program_json (JSONB), brief_doc_id, user_id, total_area_m2, floor_count, created_at
- [ ] `GET /api/arch/outputs/{id}` authenticated owner-only download endpoint
- [ ] Tool descriptor registered in `rag-chat-domain.ToolCatalog`
- [ ] Generated `.3dm` opens in Rhino without errors; total box area ≥ sum of required room areas

**Dependencies:** M0, M1, M2 (extended by M6), M3, M4, M7.

**Notes:** Spec at `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §6. Per-milestone ADR-18 closes open questions (`.3dm` library pin, Korean brief extraction prompt, output JSON Schema, over-area handling, file storage BYTEA vs object storage, orphan cleanup, BC name finalize, per-user rate limit).
```

- [ ] **Step 5: Amend `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`**

In the §"Features (initial roadmap)" table, the current M6+ row reads:
```markdown
| **M6+** | Agents | TBD (later cycles) |
```

Replace with:
```markdown
| **M6** | Docs (PDF support) | M2 docs BC accepts PDF; Apache PDFBox text extraction feeds M3 unchanged |
| **M7** | RAG-Chat (tool-calling) | rag-chat invokes external tool BCs via Spring AI 1.0 function-calling |
| **M8** | massing-gen | brief PDF → room program → basic massing → .3dm via rhino3dm sidecar |
```

- [ ] **Step 6: Verify both edits**

```bash
echo "=== roadmap.md table check (should show M6/M7/M8 rows, NOT M6+) ==="
sed -n '1,20p' docs/roadmap.md
echo
echo "=== roadmap.md M6+ section check (should be gone) ==="
grep -n "^## M6+" docs/roadmap.md || echo "  PASS: no M6+ section"
echo
echo "=== roadmap.md new milestone sections present ==="
grep -n "^## M6 \|^## M7 \|^## M8 " docs/roadmap.md
echo
echo "=== agent-teams spec check ==="
grep -A 1 "Features (initial roadmap)" docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md | head -20
```

Expected: 3 new rows in roadmap.md table, 3 new sections (`## M6`, `## M7`, `## M8`), no `## M6+` section, agent-teams spec has 3 new milestone rows.

- [ ] **Step 7: Commit + push + PR + merge + exit + sync**

```bash
git add docs/roadmap.md docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md
git commit -m "post-m5-roadmap: replace M6+ 'Agents TBD' placeholder with M6/M7/M8 brief-to-massing vertical

Per docs/superpowers/specs/2026-05-19-post-m5-roadmap.md (PR #159).

- roadmap.md: M6+ row + section retired; new M6 (PDF in docs),
  M7 (tool-calling), M8 (massing-gen) sections added
- agent-teams spec §'Features (initial roadmap)': same row
  replacement in the feature table"

git push -u origin HEAD:worktree-m6-roadmap-wiring
gh pr create --base main --head worktree-m6-roadmap-wiring \
  --title "post-m5-roadmap: amend roadmap.md + agent-teams spec for M6/M7/M8" \
  --body "Replaces the 'M6+ Agents TBD' placeholder per PR #159 spec. Adds M6 (PDF in docs), M7 (tool-calling), M8 (massing-gen) entries to both files."
gh pr merge --rebase
git push origin --delete worktree-m6-roadmap-wiring
# ExitWorktree({ action: "remove", discard_changes: true })
git fetch origin && git merge --ff-only origin/main
```

Expected output: PR created, merged, branch deleted, main fast-forwarded.

---

### Task 2: GitHub milestone restructure (rename + create)

**Stage:** GitHub structural
**Agent:** orchestrator (direct `gh` calls — no subagent needed)
**Branch:** none (pure API)

**Steps:**

- [ ] **Step 1: Inspect current milestone #7 state**

```bash
gh api repos/JeekLee/playground/milestones/7
gh issue list --milestone 7 --state all --json number,title,state
```

Expected: milestone #7 currently titled "M6+ — Agents", containing issue #31 (placeholder).

- [ ] **Step 2: Rename milestone #7 → "M6 — Docs (PDF support)"**

```bash
gh api -X PATCH repos/JeekLee/playground/milestones/7 \
  -f title='M6 — Docs (PDF support)' \
  -f description='M2 docs BC accepts PDF; Apache PDFBox text extraction feeds M3 unchanged. Spec: docs/superpowers/specs/2026-05-19-post-m5-roadmap.md §4. PRD/ADR/design cycle to be opened in a future session.'
```

- [ ] **Step 3: Create milestone for M7**

```bash
gh api -X POST repos/JeekLee/playground/milestones \
  -f title='M7 — RAG-Chat (tool-calling)' \
  -f description='rag-chat invokes external tool BCs via Spring AI 1.0 function-calling. Generic infra. Spec: docs/superpowers/specs/2026-05-19-post-m5-roadmap.md §5. PRD/ADR/design cycle to be opened in a future session.'
```

Capture the returned milestone number (likely #8).

- [ ] **Step 4: Create milestone for M8**

```bash
gh api -X POST repos/JeekLee/playground/milestones \
  -f title='M8 — massing-gen' \
  -f description='New BC: brief PDF → room program → basic massing → .3dm via rhino3dm sidecar. First tool BC. Spec: docs/superpowers/specs/2026-05-19-post-m5-roadmap.md §6. PRD/ADR/design cycle to be opened in a future session.'
```

Capture the returned milestone number (likely #9).

- [ ] **Step 5: Verify**

```bash
gh api repos/JeekLee/playground/milestones --jq '.[] | {number, title, state}'
```

Expected: all six existing milestones (M0–M5 at #1–#6) plus M6 at #7 (renamed), M7 at #8 (new), M8 at #9 (new). Each `open`.

---

### Task 3: Issue stubs (3 new) + close #31

**Stage:** GitHub structural
**Agent:** orchestrator (direct `gh` calls)
**Branch:** none (pure API)

**Steps:**

- [ ] **Step 1: Close placeholder issue #31 with redirect comment**

```bash
gh issue close 31 --comment 'Closed by post-M5 roadmap PR #159. The "M6+ Agents TBD" placeholder is replaced by three concrete milestones: M6 (PDF in docs), M7 (tool-calling), M8 (massing-gen). See docs/superpowers/specs/2026-05-19-post-m5-roadmap.md for the full spec. New placeholder issues seeded under milestones #7, #8, #9.'
```

- [ ] **Step 2: Create stub issue under milestone #7 (M6)**

```bash
gh issue create --milestone 7 \
  --title 'M6: Open the M6 cycle — PDF in docs BC' \
  --body 'Driven by `docs/roadmap.md` §M6 + `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §4.

**Acceptance criterion:**
- [ ] When the M6 cycle is opened (future session), the standard cycle runs: brainstorm spec → PRD → ADR-16 → Stage-2 design → implementation slices → close.

Placeholder issue so the milestone has at least one tracking item. The PRD will replace this with concrete acceptance items.

Spec sections to expand: §4 In scope, §4 Open questions for ADR-16.'
```

- [ ] **Step 3: Create stub issue under milestone #8 (M7)**

```bash
gh issue create --milestone 8 \
  --title 'M7: Open the M7 cycle — rag-chat tool-calling infrastructure' \
  --body 'Driven by `docs/roadmap.md` §M7 + `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §5.

**Acceptance criterion:**
- [ ] When the M7 cycle is opened (future session), the standard cycle runs: brainstorm spec → PRD → ADR-17 → Stage-2 design (minimal — no user-visible UI beyond SSE event types) → implementation slices → close.

Placeholder issue so the milestone has at least one tracking item. The PRD will replace this with concrete acceptance items.

Spec sections to expand: §5 In scope, §5 Open questions for ADR-17, §7 cross-cutting (ADR-08 Exception 4 + SSE grammar amendment).'
```

- [ ] **Step 4: Create stub issue under milestone #9 (M8)**

```bash
gh issue create --milestone 9 \
  --title 'M8: Open the M8 cycle — massing-gen BC + brief-to-massing tool' \
  --body 'Driven by `docs/roadmap.md` §M8 + `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §6.

**Acceptance criterion:**
- [ ] When the M8 cycle is opened (future session, after M6 + M7 have shipped), the standard cycle runs: brainstorm spec → PRD → ADR-18 → Stage-2 design → implementation slices (massing-gen quadruplet + rhino3dm sidecar + ToolCatalog registration) → close.

Placeholder issue so the milestone has at least one tracking item. The PRD will replace this with concrete acceptance items.

Spec sections to expand: §6 In scope, §6 Open questions for ADR-18, §7 cross-cutting (.3dm library decision, ADR-08 Exception 1 widening, tool descriptor governance).'
```

- [ ] **Step 5: Verify final state**

```bash
gh issue list --milestone 7 --state all --json number,title,state
gh issue list --milestone 8 --state all --json number,title,state
gh issue list --milestone 9 --state all --json number,title,state
```

Expected:
- Milestone #7 (M6): #31 closed + 1 new open stub
- Milestone #8 (M7): 1 new open stub
- Milestone #9 (M8): 1 new open stub

---

## Plan summary

| # | Task | Agent | Output |
|---|---|---|---|
| 1 | Roadmap + agent-teams spec amendments | orchestrator (direct edit) | One PR amending two doc files |
| 2 | GitHub milestone restructure (rename #7, create #8 + #9) | orchestrator (gh API) | 3 milestones in expected state |
| 3 | Close #31 + create 3 new stub issues | orchestrator (gh API) | Each new milestone has at least one tracking item |

3 tasks, 1 PR, 4 GitHub API operations. Total estimated time: 15–30 minutes.

---

## Out of scope for this plan

Everything that the spec defers to per-milestone cycles:
- M6 brainstorm spec / PRD / ADR-16 / Stage-2 design / implementation
- M7 brainstorm spec / PRD / ADR-17 / Stage-2 design / implementation
- M8 brainstorm spec / PRD / ADR-18 / Stage-2 design / implementation
- Any code changes (no BC scaffolding, no compose changes, no frontend routes)

When a future session opens M6 (for example), it follows the standard 9–12 task cycle mirroring M4 (`docs/superpowers/plans/2026-05-18-m4-rag-chat.md`) or M5 (`docs/superpowers/plans/2026-05-19-m5-metrics.md`).

---

## Termination criteria

This roadmap-wiring plan is complete when:
1. PR for Task 1 merged to `origin/main`; `docs/roadmap.md` shows three new milestone sections (no M6+).
2. GitHub milestones #7 / #8 / #9 exist with the expected titles + descriptions.
3. Placeholder issue #31 is closed with the redirect comment; each new milestone has at least one open stub issue.
4. Future sessions can open M6 / M7 / M8 individually by reading the milestone description (which points at the spec).
