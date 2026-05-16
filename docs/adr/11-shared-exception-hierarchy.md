# ADR-11: Shared Exception Hierarchy + Unified RestControllerAdvice

## Status
Accepted

## Context
Each BC's `-api` module needs a consistent way to turn domain / application
failures into HTTP responses. Without a shared contract:

- Every BC reinvents its own exception base class.
- Response body shapes drift (some return `{error: "..."}`, others
  `{message: "..."}`, others raw text).
- HTTP status mapping is duplicated in every `@RestControllerAdvice`.
- Log-level discipline (which exceptions are `WARN` vs `ERROR`) is per-BC
  folklore.

The VIA reference repo (`/home/jeek_lee/work/personal/VIA/via-backend/`) ships
a `core/` module with a clean solution: an abstract base, six HTTP-typed
subclasses, a reflective `ExceptionCreator` factory keyed by error code, and a
unified `@RestControllerAdvice`. This ADR adopts that pattern into
`shared-kernel` so every BC's `-api` and `-domain` modules speak the same
exception vocabulary.

Alternatives considered and rejected:
- **Spring's `ResponseStatusException`** — rejected: ties status to throw
  site, hard to attach structured error codes, no central log-level policy.
- **Per-BC exception hierarchies** — rejected: drift, duplication, and no
  cross-BC observability.
- **Vavr `Either<Error, T>` for error returns** — rejected: too heavy a
  functional-style commitment for a single-language Java codebase; exceptions
  are fine when the hierarchy is disciplined.

## Decision

### Six HTTP-typed exception subclasses

All exceptions thrown from `-domain`, `-app`, or `-infra` MUST extend exactly
one of the six subclasses below (or a domain-specific subclass that extends
one of them). All six live in `shared-kernel`, package
`dev.jeeklee.playground.shared.exception`.

| Class | HTTP status | Typical use |
|---|---|---|
| `BadRequestException` | 400 | Client supplied invalid input (validation, malformed payload, missing required field). |
| `UnauthorizedException` | 401 | Missing or invalid authentication. (Note: in this project, the gateway handles auth; backends throw this when `X-User-Id` is absent on an authenticated route — defense in depth.) |
| `ForbiddenException` | 403 | Authenticated but not permitted (visibility checks, ownership checks). |
| `NotFoundException` | 404 | Aggregate not found by ID, or filtered out by visibility. |
| `ServiceUnavailableException` | 503 | Downstream dependency unreachable (LLM gateway, Kafka broker, distributed lock timeout). |
| `InternalServerErrorException` | 500 | Unrecoverable invariant violation. The default catch-all when no other subclass applies. |

### The `AbstractException` base class

```
package dev.jeeklee.playground.shared.exception;

public abstract class AbstractException extends RuntimeException {
    protected AbstractException(ErrorCode errorCode, Object... messageArgs) { ... }
    public abstract HttpStatus httpStatus();        // pinned by subclass
    public abstract LogLevel  logLevel();           // pinned by subclass
    public ErrorCode errorCode();                   // structured error code
    public Object[]  messageArgs();                 // for message templating
}
```

- **`ErrorCode`** is an `interface` (not enum) so each BC can define its own
  enum implementing it (`DocsErrorCode implements ErrorCode`,
  `IdentityErrorCode implements ErrorCode`). The interface contract is
  `String code()` + `String defaultMessage()`. Format: `<BC>-<SUBSYSTEM>-<NNN>`
  (e.g., `DOCS-INGEST-001`, `IDENTITY-BOOTSTRAP-001`). The `<BC>` segment
  matches ADR-01's BC names.
- **`logLevel()`** is pinned per subclass:
  - `BadRequestException`, `UnauthorizedException`, `ForbiddenException`,
    `NotFoundException` → `WARN` (client-caused; expected in normal operation).
  - `ServiceUnavailableException` → `WARN` (downstream issue; usually
    transient).
  - `InternalServerErrorException` → `ERROR` (a bug or a state we didn't
    anticipate).

### `ExceptionCreator` — reflective factory keyed by error code

A static helper class in `shared-kernel`:

```
ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, documentId).throwIt();
ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, documentId)
    .as(NotFoundException.class)
    .throwIt();
```

Under the hood it reflectively instantiates the right `AbstractException`
subclass based on the `ErrorCode`'s annotation (e.g.,
`@MappedTo(NotFoundException.class)` on the enum constant). This collapses the
`throw new NotFoundException("DOCS-INGEST-002", documentId);` boilerplate to a
single fluent call and centralizes the code-to-subclass mapping.

This is a direct adoption of the VIA pattern; see VIA's `core/src/main/.../ExceptionCreator.java`
for reference (inspiration only — we re-implement, not copy).

### Unified response body shape

Every error response from any BC has this shape:

```json
{
  "errorCode": "DOCS-INGEST-002",
  "message": "Document not found: doc_01HXYZABCDEF",
  "timestamp": "2026-05-16T10:11:12.345Z",
  "path": "/api/docs/doc_01HXYZABCDEF",
  "traceId": "0123456789abcdef0123456789abcdef"
}
```

- `errorCode` is the BC's stable error code (machine-readable, suitable for
  frontend `i18n` lookup).
- `message` is the rendered default message (may be localized in future).
- `timestamp` is ISO-8601 UTC at the moment the advice fires.
- `path` is the request path (added by the advice from
  `HttpServletRequest.getRequestURI()`).
- `traceId` is the current trace ID (Micrometer Observation) if present;
  omitted otherwise.

The response shape is itself a record in `shared-kernel`:
`ErrorResponse(errorCode, message, timestamp, path, traceId)`.

