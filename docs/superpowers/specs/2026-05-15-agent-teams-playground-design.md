# Playground Web Service + Agent Teams — Design

**Date:** 2026-05-15 (revised same day)
**Status:** Approved by user, JIT execution per stage

## Context

`playground/` is a personal web service that grows over time as the user implements features they want to build. The repository is itself the product; new features add bounded-context modules to a Spring Boot multi-module backend with a Next.js frontend.

In parallel, the project doubles as a **methodology experiment** in multi-agent development on top of Claude Code subagents + Figma MCP. The first feature added — **Agent Task Queue** — is both:
1. A real feature of the playground web service (users register tasks; an LLM worker processes them; progress events flow over Kafka).
2. The first end-to-end exercise of the agent team workflow.

Future features will reuse the team and slash commands, expanding the web service one bounded context at a time.

## Repository

- GitHub: `JeekLee/playground` — **public**
- SSH remote via host alias `github-personal` (key: `~/.ssh/id_ed25519_personal`)
- Branch protection on `main` (require PR), basic issue templates, labels (`milestone`, `feature`, `bug`, `infra`)

## Team Composition (8 agents, built JIT)

| Agent | Built in phase | Role |
|---|---|---|
| `pm` | A (now) | PRD per feature; produces milestone roadmap |
| `architect` | A (now) | Transverse ADRs (Gradle/Kafka conventions); per-feature module decisions |
| `product-designer` | B | Figma mockups + design context (uses Figma MCP) |
| `backend-implementer` | C | Spring AI + multi-module + DDD + Kafka |
| `infra-engineer` | C | docker-compose entry point in `infra/` |
| `code-reviewer` | C | Read-only audit of BE+infra (and FE in phase D) |
| `frontend-implementer` | D | Next.js + FSD |
| `test-writer` | E (later cycle) | JUnit + Testcontainers + Vitest/Playwright |

## Workflow — 4 Stages with Human Review Gates

Each stage has its own slash command. Each ends with a hand-back to the user for review before proceeding.

### Stage 1 — Milestones (`/milestones`)
Agents: `pm` + `architect`
Output:
- `docs/roadmap.md` — milestone list (M0…MX) with goal per milestone
- GitHub Milestones created via `gh` (one per Mx)
- Stub issues per milestone (PM expands later)
- `docs/adr/` — transverse ADRs (Gradle structure, Kafka conventions, JDK/Spring AI versions, LLM backend choice)
**Gate:** user reviews roadmap + ADRs and either edits in chat (agents re-run) or approves.

### Stage 2 — Design (`/design <milestone>`)
Agent: `product-designer`
Output:
- Figma file with mockups for the milestone's user stories
- `docs/design/context.md` — Figma fileKey/nodeId per screen + design tokens
**Gate:** user reviews mockups in Figma and approves.

### Stage 3 — Server (`/build-server <milestone>`)
Agents (sequential within stage): `backend-implementer` → `infra-engineer` → `code-reviewer`
Output:
- `api/` — new Gradle module(s) for the milestone's bounded context, DDD-layered
- `docs/infra-requirements/be.md` — BE infra requirements
- `infra/` — updated docker-compose entry point
- Code review report
**Gate:** user reviews and triggers fixes if needed.

### Stage 4 — Client (`/build-client <milestone>`)
Agents (sequential within stage): `frontend-implementer` → `code-reviewer`
Output:
- `client/` — Next.js + FSD code reading the milestone's API
- `docs/infra-requirements/fe.md` — FE infra requirements (typically a one-time write at first cycle)
- Code review report
**Gate:** user runs `docker compose -f infra/docker-compose.yml up`, exercises the feature end-to-end.

## Pre-spec Coordination Pattern (Stage 3)

Backend writes `docs/infra-requirements/be.md` listing env vars, external services with image/port, build/run commands, healthcheck, Kafka topic provisioning. Infra-engineer reads it as input. Single-pass — no round-trip negotiation. If conflict, infra reports back and orchestrator routes to the relevant implementer.

## Tech Stack (carried into agent prompts)

- **Backend (`api/`):** Spring AI latest GA, Spring Boot, Java, Gradle multi-module (one module per bounded context), DDD layering (`domain` / `application` / `infrastructure`), EDD via Kafka, real persistence (no in-memory mocks for aggregate state).
- **Frontend (`client/`):** Next.js App Router, TypeScript, Feature-Sliced Design 7 layers (`app` / `pages` / `widgets` / `features` / `entities` / `shared`), strict unidirectional dependencies enforced by lint.
- **Infra (`infra/`):** single `docker-compose.yml` entry point starting BE + FE + Kafka (KRaft) + data store + LLM backend; pinned image tags; `.env.example` covers every variable.

## Domain — Agent Task Queue (first feature)

Initial bounded contexts (PM finalizes during Stage 1):
- **Task** — register, track state, store result
- **Worker** — LLM invocation, tool use, Spring AI integration
- **Notification** — consume progress events, deliver alerts
- (Optional) **Identity** — users / permissions

Initial Kafka events: `task.created`, `task.progressed`, `task.completed`, `task.failed`.

## Directory Layout

```
playground/
├── .claude/
│   ├── agents/        # JIT-built agent definitions
│   └── commands/      # /milestones, /design, /build-server, /build-client
├── .github/
│   ├── ISSUE_TEMPLATE/
│   └── pull_request_template.md
├── api/               # backend-implementer; multi-module Gradle
├── client/            # frontend-implementer; Next.js + FSD
├── infra/             # infra-engineer; docker-compose entry point
├── docs/
│   ├── superpowers/
│   │   ├── specs/     # this file
│   │   └── plans/
│   ├── roadmap.md     # produced by Stage 1
│   ├── prd/           # per-milestone PRDs
│   ├── adr/           # transverse + per-feature ADRs
│   ├── design/        # design context from Stage 2
│   └── infra-requirements/   # BE/FE infra needs
└── README.md
```

## Verification (per stage, end-to-end at Stage 4)

- **Stage 1:** GitHub Milestones page lists Mx; `docs/roadmap.md` exists; transverse ADRs committed.
- **Stage 2:** Figma file accessible; `docs/design/context.md` has fileKey/nodeId per screen; each mockup traces to a PRD user story.
- **Stage 3:** `./gradlew build` passes; `docker compose -f infra/docker-compose.yml config --quiet` exits 0; reviewer report has 0 blockers.
- **Stage 4:** `docker compose -f infra/docker-compose.yml up` boots the full stack; user can exercise the feature in browser; reviewer report has 0 blockers.
