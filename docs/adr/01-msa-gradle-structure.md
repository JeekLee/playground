# ADR-01: Gradle Multi-Module Monorepo, JDK, Module Layout, Ports

## Status
Accepted — **v2 supersedes ADR-01 v1** (original 7-module map). The high-level
posture (monorepo, JDK 21, one JVM per BC, port pins) is unchanged; the BC unit
of build expands from one Gradle module to a **four-module quadruplet** per BC.
Toolchain version pins stay on the **Spring Boot 3.3.x / Spring AI 1.0.0 GA**
line that ADR-04 and ADR-07/ADR-10 already commit to — VIA's newer 3.5.6 /
1.0.0-M6 set was considered but rejected in favor of the GA-stable line.

## Context
ADR-01 v1 shipped a 7-module map (gateway, shared-kernel, identity, docs,
rag-ingestion, rag-chat, metrics) with DDD layering enforced inside each
module's packages. Two pressures motivated v2:

1. **Layering enforcement was advisory (ArchUnit "recommended")** — the
   `code-reviewer` agent had to police it manually. A deep read of the VIA
   reference repo (`/home/jeek_lee/work/personal/VIA/via-backend/`) showed a
   four-module quadruplet per BC that pushes layering down to the Gradle
   dependency graph: a `-domain` module cannot accidentally `import org.springframework.*`
   because its `build.gradle.kts` doesn't depend on Spring.
2. **Version pins consolidated, not bumped.** VIA validated Spring Boot 3.5.6 +
   Spring Cloud 2024.0.2 + Spring AI 1.0.0-M6 on JDK 21. After review we keep
   ADR-01 v1's **3.3.x line** because Spring AI ships **1.0.0 GA** against
   Spring Boot 3.3 (per ADR-04) — the GA-stable pair is preferred over VIA's
   newer-but-milestone combination. ADR-01 v1's `1.0.0-M6` reference (carried
   over from an earlier draft) is corrected to `1.0.0 GA` here.

Alternatives considered and rejected:
- **Keep one Gradle module per BC + ArchUnit** — rejected: ArchUnit catches
  violations at test time, classpath-level enforcement catches them at compile
  time and is impossible to bypass.
- **VIA-style monolithic `api-server` packaging** (single JVM aggregating every
  BC's controllers) — rejected: contradicts ADR-08's "one JVM per BC, Kafka for
  cross-BC". We adopt VIA's *module shape* but not its deployment shape.
- **VIA's `setting:*` Gradle subprojects** (empty modules whose only purpose is
  to aggregate dependencies) — rejected: `buildSrc` convention plugins
  (Kotlin DSL) achieve the same dependency-aggregation effect without adding
  empty modules to the project tree.

## Decision

### Build system
- **Gradle 8.10.x** (8.x line, Kotlin DSL for all `build.gradle.kts` and
  `settings.gradle.kts`).
- **JDK 21 LTS** (Eclipse Temurin) — `sourceCompatibility = JavaVersion.VERSION_21`,
  `targetCompatibility = JavaVersion.VERSION_21`.
- **Spring Boot 3.3.x** (latest patch on the 3.3 line — exact patch pinned in
  the root version catalog; ADR-07 and ADR-10 already pin against this line).
- **Spring Cloud 2023.0.x** (BOM compatible with Spring Boot 3.3 — exact patch
  in the version catalog; pinned at `2023.0.4` during M0 bootstrap). Spring
  Cloud 2024.0.x requires Spring Boot 3.4.x and is therefore incompatible with
  our 3.3 line — confirmed by the gateway's `CompatibilityVerifier` failing
  during the M0 build.
- **Spring AI 1.0.0 (GA)** — coordinates `org.springframework.ai:spring-ai-bom:1.0.0`
  per ADR-04.

Root version catalog lives in `gradle/libs.versions.toml`. All modules pin
through it; no hard-coded versions in module `build.gradle.kts`.

### Module list — quadruplet per BC

Five BCs (identity, docs, rag-ingestion, rag-chat, metrics) × four modules each
+ gateway + shared-kernel = **22 production modules** + `buildSrc`.

