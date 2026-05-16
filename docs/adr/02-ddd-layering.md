# ADR-02: DDD Layering Per Service (Quadruplet Modules)

## Status
Accepted — **v2 supersedes ADR-02 v1**. v1 expressed layering as packages
inside one Gradle module enforced by ArchUnit (recommended). v2 promotes the
four-module quadruplet introduced in ADR-01 v2 to the unit of layering, and
codifies the VIA-compromise rules for domain purity (Spring `@Service` and
Lombok allowed on domain services that orchestrate value objects; entities,
aggregates, VOs, IDs remain framework-free).

## Context
Two pressures motivated v2:

1. **Classpath-level enforcement > test-level enforcement.** With four modules
   per BC (`-api`, `-app`, `-domain`, `-infra`) and a directed dependency graph
   (ADR-01), a `-domain` module cannot import `@Service` because Spring is not
   on its classpath. ArchUnit becomes redundant.
2. **VIA's pragmatism on domain services.** VIA allows `@Service` and Lombok on
   classes like `OAuth2ContextService` that orchestrate domain value objects
   without owning state. Forbidding Spring entirely in `-domain` pushes those
   classes into `-app`, where they fit awkwardly (they are domain logic, not
   use cases). v2 codifies VIA's compromise: framework annotations are allowed
   on **domain services**, but **never** on entities, aggregates, value
   objects, identifiers, or domain events.

