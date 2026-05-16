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

Path convention: `api/<bc>/<bc>-<layer>/` (e.g., `api/identity/identity-api/`,
`api/identity/identity-app/`). Each BC directory groups its four submodules.
`gateway` and `shared-kernel` sit directly under `api/`. Root
`settings.gradle.kts` aggregates all 22 modules with explicit
`include(":identity-api", ":identity-app", …)` calls.

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
