# ADR-19: `agent-tools` Python host service + `massing-gen` → `architecture` BC rename + LangGraph adoption policy

## Status
Accepted (2026-06-04)

## Context

ADR-18 (M8) created the first non-Java BC, `massing-gen`, as a single
Python/FastAPI container at `backend/fastapi/massing-gen/`. ADR-01's
M8 Python-flip amendment (§A01.11–§A01.14) formalized the polyglot
policy: Java/Spring Boot is the primary stack, Python BCs are
permissible by exception, and a polyglot BC ships as a single
container (no four-module quadruplet) at `backend/<runtime>/<bc>/`.
ADR-17 (M7) pins that **rag-chat owns tool-calling orchestration** via
Spring AI function-calling — the `ToolCatalog` descriptor, the
`WebClientToolDispatcher`, the per-tool circuit breaker, the depth cap.
A tool BC is a *capability endpoint* reached over HTTP (ADR-08
Exception 4); it is not an orchestrator.

Three forces motivate this ADR:

1. **The "one BC = one deployable service" rule from ADR-01 is the
   right default for Java but is wrong-grained for small Python LLM
   tools.** Every Python LLM-tool BC drags in the same stack —
   FastAPI, uvicorn, httpx, Pydantic, the spark-inference-gateway LLM
   client, structlog, prometheus-fastapi-instrumentator, and (now)
   LangGraph. Standing up a fresh container per tiny tool BC duplicates
   that stack N times, multiplies the polyglot footprint that ADR-01
   §A01.11 explicitly wanted bounded, and multiplies the compose
   service count, the Dockerfiles, and the M5 scrape targets — for
   capability endpoints that each have one inbound HTTP route.

2. **The M8 `massing-gen` name is too narrow.** The BC is becoming the
   home for *architecture-domain* LLM tools. `generate_massing` is one
   capability inside it; the brief-to-massing vertical will grow
   sibling tools (floor-plan extraction, area tabulation, code-check)
   that all belong to the same bounded context. "massing-gen" names a
   single function, not a domain.

3. **The M8 KFI brief test exposed two algorithm/persistence gaps**
   that need a real branching/looping flow and an auditable output
   record. The KFI brief produced "3 rooms · 1 floor · 31,000 m²"
   because `RectangularFirstFitMassingAlgorithm` used 대지면적
   76,272.5 m² directly as the per-floor area — no 건폐율
   (building-coverage ratio) applied, and 지하주차장 (basement parking)
   lumped into the above-ground stack — so `floorCount =
   ceil(total ÷ full-lot-area)` collapsed to 1. The LLM-extracted site
   dimensions were not persisted either, so the bad result could not be
   audited or reproduced. Fixing this needs a non-linear flow
   (re-prompt on missing dims, iterate toward a realistic floor count),
   an algorithm correction, and a persistence change.

The owner has decided the architecture pivot below. This ADR is
**Phase 1 — decision/gate only**; no production code lands in this
ADR's change-set beyond the documentation here. The contract changes
enumerated in §D2 are done as one atomic change-set in the Phase-1
implementation PR (a pure rename, no behavior change), with **no
transition/compat period** — the owner explicitly rejected any
"keep the old name for now."

This ADR amends ADR-01 (catalog: the `agent-tools` host + the
BC-per-service divergence on the Python side), ADR-17 (orchestration
boundary reaffirmed + LangGraph guardrail), and ADR-18 (the
single-service `massing-gen` framing is superseded; BC renamed to
`architecture`; service → `agent-tools`). ADR-00 gains an index row.

## Decision

### D1 — The Python side becomes a multi-BC host service named `agent-tools`

**Decision: the Python/FastAPI runtime is no longer one-service-per-BC.
It is a single deployable host service named `agent-tools` that hosts
multiple LLM-tool bounded contexts as self-contained directory modules
under `backend/fastapi/agent-tools/<bc>/`. This is a deliberate,
documented divergence from ADR-01's "one BC = one deployable service"
invariant — but ONLY on the Python side, and ONLY for small LLM-tool
BCs.**