### Unified `@RestControllerAdvice`

`shared-kernel` ships a default `@RestControllerAdvice` class
(`SharedExceptionHandler`) that handles all six `AbstractException` subclasses
plus Spring's common `MethodArgumentNotValidException`,
`HttpMessageNotReadableException`, `MissingServletRequestParameterException`
(mapping them to `BadRequestException` with a generic `SHARED-BAD-REQUEST-001`
code).

- The advice is **shipped in `shared-kernel`** but **annotated with
  `@ConditionalOnMissingBean(SharedExceptionHandler.class)`** so any BC that
  wants to override it can ship its own.
- Each BC's `-api` module auto-picks up the default via Spring Boot
  auto-configuration (`shared-kernel` ships an `AutoConfiguration` class
  registering the advice when on the classpath of a `*-api` module).
- The advice handles logging via `slf4j` with the level pinned by each
  exception's `logLevel()`. `WARN` exceptions log a single line including the
  error code, path, and message — no stack trace. `ERROR` exceptions log the
  full stack trace.

### Domain-specific subclasses

Each BC's `-domain` module ships subclasses for its domain failures:

```
package dev.jeeklee.playground.docs.domain.exception;

public final class DocumentNotFoundException extends NotFoundException {
    public DocumentNotFoundException(DocumentId id) {
        super(DocsErrorCode.DOCUMENT_NOT_FOUND, id.value());
    }
}
```

These live in `-domain` (not `-infra`) because they encode domain rules. They
do not import Spring; `AbstractException` extends `RuntimeException` only, and
`HttpStatus` is referenced as the Spring Framework type — `shared-kernel`'s
`playground.bc-domain` plugin permits `spring-web` only for the `HttpStatus`
enum reference, not for the broader web stack. (The classpath plugin will
allow `spring-web` as a `compileOnly` dependency for `-domain` so the
`HttpStatus` reference compiles without pulling the full web stack at
runtime.)

### Validation-failure mapping (Spring conventions)

The default advice maps:

| Spring exception | Mapped to | Error code |
|---|---|---|
| `MethodArgumentNotValidException` | `BadRequestException` | `SHARED-VALIDATION-001` |
| `ConstraintViolationException` (Jakarta Bean Validation) | `BadRequestException` | `SHARED-VALIDATION-002` |
| `HttpMessageNotReadableException` | `BadRequestException` | `SHARED-DESERIALIZATION-001` |
| `MissingServletRequestParameterException` | `BadRequestException` | `SHARED-MISSING-PARAM-001` |
| `NoResourceFoundException` (Spring 6.1+ static resource) | `NotFoundException` | `SHARED-NOT-FOUND-001` |
| any uncaught `Exception` | `InternalServerErrorException` | `SHARED-INTERNAL-001` |

The validation cases include a `details` field in the response body listing
the per-field violations:

```json
{
  "errorCode": "SHARED-VALIDATION-001",
  "message": "Validation failed",
  "timestamp": "...",
  "path": "...",
  "traceId": "...",
  "details": [
    {"field": "email", "code": "NotBlank", "message": "must not be blank"}
  ]
}
```

The `details` field is omitted for non-validation responses.

### Cross-reference to VIA

VIA's `core/` module (`/home/jeek_lee/work/personal/VIA/via-backend/core/src/main/java/.../exception/`)
is the inspiration. We re-implement rather than copy because:
- Our package root differs (`dev.jeeklee.playground.shared.exception` vs
  VIA's `com.via.core.exception`).
- VIA places its `@RestControllerAdvice` in the application's `api` module;
  we place it in `shared-kernel` with Boot auto-configuration so every `*-api`
  picks it up by default.
- VIA's error-code enum is project-scoped; ours is per-BC via the
  `ErrorCode` interface, matching the bounded-context vocabulary established
  by ADR-01.

## Consequences
- Positive: every BC produces identical error response bodies; the frontend
  has one shape to handle and one error-code namespace per BC for `i18n`.
- Positive: log-level discipline is encoded in the type system. A
  `NotFoundException` is `WARN`; no logger.error()-on-404 misuse possible.
- Positive: adding a new BC means defining one `<Bc>ErrorCode` enum
  implementation and a few `<Bc>NotFoundException`-style subclasses — no new
  advice, no new response shape.
- Positive: `shared-kernel` remains zero-runtime — it stays a Java library,
  only adds Spring Web as a `compileOnly` dependency for the `HttpStatus`
  reference and as a `compile` dependency from the advice class onward (the
  advice itself is only loaded in `*-api` runtimes, where Spring Web is
  already present).
- Negative: reflective `ExceptionCreator` is more magic than `throw new
  NotFoundException(...)`. Mitigated by the explicit `@MappedTo` annotation on
  every error-code enum constant — the mapping is greppable.
- Negative: the auto-configured default advice can surprise a BC that wanted
  custom handling. Mitigated by `@ConditionalOnMissingBean` — a BC can drop
  in its own advice and the default steps aside.
- Negative: pinning HTTP status at the exception class level prevents a single
  exception type from mapping to different statuses in different contexts. This
  is intentional — if you need a different status, throw a different exception.

## Related
- ADR-01 (Gradle structure) — `shared-kernel` is a library module shared by
  all BCs at compile time
- ADR-02 (DDD layering) — domain exceptions live in `-domain`, advice lives
  in `-api` (or in `shared-kernel`'s auto-config)
- ADR-08 (Inter-service comms) — error response shape is the cross-BC
  observability contract
- VIA reference repo `core/` module — inspiration (not copied verbatim)