Alternatives considered and rejected:
- **Strict no-Spring-in-`-domain`** (v1's posture) — rejected: pushes natural
  domain services into `-app` where they're miscategorized, or duplicates them
  via interfaces in `-app` + impls in `-domain` for no real isolation gain.
- **VIA's repository-in-`-domain` placement** — rejected: VIA puts repository
  interfaces in `domain.repository`. We move them to `application.repository`
  (this ADR §`-app`). Rationale: repository interfaces are *ports* — the
  contract by which use cases reach the outside world. Return types are domain
  types, but the interface itself belongs to the use-case port surface.
- **One repository interface in both `-domain` and `-app`** — rejected: doubles
  the abstraction surface for no benefit.

## Decision

### The unit of layering is the four-module quadruplet

Per ADR-01 v2, every BC ships as `<bc>-api`, `<bc>-app`, `<bc>-domain`,
`<bc>-infra`. Layering rules are enforced by the Gradle dependency graph:

```
*-api    ──implementation──> *-app
*-api    ──runtimeOnly─────> *-infra
*-app    ──implementation──> *-domain
*-infra  ──implementation──> *-app    (implements *-app port interfaces)
*-infra  ──implementation──> *-domain (only for return types in adapter signatures)
```

Direction is one-way: `*-api → *-app → *-domain`, and `*-infra` is a leaf
adapter that satisfies `*-app`'s ports. No module imports backward.

### `<bc>-domain` — domain model

Package root: `dev.jeeklee.playground.<bc>.domain.*`.

**Contents:**
- **Aggregate roots, entities, value objects, identifiers.** Java `record`s or
  immutable POJO classes. **NO Spring, NO Lombok-`@Setter`, NO JPA annotations,
  NO Jackson annotations.** Lombok `@Builder` / `@Getter` / `@Value` are
  acceptable for ergonomic records-equivalents.
- **Domain services.** Classes that orchestrate value objects without owning
  state (e.g., `OAuth2ContextService`, `EmbeddingDimensionValidator`).
  **`@Service` is allowed here.** Lombok `@RequiredArgsConstructor` is allowed.
  Domain services depend only on other `-domain` types and JDK.
- **Domain events.** POJO records under `<bc>.domain.event.*` (e.g.,
  `UserRegistered`, `DocumentUploaded`). No Spring imports.
- **Enums** representing domain vocabulary (e.g., `DocumentVisibility`,
  `IngestionStatus`).
- **Domain exceptions** extending the shared hierarchy from ADR-11
  (`AbstractException` and its six HTTP-typed subclasses live in
  `shared-kernel`). Domain code throws `DocumentNotFoundException extends
  NotFoundException` rather than inventing its own root.

**Classpath constraints (enforced by `playground.bc-domain` convention
plugin):**
- Allowed: JDK, `shared-kernel`, `org.springframework:spring-context` (for
  `@Service` only — domain services), Lombok.
- **Forbidden:** `spring-boot`, `spring-web*`, `spring-data-*`, `spring-kafka`,
  `jakarta.persistence`, `com.fasterxml.jackson.*`.

The convention plugin enforces this via a `configurations.all { exclude(...) }`
block. Lombok's annotation processor is wired so that domain `record`s can use
`@Builder` without an explicit dependency on annotation-processor coordinates.

### `<bc>-app` — application / use-case layer

Package root: `dev.jeeklee.playground.<bc>.application.*`.

**Contents:**
- **Use-case services.** `@Service`-annotated classes that orchestrate domain
  aggregates and call out through repository ports. One use case per method (or
  one per class for non-trivial flows). Annotated with `@Transactional` at the
  use-case boundary.
- **Use-case I/O DTOs.** Input/output records (`<UseCase>Command`,
  `<UseCase>Result`). Distinct from web DTOs in `-api` and from JPA entities in
  `-infra`.
- **Repository port interfaces.** `application/repository/<Aggregate>Repository`.
  Return types are domain types from `-domain`. **This is the VIA-compromise
  shift** (v1 placed these in `-domain.repository`).
- **Outbound port interfaces.** `application/port/<X>Port` for non-repository
  collaborators: `EventPublisherPort`, `EmbeddingGeneratorPort`,
  `DistributedLockPort`, etc. Implementations live in `-infra`.

**Classpath constraints (`playground.bc-app` plugin):**
- Allowed: JDK, `shared-kernel`, `*-domain`, `spring-context`,
  `spring-tx` (for `@Transactional`), Lombok.
- **Forbidden:** `spring-web*`, `spring-data-*`, `jakarta.persistence`,
  `spring-kafka`, `spring-ai-*`. (Adapters live in `-infra`.)

### `<bc>-api` — inbound HTTP adapter

Package root: `dev.jeeklee.playground.<bc>.api.*`.

**Contents:**
- **OpenAPI interfaces.** Each REST resource ships an `interface
  <Resource>OpenApi` annotated with Springdoc / Swagger annotations. The
  controller `implements` the interface; the interface is the source of truth
  for the generated OpenAPI document (VIA pattern — adopted).
- **Controllers.** `@RestController` classes that implement an OpenApi
  interface. Controllers are thin: they parse the request, call one use-case
  service in `-app`, and translate the result to a response DTO.
- **Request / response DTOs.** Records suffixed `*Request` / `*Response`. They
  are wire-format types and may carry Jackson annotations.
- **Per-BC `GroupedOpenApi` bean.** A `@Configuration` class registers a
  Springdoc `GroupedOpenApi` for the BC, so each BC's Swagger UI page is
  scoped to its own endpoints.
- **Per-BC `@RestControllerAdvice`** wiring up the shared exception hierarchy
  from ADR-11. `shared-kernel` may ship a default advice that BCs can extend
  or replace.
- **Spring Boot `@SpringBootApplication` entrypoint.** This is the only module
  with a `main` method; it bootstraps the JVM that ADR-01 pins to a port.

**Classpath constraints (`playground.bc-api` plugin):**
- Allowed: everything `-app` allows, plus Spring Web (MVC or WebFlux as the BC
  requires), Springdoc, Spring Security (when the BC validates `X-User-*`
  headers), `*-app` (`implementation`), `*-infra` (`runtimeOnly` only).
- `-api` MUST NOT import any type from `<bc>.infrastructure.*` directly —
  `runtimeOnly` forbids it at compile time. Adapters bind by component scan +
  Spring auto-configuration.

### `<bc>-infra` — outbound adapters

Package root: `dev.jeeklee.playground.<bc>.infrastructure.*`.

**Contents:**
- **JPA persistence model.** `infrastructure/persistence/<Aggregate>JpaEntity`
  is the JPA-annotated entity; `infrastructure/persistence/<Aggregate>JpaRepository`
  is the Spring Data interface; `infrastructure/persistence/<Aggregate>RepositoryImpl`
  implements the `*-app` repository port and translates between domain types
  and `*JpaEntity` via a hand-written `<Aggregate>JpaMapper` (VIA pattern —
  adopted).
- **Domain types are NEVER JPA entities.** The `-domain` aggregate root and
  the `*JpaEntity` are different classes; the mapper bridges them. This
  guarantees the domain layer survives JPA dialect changes and persistence
  technology swaps.
- **Kafka adapters.** Producers (implementing `-app`'s `EventPublisherPort`)
  and `@KafkaListener` consumer beans. Both wrap business payloads in
  `shared-kernel`'s `EventEnvelope<T>` (per ADR-03).
- **HTTP clients.** WebClient / Feign clients for external services
  (`spark-inference-gateway`, gateway-sanctioned cross-BC reads per ADR-08).
- **Spring AI adapters.** `EmbeddingGeneratorPortImpl` wrapping Spring AI's
  `EmbeddingModel`, `ChatClientPortImpl` wrapping `ChatClient`.
- **Redis / Redisson adapters.** `DistributedLockPortImpl` for the `@GlobalLock`
  pattern (when the BC needs it, per ADR-08 amendment).
- **`@Configuration` classes.** Per-BC `@EnableJpaRepositories(basePackages =
  "dev.jeeklee.playground.<bc>.infrastructure.persistence")`, Kafka producer
  / consumer factory configs, Redis connection config, etc.
- **Per-BC Flyway migrations** under `src/main/resources/db/migration/` (per
  ADR-05).

**Classpath constraints (`playground.bc-infra` plugin):**
- Allowed: JPA (Hibernate), Spring Data JPA, Flyway, Spring Kafka, WebClient,
  Spring AI starters, Spring Data Redis (Redisson), Lombok.
- Depends on `*-app` (`implementation`) and `*-domain` (`implementation`).

### Repository placement summary

```
*-app/application/repository/UserRepository                    <- interface (port)
        returns dev.jeeklee.playground.identity.domain.model.User  (domain type)

*-infra/infrastructure/persistence/UserJpaEntity               <- JPA entity
*-infra/infrastructure/persistence/UserJpaRepository           <- Spring Data JpaRepository<UserJpaEntity,UUID>
*-infra/infrastructure/persistence/UserRepositoryImpl          <- implements UserRepository
*-infra/infrastructure/persistence/UserJpaMapper               <- User <-> UserJpaEntity
```

Domain types and JPA entities are **separate classes** by design. The mapper
is hand-written (VIA pattern — adopted; we explicitly reject MapStruct for M0-M5
to keep the mapper visible and reviewable; revisit if mapper count exceeds ~30
per BC).

### Aggregate root pattern
- Each aggregate exposes a single root through which all mutations occur.
- Cross-aggregate references are by identifier value object, never by JPA
  association.
- One `*-app` repository port per aggregate root.
- One `*JpaMapper` per aggregate root.

### Enforcement
- **Primary: Gradle dependency graph + convention plugin classpath
  restrictions.** A `-domain` module cannot compile a class that references
  `@RestController` because `spring-web` is not on its classpath.
- **Secondary: code-reviewer agent** spot-checks domain-service annotation
  usage (`@Service` only on stateless orchestrators; never on aggregates).
- **ArchUnit is no longer recommended** — superseded by classpath enforcement.

## Consequences
- Positive: layering is a build-time guarantee. A pull request that violates
  the rules does not compile.
- Positive: VIA-compatible module shape lets backend-implementer agents reuse
  VIA's OpenAPI / mapper patterns directly.
- Positive: domain services keep their natural home in `-domain` (where the
  logic conceptually lives) instead of being exiled to `-app`.
- Negative: four `build.gradle.kts` files per BC instead of one. Mitigated by
  convention plugins keeping each file to a single `plugins { ... }` block.
- Negative: hand-written mappers cost keystrokes. Acceptable trade-off — they
  also document the domain/persistence boundary explicitly.
- Negative: repository port in `-app` instead of `-domain` is a deviation from
  textbook DDD ("repository abstraction belongs in the domain"). We accept it
  as a pragmatic compromise: the *contract* (return-type domain types) belongs
  to the domain, but the *port placement* belongs to the use-case layer that
  consumes it.

## Related
- ADR-01 (Gradle structure) — quadruplet module shape, convention plugins
- ADR-08 (Inter-service comms) — port interfaces for distributed locks,
  cross-BC reads
- ADR-11 (Shared exception hierarchy) — `AbstractException` subclasses thrown
  from `-domain`, caught by `-api`'s `@RestControllerAdvice`