| Concern | Decision |
|---|---|
| Host logical name | **`agent-tools`** |
| Docker service / container / hostname | **`playground-agent-tools`** (image remains `playground/agent-tools:dev`) |
| Repo layout | `backend/fastapi/agent-tools/` is the host; each BC is a directory module at `backend/fastapi/agent-tools/<bc>/` |
| Port | **18083** (unchanged — reclaims the slot `massing-gen-api` held) |
| Runtime | Python 3.12 + FastAPI + uvicorn (single ASGI app; per-BC routers mounted under their own prefixes) |
| BC self-containment | each `<bc>/` directory owns its **own** DB schema, **own** gateway route prefix, **own** tool descriptor(s) registered in rag-chat's `ToolCatalog`, and **own** prompts/LLM code. BCs do not import each other's domain modules. |
| Shared, BC-neutral code | cross-cutting plumbing (the spark-inference-gateway LLM client, the httpx docs-api client, structlog/metrics wiring, the FastAPI app factory, config loading) lives at the host level (`backend/fastapi/agent-tools/app/` or equivalent), shared by all BCs. |

**The divergence, stated honestly.** ADR-01 §A01.11 + §A01.14 say a
polyglot BC ships as its own single container. `agent-tools` breaks
that for the Python side: multiple BCs share **one process, one
container, one deploy unit, one failure domain, one scaling unit**.
The trade-off:

- **Cost accepted:** a crash, an OOM, a bad deploy, or a CPU-bound
  request in one BC affects every BC in the `agent-tools` process. The
  BCs cannot be scaled independently. A dependency bump for one BC
  (e.g., a LangGraph major) is a bump for all of them.
- **What we get in exchange:** we do not duplicate the Python / LLM /
  graph stack per tiny BC. The polyglot footprint that ADR-01 §A01.11
  wanted bounded stays bounded to **exactly one** Python service —
  the strongest possible reading of A01.11's "polyglot risk is
  bounded." Compose stays one Python service; M5 scrapes one Python
  `/metrics` endpoint; there is one Dockerfile, one `pyproject.toml`,
  one uvicorn entrypoint.

**Why this is acceptable here specifically.** These are *LLM tools* —
small capability endpoints with no independent SLA, called only
through rag-chat's orchestrator (ADR-17), rate-limited upstream by
M4's per-user chat token bucket. They are not user-facing services
with their own traffic profiles; co-locating them in one process is a
proportionate trade for a personal-scale playground. The divergence is
scoped: **Java BCs are unaffected** (they remain one-quadruplet-per-BC
per ADR-01), and **only small LLM-tool BCs** qualify for co-location
in `agent-tools`. A Python BC that grows an independent SLA, a heavy
scaling profile, or a hard isolation requirement graduates to its own
container under a future ADR amendment.

This refines ADR-01 §A01.14's "future polyglot BC pattern": a future
Python LLM-tool BC defaults to a **new directory module inside
`agent-tools`**, not a new container. A non-tool Python BC (or one
needing isolation) still follows §A01.14's single-container pattern.

### D2 — BC rename `massing-gen` → `architecture`; one-shot full rename, no compat period

**Decision: the BC currently named `massing-gen` is renamed to
`architecture`. The capability/tool inside it stays `generate_massing`
("a tool inside the `architecture` BC"). The Python service that hosts
it is renamed from `massing-gen-api` to `agent-tools`. The rename is
executed as ONE atomic change-set with no transition/compat period.**

The capability is unchanged behavior; only names move. The owner
explicitly rejected any "keep the old host for now" dual-routing.

#### Rename change-set — what changes