| Module | Type | bootJar? | Added in |
|---|---|---|---|
| `gateway` | runnable Spring Boot app | yes | M0 |
| `shared-kernel` | Java library | no | M0 |
| `identity-api` | runnable Spring Boot app | **yes** | M1 |
| `identity-app` | Java library | no | M1 |
| `identity-domain` | Java library | no | M1 |
| `identity-infra` | Java library | no | M1 |
| `docs-api` | runnable Spring Boot app | yes | M2 |
| `docs-app` | Java library | no | M2 |
| `docs-domain` | Java library | no | M2 |
| `docs-infra` | Java library | no | M2 |
| `rag-ingestion-api` | runnable Spring Boot app | yes | M3 |
| `rag-ingestion-app` | Java library | no | M3 |
| `rag-ingestion-domain` | Java library | no | M3 |
| `rag-ingestion-infra` | Java library | no | M3 |
| `rag-chat-api` | runnable Spring Boot app | yes | M4 |
| `rag-chat-app` | Java library | no | M4 |
| `rag-chat-domain` | Java library | no | M4 |
| `rag-chat-infra` | Java library | no | M4 |
| `metrics-api` | runnable Spring Boot app | yes | M5 |
| `metrics-app` | Java library | no | M5 |
| `metrics-domain` | Java library | no | M5 |
| `metrics-infra` | Java library | no | M5 |
| `buildSrc` | Gradle composite build (convention plugins) | n/a | M0 |

Path convention: `backend/<bc>/<bc>-<layer>/` (e.g., `backend/identity/identity-api/`,
`backend/identity/identity-app/`). Each BC directory groups its four submodules.
`gateway` and `shared-kernel` sit directly under `backend/` (`backend/gateway/`,
`backend/shared-kernel/`). The `backend/` directory itself is the Gradle root —
`settings.gradle.kts`, `build.gradle.kts`, `buildSrc/`, `gradle/`, `gradlew` all
live there. Root `backend/settings.gradle.kts` aggregates all 22 modules with
explicit `include(":identity:identity-api", ":identity:identity-app", …)` calls.
The repo-level monorepo root contains only `backend/`, `frontend/` (Next.js per
ADR-06), `infra/` (compose), and `docs/`.

### Per-BC dependency wiring (the quadruplet contract)

```
identity-api    --implementation--> identity-app
identity-api    --runtimeOnly------> identity-infra      // adapters bind by classpath
identity-app    --implementation--> identity-domain
identity-infra  --implementation--> identity-domain      // for return types only
identity-infra  --implementation--> identity-app         // implements -app's port interfaces
```

Rules baked into the convention plugins (see below):
- `*-api` has **no compile-time dependency** on `*-infra`. `*-infra` adapters
  are picked up via `runtimeOnly` so `*-api`'s controllers cannot import
  infrastructure types.
- `*-app` depends on `*-domain` only. It MUST NOT depend on `*-infra` (the
  port-interface inversion of dependency makes this possible).
- `*-domain` depends on nothing except `shared-kernel` and JDK.
- `*-infra` may depend on `*-app` (to implement its port interfaces) and
  `*-domain` (for the domain return types its adapters produce).
- Every module depends on `shared-kernel` if it needs the event envelope, the
  exception hierarchy (ADR-11), or shared value objects.

This dependency direction is **enforced by the Gradle build graph itself** — no
ArchUnit needed. ADR-02 spells out the per-layer responsibilities.

### `buildSrc` convention plugins (Kotlin DSL)

`buildSrc/src/main/kotlin/` ships six convention plugins. Each module applies
exactly one of `playground.bc-domain`, `playground.bc-app`, `playground.bc-api`,
`playground.bc-infra`, or `playground.spring-boot-app`. All of them apply
`playground.java-conventions` transitively.

| Plugin | Applies to | What it does |
|---|---|---|
| `playground.java-conventions` | every module | JDK 21 toolchain, UTF-8, JUnit 5, common compile flags, Lombok config (annotation processor + compileOnly), version catalog access. |
| `playground.spring-boot-app` | `gateway`, every `*-api` | Adds `org.springframework.boot` + `io.spring.dependency-management`, configures `bootJar`, registers the BC's `main-class` convention, applies Spring Cloud + Spring AI BOMs. |
| `playground.bc-domain` | every `*-domain` | `java-library` only. **Forbids** dependencies on `spring-context`, `spring-boot`, `spring-web*`, `jakarta.persistence`, `spring-kafka`, `spring-data-*` via a `configurations.all { exclude(...) }` block + a `gradle.taskGraph` check. Allows Lombok and `shared-kernel`. |
| `playground.bc-app` | every `*-app` | `java-library`. Adds Spring Context (for `@Service`, `@Transactional`) but **not** Spring Web, JPA, or Kafka. Depends on its sibling `*-domain`. |
| `playground.bc-api` | every `*-api` | Applies `playground.spring-boot-app`. Adds Spring Web + Springdoc OpenAPI + Spring Security (via BOM). Depends on its sibling `*-app` (`implementation`) and `*-infra` (`runtimeOnly`). Registers the BC's `GroupedOpenApi` bean stub. |
| `playground.bc-infra` | every `*-infra` | `java-library`. Adds JPA, Flyway, Spring Kafka, WebClient/Feign, Spring Data Redis (when the BC needs locks per ADR-08). Depends on `*-app` (`implementation`) and `*-domain` (`implementation`). |

