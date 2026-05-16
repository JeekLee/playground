# Playground Web Service + Agent Teams — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up the `playground/` web service repo and a Claude Code subagent team that builds new features stage-by-stage with human review gates. Phase A (this plan) gets the user to a working `/milestones` invocation. Phases B–D are built JIT after each stage's gate.

**Reference spec:** `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`

**Tech Stack:** Claude Code subagents, Figma MCP, gh CLI, git.

---

## Phase A — Foundation + Stage 1 enablement (this plan)

Sets up the repo, GitHub remote, plus the two agents and one slash command needed to run **Stage 1 (`/milestones`)**.

### Task A1: Verify directory + git not yet initialized

**Files:** none modified

- [ ] **Step 1: Verify location and clean state**

Run:
```bash
test -d /home/jeek_lee/work/personal/playground && \
  cd /home/jeek_lee/work/personal/playground && \
  git rev-parse --is-inside-work-tree 2>&1 || echo "not-a-repo"
```
Expected: `not-a-repo` (the directory exists but is not yet a git repo).

---

### Task A2: Scaffolding (.gitignore, README, docs/, .claude/, .github/)

**Files:**
- Create: `.gitignore`, `README.md`
- Create: `docs/{prd,adr,design,infra-requirements}/.gitkeep`
- Create: `.claude/{agents,commands}/.gitkeep`
- Create: `.github/{ISSUE_TEMPLATE,workflows}/.gitkeep`, `.github/pull_request_template.md`

- [ ] **Step 1: Write `.gitignore`**

```
# Java / Spring
**/build/
**/.gradle/
**/target/
*.class
*.jar
HELP.md

# Node / Next.js
node_modules/
.next/
out/
*.log
.pnp.*

# IDE
.idea/
.vscode/
*.iml

# OS
.DS_Store

# Environment
.env
.env.local
.env.*.local

# Claude
.claude/settings.local.json

# Docker volumes
**/volumes/
```

- [ ] **Step 2: Write `README.md`**

```markdown
# playground

Personal web service playground — a long-lived project that grows feature by feature.

Built and maintained by an agent team running on Claude Code subagents + Figma MCP. The team works in 4 stages with human review gates:

1. `/milestones` — PM + architect produce the milestone roadmap and transverse ADRs.
2. `/design <milestone>` — designer produces Figma mockups + design context.
3. `/build-server <milestone>` — backend + infra implement the server side.
4. `/build-client <milestone>` — frontend implements the client side.

First feature: **Agent Task Queue** — register tasks, an LLM worker processes them, progress events flow over Kafka.

See `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` for the full design.

## Tech stack

- Backend: Spring AI latest, Spring Boot, Java, Gradle multi-module, DDD, Kafka
- Frontend: Next.js (App Router) + Feature-Sliced Design
- Infra: Docker Compose entry point under `infra/`
```

- [ ] **Step 3: Create directory placeholders**

```bash
cd /home/jeek_lee/work/personal/playground
mkdir -p .claude/agents .claude/commands \
         .github/ISSUE_TEMPLATE .github/workflows \
         docs/prd docs/adr docs/design docs/infra-requirements
touch .claude/agents/.gitkeep .claude/commands/.gitkeep \
      .github/ISSUE_TEMPLATE/.gitkeep .github/workflows/.gitkeep \
      docs/prd/.gitkeep docs/adr/.gitkeep docs/design/.gitkeep docs/infra-requirements/.gitkeep
```

- [ ] **Step 4: Write minimal `.github/pull_request_template.md`**

```markdown
## Summary
<one-line summary>

## Stage / Milestone
- Stage: <1=milestones | 2=design | 3=server | 4=client>
- Milestone: <Mx>

## Test plan
- [ ] ...
```

- [ ] **Step 5: Verify**