| # | Identifier / reference | From | To | Where |
|---|---|---|---|---|
| 1 | Compose service name | `massing-gen-api` | `playground-agent-tools` | `infra/docker-compose.yml` |
| 2 | Compose `container_name` | `massing-gen-api` | `playground-agent-tools` | `infra/docker-compose.yml` |
| 3 | Compose `hostname` | `massing-gen-api` | `playground-agent-tools` | `infra/docker-compose.yml` |
| 4 | Compose `image` | `playground/massing-gen-api:dev` | `playground/agent-tools:dev` | `infra/docker-compose.yml` |
| 5 | Compose `build.context` | `../backend/fastapi/massing-gen` | `../backend/fastapi/agent-tools` | `infra/docker-compose.yml` |
| 6 | Compose healthcheck target | `http://localhost:18083/actuator/health` | **NO CHANGE — preserved.** The FastAPI app deliberately exposes `@app.get("/actuator/health")` (main.py) for cross-service uniformity with every Java BC's `/actuator/health`; it is intentional, not a stale Java path. Only the host alias the gateway/rag-chat target moves. | `infra/docker-compose.yml` |
| 7 | Top-level directory | `backend/fastapi/massing-gen/` | `backend/fastapi/agent-tools/architecture/` (BC module) + `backend/fastapi/agent-tools/app/` (host shared) | repo |
| 8 | rag-chat `MassingTool` endpoint host | `http://massing-gen-api:18083/internal/tools/generate-massing` | `http://playground-agent-tools:18083/internal/tools/generate-massing` | `rag-chat-domain/.../tool/MassingTool.java` (`DEFAULT_ENDPOINT`) |
| 9 | rag-chat env override var | `PLAYGROUND_MASSING_GEN_TOOL_URL` | retained name OR renamed `PLAYGROUND_ARCHITECTURE_TOOL_URL` (implementer choice — pin in Phase-1 PR; default URL is the load-bearing change) | `MassingTool.java` |
| 10 | Gateway route `uri` | `http://massing-gen-api:18083` | `http://playground-agent-tools:18083` | `gateway/.../application.yml` (route id `massing-gen-api`) |
| 11 | Gateway route `id` | `massing-gen-api` | `agent-tools` (or `architecture`) | `gateway/.../application.yml` |
| 12 | Python package paths | `app.*` under `massing-gen/` | `architecture.*` (BC) + host `app.*` | `backend/fastapi/agent-tools/` |
| 13 | M5 Prometheus scrape job target | `massing-gen-api:18083` | `playground-agent-tools:18083` | `infra/prometheus/prometheus.yml` (Phase 2 / next M5 pass) |

#### Rename change-set — what is PRESERVED (no change)

| Preserved identifier | Value |
|---|---|
| Port | **18083** |
| DB schema | **`arch`** (and `arch.outputs` table) |
| Gateway route **prefix** | **`/api/arch/**`** (`StripPrefix=2` → `/outputs/{id}`) |
| `arch.outputs` contract | unchanged (columns, BYTEA `file_bytes`, orphan-cleanup policy) |
| Tool name | **`generate_massing`** |
| Tool dispatch path | **`/internal/tools/generate-massing`** |
| Download path | **`/outputs/{id}`** (gateway-exposed at `/api/arch/outputs/{id}`) |
| Tool descriptor parameter schema | unchanged (`briefDocId` + `siteWidth`/`siteDepth`/`floorHeight`) |
| ADR-08 Exception 4 sub-row semantics | rag-chat → the tool BC over HTTP (only the hostname string moves) |
| ADR-08 Exception 5 | the BC → docs-api `/internal/docs/public/{id}/body` brief-body read (host-side `agent-tools` makes the call; semantics unchanged) |
| Korean summary format | `"%d실 · %d층 · 총 %.0f m²"` |
| LLM model / base URL | spark-inference-gateway OpenAI-compatible, model env `PLAYGROUND_MASSING_GEN_LLM_MODEL` (rename to `PLAYGROUND_ARCHITECTURE_LLM_MODEL` is optional Phase-1 cleanup; not load-bearing) |

**Atomicity requirement.** Items 1–12 land in **one** change-set so
the running system is never in a half-renamed state (e.g., the gateway
routing to `agent-tools` while rag-chat's `MassingTool` still names
`massing-gen-api`, or vice-versa). The owner rejected a staged "add
new host, deprecate old host" rollout. Because both BC names are
compose-internal hostnames (not host-exposed, not persisted in any DB
row), a rename has zero data-migration cost — the `arch` schema and
`arch.outputs` rows are name-stable (they key on `arch`, not on the
service name), so the atomic flip is a config/source rename only.
Item 13 (the M5 scrape target) may trail by one M5 maintenance pass —
a stale scrape target degrades only the M5 dashboard's BC-health cell,
not the request path.

### D3 — Orchestration boundary (hard invariant)

**Decision: inter-tool / agent orchestration — deciding which tool to
call, when, and feeding tool results back to the model — REMAINS owned
by rag-chat (Spring AI function-calling, ADR-17). The `agent-tools`
BCs are TOOLS (capability endpoints), not orchestrators.**

**Invariant (do not violate):** an `agent-tools` BC MAY use an internal
graph (LangGraph — see D4) to model its OWN multi-step flow, but it
MUST NOT become a second cross-tool orchestrator. Specifically:

- A BC MUST NOT call another tool BC's `/internal/tools/*` endpoint to
  chain capabilities. Cross-tool composition is rag-chat's job.
- A BC MUST NOT call back into rag-chat to ask the LLM to pick a next
  tool. The model-in-the-loop tool selection lives only in rag-chat.
- A BC's internal LangGraph is bounded to that BC's single capability
  (e.g., `architecture`'s graph turns a brief into a massing — it does
  not orchestrate "now generate slides too").
- The only outbound calls a BC makes are the ADR-08-sanctioned ones:
  the LLM (spark-inference-gateway) and the docs-api body read
  (Exception 5). New cross-BC HTTP edges require a new ADR-08
  exception, same as for Java BCs (ADR-08 §A08.10 discipline).

This keeps exactly one orchestrator in the system (rag-chat) and
prevents the `agent-tools` host from quietly accreting a competing
control plane. Reaffirmed verbatim in the ADR-17 amendment below.

### D4 — LangGraph adoption policy (tool-internal only)

**Decision: LangGraph is adopted for modeling a BC's INTERNAL
multi-step flow — branching, looping, retry, refine — as nodes and
edges, when that flow is genuinely non-linear. LangGraph is NOT for
cross-tool orchestration (that is rag-chat's job per D3).**

| Concern | Decision |
|---|---|
| Scope | tool-internal multi-step flows only (e.g., `architecture`'s extract → validate → re-prompt-on-missing-dims → iterate-floor-count → serialize) |
| Out of scope | cross-tool / cross-BC orchestration (D3 invariant) |
| Where it lives | inside the BC module (`backend/fastapi/agent-tools/architecture/`); the graph nodes call the BC's own application/domain functions |
| Alternative considered | **pydantic-graph** — lighter and native to the Pydantic stack the BCs already use. Rejected by the owner. |
| Rationale for LangGraph over pydantic-graph | graph/node logic management at the level the owner wants, plus visualization and observability (LangGraph's graph introspection / tracing surface). The owner weighs the heavier dependency against the operability win and chooses LangGraph. |

**Guardrail — do not gold-plate.** Do NOT wrap a still-linear pipeline
in LangGraph as an end in itself. The current `architecture` flow
(`MassingWorkflow.run`, an 8-step straight line) is linear; modeling it
as a graph is justified ONLY as the Phase-2 reference/de-risk pattern
(below), and the migrated graph must be behavior-identical. A graph is
warranted when the flow actually branches or loops (Phase 3). A linear
sequence dressed as a one-path graph adds dependency weight and reading
overhead for no control-flow benefit.

### D5 — Roadmap (phases; only Phase 1 is being executed now)

This ADR records three phases. **Only Phase 1 — this ADR — is being
executed now.** Phases 2 and 3 are accepted *direction*; their full
implementation detail (DDL, contract shapes) lands in later ADR
amendments when each phase is implemented. Do not over-specify Phase-3
DDL here.

**Phase 1 — decision/gate (this ADR).**
- Formalize D1–D4 as above.
- Execute the §D2 rename change-set atomically (pure rename, no
  behavior change). After Phase 1 the BC is `architecture`, the host
  is `agent-tools`, and the system behaves exactly as it did under
  `massing-gen` (same KFI-brief bug still present — fixing it is
  Phase 3).

**Phase 2 — install LangGraph + define the implementation/observability
pattern.**
- Add LangGraph to the `agent-tools` `pyproject.toml`.
- Migrate the `architecture` BC's CURRENT (linear) flow to a minimal
  LangGraph graph as a **de-risk + reference pattern**: prove the
  dependency, the node/edge idiom, and the observability/visualization
  surface against a known-good flow.
- **Behavior-identical; gold-plating forbidden** (D4 guardrail). The
  graph must produce byte-identical outputs to the current
  `MassingWorkflow.run` for the same inputs. No new branching is added
  in Phase 2 — that is Phase 3's job.
- Wire LangGraph's tracing/visualization into the host observability
  (structlog + the `/metrics` surface) so Phase-3's real graph is
  observable from day one.

**Phase 3 — enhancement (real graph + algorithm fix + persistence).**
Accepted DIRECTION; detailed contract/DDL deferred to a Phase-3 ADR
amendment.
- **Real branching/looping graph:** site re-prompt on missing
  dimensions (the current `MASSING_ALGORITHM_FAILED` on missing site
  dims becomes a re-prompt loop), and iterate toward a realistic floor
  count.
- **Algorithm fix:** apply 건폐율 (building-coverage ratio) so floor
  count is no longer `ceil(total ÷ full-lot-area)`; separate
  basement uses (e.g., 지하주차장) from the above-ground floor stack.
  This directly fixes the M8 KFI-brief failure ("3 rooms · 1 floor ·
  31,000 m²" — caused by using 대지면적 76,272.5 m² as the per-floor
  area with no coverage ratio and basement parking lumped in).
- **Persistence changes (direction):**
  - *Option A — store the `.3dm` in MinIO.* The MinIO sidecar
    (`playground-minio`) is already on the same `playground-net`;
    mirror docs-api's MinIO pattern (ADR-12 §A12.4 / ADR-08 §A08.3).
    Keep an **object key** in `arch.outputs` instead of inline BYTEA.
    (This is the Phase-3 realization of the BYTEA→MinIO migration path
    ADR-18 §12 already documented as a future hook.)
  - *Also persist the LLM-extracted inputs* — site width / depth,
    floor_height, coverage assumption, and the box geometry — so
    outputs are **auditable and reproducible**. The M8 KFI bug was
    un-auditable precisely because the extracted site dims were not
    stored; Phase 3 closes that gap.
  - Full `arch.outputs` DDL changes (new columns / object-key column /
    extracted-input columns) are specified in the Phase-3 ADR
    amendment, NOT here.

## Consequences

- **Positive:** the Python/LLM/graph stack is built and maintained
  once, in one `agent-tools` host, instead of once per tiny tool BC.
  The polyglot footprint stays bounded to a single Python service —
  the strongest reading of ADR-01 §A01.11.
- **Positive:** the `architecture` name correctly scopes a domain that
  will hold sibling tools beyond `generate_massing`; new architecture
  tools land as routers/descriptors inside the existing host, not as
  new containers.
- **Positive:** the orchestration boundary is now an explicit hard
  invariant (D3), so the `agent-tools` host cannot quietly grow a
  competing control plane against rag-chat.
- **Positive:** the rename is data-migration-free (the `arch` schema
  and rows key on the schema name, not the service name) and the
  preserved `/api/arch/**` prefix + `arch.outputs` contract mean zero
  frontend or DB impact.
- **Negative / trade-off:** co-locating BCs in one process means a
  shared failure / deploy / scaling domain. One BC's crash, OOM, or
  CPU-bound request degrades all BCs in `agent-tools`; they cannot be
  scaled independently; a dependency bump for one is a bump for all.
  Accepted for small LLM tools at personal scale; a BC that outgrows
  this graduates to its own container under a future ADR.
- **Negative / trade-off:** the one-shot rename with no compat period
  means the Phase-1 PR must land items 1–12 atomically — a partial
  merge leaves the gateway and rag-chat pointing at different
  hostnames. Mitigated by the small, mechanical, name-only nature of
  the change-set.
- **Negative / trade-off:** LangGraph is a heavier dependency than the
  rejected pydantic-graph. Accepted for the graph-management +
  visibility/observability win; the D4 guardrail prevents paying that
  cost for still-linear flows.
- **Negative / trade-off:** the divergence from ADR-01's
  one-BC-one-service rule is now a standing exception that future
  contributors must understand. Mitigated by scoping it tightly
  (Python LLM tools only) and documenting it in the ADR-01 amendment.

## Diagrams

```
                         rag-chat (Java, ADR-17)
                    ┌──────────────────────────────┐
                    │  ORCHESTRATOR (the only one)  │
                    │  Spring AI function-calling   │
                    │  ToolCatalog + ToolDispatcher │
                    └───────────────┬───────────────┘
                                    │ HTTP (ADR-08 Exception 4)
                                    │ POST /internal/tools/generate-massing
                                    │ X-User-Id / X-User-Sub forwarded
                                    v
        ┌───────────────────────────────────────────────────────────┐
        │  agent-tools  (Python/FastAPI host, port 18083)            │
        │  ── single process / container / deploy / failure domain ──│
        │                                                            │
        │   ┌─────────────────────────────────────────────────┐    │
        │   │  architecture BC   (backend/fastapi/agent-tools/  │    │
        │   │                     architecture/)                │    │
        │   │   tool: generate_massing                          │    │
        │   │   schema: arch  ·  route prefix: /api/arch/**     │    │
        │   │   internal LangGraph (D4): extract → validate →   │    │
        │   │     re-prompt missing dims → iterate floors →     │    │
        │   │     serialize   (NOT a cross-tool orchestrator,D3)│    │
        │   └───────────────┬───────────────────┬───────────────┘    │
        │   (future sibling architecture tools land here as new BCs) │
        └───────────────────┼───────────────────┼───────────────────┘
                            │                   │
              ADR-08 Exc.5  │                   │  LLM (spark-inference-gateway,
        GET /internal/docs/ │                   │  OpenAI-compatible, Qwen3 family)
        public/{id}/body    v                   v
                       docs-api (Java)    host.docker.internal:10080 / spark-inference-net
```

A FigJam version of this context map is **not generated** for Phase 1.
If produced later (optional), link it from `docs/adr/00-overview.md`
under "Diagrams".

See `docs/adr/01-msa-gradle-structure.md` §A01.15 (agent-tools host +
BC-per-service divergence), `docs/adr/17-m7-rag-chat-tool-calling.md`
§A17.1 (orchestration boundary + LangGraph guardrail), and
`docs/adr/18-m8-massing-gen.md` §A18.10 (massing-gen → architecture
supersession) for the corresponding amendments.

## Amendment 2026-06-04 (Phase 2a) — `app/` host framing refined to `shared_kernel/` + BC modules

§D1 described the host-shared plumbing as living at `agent-tools/app/`.
Phase 2a (implemented; behavior-identical, no LangGraph) refines this to
mirror the Java `shared-kernel` module pattern (ADR-01/02) instead:

- **`shared_kernel/`** (BC-neutral, reusable by future BCs): `errors`
  (the `MassingError` + `MassingErrorCode` "tool error" vocabulary, moved
  **wholesale** — base/BC-specific split deferred until a 2nd BC needs it),
  `llm_client` (spark-gateway), `docs_client` (docs-api), `database`
  (engine/session), `config` (single `Settings` — kept unified; the
  host/BC config split is deferred), `context` (`UserContext` + X-User-Id
  header deps), and `models` (only `DocsDetailSubset`, the docs-client wire
  type — kept here so `shared_kernel` imports nothing from a BC).
- **`architecture/`** (BC domain): `models`, `algorithm`, `brief_extractor`,
  `serializer`, `slug`, `summary`, `content_disposition`, `workflow`, the
  BC-wiring `deps` (`get_workflow`/`get_docs_client`), `routers/`, `schema.sql`.
- **`main.py`** (top-level entrypoint, `uvicorn main:app`): composes the
  shared_kernel app factory + mounts the `architecture` BC.

**Hard invariant:** `shared_kernel` MUST NOT import from any BC package.
**Deferred to Phase 2b / later:** env-var renames (`PLAYGROUND_MASSING_GEN_*`
unchanged), the `config` host/BC split, and the errors base/BC-specific split.
Verified: 39 unit tests pass under the new imports; container boots healthy
on `uvicorn main:app`; direct `generate_massing` call serves 200
(cross-package runtime path intact).

## Amendment 2026-06-04 (Phase 2c) — architecture BC internal layering + graph/chains/nodes + langchain-openai

Phase 2c gives the `architecture` BC the **api / app / domain / infra**
layering (the DDD layering of ADR-02), as Python **sub-packages** (not
separate deploy modules), and structures the LangGraph orchestration into
`graph` / `chains` / `nodes` under `app`. This is a *deliberate refinement*
of ADR-18 §A18.1's "no four-module quadruplet": A18.1 ruled out separate
**Gradle/deploy modules** for a polyglot BC (still true — `architecture` is
one directory in one container); it did NOT preclude DDD **directory
layering inside** the BC. The owner chose to adopt the layering + the
graph/chain/node vocabulary from the start, accepting some structure ahead
of need to avoid later churn/confusion.

### §A19.7. Layer layout (per BC, under `agent-tools/<bc>/`)

```
architecture/
├── api/      routers (tools, outputs), request/response wire DTOs,
│             content_disposition (HTTP header), FastAPI deps
├── app/      use-case orchestration (see §A19.8)
├── domain/   algorithm (compute_massing), domain models (Room, RoomBox,
│             SiteFootprint, ExtractedProgram), slug, summary — framework-free
└── infra/    serializer (rhino3dm adapter), ArchOutput (SQLAlchemy) +
              persistence, schema.sql
```
`shared_kernel/` (ADR-19 §D1 / Phase-2a amendment) stays a peer package;
infra/app consume its db / clients / config / context / errors.

### §A19.8. `app/` — orchestrator + graph / chains / nodes

```
app/
├── workflow.py     orchestrator StateGraph: composes nodes + subgraphs
├── nodes/          single-step units (fetch_brief, extract, serialize, persist)
├── graphs/         subgraphs — sub-flows that have (or will have) their own
│                   branching/loops; composed as nodes in workflow.py
└── chains/         LCEL chains — single LLM-interaction units (prompt | model | parser)
```

**Pattern policy (which tool for what):**
- **node** — one step, no branching. (`fetch_brief`, `serialize`, `persist`,
  and the `extract` node that invokes the brief-extraction chain.)
- **chain** (`chains/`, LCEL `Runnable`) — one LLM interaction:
  `ChatPromptTemplate | model | with_structured_output(...)`.
- **subgraph** (`graphs/`, compiled `StateGraph` used as a node) — a sub-flow
  with its **own** branching/loops. Do NOT make a single-step thing a subgraph.
- **orchestrator** (`workflow.py`) — top-level control flow only; composes the
  above. The ADR-17/§D3 boundary still holds: graphs/subgraphs are
  **tool-internal**; they never orchestrate across tool BCs or call back into
  rag-chat.

### §A19.9. Current decomposition (Phase 2c — behavior preserved at the result level)

| Step | Pattern | Home |
|---|---|---|
| brief fetch + extracted/body check | node | `app/nodes/` (uses `shared_kernel.docs_client`) |
| brief → room program (LLM) | **chain** | `app/chains/brief_extraction.py` |
| resolve site + compute massing | **subgraph** | `app/graphs/massing.py` (linear now; Phase-3 건폐율/floor-converge loop lands here) |
| rhino3dm `.3dm` | node | `app/nodes/` (→ `infra/serializer`) |
| persist + response | node | `app/nodes/` (→ `infra` ArchOutput + `shared_kernel.database`) |

### §A19.10. langchain-openai adoption

`shared_kernel` exposes a `ChatOpenAI` factory pointed at the
spark-inference-gateway (OpenAI-compatible; `base_url`, `api_key`, model
`qwen3-vl-30b-a3b`). Chains compose `prompt | model | with_structured_output`.
This **retires the hand-rolled httpx `llm_client` for the extraction path**.

**Not behavior-identical in the LLM path** (the call mechanism changes from
`response_format: json_object` + in-prompt schema to `with_structured_output`).
The result type (`ExtractedProgram`) is unchanged. Because the spark gateway
already proved OpenAI-compatible **function-calling** in M7 (rag-chat), either
`method="function_calling"` or `method="json_mode"` is viable — the
implementer pins whichever reproduces the M8 extraction, and the change is
**gated on a real-gateway E2E** showing extraction parity before merge.

### §A19.11. Deferred
Hexagonal **ports** (`DocsPort` / `LlmPort` / `MassingRepositoryPort` in `app`,
implemented in `infra`/`shared_kernel`) are deferred to Phase 3, when the
re-prompt + converge loops make the indirection pay off. Env-var renames and
the `config` host/BC split remain deferred (Phase-2a note).

## Amendment 2026-06-04 (ADR-20) — architecture BC is a stateless generator; download moves to rag-chat

ADR-20 retires the `/api/arch/**` download route + `arch.outputs` BYTEA store
that ADR-19 §D2 preserved. The `architecture` BC now returns the `.3dm` as a
tool-result `artifact` (ADR-20 §D2); rag-chat stores it in MinIO + serves the
download as a message attachment (`GET /api/rag/chat/attachments/{id}`). The
gateway `/api/arch/**` route is removed. The `infra` layer's persistence
(ArchOutput) is dropped; serialization (rhino3dm) stays — it just feeds the
returned `artifact` instead of a DB row. Port 18083, `/internal/tools/
generate-massing`, and the 7-node interpretation pipeline are unchanged.