VIA's `setting:*` empty dep-aggregator subprojects are **explicitly rejected** —
the convention plugins above absorb that role without adding modules to the
project tree.

### Java package root
Group: `dev.jeeklee.playground`. Each BC roots at `dev.jeeklee.playground.<bc>`
(e.g., `dev.jeeklee.playground.identity`). Inside a BC, the four modules share
that package root and split by layer subpackage (per ADR-02):
- `identity-domain` → `dev.jeeklee.playground.identity.domain.*`
- `identity-app` → `dev.jeeklee.playground.identity.application.*`
- `identity-api` → `dev.jeeklee.playground.identity.api.*`
- `identity-infra` → `dev.jeeklee.playground.identity.infrastructure.*`

`shared-kernel` roots at `dev.jeeklee.playground.shared`.

### Port assignments (unchanged from v1)

Only `*-api` modules run a JVM — they are the only ones with a `bootRun`.

| Module | Port | Host-exposed? |
|---|---|---|
| `gateway` | **18080** | yes (single ingress) |
| `identity-api` | **18081** | no (compose-internal) |
| `docs-api` | **18082** | no |
| `rag-ingestion-api` | **18083** | no |
| `rag-chat-api` | **18084** | no |
| (reserved) | 18085 | reserved for next BC |
| `metrics-api` | **18086** | no |

`*-app`, `*-domain`, `*-infra` are libraries — they have no port and no
`bootRun`. The compose service for each BC builds and runs the `*-api` image,
which transitively packages its sibling modules into a single fat jar.

### bootRun behavior
`./gradlew :identity-api:bootRun` produces one JVM bound to port 18081 with the
identity quadruplet's classpath. For local non-Docker development, all six JVMs
(gateway + five `*-api`s) can be launched in parallel without port collision.

### Per-BC test layout
Each layer module owns its own tests:
- `*-domain` → pure JUnit, no Spring context.
- `*-app` → Spring Boot slice tests (`@SpringBootTest` with a minimal config
  importing only `*-app`) and unit tests with the domain mocked.
- `*-api` → MockMvc / WebTestClient slice tests against the controllers.
- `*-infra` → Testcontainers for the real JPA / Kafka adapters (ADR-05).

## Consequences
- Positive: layering enforcement moves from advisory (ArchUnit) to mandatory
  (classpath). A `*-domain` module that tries to import `@Service` fails to
  compile because Spring isn't on its classpath.
- Positive: each BC's quadruplet maps cleanly to the VIA pattern, so backend-
  implementer agents can borrow VIA's solutions for OpenAPI structure, mappers,
  and adapters without translation overhead.
- Positive: `buildSrc` convention plugins keep individual module
  `build.gradle.kts` files near-empty (one `plugins { id("playground.bc-domain") }`
  block) — adding a new BC is mostly directory creation + `settings.gradle.kts`
  inclusion.
- Negative: 22 modules vs 7 — IDE indexing cost rises, Gradle configuration time
  rises. Mitigated by configuration cache + build cache (enabled in
  `gradle.properties`).
- Negative: a Spring Boot patch bump touches every `*-api` module via the BOM;
  coordinated upgrade across BCs is required (same constraint as v1, just more
  modules to recompile).
- Negative: cross-module refactors (e.g., moving a class from `-app` to
  `-domain`) now touch two `build.gradle.kts` files plus the move. Acceptable
  cost for the layering guarantee.

## Amendment 2026-05-22 (ADR-12 amendment M6.1) — BC count -1, module count -4, port 18083 freed

The M6.1 master amendment in **ADR-12 (2026-05-22)** dissolves the
`rag-ingestion` BC into `docs` (see ADR-12 §A12.1 for the rationale).
The Gradle module map shrinks accordingly:

### A01.1. Module list — `rag-ingestion-*` quadruplet removed

The `rag-ingestion` BC's four-module quadruplet is **deleted**:

- ~~`rag-ingestion-api`~~ — retired runnable Spring Boot app; the deployment is removed from compose.
- ~~`rag-ingestion-app`~~ — retired Java library; `IngestionService`, `EmbeddingPort`, `ChunkRepositoryPort`, `DistributedLockPort`, `BodyFetchPort` move to `docs-app`.
- ~~`rag-ingestion-domain`~~ — retired Java library; `MarkdownAwareChunker`, `Chunk` VO, `ChunkingPolicy` constants move to `docs-domain`.
- ~~`rag-ingestion-infra`~~ — retired Java library; `BgeM3EmbeddingAdapter`, `PgvectorChunkRepositoryAdapter`, `RedissonDistributedLockAdapter`, Kafka listener containers, DLQ recoverer move to `docs-infra`.

