# playground

Personal web service playground — a long-lived project that grows feature by feature.

Built and maintained by an agent team running on Claude Code subagents + Figma MCP. The team works in 4 stages with human review gates:

1. `/milestones` — PM + architect produce the milestone roadmap and transverse ADRs.
2. `/design <milestone>` — designer produces Figma mockups + design context.
3. `/build-server <milestone>` — backend + infra implement the server side.
4. `/build-client <milestone>` — frontend implements the client side.

First feature: **Agent Task Queue** — register tasks, an LLM worker processes them, progress events flow over Kafka.

See [`docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md`](docs/superpowers/specs/2026-05-15-agent-teams-playground-design.md) for the full design.

## Tech stack

- Backend: Spring AI (latest), Spring Boot, Java, Gradle multi-module, DDD, Kafka
- Frontend: Next.js (App Router) + Feature-Sliced Design
- Infra: Docker Compose entry point under `infra/`