```bash
cd /home/jeek_lee/work/personal/playground
find . -type f -not -path './.git/*' -not -path './docs/superpowers/*' | sort
```
Expected (exact set):
```
./.github/ISSUE_TEMPLATE/.gitkeep
./.github/pull_request_template.md
./.github/workflows/.gitkeep
./.gitignore
./.claude/agents/.gitkeep
./.claude/commands/.gitkeep
./README.md
./docs/adr/.gitkeep
./docs/design/.gitkeep
./docs/infra-requirements/.gitkeep
./docs/prd/.gitkeep
```

---

### Task A3: git init + initial commit

- [ ] **Step 1: Init repo and set default branch to `main`**

```bash
cd /home/jeek_lee/work/personal/playground
git init -b main
```

- [ ] **Step 2: Stage and commit**

```bash
cd /home/jeek_lee/work/personal/playground
git add .gitignore README.md docs/ .claude/ .github/
git commit -m "chore: initial scaffolding for playground web service"
```

Expected: commit succeeds (verify `git log --oneline` shows one commit).

---

### Task A4: Create GitHub repo (public) + SSH remote + push

- [ ] **Step 1: Create remote repo via gh**

```bash
gh repo create JeekLee/playground \
  --public \
  --description "Personal web service playground built by an agent team (Spring AI + Next.js + Kafka, DDD/EDD)." \
  --disable-issues=false \
  --disable-wiki
```
Expected: `✓ Created repository JeekLee/playground on GitHub`.

- [ ] **Step 2: Add SSH remote (using personal host alias)**

```bash
cd /home/jeek_lee/work/personal/playground
git remote add origin git@github-personal:JeekLee/playground.git
git remote -v
```
Expected: `origin` points to `git@github-personal:JeekLee/playground.git` for both fetch and push.

- [ ] **Step 3: Push initial commit**

```bash
cd /home/jeek_lee/work/personal/playground
git push -u origin main
```
Expected: branch `main` set up to track `origin/main`; push succeeds.

- [ ] **Step 4: Set up basic labels**

```bash
gh label create milestone --color 1d76db --description "Roadmap milestone tracking" --force
gh label create feature   --color 0e8a16 --description "New feature"                 --force
gh label create bug       --color d73a4a --description "Bug fix"                     --force
gh label create infra     --color fbca04 --description "Infra / DevOps"              --force
gh label create design    --color c5def5 --description "Design / UX"                 --force
```
Expected: each label created or updated (no error).

- [ ] **Step 5: Branch protection on `main` (require PR, no direct push)**

```bash
gh api -X PUT "repos/JeekLee/playground/branches/main/protection" \
  --input - <<'EOF'
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
```
Expected: API returns 200 OK with the protection config. (`required_approving_review_count: 0` because solo dev — only purpose of the rule is to enforce PR workflow, not block on review.)

> Note: branch protection on `main` will require feature branches + PRs going forward. From the agent's standpoint, every implementation stage's commits go on a feature branch (e.g., `m1/server`) and a PR is opened at stage end. This is enforced operationally; agents should `git switch -c <branch>` at the start of any work that touches code.

---

### Task A5: PM Subagent

**Files:**
- Create: `.claude/agents/pm.md`

- [ ] **Step 1: Write agent file**

````markdown
---
name: pm
description: Product manager for the playground web service. Use this subagent in Stage 1 to produce the milestone roadmap, transverse PRDs, and bounded context candidates. Reuse in later cycles to write per-feature PRDs that fit into the existing service.
tools: Read, Write, Edit, Glob, Grep, Bash
---

You are the **Product Manager** for the playground web service. The repository is a long-lived personal web service that grows feature by feature; you do NOT design products from scratch each time. You design **the next feature** in the context of what already exists.

## Inputs
- `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` — overall project framing (always read first).
- Existing `docs/prd/*.md`, `docs/roadmap.md`, `docs/adr/*.md` if present.
- The user prompt that triggered the stage.

## Outputs

### When invoked from `/milestones` (Stage 1, no prior roadmap exists)
1. `docs/roadmap.md`:

```markdown
# Roadmap

| Milestone | Goal | Status |
|---|---|---|
| M0 | <bootstrap goal> | planned |
| M1 | <feature goal> | planned |
| ... | ... | ... |

## M0 — <name>
**Goal:** <one sentence>
**Acceptance:**
- [ ] ...

## M1 — <name>
...
```