The directories `backend/rag-ingestion/rag-ingestion-{domain,app,infra,api}/`
are `git rm`'d. Root `backend/settings.gradle.kts` removes the four
`include(":rag-ingestion:rag-ingestion-*")` lines.

Java package root: `dev.jeeklee.playground.ragingestion.*` is **deleted**
in M6.1; the moved classes get their package roots rewritten to
`dev.jeeklee.playground.docs.*` (matching the corresponding layer
subpackage per ADR-02 — `domain.chunking.MarkdownAwareChunker`,
`application.ingestion.IngestionService`, etc.).

### A01.2. Updated module count + ADR-00 graph

Five BCs (identity, docs, rag-chat, metrics) × four modules each + gateway +
shared-kernel = **18 production modules** (down from 22 at the pre-M6.1
post-ADR-15 count of 26 with metrics, or 22 at the pre-ADR-15 count).
Counting the post-ADR-15 baseline of 26: **26 - 4 = 22 production modules
+ `buildSrc`** after M6.1.

ADR-00's ASCII module dependency graph is redrawn in lockstep — the
`rag-ingestion-api` lane is removed; the `docs.document.uploaded` arrow
that used to point at `rag-ingestion-api` is rerouted as an in-BC
loop on `docs-api`.

### A01.3. Port table — 18083 freed

Port 18083 returns to the **reservation pool**. The updated table:

