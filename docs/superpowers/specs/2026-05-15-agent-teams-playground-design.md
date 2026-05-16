# Playground Web Service + Agent Teams — Design

**Date:** 2026-05-15 (revised same day)
**Status:** Approved by user, JIT execution per stage

## Context

`playground/` is a long-lived personal web service that grows feature by feature. The repo is itself the product; each new feature adds a bounded-context module (its own runnable Spring Boot service) to a Gradle multi-module monorepo. A Spring Cloud Gateway sits in front, handling Google OAuth and routing.

In parallel, the project doubles as a **methodology experiment** in multi-agent development on top of Claude Code subagents + Figma MCP. Each milestone (one bounded context) is built by the agent team through 4 stages with human review gates.

## Features (initial roadmap)

| Mx | Bounded context | What it does |
|---|---|---|
| **M0** | Bootstrap | Empty Gradle multi-module + gateway shell + shared-kernel + compose with Kafka/Postgres/Redis (**manual setup by human**) |
| **M1** | Identity | Google OAuth in gateway, user records, `/me` endpoint, session cookie |
| **M2** | Docs | MD document hosting (replaces user's git blog) — upload, list, render |
| **M3** | RAG-Ingestion | `document.uploaded` Kafka consumer → chunk → embed (BGE-M3 via spark-inference-gateway) → pgvector |
| **M4** | RAG-Chat | Chatbot UI + retrieval + generation (Qwen3-32B via spark-inference-gateway) |
| **M5** | Metrics | Spark REST polling + Docker container status dashboard |
| **M6+** | Agents | TBD (later cycles) |

## Architecture — Multi-Module MSA with API Gateway

- Gradle multi-module monorepo, each BC = its own Spring Boot app with own `main()`, own port, own DB schema.
- **Gateway** (Spring Cloud Gateway) handles Google OAuth as OAuth2 Client, manages session cookie, injects `X-User-Id` / `X-User-Email` headers to backend services.
- Backend services trust those headers (only reachable inside the docker network — gateway is the single ingress).
- Cross-BC communication via **Kafka** (KRaft mode) — no direct HTTP between BCs.
- **Shared kernel module** provides event envelope, common DTOs, OAuth resource server stub (if a service ever needs JWT for testing).

### LLM backend (reused, not duplicated)

Playground does **not** run its own LLM. It calls the existing `spark-inference-gateway`:
- Host binding: `127.0.0.1:10080` (vLLM, OpenAI-compatible API)
- Generation model: **Qwen3-32B**
- Embedding model: **BGE-M3**
- Reached from playground compose via `host.docker.internal:10080` with `extra_hosts: ["host.docker.internal:host-gateway"]`

### Vector store

**Postgres + pgvector** in playground's own Postgres container. No separate vector DB (Qdrant/OpenSearch).

### What playground containers run (compose)

```
gateway, identity, docs, rag-ingestion, rag-chat, metrics, client (Next.js)
+ kafka (KRaft), postgres-playground (with pgvector), redis-playground
```

External dependency: `spark-inference-gateway` (host process).

## Repository

- GitHub: `JeekLee/playground` — **public**
- SSH remote via host alias `github-personal` (key: `~/.ssh/id_ed25519_personal`)
- Branch protection on `main` (require PR), labels (`milestone`, `feature`, `bug`, `infra`, `design`)

## Team Composition (8 agents, built JIT)

| Agent | Built in phase | Role |
|---|---|---|
| `pm` | A (done) | PRD per milestone; produces milestone roadmap and GitHub milestones |
| `architect` | A (done) | Transverse ADRs at Stage 1; per-milestone ADRs in later cycles |
| `product-designer` | B (after Stage 1 gate) | Figma mockups + design context (uses Figma MCP) |
| `backend-implementer` | C (after Stage 2 gate) | Spring AI + Spring Boot + multi-module + DDD + Kafka |
| `infra-engineer` | C | docker-compose entry point in `infra/` |
| `code-reviewer` | C | Read-only audit |
| `frontend-implementer` | D (after Stage 3 gate) | Next.js + FSD |
| `test-writer` | E (after first feature stable) | JUnit + Testcontainers + Vitest/Playwright |

## Workflow — 4 Stages with Human Review Gates

Each stage has its own slash command. Each ends with hand-back to user for review.

### Stage 1 — Milestones (`/milestones`)
Agents: `pm` + `architect` (parallel)
Output: `docs/roadmap.md`, GitHub Milestones, stub issues, `docs/prd/M1-identity.md`, transverse ADRs under `docs/adr/`
**Gate:** user reviews roadmap + ADRs + M1 PRD.

### Stage 2 — Design (`/design <milestone>`)
Agent: `product-designer`
Output: Figma file + `docs/design/context.md` for that milestone's screens
**Gate:** user reviews mockups.

### Stage 3 — Server (`/build-server <milestone>`)
Agents (sequential): `backend-implementer` → `infra-engineer` → `code-reviewer`
Output: New Spring Boot module under `api/<service>/`, updated `infra/docker-compose.yml`, `docs/infra-requirements/be.md`
Branch: `m<x>/server` → PR
**Gate:** user reviews PR + runs server-side smoke.

### Stage 4 — Client (`/build-client <milestone>`)
Agents (sequential): `frontend-implementer` → `code-reviewer`
Output: New routes/widgets/features under `client/src/`, `docs/infra-requirements/fe.md` (first cycle only)
Branch: `m<x>/client` → PR
**Gate:** user runs `docker compose up`, exercises end-to-end.

## Pre-spec Coordination Pattern (Stage 3)

Backend writes `docs/infra-requirements/be.md` (env vars, external services with image/port, build/run commands, healthcheck, Kafka topic provisioning). Infra-engineer reads it as input. Single-pass — no round-trip. If conflict, infra reports back and orchestrator routes to BE.

## Tech Stack (pinned in architect's ADRs at Stage 1)

- **Backend:** Spring AI latest GA, Spring Boot, Java, Gradle multi-module, DDD layering, EDD via Kafka (KRaft).
- **Gateway:** Spring Cloud Gateway + Spring Security OAuth2 Client (Google).
- **Frontend:** Next.js App Router + TypeScript + Feature-Sliced Design (7 layers, strict unidirectional deps, ESLint-enforced).
- **Infra:** Docker Compose single entry point; pinned image tags; `.env.example` covers every variable.
- **LLM:** External `spark-inference-gateway` (vLLM, OpenAI-compatible) — Qwen3-32B (chat), BGE-M3 (embeddings).
- **Vector store:** pgvector in playground's Postgres.

## Directory Layout

```
playground/
├── .claude/
│   ├── agents/        # JIT-built agent definitions
│   └── commands/      # /milestones, /design, /build-server, /build-client (built JIT)
├── .github/
│   ├── ISSUE_TEMPLATE/
│   └── pull_request_template.md
├── api/                       # Gradle multi-module monorepo
│   ├── gateway/               # Spring Cloud Gateway (OAuth2 Client)
│   ├── shared-kernel/         # event envelopes, common DTOs
│   ├── identity/              # OAuth user records, /me
│   ├── docs/                  # MD hosting (added in M2)
│   ├── rag-ingestion/         # (added in M3)
│   ├── rag-chat/              # (added in M4)
│   └── metrics/               # (added in M5)
├── client/                    # Next.js + FSD
├── infra/                     # docker-compose entry point
├── docs/
│   ├── superpowers/
│   │   ├── specs/             # this file
│   │   └── plans/
│   ├── roadmap.md             # produced by Stage 1
│   ├── bootstrap.md           # M0 manual setup guide (written post-Stage 1)
│   ├── prd/                   # per-milestone PRDs
│   ├── adr/                   # transverse + per-feature ADRs
│   ├── design/                # design context from Stage 2
│   └── infra-requirements/    # BE/FE infra needs
└── README.md
```

## Verification (per stage)

- **Stage 1:** GitHub Milestones page lists M0..M6; `docs/roadmap.md` + 8 transverse ADRs + M1 PRD committed.
- **M0 (manual):** `docker compose -f infra/docker-compose.yml up` boots empty stack (gateway + Kafka + Postgres + Redis); `curl http://localhost:<gateway-port>/actuator/health` returns UP.
- **Stage 2 per Mx:** Figma file accessible; each mockup traces to a PRD user story.
- **Stage 3 per Mx:** `./gradlew :<service>:build` passes; `docker compose config --quiet` exits 0; reviewer report 0 blockers; gateway routes new service.
- **Stage 4 per Mx:** End-to-end: log in via Google → exercise feature in browser.