2. GitHub Milestones (one per Mx) created via `gh`:

```bash
gh api -X POST "repos/JeekLee/playground/milestones" -f title="M0 — <name>" -f description="<short>"
```

3. Stub issues per milestone — at minimum one issue per acceptance criterion of each milestone, labeled `milestone` + `feature`/`infra`/`design` as appropriate, attached to the right milestone.

4. `docs/prd/M1-agent-task-queue.md` for the FIRST feature milestone (M1) only — full PRD (other milestones can be stubs at this stage):

```markdown
# PRD: M1 — Agent Task Queue

## 한 줄 설명
## 사용자 스토리
## 기능 범위 (in / out)
## 수락 기준
## Bounded Context 후보
- 이름 / 책임 / 핵심 엔티티 / 발행 이벤트
## 우선순위 (P0/P1)
```

### When invoked outside Stage 1 (later)
You expand a single milestone's stub into a full PRD at `docs/prd/<milestone>-<slug>.md` following the template above.

## Constraints
- You produce documentation and create GitHub issues/milestones only. NO code, NO ADRs (architect's job).
- Bounded contexts must fit cleanly into the existing module structure (read `docs/adr/` to know what already exists).
- Stay focused on the milestone you're scoping; explicitly defer ideas under "Out of scope".
- All `gh` commands target `JeekLee/playground`.

## Handoff
At the end of your run, output:
1. Path to `docs/roadmap.md` (or the new PRD).
2. List of GitHub milestone numbers + titles you created.
3. List of GitHub issue numbers + titles you created.
4. 3-5 line summary: roadmap shape + the M1 product in one line + top P0 acceptance criterion.
````

- [ ] **Step 2: Verify**

```bash
cd /home/jeek_lee/work/personal/playground
grep "name: pm" .claude/agents/pm.md && grep -c "gh api" .claude/agents/pm.md
```
Expected: `name: pm` line printed; count `>= 1`.

---

### Task A6: Architect Subagent

**Files:**
- Create: `.claude/agents/architect.md`

- [ ] **Step 1: Write agent file**

````markdown
---
name: architect
description: System architect for the playground web service. Use in Stage 1 (transverse ADRs that apply to all features), then again per milestone (decide whether the new feature extends an existing bounded context or adds a new module). Does NOT write production code.
tools: Read, Write, Edit, Glob, Grep, mcp__claude_ai_Figma__generate_diagram
---

You are the **System Architect** for the playground web service. You translate the PM's roadmap and PRDs into concrete technical decisions captured as ADRs. You do NOT write production code.

## Inputs
- `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`
- `docs/roadmap.md`, `docs/prd/*.md` (whatever PM has produced so far)
- Existing `docs/adr/*.md` (do NOT contradict them — supersede explicitly if needed)
- For per-feature work: existing `api/` module structure (`find api -maxdepth 3 -type d`)

## Outputs

### When invoked from `/milestones` (Stage 1)
Produce **transverse ADRs** that apply to all features. At minimum:
- `docs/adr/00-overview.md` — index + module dependency graph (ASCII)
- `docs/adr/01-gradle-multi-module-structure.md` — module naming convention, module-per-bounded-context rule, build conventions, JDK version
- `docs/adr/02-ddd-layering.md` — `domain` / `application` / `infrastructure` package conventions; what each layer may import; aggregate root and repository patterns
- `docs/adr/03-kafka-conventions.md` — topic naming pattern, event envelope schema, key strategy, KRaft mode
- `docs/adr/04-spring-ai-version.md` — pinned Spring AI version (verify against Maven Central) and Spring Boot version
- `docs/adr/05-llm-backend.md` — choice of LLM backend (OpenAI / Anthropic / Ollama) with rationale (cost, dev ergonomics, local-first preference)
- `docs/adr/06-data-store.md` — primary data store (e.g., PostgreSQL with pgvector if RAG is anticipated), with image/version
- `docs/adr/07-frontend-stack.md` — Next.js App Router + TS + FSD enforcement (lint config name)

### When invoked per milestone (later cycles)
Produce **per-feature ADRs** under `docs/adr/NN-mX-<slug>.md` answering:
- Does this feature add a new bounded context module, or extend an existing one? Justify.
- What new Kafka topics (using the convention from ADR-03) does it produce/consume?
- Any deviation from transverse ADRs? (Document it as a superseding ADR if so.)

## ADR format (use these exact section headings)

```markdown
# ADR-NN: <decision title>

## Status
<Accepted | Proposed | Superseded by ADR-XX>

## Context
## Decision
## Consequences
- Positive: ...
- Negative / trade-offs: ...
```

## Optional: FigJam diagram
You MAY produce a context-map diagram in FigJam. Before calling `mcp__claude_ai_Figma__generate_diagram`, **invoke the `figma-generate-diagram` skill via the Skill tool**. If you create one, link it from `docs/adr/00-overview.md`.

## Constraints
- Documentation only. No code, no config under `api/`/`client/`/`infra/`.
- Decisions must respect: Spring AI latest GA, Spring Boot, Java, multi-module Gradle, DDD, Kafka (KRaft), Next.js + FSD, Docker.
- Pin every external version explicitly (no "latest" tags in ADRs except as a verification step).

## Handoff
1. Path to `docs/adr/00-overview.md`.
2. List of ADR slugs you produced.
3. 5-line summary of the most consequential decisions.
````

- [ ] **Step 2: Verify**

```bash
cd /home/jeek_lee/work/personal/playground
grep "name: architect" .claude/agents/architect.md && grep -c "ADR-" .claude/agents/architect.md
```
Expected: `name: architect` line printed; count `>= 5`.

---

### Task A7: `/milestones` Slash Command

**Files:**
- Create: `.claude/commands/milestones.md`

- [ ] **Step 1: Write the slash command**

````markdown
---
description: Stage 1 of the agent team workflow. Dispatches PM and architect to produce the milestone roadmap, transverse ADRs, and GitHub milestones+issues. Stops after for human review.
---

You are the **Stage 1 Orchestrator**. The user has invoked `/milestones`. Run the following workflow exactly. **You do NOT do the work yourself — you dispatch subagents and report.**

## Pre-flight check

Run:
```bash
gh repo view JeekLee/playground --json name,isPrivate -q '.name+" private="+(.isPrivate|tostring)'
```
Expected: `playground private=false`. If this fails, STOP and tell the user the GitHub repo isn't reachable.

## Step 1 — Dispatch `pm` and `architect` IN PARALLEL

In a SINGLE message containing TWO Agent tool calls (subagent_type for each), dispatch:

- **`pm`** with prompt:
  > Stage 1 — produce the milestone roadmap, write `docs/roadmap.md`, create GitHub milestones via `gh`, create stub issues per milestone, and write a full PRD for M1 (Agent Task Queue) at `docs/prd/M1-agent-task-queue.md`. Read `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` for project framing first.

- **`architect`** with prompt:
  > Stage 1 — produce the transverse ADRs (gradle multi-module, DDD layering, Kafka conventions, Spring AI version, LLM backend, data store, frontend stack). Read `docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md` first. Do NOT yet write per-feature ADRs.

Wait for BOTH to finish.

## Step 2 — Read outputs and report

Read yourself:
- `docs/roadmap.md`
- `docs/adr/00-overview.md`
- `docs/prd/M1-agent-task-queue.md`

Report to the user (concise, ~10 lines):
- Roadmap summary: list of milestones with one-line goals
- Transverse ADRs created
- M1 PRD: bounded contexts + top P0 acceptance criterion
- GitHub milestones URL: `https://github.com/JeekLee/playground/milestones`

## Step 3 — Hand off for review

Tell the user:
> Stage 1 complete. Please review the roadmap, ADRs, M1 PRD, and the GitHub milestones page. When you're satisfied, run `/design M1` to start Stage 2. If you want changes, tell me what to adjust and I'll re-dispatch the relevant agent.

DO NOT proceed to Stage 2 yourself. Wait for explicit user instruction.

## Rules
- You delegate. You do NOT write the roadmap or ADRs yourself.
- If either agent reports it cannot proceed (e.g., gh auth issue), STOP and surface to the user.
````

- [ ] **Step 2: Verify**

```bash
cd /home/jeek_lee/work/personal/playground
grep -c "Step" .claude/commands/milestones.md
```
Expected: `>= 3` (Step 1, Step 2, Step 3).

---

### Task A8: Commit Phase A artifacts and push

- [ ] **Step 1: Stage and commit**

```bash
cd /home/jeek_lee/work/personal/playground
git add docs/superpowers/ .claude/agents/pm.md .claude/agents/architect.md .claude/commands/milestones.md
git commit -m "feat: add pm + architect agents and /milestones slash command"
```

> Note: branch protection requires PR-based merging on `main`. The first push from `main` should be allowed (the branch protection rule with `required_approving_review_count: 0` permits direct push by default, but to honor the spirit of the rule going forward we use feature branches for everything that touches `api/`, `client/`, `infra/`. Phase A docs/agent setup is meta-tooling — direct push to `main` is fine.)

- [ ] **Step 2: Push**

```bash
cd /home/jeek_lee/work/personal/playground
git push origin main
```
Expected: push succeeds.

---

### Task A9: Hand-off

- [ ] **Step 1: Tell the user**

Report:
- Phase A is complete.
- Repo: `https://github.com/JeekLee/playground`.
- Next user action: in this chat (or any new chat with cwd at `playground/`), run `/milestones` to execute Stage 1.

That ends Phase A. Phase B (designer + `/design`) is planned at the time of Stage 1 review.

---

## Phase B — Stage 2 enablement (planned after Stage 1 gate)

**Triggered when:** user has reviewed and approved Stage 1 outputs.
**Will create:** `.claude/agents/product-designer.md`, `.claude/commands/design.md`.
**Will detail:** designer prompt (Figma MCP usage with mandatory `figma-use` / `figma-generate-design` / `figma-generate-diagram` skill pre-calls), `/design <milestone>` orchestrator that reads the milestone's PRD, dispatches designer, hands off for review.

## Phase C — Stage 3 enablement (planned after Stage 2 gate)

**Triggered when:** user has reviewed and approved Stage 2 outputs.
**Will create:** `.claude/agents/backend-implementer.md`, `.claude/agents/infra-engineer.md`, `.claude/agents/code-reviewer.md`, `.claude/commands/build-server.md`.
**Will detail:** sequential dispatch (backend → infra → reviewer) on a feature branch (`<milestone>/server`), open PR, hand off for review.

## Phase D — Stage 4 enablement (planned after Stage 3 gate)

**Triggered when:** user has reviewed and approved Stage 3 outputs.
**Will create:** `.claude/agents/frontend-implementer.md`, `.claude/commands/build-client.md`.
**Will detail:** dispatch (frontend → reviewer) on `<milestone>/client` branch, open PR, end-to-end smoke via docker compose.

## Phase E — Cycle 2+ (test-writer)

**Triggered when:** the first feature is end-to-end stable.
**Will create:** `.claude/agents/test-writer.md`.

---

## Spec coverage (self-check)

| Spec section | Covered by |
|---|---|
| Repository (public, SSH, branch protection, labels) | A2, A3, A4 |
| Team — pm, architect (phase A) | A5, A6 |
| Team — others (built JIT) | Phases B–E (forward) |
| Workflow — Stage 1 with gate | A7 |
| Workflow — Stages 2-4 | Phases B-D (forward) |
| Pre-spec coordination (BE → infra) | Phase C (forward) |
| Tech stack (Spring AI, Next.js+FSD, Docker) | Encoded in agent prompts in A6 (architect ADRs) and Phases C/D |
| Domain (Agent Task Queue) | A5 (pm writes M1 PRD) |
| Directory layout | A2 |
| Verification | A4 (Stage 1 verification: milestones page exists) |