| Module | Port | Host-exposed? |
|---|---|---|
| `gateway` | **18080** | yes (single ingress) |
| `identity-api` | **18081** | no (compose-internal) |
| `docs-api` | **18082** | no |
| ~~`rag-ingestion-api`~~ | ~~**18083**~~ | **Retired by this amendment** — port returned to reservation pool |
| `rag-chat-api` | **18084** | no |
| (reserved) | 18083 | freed by M6.1; reserved for next BC |
| (reserved) | 18085 | reserved for next BC (per ADR-01 v2's original reservation) |
| `metrics-api` | **18086** | no |

The next BC slot to claim either 18083 or 18085 picks whichever fits its
naming intent; both are equivalent for any new runnable.

### A01.4. Convention plugins — no change

The six convention plugins (`playground.{java-conventions,spring-boot-app,bc-{domain,app,api,infra}}`)
are unchanged by M6.1. The dissolved rag-ingestion modules used the
same plugins as the surviving docs modules; the layering rules carry
over verbatim through the package-and-build-file `git mv`.

### A01.5. Consequences (M6.1-specific)

- **Positive:** four fewer modules in the project tree; IDE indexing cost drops; Gradle configuration time drops; the "where does X live" mental model simplifies.
- **Positive:** the BC count drops from 6 to 5; the BC list aligns more cleanly with the user-facing surface (identity, docs, rag-chat, metrics — chat is built on top of docs's chunks read model, not a sibling BC).
- **Negative:** the `docs` BC's surface area grows substantially — every responsibility the rag-ingestion BC held now sits inside docs-api's JVM, behind a single port and a single Tomcat thread pool. Operational reasoning ("if docs-api OOMs, what breaks") expands.
- **Negative:** the four-module-per-BC pattern that ADR-01 v2 framed as "consistent across every BC" is broken in **size**: docs now ships heavier modules than identity / rag-chat / metrics. The *shape* (four modules per BC, layering enforced by classpath) is preserved.

See `docs/adr/12-m2-docs.md` amendment 2026-05-22 §A12.1 + §A12.5 for
the full M6.1 specification.

## Amendment 2026-05-22 (ADR-18, M8) — `massing-gen` quadruplet + port 18083 claimed

The M8 PR set (ADR-18 — `docs/adr/18-m8-massing-gen.md`) introduces
the `massing-gen` BC — the first concrete `ToolCatalog` consumer and
the first new BC since M5. The Gradle module map and port table both
update.

### A01.6. Module list — `massing-gen-*` quadruplet added

The `massing-gen` BC's four-module quadruplet is **added**:

| Module | Type | bootJar? | Added in |
|---|---|---|---|
| `massing-gen-api` | runnable Spring Boot app | **yes** | **M8** |
| `massing-gen-app` | Java library | no | **M8** |
| `massing-gen-domain` | Java library | no | **M8** |
| `massing-gen-infra` | Java library | no | **M8** |

The directories
`backend/massing-gen/massing-gen-{api,app,domain,infra}/` are
created. Root `backend/settings.gradle.kts` gains four new
`include(":massing-gen:massing-gen-*")` lines.

Java package root: **`dev.jeeklee.playground.massinggen.*`**
(joined-word convention matching `ragchat` for `rag-chat` and
historical `ragingestion` for `rag-ingestion`; see ADR-18 §14).
Per-layer subpackages mirror ADR-02 — `massing-gen-domain` →
`dev.jeeklee.playground.massinggen.domain.*`,
`massing-gen-app` → `...application.*`,
`massing-gen-api` → `...api.*`,
`massing-gen-infra` → `...infrastructure.*`.

### A01.7. Port table — 18083 claimed by `massing-gen-api`

Port 18083 (freed by the M6.1 amendment §A01.3 when `rag-ingestion-api`
was retired) is **claimed by `massing-gen-api`**:

| Module | Port | Host-exposed? |
|---|---|---|
| `gateway` | **18080** | yes (single ingress) |
| `identity-api` | **18081** | no (compose-internal) |
| `docs-api` | **18082** | no |
| **`massing-gen-api`** | **18083** | **no** (compose-internal; gateway forwards `/api/arch/**` but not `/internal/**`) |
| `rag-chat-api` | **18084** | no |
| (reserved) | 18085 | freed by M6.1; remains reserved for next BC |
| `metrics-api` | **18086** | no |

**Stale-value note:** the M8 spec
(`docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §6), the M8
PRD (`docs/prd/M8-massing-gen.md` §"Bounded Context"), and
`docs/roadmap.md` §M8 all carried **`18086`** as the working
candidate for `massing-gen-api`. That working value is **stale**:
`18086` was claimed by `metrics-api` in M5 (per §A01.3) before the
M8 spec was authored. ADR-18 §2 rejects 18086 explicitly and
pins **18083**. This amendment ratifies the port table. (PRD §Story 1
note "open question Q-A" is closed by ADR-18 §2 +
this amendment.)

### A01.8. Updated module count

Six BCs (identity, docs, rag-chat, metrics, **massing-gen**) × four
modules each + gateway + shared-kernel = **26 production modules** +
`buildSrc`. Up from 22 (post-M6.1 baseline per A01.2).

ADR-00's ASCII module dependency graph is redrawn in lockstep — a new
`massing-gen-api` lane is added on the gateway downstream;
`rag-chat-api → massing-gen-api` is drawn (Exception 4 sub-row per
ADR-08 §A08.11); `massing-gen-api → docs-api` is drawn (Exception 5
per ADR-08 §A08.12); `massing-gen-api → rhino3dm-bridge` (new
external sidecar) is drawn.

### A01.9. Convention plugins — no change

The six convention plugins
(`playground.{java-conventions,spring-boot-app,bc-{domain,app,api,infra}}`)
are unchanged by M8. The `massing-gen-{api,app,domain,infra}` modules
apply the same `playground.bc-*` plugins as every other BC; the
layering rules and Spring-free invariants apply verbatim.

### A01.10. Consequences (M8-specific)

- **Positive:** the reservation pool shrinks from 2 free slots
  (18083 + 18085) to 1 (18085). The pool was intentionally
  over-provisioned by M6.1; M8 absorbs the slack.
- **Positive:** the new BC is the first concrete consumer of M7's
  generic tool-calling infra — proving the 4-module quadruplet
  pattern scales to add a new BC with one new schema + one new
  sidecar + two new ADR-08 amendments (sub-row + fresh exception)
  + one descriptor registration.
- **Negative:** module count climb (22 → 26) is the expected
  fresh-BC cost. IDE indexing rises proportionally; Gradle
  configuration cache + build cache absorb it.
- **Negative:** the spec/PRD/roadmap-level port mistake (18086 →
  18083) means three docs need text amendments to reference the
  correct port (the roadmap §M8 row is updated as part of this PR
  set; the spec §6 is left as-is — historical record of the working
  value at spec-authoring time; the PRD §Story 1 already cataloged
  the issue as open question Q-A and references ADR-18 for the
  closure).

See `docs/adr/18-m8-massing-gen.md` §2 + §17 for the full M8
specification.

## Amendment 2026-05-22 (M8 Python flip — polyglot policy)

> This amendment is appended to ADR-01 as a new amendment block
> following the M8 block (§A01.6–§A01.10) above. The M8 BC's
> implementation language is flipped from Java/Spring Boot to
> Python/FastAPI (per ADR-18 §A18.1). This amendment formalizes a
> polyglot policy that ADR-01 did not previously articulate, reverts
> the Java module count change introduced in §A01.6–§A01.10, and
> preserves the port 18083 reservation under the new language. The
> §A01.6–§A01.10 block above is **not rewritten** — its Java-quadruplet
> framing remains as the audit record of the design intent at the
> time M8 was first scoped.

### §A01.11. Polyglot policy — Java BCs are primary; Python BCs are permissible by exception

**Decision: Java/Spring Boot remains the primary backend stack
across the playground monorepo. Python BCs are permissible when (a)
the BC has a hard Python-only dependency that the JVM cannot match
in-process (rhino3dm.py for M8), AND (b) the BC's domain logic is
better expressed in Python than in Java, AND (c) the polyglot choice
is documented in an explicit per-milestone ADR amendment that lists
the trade-offs.**

M8 (`massing-gen`) is the **first such Python BC**. Future BCs are
expected to default back to Java unless the same three-pronged test
is met (rare).

**What this means for the project shape:**

- The monorepo is polyglot in the strict sense — `backend/springboot/`
  (Java) and `backend/fastapi/` (Python) live side-by-side under the
  same `backend/` umbrella, the same compose network, the same
  Postgres / Kafka / spark-inference-gateway / gateway. The split
  inside `backend/` is by **runtime/framework** (`springboot/` vs
  `fastapi/`) rather than by language — this maps 1:1 in practice
  (each runtime has exactly one language), but the framework name
  reads more concretely at a glance. The transverse ADRs (00–09)
  still apply at the **contract** layer (port pins, Kafka envelope,
  schema-per-BC, gateway OAuth, ADR-08 channels) but not at the
  **toolchain** layer (Gradle, JDK 21, Lombok, ArchUnit) for the
  Python BC.
- ArchUnit's classpath-level layering enforcement (§"Per-BC
  dependency wiring") is not replicable in Python. The Python BC
  enforces layering via directory convention + lint rules + code
  review (per ADR-18 §A18.4). This is a deliberate downgrade for
  the Python BC only; Java BCs retain the stronger guarantee.
- Spring AI / Spring Cloud / Spring Boot patch bumps no longer
  touch the Python BC (it has no Spring on its classpath). The
  Python BC's deps evolve on its own cadence via `pyproject.toml`.

### §A01.12. Module count correction — Java modules stay at 22

**Decision: the Java module count returns to **22** (post-M6.1
baseline per §A01.2). The +4 modules introduced in §A01.6 (the
`massing-gen-{api,app,domain,infra}` quadruplet) are reverted — no
such Java modules exist. The `backend/settings.gradle.kts` does NOT
gain `:massing-gen:massing-gen-*` includes.**

| Category | Count | Notes |
|---|---|---|
| Java BCs | 5 | identity, docs, rag-chat, metrics, + `gateway` + `shared-kernel` (non-BC modules) |
| Java production modules | **22** | 5 BCs × 4 modules + gateway + shared-kernel; same as post-M6.1 baseline |
| Python services | **1** | `backend/fastapi/massing-gen/` (single container, no four-module quadruplet) |
| Total runnable backend containers | **6** | gateway + 4 Java BC `-api`s + 1 Python service |

The ADR-00 ASCII module dependency graph is **redrawn** in the M8
amendment-overview row to reflect the polyglot shape: the
`massing-gen-api` lane stays drawn (the BC still exists), but its
sub-modules drop and its language label becomes "Python (FastAPI)"
instead of the four-module Java cluster. The `rhino3dm-bridge`
external-sidecar lane is **removed** entirely (the sidecar is
retired per ADR-18 §A18.2). The `rag-chat-api → massing-gen-api`
arrow (Exception 4) and the `massing-gen-api → docs-api` arrow
(Exception 5) are preserved — both are HTTP contracts independent
of implementation language.

**Top-level directory layout:**

```
playground/
├── backend/
│   ├── springboot/   # Java BCs (Gradle multi-module, JDK 21, Spring Boot 3.3.x)
│   │   ├── gateway/
│   │   ├── shared-kernel/
│   │   ├── identity/
│   │   ├── docs/
│   │   ├── rag-chat/
│   │   ├── metrics/
│   │   ├── buildSrc/
│   │   ├── settings.gradle.kts
│   │   └── ...
│   └── fastapi/      # Non-Java BCs — Python 3.12 + FastAPI + uvicorn
│       └── massing-gen/   # sole occupant at ship
├── frontend/
├── infra/
└── docs/
```

`backend/springboot/` is the Gradle multi-project root; it is
unaware of its sibling `backend/fastapi/`. Each language tree
publishes its own Docker image consumed by `infra/docker-compose.yml`
— compose is the only aggregation layer.

### §A01.13. Port 18083 — reservation preserved under Python container

**Decision: port 18083 remains reserved for `massing-gen-api`. The
hostname (`massing-gen-api`) and the compose service name
(`massing-gen-api`) are preserved verbatim; only the container's
base image and entrypoint change (was Java fat-jar in §A01.7's
intent, is now `python:3.12-slim` + `uvicorn`).**

The port table (post-Python-flip; supersedes the §A01.7 framing for
the row marked "Python container" and supersedes both §A01.7 and the
M6.1 §A01.3 reservation status for the `18083` row):

| Module | Port | Host-exposed? | Runtime |
|---|---|---|---|
| `gateway` | **18080** | yes (single ingress) | Java (Spring Boot 3.3) |
| `identity-api` | **18081** | no (compose-internal) | Java |
| `docs-api` | **18082** | no | Java |
| **`massing-gen-api`** | **18083** | **no** | **Python 3.12 (FastAPI + uvicorn)** — flipped from Java per ADR-18 §A18.1 |
| `rag-chat-api` | **18084** | no | Java |
| (reserved) | 18085 | reserved for next BC | — |
| `metrics-api` | **18086** | no | Java |

Other compose services that backend BCs reach (`postgres-playground`
10232, `redis-playground` 10279, `opensearch-playground` 10292,
`minio-playground` 10294/10295, the M5 observability stack, the
host's `spark-inference-gateway` at `host.docker.internal:10080`)
all remain language-neutral — the Python BC consumes them via
SQLAlchemy / httpx the same way the Java BCs consume them via
JDBC / Spring AI / Spring `WebClient`.

### §A01.14. Cross-BC observation — future polyglot BC pattern

**Decision: any future polyglot BC follows M8's pattern: single
container, `backend/<runtime>/<bc-name>/` directory (e.g.,
`backend/fastapi/<bc-name>/` for another Python BC,
`backend/axum/<bc-name>/` for a Rust BC, etc.), exposes its API
contract over HTTP at a `*-api` hostname on a port from the same
`1808x` block. The four-module quadruplet does not apply — those
are an artifact of `backend/springboot/`'s hexagonal idiom.**

| Cross-BC concern | Java BC | Polyglot BC (M8 pattern) |
|---|---|---|
| Source directory | `backend/<bc>/<bc>-{api,app,domain,infra}/` | `services/<bc>/` |
| Build system | Gradle (via `backend/settings.gradle.kts`) | per-BC (e.g., `pyproject.toml` for Python) |
| Layering enforcement | classpath-level (ArchUnit + Gradle plugin graph) | review-enforced (no classpath analog) |
| Runtime | JVM (Spring Boot fat-jar) | language-specific (uvicorn for Python; ...) |
| Port | one of `18080–18086` (or future `1808x` slots) | same block |
| Hostname (compose) | `<bc>-api` | `<bc>-api` |
| Postgres schema | schema-per-BC per ADR-05 | schema-per-BC per ADR-05 (unchanged) |
| Kafka envelope | shared-kernel `EventEnvelope<T>` (Java) | per-BC shim (Python equivalent — not needed for M8 since M8 has no Kafka surface; future polyglot BC with Kafka adds an ADR-03 amendment for serialization parity) |
| Cross-BC HTTP exceptions | per ADR-08 amendments | per ADR-08 amendments (HTTP contract is language-neutral) |
| Observability scrape | Spring Boot Actuator `/actuator/prometheus` | language-specific endpoint (e.g., `/metrics` for FastAPI per ADR-18 §A18.8) — M5 PromQL whitelist accommodates both |

The polyglot policy does NOT pre-authorize future polyglot BCs.
Each one still requires a per-milestone ADR amendment per §A01.11's
three-pronged test.

See `docs/adr/18-m8-massing-gen.md` §A18.1–§A18.9 for the full M8
Python flip specification.

## Amendment 2026-06-04 (ADR-19) — `agent-tools` multi-BC Python host + Python-side BC-per-service divergence

> This amendment is appended to ADR-01 following the M8 Python-flip
> block (§A01.11–§A01.14) above. ADR-19 pivots the Python side from
> "one container per Python BC" (the §A01.14 framing) to a single
> multi-BC host service, `agent-tools`. The §A01.11–§A01.14 block is
> **not rewritten** — it remains the audit record of the
> one-container-per-Python-BC intent at M8 ship. This amendment
> supersedes §A01.13's `massing-gen-api` service/hostname row and
> refines §A01.14's "future polyglot BC pattern" for the LLM-tool
> sub-case. The Java side is **untouched** — Java BCs remain
> one-quadruplet-per-BC per §A01.2.

### §A01.15. `agent-tools` — single Python host for multiple LLM-tool BCs

**Decision: the Python/FastAPI runtime is no longer one-service-per-BC.
A single deployable host service, `agent-tools`, hosts multiple
LLM-tool bounded contexts as self-contained directory modules under
`backend/fastapi/agent-tools/<bc>/`. This is a deliberate divergence
from ADR-01's "one BC = one deployable service" invariant (§A01.2),
scoped to the Python side and to small LLM-tool BCs only. Java BCs are
unaffected.** (See ADR-19 §D1 for the full rationale and trade-offs.)

| Concern | Decision |
|---|---|
| Host service name | **`agent-tools`** (compose service / `container_name` / `hostname` / image `playground/agent-tools:dev`) |
| Port | **18083** (unchanged; reclaims the slot `massing-gen-api` held under §A01.13) |
| Runtime | Python 3.12 + FastAPI + uvicorn — single ASGI app, per-BC routers mounted under their own prefixes |
| Repo layout | `backend/fastapi/agent-tools/` is the host; each BC is a directory module `backend/fastapi/agent-tools/<bc>/`; host-shared plumbing lives at `backend/fastapi/agent-tools/app/` (LLM client, docs-api client, config, app factory, observability) |
| Per-BC ownership | each BC owns its own DB schema (schema-per-BC per ADR-05 still holds), own gateway route prefix, own `ToolCatalog` descriptor(s), own prompts/LLM code; BCs do not import each other's domain modules |

**Divergence trade-off (honest statement):** the co-located BCs share
one process, one container, one deploy unit, one failure domain, and
one scaling unit. A crash / OOM / CPU-bound request / dependency bump
in one BC affects all BCs in `agent-tools`. Accepted in exchange for
**not duplicating the Python / LLM / LangGraph stack per tiny tool BC**
and for keeping the polyglot footprint bounded to exactly one Python
service — the strongest reading of §A01.11's "polyglot risk is
bounded". Qualifies only for small LLM-tool BCs with no independent
SLA; a Python BC that outgrows co-location graduates to its own
container under a future ADR amendment.

### §A01.16. `massing-gen-api` service retired → `agent-tools`; port table

**Decision: the `massing-gen-api` compose service / hostname (pinned
under §A01.13) is **retired** and replaced by `agent-tools` on the same
port 18083. The `massing-gen` BC is renamed to `architecture` and
relocates to `backend/fastapi/agent-tools/architecture/` (see ADR-19
§D2 for the full rename change-set and ADR-18 §A18.10).**

Java module count is **unchanged** (still 22 — the Python host is not a
Gradle module). Total runnable backend containers is **unchanged at 6**
(gateway + 4 Java BC `-api`s + 1 Python host) — the Python host count
stays 1; it now carries multiple BCs instead of one.

Port table (supersedes §A01.13's `massing-gen-api` row):

| Module / host | Port | Host-exposed? | Runtime |
|---|---|---|---|
| `gateway` | **18080** | yes (single ingress) | Java (Spring Boot 3.3) |
| `identity-api` | **18081** | no | Java |
| `docs-api` | **18082** | no | Java |
| **`agent-tools`** | **18083** | **no** | **Python 3.12 (FastAPI + uvicorn)** — multi-BC host; hosts the `architecture` BC (was `massing-gen-api`) |
| `rag-chat-api` | **18084** | no | Java |
| (reserved) | 18085 | reserved for next Java BC | — |
| `metrics-api` | **18086** | no | Java |

Top-level Python layout (supersedes §A01.12's `backend/fastapi/`
sketch):

```
backend/fastapi/
└── agent-tools/            # single Python host service (port 18083)
    ├── pyproject.toml      # one dependency set for all hosted BCs (incl. LangGraph from Phase 2)
    ├── Dockerfile          # python:3.12-slim + uvicorn agent_tools.main:app
    ├── app/                # host-shared plumbing (LLM client, docs client, config, observability, app factory)
    └── architecture/       # the architecture BC (was massing-gen): schema arch, /api/arch/**, generate_massing
        └── (future sibling architecture tools land as new BC modules here)
```

### §A01.17. Future Python LLM-tool BC pattern (refines §A01.14)

**Decision: a future Python LLM-tool BC defaults to a **new directory
module inside `agent-tools`** (`backend/fastapi/agent-tools/<bc>/`),
NOT a new container. §A01.14's single-container-per-polyglot-BC pattern
still applies to non-tool Python BCs and to any Python BC needing
independent isolation / scaling — those graduate to their own container
under a per-milestone ADR.**

The §A01.11 three-pronged polyglot test still gates *adding Python at
all*. Once a Python LLM-tool BC qualifies, §A01.15 makes its default
home `agent-tools` rather than a fresh container.

See `docs/adr/19-agent-tools-host-and-architecture-bc.md` for the full
specification.
