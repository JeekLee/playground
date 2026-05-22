# ADR-18: M8 `massing-gen` BC — Implementation Decisions

## Status
Accepted (2026-05-22)

## Context

The M8 PRD (`docs/prd/M8-massing-gen.md`, landed on this branch as commit
`950631c`) pins the user-facing scope of the first concrete tool BC: a new
`massing-gen-{api,app,domain,infra}` quadruplet, a `POST /internal/tools/generate-massing`
endpoint, a `BriefProgramExtractor` LLM call + JSON-Schema validation, a
Spring-free `MassingAlgorithm`, a `Rhino3dmAdapter` calling a Node sidecar
container (`rhino3dm-bridge`), a new `arch.outputs` table, a
`GET /api/arch/outputs/{id}` owner-only download, and a single descriptor
registration in `rag-chat-domain.ToolCatalog`. It deliberately defers **14
implementation-shape questions** to this per-milestone ADR — the 9 carried
from spec §6 verbatim plus 5 raised by the PRD itself (Q-A through Q-E).

ADR-17 did the same job for M7 (Spring AI 1.0.0 GA `ChatClient.tools(...)`
call shape, per-tool Resilience4j breaker, 16 KiB result cap, SSE wire
shape, Exception 4 introduction). ADR-16 did it for M6 (Apache PDFBox,
Vision OCR fallback, `mime_type` column). ADR-12's M6.1 amendment dissolved
rag-ingestion into docs and **retired ADR-08 Exception 1** — which means
the spec §7 working assumption that M8 would "widen Exception 1 from M3 to
M3 + massing-gen" is stale; M8 must pick a fresh body-fetch mechanism.

The M8 BC change is, in shape, **the largest BC additive since M2** — a
fresh four-module quadruplet, a new schema, a new sidecar container, a new
download surface, and the first concrete `ToolCatalog` descriptor. But the
edit blast radius outside the new BC is small:

- **One new BC.** `massing-gen-{api,app,domain,infra}` joins the project.
  Module count climbs from 22 (post-M6.1) to 26.
- **One new schema.** `arch` joins `identity`/`docs`/`chat` (and the
  reserved `metrics`). Schema count returns to 4.
- **One new sidecar container.** `rhino3dm-bridge` (Node 18-alpine +
  `rhino3dm` npm) joins the compose stack. Compose-internal only.
- **One new ADR-08 sub-row under Exception 4.** `rag-chat-api` →
  `massing-gen-api` `/internal/tools/generate-massing` (the template
  ADR-17 §A08.8 established gets its first concrete entry).
- **One new ADR-08 exception type.** `massing-gen-api` → `docs-api`
  for brief body read (Q-E: fresh exception over cross-schema SELECT —
  see §5 below).
- **One new ADR-08 exception sub-row only — Exception 1 is NOT
  revived.** The spec §7's "widen Exception 1" wording is stale and
  superseded by §5 of this ADR.
- **One new ToolCatalog descriptor.** `MassingTool` constant in
  `rag-chat-domain.tool` — single-line addition to
  `ToolCatalog.descriptors()`.

What M8 does **not** change:

- **No M4 / M7 code touched** beyond the `ToolCatalog` 1-line registration
  (PRD Story 12 invariant). The SSE grammar, the breaker pattern, the
  depth cap, the dispatcher — all reused verbatim.
- **No M2 / M6 / M6.1 code touched** for the brief-body read. The body
  endpoint M8 calls is the defensively-preserved
  `/internal/docs/public/{id}/body` route from ADR-12 §2 (kept in the
  codebase per ADR-08 §A08.1, never deleted — M8 is its first new
  consumer post-M6.1).
- **No Kafka surface.** `massing-gen` is neither producer nor consumer
  (mirrors M4 / M7 — synchronous HTTP only). ADR-03 is not amended.
- **No new external LLM model.** Qwen3-32B reused from ADR-04 / ADR-14;
  no new model added to spark-inference-gateway.
- **No new gateway route added at user-facing prefix.** The download
  endpoint sits at `/api/arch/outputs/{id}` which gateway forwards
  unchanged to `massing-gen-api` per ADR-07's route table (a single new
  route prefix added — `/api/arch/**`).

Three transverse ADR amendments land in the same PR set:

- **ADR-08 §A08.11–§A08.14** — adds the `rag-chat-api` →
  `massing-gen-api` sub-row to Exception 4, introduces **Exception 5**
  (`massing-gen-api` → `docs-api` for brief body read), redraws the
  allowed-channels table.
- **ADR-01 §A01.6–§A01.9** — adds the `massing-gen-{api,app,domain,infra}`
  quadruplet, pins port **18083** (freed by M6.1; spec's port 18086 is
  stale — owned by metrics-api per A01.3), bumps module count to 26.
- **ADR-05 §A05.5–§A05.8** — introduces the `arch` schema and the
  `arch.outputs` table DDL.

ADR-00 gains a row for ADR-18; ADR-01 / ADR-05 / ADR-08 gain amendment
markers. The roadmap §M8 row is rewritten to reference the PRD + this ADR.

## Decision

### 1. Module placement — fresh `massing-gen` quadruplet (ADR-01 invariant)

**Decision:** M8 ships a fresh four-module quadruplet
`massing-gen-{api, app, domain, infra}` at
`backend/massing-gen/massing-gen-{api,app,domain,infra}/`. The
Java package root is `dev.jeeklee.playground.massinggen` (matching
the `dev.jeeklee.playground.<bc>` convention from ADR-01 §"Java package
root"). The four modules use the existing `playground.bc-{domain,app,api,infra}`
convention plugins from ADR-01 v2's `buildSrc` — no new plugin needed.

| Module | Type | bootJar? | Java package |
|---|---|---|---|
| `massing-gen-api` | runnable Spring Boot app | **yes** | `dev.jeeklee.playground.massinggen.api.*` |
| `massing-gen-app` | Java library | no | `dev.jeeklee.playground.massinggen.application.*` |
| `massing-gen-domain` | Java library | no | `dev.jeeklee.playground.massinggen.domain.*` |
| `massing-gen-infra` | Java library | no | `dev.jeeklee.playground.massinggen.infrastructure.*` |

The component-placement table (PRD §"Bounded Context: massing-gen"
verbatim, restated for ADR audit):

| Component | Module | Spring-free? |
|---|---|---|
| `Program` (record: `List<Room> rooms`, `SiteFootprint site`, `float floorHeightM`) | `massing-gen-domain` | **Yes** |
| `Room` (record: `String name`, `float areaM2`) | `massing-gen-domain` | **Yes** |
| `SiteFootprint` (record: `float widthM`, `float depthM`) | `massing-gen-domain` | **Yes** |
| `RoomBox` (record: `int floor`, `float x`, `float y`, `float widthM`, `float depthM`, `float heightM`, `String roomName`) | `massing-gen-domain` | **Yes** |
| `MassingErrorCode` (enum — see §7) | `massing-gen-domain` | **Yes** |
| `MassingException` (extends `AbstractException` per ADR-11) | `massing-gen-domain` | **Yes** (the `shared-kernel` exception base has no Spring imports) |
| `MassingAlgorithm` (interface — `compute(Program, int maxFloors): List<RoomBox>`) | `massing-gen-domain` | **Yes** |
| `RectangularFirstFitMassingAlgorithm` (impl) | `massing-gen-domain` | **Yes** |
| `GenerateMassingUseCase` (orchestrator) | `massing-gen-app` | Partial — `@Service`, `@Transactional` allowed per ADR-02 |
| `BriefProgramExtractor` (LLM extraction wrapper — interface in `-app`, impl in `-infra`) | `massing-gen-app` (port) + `massing-gen-infra` (`SpringAiBriefExtractorAdapter`) | port = **Yes**, adapter = **No** |
| `BriefBodyPort` (interface — `Mono<String> fetchBody(UUID briefDocId, UserId user)` — see §5) | `massing-gen-app` | **Yes** |
| `Rhino3dmPort` (interface — `Mono<byte[]> serialize(List<RoomBox> boxes)`) | `massing-gen-app` | **Yes** |
| `ArchOutputRepository` (interface — `save(...)`, `findByIdAndUserId(...)`) | `massing-gen-app` | **Yes** |
| `HttpBriefBodyAdapter` (WebClient → `docs-api`'s `/internal/docs/public/{id}/body`) | `massing-gen-infra` | **No** |
| `SpringAiBriefExtractorAdapter` (Spring AI `ChatClient` → spark-inference-gateway) | `massing-gen-infra` | **No** |
| `Rhino3dmAdapter` (WebClient → `rhino3dm-bridge` sidecar) | `massing-gen-infra` | **No** |
| `ArchOutputJpaRepository` (JPA impl + `arch.outputs` `@Entity`) | `massing-gen-infra` | **No** |
| Resilience4j `CircuitBreaker` registration for `rhino3dm-bridge` + `spark-gateway` reuse | `massing-gen-infra` | **No** |
| `GenerateMassingController` (`POST /internal/tools/generate-massing`) | `massing-gen-api` | **No** |
| `ArchOutputDownloadController` (`GET /api/arch/outputs/{id}`) | `massing-gen-api` | **No** |
| Flyway migrations for `arch.outputs` | `massing-gen-infra` (`src/main/resources/db/migration/`) | n/a |
| `MassingTool` `ToolDescriptor` constant + `ToolCatalog` registration | `rag-chat-domain.tool.MassingTool` (NEW — owned by M8 PR set but lives in the rag-chat-domain module) | **Yes** |

The `MassingTool` constant placement deserves a note: ADR-17 §D pins
that `ToolCatalog.descriptors()` is hardcoded at classpath-load time
and new tools "land via PR to `rag-chat-domain` adding a new descriptor
constant". M8 follows that pin — the descriptor is a single Java
static-field addition in `rag-chat-domain` (which keeps M7's
Spring-free invariant intact). The M8 PR touches one rag-chat file by
exactly the lines needed to add the descriptor; this is the **only**
M4/M7 file the M8 PR set modifies (PRD Story 12 invariant).

### 2. Q-A — `massing-gen-api` port pin: **18083**

**Decision: `massing-gen-api` binds port **18083**.**

Closes PRD open question **Q-A** ("spec §6's port 18086 is stale —
metrics-api owns 18086 per ADR-01 §A01.3").

| Concern | Pin |
|---|---|
| Port | **18083** |
| Host-exposed? | **No** (compose-internal only — backends MUST NOT be host-exposed per ADR-08) |
| Gateway-routable? | **Yes** for `/api/arch/**` (the download endpoint). **No** for `/internal/**` (the tool dispatch endpoint, per Exception 4's `/internal/**`-not-forwarded rule from ADR-08 §A08.8). |
| ADR-01 port table update | See §A01.7 below. |

**Rationale (alternatives evaluated):**

- **18086 (spec §6 working value):** STALE. Owned by `metrics-api` per
  ADR-01 §A01.3 since M5 ship. Cannot be reused.
- **18083 (chosen):** Freed by M6.1 — the original `rag-ingestion-api`
  port returned to the reservation pool when the BC dissolved into
  `docs` (ADR-01 §A01.3 amendment). M8 claims it first.
- **18085 (also free):** Reserved for "next BC" since ADR-01 v2 shipped;
  also viable. Rejected only because **18083** is the older free slot
  (FIFO discipline — claim the earlier-freed slot first; 18085 stays
  reserved for the next tool BC, e.g., M9's `slide-gen`).
- **18087+ (new slot):** Rejected — two free ports already exist in
  the reservation pool; carving a new slot is wasteful and would push
  the `18086` ceiling outward unnecessarily.

The descriptor's `endpoint` field therefore reads
`http://massing-gen-api:18083/internal/tools/generate-massing` (M7
ADR-17 §9 shape; pin in §6 below).

### 3. Q-E — brief body fetch mechanism: **fresh ADR-08 Exception 5 (HTTP)**

**Decision: `massing-gen-app`'s `BriefBodyPort` is implemented by an
`HttpBriefBodyAdapter` in `massing-gen-infra` that calls
**`GET http://docs-api:18082/internal/docs/public/{id}/body`** via
WebClient. The call is sanctioned by a fresh **ADR-08 Exception 5**
(see §A08.12 below) — NOT by reviving the retired Exception 1.**

Closes PRD open question **Q-E** ("spec §7's 'widen Exception 1' is
stale because M6.1 retired Exception 1").

**Why HTTP over cross-schema SELECT (the M4 pattern):**

| Mechanism | Trade-off | Choice |
|---|---|---|
| (a) Fresh ADR-08 Exception 5 — HTTP via `/internal/docs/public/{id}/body` | Honors BC isolation — `massing-gen-api` does not learn the `docs.documents` row layout; the docs BC is free to evolve columns under the API contract. Adds 1 HTTP hop (~5 ms compose-internal). | **Chosen** |
| (b) Cross-schema SELECT (M4 ADR-14 §3 pattern reused) | Zero HTTP hop, ~1 ms SQL. Couples `massing-gen-api` to the `docs.documents` table schema at the SQL layer; widens the M4 cross-schema exception from 2 schemas (`docs,identity`) to 3 (`docs,identity,arch-reader`); the SELECT carries no visibility-rule code path (M4 ADR-14 §3.2 includes visibility filter — `massing-gen` would need an equivalent). | Rejected |

**Rationale for (a) over (b):**

- **M4's cross-schema SELECT was justified by latency** (citation
  enrichment runs inside TTFT P95 ≤ 2.0 s, per ADR-14 §3 table). M8's
  brief-body fetch runs **once per tool call**, not per token — the
  ~5 ms HTTP hop is invisible against the LLM extraction call (10–30 s)
  and the sidecar serialization call (1–10 s). No latency budget
  pressure justifies bypassing BC isolation.
- **The `/internal/docs/public/{id}/body` route already exists** as
  defensively-preserved code per ADR-08 §A08.1. The route's only
  prior caller (`rag-ingestion-infra`) was deleted in M6.1; the
  controller (`InternalDocumentController` in `docs-api`) is
  preserved in expectation of exactly this case — a future BC reviving
  cross-BC body-fetch under a new ADR-08 exception. M8 is that future
  BC. **Zero docs-api code change required.**
- **The "Exception 1 widening" framing in spec §7 was authored before
  M6.1** retired Exception 1. The spec's working language is stale; a
  fresh Exception 5 with the M6.1 rag-ingestion exit factored out is
  the architecturally clean answer.
- **Cross-schema SELECT would compound** M4's existing exception. M4
  reads `(id, title, visibility)` from `docs.documents` for citation
  enrichment — a stable, narrow read. M8 would need the full
  `body TEXT` column, broadening the SQL-layer surface. HTTP keeps
  the body fetch behind a controller method that can validate
  `extraction_status='completed'` and the visibility / owner check
  before returning bytes (per ADR-12 §2's invariant for the route).

**Constraints inherited from ADR-12 §2's route specification:**

- **Read-only.** `massing-gen` MUST NOT mutate any `docs` state via
  this route.
- **Internal route prefix (`/internal/**`).** docs BC routes prefixed
  `/internal/` are explicitly **not** exposed through the gateway.
- **User identity propagation IS performed** — distinct from
  Exception 1's "no user identity" rule. M8 forwards `X-User-Id` and
  `X-User-Sub` from the inbound chat-dispatched request to the
  outbound docs body fetch (mirrors Exception 4's identity-forwarding
  rule from ADR-08 §A08.8). The docs-api controller
  `InternalDocumentController.getBody(...)` accepts these headers
  even though Exception 1's original contract did not forward them —
  ADR-12 §2's body-fetch handler does not enforce visibility today
  (it was designed for ingestion bookkeeping), but the route is
  user-private by virtue of `massing-gen-app` performing an
  authorization check on the result (does the brief belong to the
  caller? — if `docs.documents.visibility = 'private'` and
  `owner_user_id != X-User-Id`, the brief is rejected). The
  authoritative tenant-isolation lives in the **caller** (`massing-gen`),
  not the called route.

  > **Note:** the docs body endpoint at `/internal/docs/public/{id}/body`
  > does NOT filter by visibility today. ADR-12 §2 framed it as
  > ingestion-bookkeeping (returns body regardless of visibility, because
  > rag-ingestion needed both public and private bodies for chunking).
  > For M8, the visibility/ownership check lives at the body-DTO read
  > site in `BriefProgramExtractor` — if `documents.visibility = private
  > AND owner_user_id != caller_user_id`, reject with
  > `BRIEF_NOT_ACCESSIBLE`. This keeps docs-api zero-change for M8.

- **`extraction_status = 'completed'` pre-check.** Brief docs with
  `extraction_status = 'processing'` or `'failed'` cause M8 to return
  `BRIEF_NOT_READY` (HTTP 422 — see §7); the body endpoint
  returns the empty-string `body` for `processing` rows (ADR-12
  §A12.6) so the extractor naturally sees an empty body and the
  M8-side check fires. The check is at the `GenerateMassingUseCase`
  level: query the docs metadata route
  `GET /internal/docs/public/{id}` (also in ADR-12 §2) first to read
  `extraction_status`; only proceed to body-fetch if `completed`.
- **Reliability discipline.** WebClient timeout 5 s, up to 3 retries
  with exponential backoff (mirrors ADR-12 §2 + ADR-13 §2's discipline
  verbatim — 200/400/800 ms base, jitter 0.5). Permanent failure
  → `SIDECAR_FAILED` no — rather, body-fetch failure maps to
  `BRIEF_FETCH_FAILED` (HTTP 502 — see §7).

**Implementation sketch:**

```java
// massing-gen-app/BriefBodyPort.java
public interface BriefBodyPort {
    Mono<BriefBody> fetchBriefBody(UUID briefDocId, UserId user);
}

public record BriefBody(
    UUID id,
    String markdown,
    String extractionStatus,
    Visibility visibility,
    UserId ownerUserId
) {}

// massing-gen-infra/HttpBriefBodyAdapter.java
@Component
public class HttpBriefBodyAdapter implements BriefBodyPort {
    private final WebClient docsClient;  // baseUrl = http://docs-api:18082

    @Override
    public Mono<BriefBody> fetchBriefBody(UUID briefDocId, UserId user) {
        return fetchMetadata(briefDocId, user)
            .flatMap(meta -> {
                if (!"completed".equals(meta.extractionStatus())) {
                    return Mono.error(new MassingException(BRIEF_NOT_READY));
                }
                if (meta.visibility() == PRIVATE && !meta.ownerUserId().equals(user)) {
                    return Mono.error(new MassingException(BRIEF_NOT_ACCESSIBLE));
                }
                return fetchBody(briefDocId, user)
                    .map(md -> new BriefBody(briefDocId, md, "completed",
                                              meta.visibility(), meta.ownerUserId()));
            });
    }
    // ... fetchMetadata, fetchBody with X-User-Id + X-User-Sub headers
}
```

### 4. Q-C — LLM call form: **Spring AI 1.0.0 GA `ChatClient`** (uniformity with M4/M6/M7)

**Decision: `BriefProgramExtractor` is implemented as
`SpringAiBriefExtractorAdapter` in `massing-gen-infra`, using Spring AI
1.0.0 GA's `ChatClient.prompt(...).user(...).call().content()`
shape (synchronous — non-streaming; the extractor needs the full JSON
response before validation). The Spring AI BOM coordinate is reused
from ADR-04 / ADR-14 / ADR-17 — no version bump. The `spring-gateway`
Resilience4j breaker from ADR-14 §4 is **reused**; M8 does NOT register
a new breaker for this LLM call.**

Closes PRD open question **Q-C** ("LLM call form — Spring AI ChatClient
vs direct WebClient").

| Concern | Pin |
|---|---|
| Spring AI BOM | **`org.springframework.ai:spring-ai-bom:1.0.0`** (reused — no bump) |
| Starter | `spring-ai-openai-spring-boot-starter` (reused) |
| Call shape | **`chatClient.prompt().system(systemPrompt).user(briefBody).call().content()`** — synchronous (`.call()`, not `.stream()`). The extractor blocks on a single LLM round-trip; no need to stream extraction. |
| Model | **`Qwen3-32B`** via spark-inference-gateway (reused from ADR-04). `application.yml`: `spring.ai.openai.chat.options.model: ${PLAYGROUND_MASSING_LLM_MODEL:Qwen3-32B}`. Defaults to the same Qwen3-32B pin as M4 — operator may override if a code-tuned model becomes available. |
| `base-url` | `http://host.docker.internal:10080/v1` (reused from ADR-04 / ADR-14 §B's spark-inference-gateway pin) |
| Temperature | **0.1** (low — extraction must be deterministic). `spring.ai.openai.chat.options.temperature: 0.1`. |
| Max tokens | **3000** (typical 30-room program JSON ≈ 2 KiB ≈ 800 tokens; 3000 gives 3.5× headroom). `spring.ai.openai.chat.options.max-tokens: 3000`. |
| Response format | **Plain text expected to parse as JSON**. M8 does **not** use Spring AI's structured output (`.responseFormat(StructuredOutputConverter.class)`) because the M4/M7 backend has not validated that path; M8 sticks to the call shape M4 already proved. The validator (§9) handles malformed-JSON cases. |
| Resilience4j breaker | **Reused `spark-gateway` breaker from ADR-14 §4** — not a new instance. Rationale: same upstream (spark-inference-gateway), same failure semantic; a sustained 5xx burst on either path means the same gateway is sick, and a unified breaker correctly trips both M4 chat and M8 extraction. |
| Observability | M8 logs `BriefProgramExtractor` invocations at INFO with `briefDocId`, `extractionDurationMs`, `inputBriefBodyChars`, `outputProgramJsonBytes`, `schemaValidationOk`. Micrometer counter `massing_gen_brief_extraction_total{result="ok|schema_invalid|empty|llm_error"}`. |

**Rationale for (a) Spring AI `ChatClient` over (b) direct WebClient:**

- **Uniformity** — M4 / M6 (Vision) / M7 all use Spring AI `ChatClient`.
  Introducing a direct WebClient path in M8 would create a second
  shape that future maintainers must keep in sync (e.g., the
  `OPENAI_API_KEY` placeholder, the `User-Agent`, the response-format
  envelope). One shape is cheaper.
- **Breaker reuse** — A direct WebClient would need its own
  Resilience4j breaker, doubling the burst-isolation surface for a
  call to the same upstream. Spring AI's adapter integrates with the
  shared `spark-gateway` breaker via the ADR-14 §4 wrapper pattern
  (the same wrapper M8 reuses).
- **`massing-gen-app` dependency weight is acceptable** — the
  Spring AI starter adds ~5 MB to the fat jar. M8 is not size-sensitive
  (`rhino3dm-bridge` sidecar dwarfs it in any comparison).
- **Forward compatibility** — if Spring AI 1.x adds first-class
  JSON-Schema-shaped tool output in a minor version, the migration
  is in-place; a direct WebClient path would need rewriting.

**Considered alternative (b):** direct `WebClient` to the
spark-inference-gateway OpenAI-compatible endpoint, hand-rolling
prompt assembly + JSON parsing. Rejected for the uniformity +
breaker-reuse reasons above.

### 5. Q-B — `summary` field i18n policy: **Korean fixed string**

**Decision: `tool_result.result.summary` is a Korean-fixed string
following the pattern `"{rooms}실 · {floors}층 · 총 {area} m²"`. The
LLM does NOT generate the summary — the `GenerateMassingUseCase`
constructs it deterministically from the extraction + algorithm
output.**

Closes PRD open question **Q-B** ("summary i18n — Korean vs English vs
LLM-language-follow").

| Concern | Pin |
|---|---|
| Format string | `"%d실 · %d층 · 총 %.0f m²"` (locale-independent, ASCII-safe punctuation `·`) |
| Example value | `"12실 · 3층 · 총 480 m²"` |
| Generation | Deterministic — `String.format(...)` at the end of `GenerateMassingUseCase` after algorithm completion. No LLM call for the summary itself. |
| Locale handling | Korean-fixed for P0. The playground UI is Korean-primary (M4 chat composer accepts Korean queries, the design system spec is Korean-default). Future M8.1 may add a locale-aware variant if English-locale users surface. |
| Design doc reference | `docs/design/M6-M8-brief-to-massing.md` §2.3 shows `"12 rooms · 3 floors · 480 m² total"` as a reference. **This ADR supersedes that example with the Korean-fixed form.** The design doc carries a small textual amendment (see §11 below) noting that the rendered string is Korean-fixed; the design-token vocabulary is unchanged. |

**Rationale:**

- **User's primary language is Korean.** The chat composer accepts
  Korean turns; the LLM produces Korean prose; the brief PDFs are
  Korean. An English-fixed summary in the middle of Korean
  surrounding text creates a jarring code-switch.
- **LLM-language-follow rejected.** The LLM does not have access
  to the post-algorithm room count / floor count — the algorithm
  runs after the LLM extraction. Routing the summary back through
  another LLM call to translate it doubles the LLM-call count and
  introduces translation drift.
- **English-fixed rejected.** Engineering-norm code/console can stay
  English (`MassingErrorCode` enum names, log strings, JSON field
  names); the **user-visible card label** is Korean.
- **The format is parseable** — `"12실 · 3층 · 총 480 m²"` carries
  the three numbers in stable positions, so future M8.1 work that
  wants to derive analytics from old `chat.messages` rows can regex
  them out if the assistant content ever persists the summary.

The Korean format-string lives as a constant in
`massing-gen-domain.MassingSummary` (Spring-free, pure Java):

```java
public final class MassingSummary {
    private static final String FORMAT = "%d실 · %d층 · 총 %.0f m²";
    public static String format(int rooms, int floors, double totalAreaM2) {
        return String.format(Locale.ROOT, FORMAT, rooms, floors, totalAreaM2);
    }
}
```

### 6. Q-D — frontend M8-specific error code recognition: **`tool_error.message` prefix pattern**

**Decision: M8 BC encodes its domain-specific error code as a
**`<CODE>: <human-readable>`** prefix inside the existing
M7 `tool_error.message` field. The frontend regex-extracts the prefix
to drive the secondary-action label selection. M7 ADR-17's `tool_error`
payload schema is NOT extended; no new `detail.code` field is added.**

Closes PRD open question **Q-D** ("frontend's M8-specific
`BRIEF_EXTRACTION_FAILED` recognition mechanism").

**Wire shape (verbatim from PRD Wire-shape contracts):**

```json
{
  "id": "call_01HZ…",
  "name": "generate_massing",
  "code": "UPSTREAM_4XX",
  "message": "BRIEF_EXTRACTION_FAILED: Could not extract room program from brief — is this a competition brief PDF?"
}
```

| Concern | Pin |
|---|---|
| Prefix grammar | **`<DOMAIN_CODE>: <human-readable>`** — uppercase domain code (matching `MassingErrorCode` enum names), colon-space separator, then the free-form message body. The domain code MUST match `[A-Z_]{1,40}` and the colon-space sequence is mandatory. |
| Frontend parser regex | `/^([A-Z_]{1,40}):\s+(.+)$/` — captures `(code, message_body)`. If no match, the frontend renders the message verbatim with a generic `↻ Retry` secondary action. |
| Secondary action mapping | Frontend maps recognized prefixes to design doc §2.5 actions: `BRIEF_EXTRACTION_FAILED` → `↗ Try a different brief` (links to `/docs/new`); `MASSING_ALGORITHM_FAILED` → `↻ Retry with different inputs`; `BRIEF_NOT_READY` / `BRIEF_NOT_ACCESSIBLE` → `↗ Open brief` (links to `/docs/{briefDocId}`); fallback for `TIMEOUT` / `SIDECAR_FAILED` / `SIDECAR_TIMEOUT` / `INTERNAL` / unknown → `↻ Retry`. |
| Why prefix over `detail.code` | (1) M7 ADR-17 §3.1 invariant says `tool_error` payload schema is exactly `(id, name, code, message)` — the M7 enum (`code`) is wire-level; adding `detail.code` would amend M7 mid-cycle. (2) The prefix grammar is documented here in ADR-18; future tool BCs that adopt it inherit a consistent shape. (3) The message body remains human-readable for any consumer (e.g., audit log) that doesn't parse the prefix. |
| Why prefix over message-only with frontend-name-switching | The frontend would otherwise need to know tool-specific domain codes per tool BC name; the prefix grammar makes the error class machine-readable independent of tool name. |

**Future hook:** if more than 3 tool BCs adopt the prefix grammar and
the frontend mapping table becomes unwieldy, M9+ may amend M7 ADR-17
to add an optional `detail.code` field; M8's wire shape stays compatible
because the prefix is also present in the message body. The two
mechanisms can coexist transitively.

**Why this matters for design doc §2.5:** the design doc enumerates 4
error sub-states (`BRIEF_EXTRACTION_FAILED`, `MASSING_ALGORITHM_FAILED`,
`TIMEOUT`, `TOOL_5XX`); the prefix grammar covers the first two
directly, the latter two map via M7's existing 7-value enum (TIMEOUT /
UPSTREAM_5XX). No new error sub-states are introduced.

### 7. `MassingErrorCode` enum + HTTP status mapping

**Decision:** M8's `MassingErrorCode` enum has **7 values**, mapped to
HTTP status per the table below. The enum lives in `massing-gen-domain`
(Spring-free); the HTTP mapping lives in `massing-gen-api` via the
`@RestControllerAdvice` from ADR-11.

| Code | HTTP | Origin | Message prefix in tool_error.message |
|---|---|---|---|
| `BRIEF_NOT_FOUND` | **404** | brief doc id does not exist in `docs.documents` | `BRIEF_NOT_FOUND: Brief document <id> not found.` |
| `BRIEF_NOT_ACCESSIBLE` | **403** | brief is private and caller is not the owner | `BRIEF_NOT_ACCESSIBLE: You do not have access to this brief.` |
| `BRIEF_NOT_READY` | **422** | brief's `extraction_status` is not `'completed'` (still `processing` or `failed`) | `BRIEF_NOT_READY: Brief is still being analyzed — please wait for extraction to complete.` |
| `BRIEF_EXTRACTION_FAILED` | **422** | LLM produced empty/sparse program, or response failed JSON-Schema validation | `BRIEF_EXTRACTION_FAILED: Could not extract room program from brief — is this a competition brief PDF?` |
| `MASSING_ALGORITHM_FAILED` | **422** | algorithm rejects input (over-area beyond `maxFloors` cap; see §8) | `MASSING_ALGORITHM_FAILED: Room program exceeds maximum buildable volume — narrower site or fewer rooms required.` |
| `SIDECAR_TIMEOUT` | **504** | `rhino3dm-bridge` sidecar exceeded the per-call timeout (working 30 s — see §10) | `SIDECAR_TIMEOUT: .3dm serialization did not complete within 30s.` |
| `SIDECAR_FAILED` | **502** | sidecar returned 5xx, or breaker is OPEN, or connect-refused | `SIDECAR_FAILED: .3dm serialization service is unavailable.` |
| `INTERNAL` | **500** | uncaught (last-resort mapping per ADR-11) | `INTERNAL: An unexpected error occurred.` |

The 4xx cases map to M7's `UPSTREAM_4XX` enum value (per ADR-17 §2's
classification — `WebClientResponseException.4xx` does not trip the
breaker); the 5xx cases map to `UPSTREAM_5XX` (counted against the
per-tool breaker `tool-generate_massing`). The frontend receives the
M7 enum in `tool_error.code` and the M8-specific prefix in
`tool_error.message`.

**Note on 422 vs 400:** the PRD raised both `400` and `422` as
candidates for `BRIEF_EXTRACTION_FAILED`. This ADR pins **422**
(Unprocessable Entity) for the three semantic-level failures
(`BRIEF_NOT_READY`, `BRIEF_EXTRACTION_FAILED`, `MASSING_ALGORITHM_FAILED`)
— the request shape was syntactically valid (`briefDocId` is a UUID,
optional fields are numbers), but the server cannot complete the
operation against the referenced entity. 400 is reserved for malformed
request shape (e.g., `briefDocId` not a UUID). This matches Spring
Boot 3's convention and ADR-11's HTTP-typed exception hierarchy.

### 8. Q-#4 (spec §6) — MassingAlgorithm `maxFloors` + over-area policy: **throw, default cap 10**

**Decision: the `MassingAlgorithm.compute(Program, int maxFloors)`
method throws `MassingException(MASSING_ALGORITHM_FAILED)` when
`ceil(totalAreaM2 / siteAreaM2) > maxFloors`. The use-case calls
`compute(...)` with `maxFloors = 10` as the default. Auto-adjust
(silently bumping floor count) and auto-scale (silently shrinking
rooms) are **rejected** — the architect's mental model of "this brief
needs N floors" is preserved by failing loudly.**

Closes PRD/spec open question **#4** ("MassingAlgorithm tolerance —
over-area handling").

| Concern | Pin |
|---|---|
| Default `maxFloors` | **10** — covers >99% of plausible competition briefs (>10-floor briefs are typically high-rise programs outside M8's P0 scope). |
| Env override | `PLAYGROUND_MASSING_MAX_FLOORS` → `application.yml` `playground.massing.max-floors: 10`. Bound to a `MassingProperties` `@ConfigurationProperties` POJO in `massing-gen-app`. |
| Over-floor behaviour | Throw `MassingException(MASSING_ALGORITHM_FAILED)` with message `"Room program exceeds maximum buildable volume — narrower site or fewer rooms required"`. Mapped to HTTP 422 per §7. |
| Floor-count formula | `floorCount = (int) Math.ceil(totalAreaM2 / siteAreaM2)`. Then `if (floorCount > maxFloors) throw ...`. |
| Per-floor packing | First-fit + area balance per spec §6 working. Sort rooms by area descending; iterate floors round-robin while a floor has remaining area; pack rectangles greedily inside the site footprint. The algorithm interface is `compute(Program, int maxFloors): List<RoomBox>`; the rectangular-first-fit implementation is one concrete strategy, callable via the interface so future M8.1 strategies can swap in without changing the use-case. |
| Site-footprint defaults | If brief extraction did not yield site dimensions AND the request did not specify `siteWidth`/`siteDepth`, the use-case falls back to **`20.0 × 10.0` (m × m)** before invoking the algorithm. This is the spec §6 working default. PRD §Story 1 acceptance bullet says the descriptor's parameter schema describes the defaults; the actual fallback lives here. |
| Floor height default | If brief extraction did not yield floor height AND the request did not specify `floorHeight`, use **`3.5 m`** (matches descriptor's `default: 3.5` in §6 below). |

**Considered alternatives:**

- **Auto-adjust (silently bump floor count):** rejected. Loses the
  signal that the brief is over-scope; the user gets a 12-floor
  building back without realizing they over-specified.
- **Auto-scale (shrink rooms proportionally):** rejected. Violates
  the program's design intent (the architect specified rooms by
  name + required area; shrinking them is a creative decision the
  algorithm has no authority to make).
- **Soft warning (return result with a flag):** considered but
  rejected — the SSE `tool_result` payload has no warning channel,
  and adding one would amend M7 ADR-17 §3 mid-cycle for one case.

### 9. Q-#3 (spec §6) — `programJson` JSON Schema + validator library

**Decision: the LLM's structured output is validated against a JSON
Schema (`/programJson.schema.json`) loaded from
`massing-gen-app/src/main/resources/schemas/programJson.schema.json`
at adapter construction time. The validator is **`com.networknt:json-schema-validator:1.5.3`**
— Jackson-native, depends on the same Jackson version Spring Boot 3.3
ships, no transitive dependency conflicts.**

Closes PRD/spec open question **#3** ("Output JSON Schema for program").

| Concern | Pin |
|---|---|
| Validator library | **`com.networknt:json-schema-validator:1.5.3`** (latest 1.5.x at ADR ship; Jackson-native) |
| Why networknt over everit-json-schema | networknt is Jackson-native (no separate `org.json` dependency, which everit pulls in); networknt is more actively maintained (everit-json-schema is now community-maintained); networknt supports JSON Schema draft 2020-12. Both libraries are MIT/Apache-licensed. |
| Schema draft | **JSON Schema Draft 2020-12** — the latest stable spec. |
| Schema location | `massing-gen-app/src/main/resources/schemas/programJson.schema.json` |
| Schema content (P0) | See below |

**P0 `programJson.schema.json`:**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://playground.jeeklee.dev/schemas/programJson.schema.json",
  "title": "ExtractedProgram",
  "type": "object",
  "required": ["rooms"],
  "additionalProperties": false,
  "properties": {
    "rooms": {
      "type": "array",
      "minItems": 1,
      "maxItems": 100,
      "items": {
        "type": "object",
        "required": ["name", "areaM2"],
        "additionalProperties": false,
        "properties": {
          "name": {
            "type": "string",
            "minLength": 1,
            "maxLength": 80,
            "description": "Room name as extracted from the brief (Korean primary, e.g., '강의실 1')."
          },
          "areaM2": {
            "type": "number",
            "exclusiveMinimum": 0,
            "maximum": 10000,
            "description": "Required area in square meters."
          }
        }
      }
    },
    "siteWidthM": {
      "type": "number",
      "exclusiveMinimum": 0,
      "maximum": 1000,
      "description": "Site width in meters. Optional — if absent, falls back to request param or 20.0."
    },
    "siteDepthM": {
      "type": "number",
      "exclusiveMinimum": 0,
      "maximum": 1000,
      "description": "Site depth in meters. Optional — if absent, falls back to request param or 10.0."
    },
    "floorHeightM": {
      "type": "number",
      "exclusiveMinimum": 0,
      "maximum": 20,
      "description": "Floor-to-floor height in meters. Optional — if absent, falls back to request param or 3.5."
    }
  }
}
```

- `rooms` is the only required field. `siteWidthM` / `siteDepthM` /
  `floorHeightM` are optional and fall through to request params
  then to numerical defaults (§8 above).
- `additionalProperties: false` is strict — the LLM is not allowed to
  invent extra fields; if it does, validation fails and the result
  flips to `BRIEF_EXTRACTION_FAILED`. This is intentional — drift in
  the LLM's output shape is an early-warning signal for prompt
  template instability.
- Future extensions (e.g., `heightLimitM`, `farTarget`, `roomType`,
  `requiredFloor`) land via schema-version bump (`$id` versioning;
  the schema gains a new versioned file, the old file is kept for
  audit; M8.1 scope).

**LLM prompt template** — see §10 below.

### 10. Q-#2 (spec §6) — Korean brief-extraction prompt template

**Decision: the system prompt is a Korean-language extraction
instruction targeting Qwen3-32B. The full prompt template (system +
user-template + few-shot) lives in
`massing-gen-app/src/main/resources/prompts/brief-extraction.system.txt`
+ `.user.txt`. A 3-shot fixture set lives in
`prompts/brief-extraction.fewshot.json` and is concatenated at runtime.
The exact prompt text is left to Stage-3 implementer evolution; this
ADR pins the structural envelope and the few-shot count.**

Closes PRD/spec open question **#2** ("Korean brief-extraction prompt
template").

| Concern | Pin |
|---|---|
| Language | Korean (system + user-template); few-shot examples are Korean briefs → JSON pairs |
| System prompt skeleton | "당신은 한국 건축 공모전 지침서(brief)에서 실 프로그램과 대지 정보를 JSON으로 추출하는 도우미입니다. 반드시 아래 JSON Schema를 만족하는 JSON만 출력하세요. 추가 설명, 마크다운, 코드펜스(```)를 출력하지 마세요. <SCHEMA>{programJson.schema.json}</SCHEMA>" — schema body inlined verbatim. |
| User-template skeleton | "<BRIEF>{briefBody}</BRIEF>" — single template variable `{briefBody}` filled with the markdown body fetched from docs-api. Brief bodies are typically 5–30 KiB; Qwen3-32B's 32 K-token context handles them with room for the schema + few-shot. |
| Few-shot count | **3** (curated by the human reviewer during Stage-3 prompt-engineering work). Each shot is `{ "brief": "<truncated Korean brief excerpt>", "output": "<programJson>" }`. |
| Where the few-shot lives | `massing-gen-app/src/main/resources/prompts/brief-extraction.fewshot.json` (list of 3 shot objects). Loaded at adapter construction; concatenated as alternating assistant/user messages before the actual user message per Spring AI's `ChatClient.prompt()` builder API. |
| Prompt versioning | Files are version-stamped in their header comment (`# v1`, `# v2`, ...). Bumps land via PR; no runtime hot-reload. M8.1 may add a `prompt-version` SSE telemetry field if A/B-testing prompts becomes useful. |
| Failure semantic | If the LLM outputs non-JSON (e.g., prose explanation despite the prompt instructing JSON-only), the parser throws → `BRIEF_EXTRACTION_FAILED`. The validator (§9) catches schema-violating but parseable JSON; the parser catches non-JSON. |

**Implementer note:** the prompt text in the resources files is
intentionally **not pinned in this ADR** — prompt tuning is iterative,
and pinning the exact wording in an ADR would make every prompt
adjustment require an ADR amendment. The implementer (Stage-3) owns
the prompt evolution, subject to the schema-shape and few-shot-count
constraints pinned here.

### 11. Q-#1 + sidecar contract — `rhino3dm-bridge` Node sidecar + box-coordinate convention

**Decision: M8 adopts a **Node 18-alpine + `rhino3dm` npm package**
sidecar named `rhino3dm-bridge`. The sidecar exposes a single endpoint
`POST /serialize` taking a JSON box list and returning
`application/octet-stream` `.3dm` bytes. The npm package pin is
**`rhino3dm@8.4.0`** (latest 8.x at ADR ship; aligned with
Rhinoceros 8's OpenNURBS).**

Closes PRD/spec open question **#1** (`.3dm` library — Node sidecar vs
OpenNURBS C JNI vs Python rhino3dm) and the sidecar contract details.

| Concern | Pin |
|---|---|
| Sidecar image base | **`node:18-alpine`** |
| npm package | **`rhino3dm@8.4.0`** |
| Dockerfile location | `infra/sidecars/rhino3dm-bridge/Dockerfile` |
| Compose service name | `rhino3dm-bridge` |
| Compose-internal port | **`4000`** (HTTP) |
| Host-exposed? | **No** (compose-internal only) |
| HTTP framework | Node's `http` built-in or a thin layer like `fastify@4.x` — implementer's choice; the sidecar has 1 endpoint and no business logic. |
| Resource limits | `mem_limit: 512m`, `cpus: 1.0` (sidecar is single-threaded Node; one request at a time per chat turn). |
| Health check | `wget -q -O- http://localhost:4000/health || exit 1` against a `/health` endpoint returning `{ "ok": true }`. |

**Sidecar HTTP contract:**

```
POST http://rhino3dm-bridge:4000/serialize
Content-Type: application/json

{
  "boxes": [
    {
      "x": 0.0, "y": 0.0, "z": 0.0,
      "width": 8.0, "depth": 6.0, "height": 3.5,
      "roomName": "로비 (Lobby)",
      "floor": 1
    },
    ...
  ]
}
```

Response (200):
```
Content-Type: application/octet-stream
Body: <binary .3dm bytes>
```

Response (4xx/5xx):
```
Content-Type: application/json
Body: { "code": "<error code>", "message": "<human-readable>" }
```

**Coordinate / metadata convention:**

| Field | Convention |
|---|---|
| `x, y` | Lower-left corner of the box's footprint, measured from the **site footprint's lower-left corner (origin = `(0, 0)`)**. Units: meters. |
| `z` | Lower face of the box. **`z = (floor - 1) * floorHeight`** (floor 1's lower face at 0). Units: meters. |
| `width` | Box extent along X axis. Meters. |
| `depth` | Box extent along Y axis. Meters. |
| `height` | Box extent along Z axis. **Equal to `floorHeight`** for every room (single-floor-height M8 P0 invariant). Meters. |
| `roomName` | Becomes the **Rhino layer name** for the box (one box = one layer). Korean room names are preserved as UTF-8; Rhino 7+ handles Unicode layer names. |
| `floor` | Becomes a Rhino **user-text attribute** (`box.UserData.SetString("floor", floor.toString())`) so downstream Grasshopper / scripts can filter by floor. |

**Algorithm-level invariants (verified by sidecar + algorithm tests):**

- All box `x + width ≤ siteWidth` and `y + depth ≤ siteDepth`
  (no box exceeds the site footprint).
- No two boxes on the same floor overlap (rectangle-overlap test in
  algorithm-level unit test).
- `floor` ∈ [1, floorCount].

**Considered alternatives:**

- **OpenNURBS C JNI:** rejected per spec §6 — JNI integration cost
  (custom toolchain in Gradle, native-library bundling per OS) is
  disproportionate to the M8 P0 scope.
- **`rhino3dm.py` Python sidecar:** would work; rejected only because
  the playground stack has zero Python services today. Adding Python
  for one sidecar adds an out-of-band toolchain to operate; Node is
  closer to the existing JS/TS frontend toolchain. (The implementer
  has experience with both; Node is the smaller activation energy.)

### 12. Q-#5 (spec §6) — file storage: **BYTEA in `arch.outputs.file_bytes`**

**Decision: M8 stores the `.3dm` binary in **Postgres BYTEA** in the
`arch.outputs.file_bytes` column. MinIO (the docs-originals sidecar
introduced by M6.1 per ADR-12 §A12.4 + ADR-08 §A08.3) is **NOT**
used for `.3dm` files in M8 P0.**

Closes PRD/spec open question **#5** ("File storage — BYTEA vs MinIO").

| Concern | Pin |
|---|---|
| Storage | **BYTEA** in `arch.outputs.file_bytes` |
| Typical `.3dm` size | 5–50 KiB for a 10–30-room massing (rectangular boxes with no surface detail). 10× headroom for outlier briefs is ~500 KiB. |
| Postgres TOAST ceiling | TOAST handles BYTEA columns up to ~1 GiB per row; M8's typical 5–50 KiB rows are far below the ceiling. |
| Migration path to MinIO | If `arch.outputs` corpus grows to >10 K rows (operator-visible signal in M5 dashboards), M8.1 introduces an `arch.outputs.minio_key` column alongside `file_bytes`, populates it for new writes, lazily migrates old rows, then drops `file_bytes`. The migration is operationally identical to the M6.1 docs-originals → MinIO migration except the docs corpus is much larger and was MinIO-from-day-one. |

**Rationale for BYTEA over MinIO in M8 P0:**

- **Storage volume is small.** Each `.3dm` is ~5–50 KiB; a busy month
  of 100 generations = 5 MiB total. Personal-scale; Postgres handles
  it without backpressure.
- **Operational simplicity.** MinIO requires a bucket-ownership policy
  (per ADR-08 §A08.3 — `playground-docs-originals` is owned by
  docs-api only; another BC needs another bucket or sidecar). Adding
  `playground-arch-outputs` would mean either (a) a second bucket
  on the same MinIO with new permission rules, or (b) a second MinIO
  sidecar entirely. Both add ops surface for a 50 KiB-per-row file.
- **Transactional consistency.** The `arch.outputs` row INSERT + file
  bytes write are atomic in a single Postgres transaction. With MinIO
  the put would be a separate write whose failure case (DB row
  committed, MinIO put failed) requires cleanup (orphan compaction
  job — the same nightly `@Scheduled` pattern docs-api uses for its
  MinIO objects, per ADR-12 amendment). For a 50 KiB file the
  complexity is disproportionate.
- **Download streaming is fine from BYTEA.** Postgres BYTEA reads are
  buffered into the JVM heap, but at 50 KiB per row the heap pressure
  is negligible. The download controller (§13) streams `byte[]` into
  the `HttpServletResponse` output stream.
- **M6.1 chose MinIO for docs-originals** because (1) PDFs are
  10 MiB-class blobs, (2) docs-originals corpus is the user's
  document corpus (could be 1000s of files), (3) the extracted
  Markdown lives in Postgres separately. M8's calculus is the
  opposite — small file, small expected volume.

**Considered alternative:** MinIO from day 1. Rejected as premature
optimization; the trigger for migration (corpus growth) is operator-
observable and the migration path is documented (above) — no
information is lost by deferring.

### 13. Q-#6 (spec §6) — orphan cleanup policy: **untouched (P0)**

**Decision: `arch.outputs` rows are **untouched** when the referenced
`docs.documents` row is deleted. The `brief_doc_id` column becomes a
**dangling app-level FK** (matches M4's `chat.message_citations.document_id`
treatment per ADR-14 §3). The generated `.3dm` outlives the source
brief; the architect's previously-downloaded files remain valid.**

Closes PRD/spec open question **#6** ("Orphan cleanup policy").

| Concern | Pin |
|---|---|
| Cascade DELETE | **No** — Postgres FK from `arch.outputs.brief_doc_id` to `docs.documents.id` is **not** declared (different schemas; cross-schema FK is allowed in Postgres but rejected here for schema-isolation invariant per ADR-05). The column is app-level FK only. |
| Cascade SET NULL | **No** — would lose audit traceability ("which brief did this `.3dm` come from?") without operator benefit. |
| Soft-delete (`deleted_at` column) | **No** for M8 P0 — adds a column for a use case that hasn't materialized. |
| Untouched (chosen) | The `.3dm` row and its bytes persist; the dangling `brief_doc_id` points at a non-existent docs row. Frontend rendering of "this massing came from brief X" gracefully degrades to "brief no longer available" if the row is missing. |
| Future hook | If corpus growth + storage pressure surface, M8.1 introduces an `arch.outputs.expires_at TIMESTAMPTZ NULL` column + a nightly `@Scheduled` cleanup job (matches ADR-12 amendment's nightly orphan pattern). M8 P0 does not introduce that machinery — the column would be added, defaulted to NULL, ignored until the cleanup job lands. |

**Rationale:**

- **The architect's downloaded `.3dm` is the canonical artifact.**
  Once on disk, the file is independent of the playground BC entirely.
  The `arch.outputs` row is server-side audit trail; deleting it
  retroactively does not affect the user's local file.
- **The brief is a starting point, not a permanent reference.**
  Architects commonly delete competition briefs after the competition
  closes; their massings remain valid as design kickoff geometry.
- **The dangling-FK pattern is already in the codebase** via M4's
  `chat.message_citations` (ADR-14 §3 — "stale citation rendering").
  Reusing the pattern keeps mental model consistency.

### 14. Q-#7 (spec §6) — BC name: **`massing-gen`** (final)

**Decision: BC name is **`massing-gen`** — verbatim from the spec §6
working value, confirmed.**

Closes PRD/spec open question **#7** ("BC naming — `massing-gen` vs
`arch-massing` vs `massing`").

| Concern | Pin |
|---|---|
| BC name | `massing-gen` |
| Module names | `massing-gen-{api,app,domain,infra}` |
| Java package | `dev.jeeklee.playground.massinggen.*` (drops the hyphen for Java package convention — Java packages cannot contain hyphens). The pattern `massinggen` (joined word, all lowercase) mirrors `ragchat` for `rag-chat` and `ragingestion` (pre-M6.1) for `rag-ingestion`. |
| Compose service name | `massing-gen-api` (hyphenated; compose service names allow hyphens). |

**Rationale:**

- **Functional-prefix consistency** — the playground already has
  `rag-ingestion` (pre-M6.1) and `rag-chat` as functional-prefix BCs.
  `massing-gen` follows the same convention (action-prefix).
- **`arch-massing` (domain-prefix) rejected** — would introduce a new
  naming pattern for an alleged "arch" domain that has exactly one
  BC; the pattern is not currently load-bearing and creates
  inconsistency.
- **`massing` (short) rejected** — ambiguous about the BC's
  responsibility (is it a noun-class container BC that holds masses,
  or a verb-class generator?); the `-gen` suffix disambiguates.
- **Forward compatibility** — future tool BCs (`slide-gen`,
  `image-gen`, `plan-gen`) all naturally take the same `-gen` suffix.

### 15. Q-#8 (spec §6) — descriptor parameter granularity: **minimal (4 params)**

**Decision: the `MassingTool` `ToolDescriptor`'s `parameterSchema`
exposes exactly **4 properties** (`briefDocId`, `siteWidth`,
`siteDepth`, `floorHeight`) — the minimal set the spec §6 +
PRD Story 1 already enumerated. Additional knobs
(`heightLimit`, `farTarget`, `coreLocation`, `requiredFloor`) are
**deferred to M8.1**.**

Closes PRD/spec open question **#8** ("Tool descriptor parameter
granularity — minimal vs richer").

| Concern | Pin |
|---|---|
| Required | `briefDocId` (UUID) |
| Optional | `siteWidth` (number), `siteDepth` (number), `floorHeight` (number; default `3.5`) |
| `parameterSchema` JSON | See descriptor declaration in §6 below |
| Additional knobs in M8.1 | `heightLimit`, `farTarget`, `coreLocation`, `requiredFloor` (per-room) — designed but deferred. Each new optional field is one schema property addition + one `Program` VO field; no breaking change. |

**Rationale:**

- **LLM context inflation.** Every additional descriptor property is
  text the LLM must reason about per turn. At 4 params the LLM
  reliably chooses correct argument shapes; at 10+ params the LLM
  starts inventing values for fields the user did not specify
  (observed in M4 prompt-engineering during ADR-14).
- **User-confusion cost.** Architects ask the chat in natural
  language; the descriptor's `description` plus the parameter set
  determines what natural-language prompts the LLM accepts. A wide
  parameter set creates a learnability curve.
- **Future expansion is non-breaking.** Adding optional properties
  later does not invalidate descriptors that don't use them; M8.1
  growth is plain-additive.

### 16. Q-#9 (spec §6) — per-user rate limit: **none (chat-level covers)**

**Decision: no per-tool / per-user rate limit on M8. The M4
chat-level token bucket (60/hour + 200/day per ADR-14 §5) is the
sole operator protection — a massing-gen call costs exactly one
LLM round-trip + one sidecar round-trip ≈ one chat turn's resource
cost.**

Closes PRD/spec open question **#9** ("Per-user rate limit on
massing-gen — chat-level vs additional").

| Concern | Pin |
|---|---|
| Per-user massing-gen quota | **None** (P0) |
| Per-user chat token bucket | Unchanged — counts massing-gen turns as 1 turn each (ADR-17 §7 invariant). |
| Per-tool circuit breaker | `tool-generate_massing` per ADR-17 §5 — protects against burst failures, not cost. |
| Per-sidecar circuit breaker | `rhino3dm-bridge` (NEW for M8 — see §17) — protects against sidecar OOM / hangs. |
| Future hook | If single-user massing-gen call rate becomes a cost driver (spark-inference-gateway GPU saturation observed on M5 dashboards), M8.1 introduces a Redisson `RRateLimiter` keyed `massing-gen:user:<userId>:rate` with a per-user/hour cap. The hook is operator-observable on the M5 metrics dashboard. |

**Rationale:**

- **LLM call is the dominant cost.** A massing-gen turn does
  1 chat-level LLM call (M4) + 1 extraction LLM call (M8) — 2× LLM
  cost per turn. Chat token bucket counts the turn once; this is
  effectively a 2× under-count. At personal scale this is
  acceptable (60/hour = 60 turns/hour = 120 LLM calls/hour, well
  within spark-inference-gateway capacity).
- **Premature optimization risk.** Adding a per-tool rate limit
  requires Redisson key partitioning per descriptor + ADR
  amendment per tool BC; for one tool BC the operational
  overhead exceeds the cost-protection gain.
- **The breaker is the burst protection.** A burst of 10
  massing-gen 5xx in 60s trips `tool-generate_massing` OPEN
  per ADR-17 §5; subsequent calls fail fast without burning
  LLM cost or sidecar cycles.

### 17. Per-sidecar circuit breaker (`rhino3dm-bridge`)

**Decision: `massing-gen-infra` registers a new Resilience4j
`CircuitBreaker` named **`rhino3dm-bridge`** wrapping the
`Rhino3dmAdapter`'s WebClient calls. Thresholds mirror ADR-14 §4's
`spark-gateway` breaker verbatim (50% over 60 s sliding window,
minimum 10 calls, 30 s OPEN, 1 half-open probe).**

Mirrors PRD Story 10's resilience invariant.

| Concern | Pin |
|---|---|
| Breaker name | **`rhino3dm-bridge`** |
| Library | Reused `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` + `resilience4j-reactor:2.2.0` (no version bump; reused from ADR-14 / ADR-17) |
| Failure-rate threshold | 50% |
| Sliding window | 60 s |
| Minimum calls | 10 |
| OPEN duration | 30 s |
| Permitted calls in HALF_OPEN | 1 |
| Exception classification | `WebClientResponseException.5xx`, `IOException`, `TimeoutException` count as failure. 4xx does NOT (per ADR-14 §4 invariant). |
| Sidecar HTTP timeout | **30 s** (per-WebClient timeout; tighter than the breaker's call window). Configurable via `PLAYGROUND_RHINO3DM_BRIDGE_TIMEOUT_MS`. |
| Tool-level breaker (`tool-generate_massing`) | Separate — registered by ADR-17 §5's per-descriptor mechanism (M7 invariant). Sidecar burst flips `rhino3dm-bridge` OPEN; tool-level burst (LLM extraction failures, body-fetch failures, etc.) flip `tool-generate_massing` OPEN. The two are **independent**. |
| Metric exposure | Micrometer: `resilience4j_circuitbreaker_state{name="rhino3dm-bridge"}`, `resilience4j_circuitbreaker_calls{name="rhino3dm-bridge",kind="..."}`. Visible in M5 dashboards. |

### 18. `arch.outputs` table — full DDL

**Decision: the `arch.outputs` table (and the `arch` schema) is
created by a Flyway migration owned by `massing-gen-infra` at
`backend/massing-gen/massing-gen-infra/src/main/resources/db/migration/V202605230001__arch_outputs.sql`.**

```sql
-- V202605230001__arch_outputs.sql

CREATE SCHEMA IF NOT EXISTS arch;

CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK, no constraint — see §13)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK, no constraint)
    file_bytes      BYTEA        NOT NULL,                  -- the .3dm binary (per §12)
    program_json    JSONB        NOT NULL,                  -- the extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    summary         TEXT         NOT NULL,                  -- Korean-fixed (per §5)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_arch_outputs_user
    ON arch.outputs (user_id, created_at DESC);

CREATE INDEX idx_arch_outputs_brief
    ON arch.outputs (brief_doc_id);

COMMENT ON TABLE arch.outputs IS
    'Generated .3dm massing outputs from massing-gen BC. Owner-tagged; .3dm bytes stored inline as BYTEA per ADR-18 §12.';
COMMENT ON COLUMN arch.outputs.brief_doc_id IS
    'App-level FK to docs.documents.id. May dangle after brief deletion (ADR-18 §13).';
COMMENT ON COLUMN arch.outputs.user_id IS
    'App-level FK to identity.users.id. Forwarded from X-User-Id header (ADR-08 §A08.11).';
COMMENT ON COLUMN arch.outputs.summary IS
    'Korean-fixed format: "{rooms}실 · {floors}층 · 총 {area} m²" (ADR-18 §5).';
```

| Concern | Pin |
|---|---|
| Schema | `arch` (NEW — see §A05.5) |
| Flyway naming | `V202605230001__arch_outputs.sql` (matches `V<yyyyMMddHHmm>__<snake>.sql` per ADR-05) |
| Migration history table | `flyway_arch` (per-schema history per ADR-05) |
| Hikari `search_path` | `SET search_path TO arch, public` (per-BC; `massing-gen-api` reads docs metadata via HTTP, not SQL — no cross-schema search_path) |
| Hibernate `default_schema` | `arch` |
| Connection pool | `maximum-pool-size: 5` (ADR-05 default; M8 is low-frequency) |

### 19. `application.yml` configuration (`massing-gen-api`)

```yaml
# backend/massing-gen/massing-gen-api/src/main/resources/application.yml
server:
  port: ${SERVER_PORT:18083}            # §2

spring:
  application:
    name: massing-gen-api

  datasource:
    url: jdbc:postgresql://postgres-playground:5432/playground
    username: ${POSTGRES_USER:playground}
    password: ${POSTGRES_PASSWORD:playground}
    hikari:
      maximum-pool-size: 5
      pool-name: massing-gen-pool
      connection-init-sql: "SET search_path TO arch, public"
  jpa:
    properties:
      hibernate:
        default_schema: arch
        dialect: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    schemas: arch
    table: flyway_arch
    locations: classpath:db/migration

  ai:
    openai:
      base-url: ${SPRING_AI_OPENAI_BASE_URL:http://host.docker.internal:10080/v1}
      api-key: ${SPRING_AI_OPENAI_API_KEY:dummy}
      chat:
        options:
          model: ${PLAYGROUND_MASSING_LLM_MODEL:Qwen3-32B}
          temperature: 0.1
          max-tokens: 3000

playground:
  massing:
    max-floors: ${PLAYGROUND_MASSING_MAX_FLOORS:10}            # §8
    rhino3dm-bridge:
      url: ${PLAYGROUND_RHINO3DM_BRIDGE_URL:http://rhino3dm-bridge:4000}
      timeout-ms: ${PLAYGROUND_RHINO3DM_BRIDGE_TIMEOUT_MS:30000}   # §17
    docs-api:
      url: ${PLAYGROUND_DOCS_API_URL:http://docs-api:18082}
      body-fetch-timeout-ms: ${PLAYGROUND_DOCS_BODY_FETCH_TIMEOUT_MS:5000}   # §3

resilience4j:
  circuitbreaker:
    instances:
      rhino3dm-bridge:                                          # §17
        failure-rate-threshold: 50
        sliding-window-size: 60
        sliding-window-type: TIME_BASED
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 1
        automatic-transition-from-open-to-half-open-enabled: true
      spark-gateway:                                            # reused from ADR-14 §4 — same thresholds
        failure-rate-threshold: 50
        sliding-window-size: 60
        sliding-window-type: TIME_BASED
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 1
```

Env-var summary (per the M5 dashboard's BC-config view):

| Env var | Default | Origin |
|---|---|---|
| `SERVER_PORT` | `18083` | §2 |
| `PLAYGROUND_MASSING_LLM_MODEL` | `Qwen3-32B` | §4 |
| `PLAYGROUND_MASSING_MAX_FLOORS` | `10` | §8 |
| `PLAYGROUND_RHINO3DM_BRIDGE_URL` | `http://rhino3dm-bridge:4000` | §11 |
| `PLAYGROUND_RHINO3DM_BRIDGE_TIMEOUT_MS` | `30000` | §17 |
| `PLAYGROUND_DOCS_API_URL` | `http://docs-api:18082` | §3 |
| `PLAYGROUND_DOCS_BODY_FETCH_TIMEOUT_MS` | `5000` | §3 |
| `SPRING_AI_OPENAI_BASE_URL` | `http://host.docker.internal:10080/v1` | ADR-04 |
| `SPRING_AI_OPENAI_API_KEY` | `dummy` | ADR-04 |

### 20. `MassingTool` descriptor (the `ToolCatalog` registration)

**Decision: the `MassingTool` class lives at
`backend/rag-chat/rag-chat-domain/src/main/java/dev/jeeklee/playground/ragchat/domain/tool/MassingTool.java`.
The class is owned by the M8 PR set (its addition is the one
rag-chat-domain file the M8 PR set touches). `ToolCatalog.descriptors()`
references it via a single static field.**

```java
package dev.jeeklee.playground.ragchat.domain.tool;

import java.net.URI;
import java.time.Duration;

public final class MassingTool {

    public static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
        "generate_massing",
        "Given a brief document ID, extract the room program and generate a basic stacked massing model. Returns a Rhino .3dm file URL and a one-line Korean summary.",
        """
        {
          "type": "object",
          "required": ["briefDocId"],
          "additionalProperties": false,
          "properties": {
            "briefDocId": {
              "type": "string",
              "format": "uuid",
              "description": "ID of the brief document already uploaded to /docs."
            },
            "siteWidth": {
              "type": "number",
              "exclusiveMinimum": 0,
              "description": "Site width in meters. Optional — defaults to value extracted from brief, or 20."
            },
            "siteDepth": {
              "type": "number",
              "exclusiveMinimum": 0,
              "description": "Site depth in meters. Optional — defaults to value extracted from brief, or 10."
            },
            "floorHeight": {
              "type": "number",
              "exclusiveMinimum": 0,
              "default": 3.5,
              "description": "Floor-to-floor height in meters."
            }
          }
        }
        """,
        URI.create("http://massing-gen-api:18083/internal/tools/generate-massing"),
        Duration.ofSeconds(60)
    );

    private MassingTool() {}
}
```

`ToolCatalog.descriptors()` becomes:

```java
public List<ToolDescriptor> descriptors() {
    return List.of(MassingTool.DESCRIPTOR);
}
```

| Concern | Pin |
|---|---|
| `name` | `generate_massing` |
| `description` | (verbatim in code above) |
| `parameterSchema` | (verbatim above; matches §9 + §15) |
| `endpoint` | `http://massing-gen-api:18083/internal/tools/generate-massing` |
| `timeout` | **`Duration.ofSeconds(60)`** — covers worst-case LLM extraction (10–30 s) + algorithm (~ms) + sidecar serialization (1–5 s) + DB write (~ms), with headroom. Per ADR-17 §1's per-descriptor timeout rule. PRD §Story 1 working = 60 s; confirmed. |

### 21. Components — wire-shape summary (PRD §"Wire-shape contracts" carry)

The PRD already enumerates the request / response / sidecar / SSE
wire shapes in detail (§"Wire-shape contracts"); this ADR does not
duplicate them but pins these incremental wire-shape details:

| Surface | Pin |
|---|---|
| `POST /internal/tools/generate-massing` request | `application/json`, body shape per PRD; required: `briefDocId`. Missing required → HTTP 400 with M7-mapped `tool_error.code: UPSTREAM_4XX`. Extra fields ignored. |
| `POST /internal/tools/generate-massing` response (200) | `application/json`, body shape per PRD §"Wire-shape contracts"; `fileUrl` is relative (`/api/arch/outputs/<uuid>`); `summary` is Korean-fixed (§5). |
| `POST /internal/tools/generate-massing` response (4xx/5xx) | `application/json`, body shape `{ "code": "<MassingErrorCode>", "message": "<MassingErrorCode>: <human>" }`. The `message` carries the prefix grammar (§6) — when M7's dispatcher reads this 4xx/5xx body, it copies `message` into the SSE `tool_error.message` verbatim (the prefix is preserved). |
| `GET /api/arch/outputs/{id}` request | Authenticated (gateway forwards `X-User-Id` + `X-User-Sub`); `id` = UUID. |
| `GET /api/arch/outputs/{id}` response (200) | `Content-Type: application/octet-stream`, `Content-Disposition: attachment; filename="massing-<briefSlug>-<timestamp>.3dm"`. `briefSlug` is derived from `docs.documents.title` (lowercased, non-alphanumeric → `-`, collapsed runs, trimmed; max 40 chars). `timestamp` = `arch.outputs.created_at` formatted as `yyyyMMdd-HHmmss` UTC. Body = `arch.outputs.file_bytes`. |
| `GET /api/arch/outputs/{id}` response (404) | Owner mismatch OR row not found → both 404 (tenant-isolation invariant; per ADR-12 §10 pattern — never 403, never leak existence). |
| Gateway route addition | `/api/arch/**` → `http://massing-gen-api:18083` per ADR-07's route table. ADR-07 amendment (informational — one new prefix, no auth posture change since the route is auth-required). |

### 22. Test surface

| Test class | Location | What it covers |
|---|---|---|
| `MassingAlgorithmTest` (unit) | `massing-gen-domain/src/test/java/.../algorithm/` | (a) Total box area ≥ total room area (within tolerance), (b) floor count formula = `ceil(totalArea / siteArea)`, (c) all boxes inside site footprint (`x+w ≤ siteW`, `y+d ≤ siteD`), (d) no per-floor overlap, (e) over-area input throws `MassingException(MASSING_ALGORITHM_FAILED)`, (f) edge cases — empty program (rejected by validator earlier), single 1×1 room, very tall row of narrow rooms. |
| `MassingSummaryTest` (unit) | `massing-gen-domain/src/test/java/.../summary/` | Korean format: `MassingSummary.format(12, 3, 480.0)` returns `"12실 · 3층 · 총 480 m²"`. Locale stability — `Locale.US` and `Locale.KOREA` produce the same output. |
| `SpringAiBriefExtractorAdapterTest` (integration, WireMock spark-gateway) | `massing-gen-infra/src/test/java/.../adapter/` | (a) Happy path — WireMock stub returns valid programJson → adapter returns `Program`. (b) LLM returns empty array `{"rooms":[]}` → JSON-Schema validator throws (minItems=1) → `BRIEF_EXTRACTION_FAILED`. (c) LLM returns non-JSON prose → parser throws → `BRIEF_EXTRACTION_FAILED`. (d) LLM returns extra fields (`additionalProperties` violation) → validator throws → `BRIEF_EXTRACTION_FAILED`. (e) Resilience4j breaker `spark-gateway` shared with M4 — sustained 5xx → breaker OPEN → subsequent extract throws `CallNotPermittedException` → wrapped as `SIDECAR_FAILED`-class. |
| `Rhino3dmAdapterTest` (integration, WireMock sidecar) | `massing-gen-infra/src/test/java/.../adapter/` | (a) Happy path — stub returns `.3dm` byte stream → adapter returns `byte[]`. (b) Sidecar 5xx burst → `rhino3dm-bridge` breaker OPEN → subsequent call returns `CallNotPermittedException` → mapped to `SIDECAR_FAILED`. (c) Sidecar timeout (stub delays past 30 s) → `TimeoutException` → mapped to `SIDECAR_TIMEOUT`. (d) Breaker isolation — `rhino3dm-bridge` breaker OPEN does not affect `spark-gateway` breaker. |
| `HttpBriefBodyAdapterTest` (integration, WireMock docs-api) | `massing-gen-infra/src/test/java/.../adapter/` | (a) Happy path — metadata stub returns `extraction_status=completed`, body stub returns `application/json {"markdown":"..."}` → adapter returns `BriefBody`. (b) `extraction_status=processing` → `BRIEF_NOT_READY`. (c) Visibility=private + owner mismatch → `BRIEF_NOT_ACCESSIBLE`. (d) docs-api 404 → `BRIEF_NOT_FOUND`. (e) `X-User-Id` + `X-User-Sub` headers forwarded (WireMock capture + assertion). |
| `GenerateMassingUseCaseTest` (slice) | `massing-gen-app/src/test/java/.../usecase/` | (a) Full happy path with mocked ports — returns valid response DTO + persists `arch.outputs` row. (b) `BRIEF_NOT_FOUND` propagation. (c) `BRIEF_EXTRACTION_FAILED` propagation. (d) `MASSING_ALGORITHM_FAILED` (over-area). (e) `SIDECAR_TIMEOUT` propagation. (f) `arch.outputs.user_id` = the request's `X-User-Id`. |
| `GenerateMassingControllerTest` (slice, MockMvc) | `massing-gen-api/src/test/java/.../controller/` | (a) Request shape validation (missing `briefDocId` → 400). (b) Response shape (`fileUrl` is relative, `summary` is Korean). (c) Error-code prefix in message body (`BRIEF_EXTRACTION_FAILED: ...`). (d) Header validation (`X-User-Id` / `X-User-Sub` required). |
| `ArchOutputDownloadControllerTest` (slice, MockMvc) | `massing-gen-api/src/test/java/.../controller/` | (a) Owner-only access — owner sees 200, non-owner sees 404. (b) `Content-Disposition` filename shape. (c) Body bytes match `arch.outputs.file_bytes`. |
| `MassingGenE2ETest` (end-to-end, **real sidecar**) | `massing-gen-api/src/test/java/.../e2e/` | Spin up the actual `rhino3dm-bridge` sidecar via Testcontainers (`GenericContainer<>("rhino3dm-bridge:test")`). Drive a full happy-path request → assert returned `.3dm` byte stream is non-empty and parseable by `rhino3dm` (or at minimum has the `.3dm` magic-bytes header `4C 1B`). |
| `MassingToolDescriptorTest` (unit, `rag-chat-domain`) | `rag-chat-domain/src/test/java/.../tool/` | `MassingTool.DESCRIPTOR.name() == "generate_massing"`, `endpoint().toString() == "http://massing-gen-api:18083/internal/tools/generate-massing"`, `timeout().equals(Duration.ofSeconds(60))`, `parameterSchema()` parses as valid JSON with required `briefDocId`. |
| `ToolCatalogIntegrationTest` (M7 regression) | `rag-chat-domain/src/test/java/.../tool/` | `ToolCatalog.descriptors()` returns exactly 1 entry whose name == `"generate_massing"`. M7's echo-fixture test (`M7EchoToolE2ETest`) still passes — `generate_massing` does not poison the test fixture's separate test path. |
| `M4RegressionInvariantTest` (M4 regression, untouched) | existing | The M4 SSE happy-path / abort-path / rate-limit test suite passes unchanged. |
| `M6RegressionInvariantTest` (M6 regression, untouched) | existing | The M6 PDF upload + extraction test suite passes unchanged (M8 does not touch docs-api code). |
| `M7RegressionInvariantTest` (M7 regression, mostly untouched) | existing | The M7 dispatcher / breaker / depth-cap / per-tool-isolation test suite passes; the only addition is that `ToolCatalog.descriptors()` is now non-empty, so any test asserting "empty list" is updated to "list containing generate_massing". The synthetic `echo` test fixture remains untouched. |

### 23. Rollout — single PR with internal commits

**Decision: M8 lands as a single PR off branch
`worktree-m8-massing-gen`. Internal commits chunk the work; the PR is
not split. Mirrors M7's rollout pattern from ADR-17 §13.**

Suggested commit topology (implementer-determined; refinement of
PRD §9's hint):

1. **Module scaffolding** — `backend/massing-gen/{api,app,domain,infra}/`
   directories, `build.gradle.kts` files via the existing
   `playground.bc-*` convention plugins, `settings.gradle.kts` updates.
2. **Domain layer** — `Program`, `Room`, `SiteFootprint`, `RoomBox`,
   `MassingErrorCode`, `MassingException`, `MassingAlgorithm`
   interface, `RectangularFirstFitMassingAlgorithm` impl,
   `MassingSummary`. Unit tests (`MassingAlgorithmTest`,
   `MassingSummaryTest`).
3. **App layer (ports)** — `BriefBodyPort`, `Rhino3dmPort`,
   `BriefProgramExtractor`, `ArchOutputRepository` interfaces.
   `GenerateMassingUseCase` orchestrator (with mock-friendly ports).
   `MassingProperties` `@ConfigurationProperties`. Slice tests
   (`GenerateMassingUseCaseTest`).
4. **JSON Schema + prompt resources** — `programJson.schema.json`,
   `brief-extraction.system.txt`, `.user.txt`, `.fewshot.json`.
5. **Infra adapters** — `SpringAiBriefExtractorAdapter`,
   `HttpBriefBodyAdapter`, `Rhino3dmAdapter`, `ArchOutputJpaRepository`
   + `@Entity`, Resilience4j `rhino3dm-bridge` breaker config. Slice
   tests (each adapter's integration test).
6. **Flyway migration** — `V202605230001__arch_outputs.sql`.
7. **API controllers** — `GenerateMassingController`,
   `ArchOutputDownloadController`. Slice tests.
8. **Sidecar Dockerfile + npm package** — `infra/sidecars/rhino3dm-bridge/`
   directory: `Dockerfile`, `package.json`, `index.js`.
9. **Compose update** — `infra/docker-compose.yml`: new
   `massing-gen-api` service block (port 18083, internal),
   `rhino3dm-bridge` service block (port 4000, internal).
10. **Gateway route** — `gateway`'s `application.yml`: route
    `/api/arch/**` → `http://massing-gen-api:18083` (auth-required).
11. **ToolCatalog registration** —
    `backend/rag-chat/rag-chat-domain/src/main/java/.../tool/MassingTool.java`
    + 1-line update to `ToolCatalog.descriptors()`. Unit test.
12. **End-to-end tests** — `MassingGenE2ETest` with real sidecar
    container.
13. **ADR + roadmap + design doc amendments** — this ADR + ADR-01
    A01.6+ + ADR-05 A05.5+ + ADR-08 A08.11+ + ADR-00 row + roadmap
    M8 row + design doc small textual amendment (§11).
14. **Frontend `tool_result` / `tool_error` card render** — per
    design doc §2.3 + §2.4 + §2.5; the `tool_error.message` prefix
    parser (§6) lives in
    `frontend/src/features/chat/parseToolErrorMessage.ts` (or
    similar; implementer-named).

The PR ships after end-to-end verification — Rhino-open test of the
generated `.3dm` is the gate (PRD acceptance "`.3dm` opens in Rhino
without errors").

## Consequences

- **Positive: M8 is the first concrete `ToolCatalog` consumer.** The
  M7 generic infra was proven against synthetic fixtures; M8 proves
  it against a real domain tool. Future tool BCs (M9+ `slide-gen`,
  `image-gen`) clone the M8 pattern — 4-module quadruplet, new
  schema, new Exception 4 sub-row, new circuit breaker, descriptor
  in `rag-chat-domain.tool.<Name>Tool.DESCRIPTOR`.
- **Positive: ADR-08 amendment discipline preserved.** Spec §7's
  stale "widen Exception 1" framing is explicitly closed in §5 — a
  fresh Exception 5 is created instead. The M6.1 retirement of
  Exception 1 is honored, not silently reversed.
- **Positive: port reservation pool returns to its M6.1 shape minus
  one.** 18083 claimed; 18085 still reserved. Future M9 tool BC has
  a clean slot.
- **Positive: storage media (BYTEA) chosen for simplicity, not
  optimism.** The BYTEA→MinIO migration path is documented (§12); the
  trigger (corpus growth) is operator-observable on M5 dashboards.
- **Negative: two LLM round-trips per chat turn.** M4's chat-level
  LLM call + M8's brief-extraction LLM call. At personal scale this
  is acceptable (per §16); at higher scale a per-tool rate limit
  would be added in M8.1.
- **Negative: the docs-api `/internal/docs/public/{id}/body` route
  is revived as a real cross-BC path.** M6.1 had deprecated the
  route to "defensively preserved"; M8 promotes it back to active
  use. This is the price of avoiding cross-schema SELECT widening
  (§5); the trade-off is documented above.
- **Negative: the `summary` Korean-fixed string is not
  internationalized.** Future English-locale users would see a
  Korean summary card. The choice (§5) is pragmatic for the
  Korean-primary playground; an M8.1 i18n pass is the documented
  remedy.
- **Negative: the `MassingTool.DESCRIPTOR` placement in
  `rag-chat-domain`** is the only file outside `backend/massing-gen/`
  that the M8 PR touches. ADR-17 §D documented this pattern as
  intentional ("New tools land via PR to `rag-chat-domain` adding a
  new descriptor constant"); operationally this means M8 cycle is
  not strictly self-contained — it must amend `rag-chat-domain`.
  This is the explicit cost of the hardcoded-catalog choice from
  M7 P0; M7.1 dynamic registry would eliminate it.

---

## §A01 — Amendment to ADR-01 (Gradle Multi-Module Monorepo)

> This amendment is appended to `docs/adr/01-msa-gradle-structure.md`
> as a new amendment block following the existing M6.1 block
> (§A01.1–§A01.5). It adds the `massing-gen` quadruplet, pins port
> 18083, and bumps module count.

### §A01.6. Module list — `massing-gen-*` quadruplet added

The `massing-gen` BC's four-module quadruplet is **added**:

| Module | Type | bootJar? | Added in |
|---|---|---|---|
| `massing-gen-api` | runnable Spring Boot app | **yes** | **M8** |
| `massing-gen-app` | Java library | no | **M8** |
| `massing-gen-domain` | Java library | no | **M8** |
| `massing-gen-infra` | Java library | no | **M8** |

The directories `backend/massing-gen/massing-gen-{api,app,domain,infra}/`
are created. Root `backend/settings.gradle.kts` gains four new
`include(":massing-gen:massing-gen-*")` lines.

Java package root: `dev.jeeklee.playground.massinggen.*` (joined-word
convention matching `ragchat` for `rag-chat`; see ADR-18 §14).

### §A01.7. Port table — 18083 reclaimed for massing-gen

Port 18083 (freed by M6.1 amendment §A01.3) is **claimed by
massing-gen-api**. The updated table:

| Module | Port | Host-exposed? |
|---|---|---|
| `gateway` | **18080** | yes (single ingress) |
| `identity-api` | **18081** | no (compose-internal) |
| `docs-api` | **18082** | no |
| **`massing-gen-api`** | **18083** | **no** (compose-internal; gateway forwards `/api/arch/**`, not `/internal/**`) |
| `rag-chat-api` | **18084** | no |
| (reserved) | 18085 | freed for next BC (per M6.1 amendment §A01.3) |
| `metrics-api` | **18086** | no |

The spec §6 + roadmap §M8 + PRD §"Story 1" all carried port `18086` as
the working candidate for `massing-gen-api`; **that working value is
stale** and was rejected in ADR-18 §2 (18086 is owned by `metrics-api`
since M5). ADR-18 §2 confirms **18083**; this amendment ratifies the
port table.

### §A01.8. Updated module count + ADR-00 graph

Six BCs (identity, docs, rag-chat, metrics, **massing-gen**) × four
modules each + gateway + shared-kernel = **26 production modules** +
`buildSrc`. Up from 22 (post-M6.1 baseline per A01.2). Counting from
the same baseline ADR-15 used: 22 + 4 = **26**.

ADR-00's ASCII module dependency graph is redrawn in lockstep — a new
`massing-gen-api` lane is added; the `rag-chat-api` → `massing-gen-api`
arrow is drawn (Exception 4 sub-row per A08.11); the `massing-gen-api`
→ `docs-api` arrow is drawn (Exception 5 per A08.12).

### §A01.9. Convention plugins — no change

The six convention plugins (`playground.{java-conventions,spring-boot-app,bc-{domain,app,api,infra}}`)
are unchanged by M8. The `massing-gen` modules apply the same
`playground.bc-{domain,app,api,infra}` plugins as every other BC.

### §A01.10. Consequences (M8-specific)

- **Positive:** the reservation pool reduces from 2 free slots (18083
  + 18085) to 1 (18085). The pool was intentionally over-provisioned
  by M6.1; M8 absorbs the slack.
- **Positive:** module count climb (22 → 26) is the expected fresh-BC
  cost. IDE indexing rises proportionally; Gradle configuration cache
  + build cache (per ADR-01 §"Consequences") absorb it.
- **Negative:** docs-api now has two distinct external HTTP consumers
  (cross-BC) for the first time since M6.1: massing-gen-api (Exception
  5) + the historical `gateway` (general route forwarding). The two
  call different endpoints, so no contention, but the docs-api
  controller surface gains a re-promoted internal handler (was
  defensively preserved; now actively used). See A08.12 below.

See `docs/adr/18-m8-massing-gen.md` §2 + §17 for the full M8
specification.

---

## §A05 — Amendment to ADR-05 (Data Store)

> This amendment is appended to `docs/adr/05-data-store.md` as a new
> amendment block following the existing M6.1 block (§A05.1–§A05.4).
> It introduces the `arch` schema and the `arch.outputs` table.

### §A05.5. New schema — `arch` (owned by `massing-gen` BC)

The `arch` schema is **added** to the schema-per-BC list:

| Schema | Owned by | Notes |
|---|---|---|
| `identity` | `identity` service | Users, OAuth links |
| `docs` | `docs` service | Document metadata; raw MD body as TEXT; chunks + pgvector embeddings; outbox table |
| `chat` | `rag-chat` service | Chat sessions, messages, citations |
| **`arch`** | **`massing-gen` service** | **Generated .3dm outputs (`arch.outputs`). Owner-tagged; .3dm bytes inline as BYTEA per ADR-18 §12.** |
| `metrics` | `metrics` service | Snapshot history (stateless in M5 per ADR-15 — no `metrics` schema is provisioned in P0; the slot is reserved) |
| `flyway_<schema>` | per-service Flyway | Each service's migration history table |

Total schema count rises from 3 (post-M6.1) to 4. The
schema-per-BC invariant is preserved.

### §A05.6. `arch.outputs` table — DDL pinned

The full DDL lives in ADR-18 §18 (the Flyway migration
`V202605230001__arch_outputs.sql`). For ADR-05's schema-overview audit:

```sql
CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK)
    file_bytes      BYTEA        NOT NULL,                  -- .3dm binary (ADR-18 §12)
    program_json    JSONB        NOT NULL,                  -- extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    summary         TEXT         NOT NULL,                  -- Korean-fixed (ADR-18 §5)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_arch_outputs_user  ON arch.outputs (user_id, created_at DESC);
CREATE INDEX idx_arch_outputs_brief ON arch.outputs (brief_doc_id);
```

| Concern | Pin |
|---|---|
| Schema | `arch` (NEW) |
| Tables in M8 P0 | one — `arch.outputs` |
| pgvector usage in `arch` | none — the `arch` schema does not create the `vector` extension or any embedding columns. M8 has no retrieval surface; vector search stays in `docs.document_chunks` (M3-derived, M6.1-relocated). |
| File storage | BYTEA inline (per ADR-18 §12); migration to MinIO documented but deferred |
| Cross-schema FK to `docs.documents` | app-level only (no DB FK; per ADR-05's schema-per-BC invariant). Dangling FK semantic documented in ADR-18 §13. |
| Cross-schema FK to `identity.users` | app-level only. |
| Hikari `search_path` (massing-gen-api) | `SET search_path TO arch, public` (no cross-schema reads — M8 reads docs metadata over HTTP via Exception 5, not via SQL). |
| Hibernate `default_schema` (massing-gen-api) | `arch` |

### §A05.7. No new cross-schema SELECT exception

M8's body-fetch goes over HTTP (per ADR-18 §5 + ADR-08 §A08.12). The
M4 cross-schema SELECT exception (ADR-14 / §A05.4's `chat,docs,identity`
search_path) is **not** extended. M8 does NOT widen the cross-schema
SELECT surface.

This is intentional — see ADR-18 §5 for the trade-off analysis. The
schema-per-BC invariant strengthens here: `arch` is fully isolated;
the only cross-schema interaction is at the app-level FK columns,
which carry UUIDs only (no SQL-level coupling).

### §A05.8. Object storage — slot 6 still reserved

The 6th object-storage reservation slot opened by M6.1 amendment
§A05.3 (host-port 10296, compose service name TBD) remains
**reserved**. M8 does NOT claim it (per ADR-18 §12 — `.3dm` files
stay in BYTEA, not MinIO). The slot stays open for M9+.

See `docs/adr/18-m8-massing-gen.md` §12 + §18 for the full storage
specification.

---

## §A08 — Amendment to ADR-08 (Inter-Service Communication)

> This amendment is appended to `docs/adr/08-inter-service-comms.md`
> as a new amendment block following the existing M7 block
> (§A08.8–§A08.10). It adds the `rag-chat-api` → `massing-gen-api`
> Exception 4 sub-row and introduces **Exception 5**
> (`massing-gen-api` → `docs-api` for brief body read).

### §A08.11. Exception 4 (rag-chat → tool BCs) — first sub-row added

The M7 amendment §A08.8 introduced **Exception 4** as a template with
0 sub-rows. M8 adds the first sub-row:

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/generate-massing` | `rag-chat-api` | `massing-gen-api` | Brief PDF → `.3dm` massing (M8). |

The Exception 4 template constraints (§A08.8) apply verbatim:
- One direction only.
- Internal `/internal/**` route prefix; gateway does not forward.
- `X-User-Id` + `X-User-Sub` headers forwarded.
- WebClient timeout = descriptor's `timeout` (60 s for
  `generate_massing`, per ADR-18 §20).
- Per-tool Resilience4j breaker `tool-generate_massing` (per M7 ADR-17
  §5 — auto-registered).
- No WebClient-level retries.

The M7 ADR-17 §A08.8 mention of this exact sub-row ("Sub-rows at M7
ship: none ... M8 adds the first sub-row") is now materialized.

### §A08.12. Exception 5 — `massing-gen-api` → `docs-api` (brief body read)

**Sanctioned route:** `massing-gen-infra`'s `HttpBriefBodyAdapter`
WebClient may call two routes on the docs BC:

| Method | Path | Returns | Purpose |
|---|---|---|---|
| `GET` | `/internal/docs/public/{id}` | `200 application/json` document metadata (`id`, `title`, `extraction_status`, `visibility`, `owner_user_id`) | Pre-check that the brief is `extraction_status='completed'` and visible to the caller. |
| `GET` | `/internal/docs/public/{id}/body` | `200 application/json {"markdown":"..."}` | Fetch the brief body (markdown — PDF-extracted via M6.1's Vision OCR pipeline, written by docs-api's async extraction worker). |

**Justification:**

- M8's brief-body fetch needs the PDF-extracted markdown body, which
  is only authoritatively held by the docs BC (`docs.documents.body`).
  Cross-schema SELECT was considered (M4 ADR-14 §3 pattern) and
  rejected per ADR-18 §5 — the latency budget allows HTTP, and HTTP
  preserves BC isolation (docs-api can evolve `documents` columns
  without breaking massing-gen).
- The `/internal/docs/public/{id}/body` route was retired from active
  use in M6.1 (Exception 1 retired per A08.1) but the controller was
  defensively preserved in expectation of exactly this case — a
  future BC reviving cross-BC body-fetch under a fresh exception.
  Exception 5 is that fresh exception.

**Constraints (mirrors Exception 1's discipline, but with identity propagation enabled):**

- **Read-only.** `massing-gen` MUST NOT mutate any docs state.
- **Internal route prefix (`/internal/**`).** docs BC routes prefixed
  `/internal/` are explicitly not forwarded by the gateway (ADR-07's
  route table excludes `/internal/**`).
- **User identity propagation IS performed.** `X-User-Id` and
  `X-User-Sub` are forwarded from the inbound chat-dispatched request
  (Exception 4's downstream context); the docs body controller does
  not consult them today (M6.1 visibility check lives at the public
  list/read endpoints, not at the internal route), but
  `massing-gen-app` performs the owner-vs-visibility check on the
  metadata response before requesting the body. The route's body
  contract is unchanged; the caller (`massing-gen`) is the
  authorization site.
- **Reliability discipline.** WebClient timeout **5 s**, up to **3
  retries** with exponential backoff (200/400/800 ms base, jitter
  0.5) — mirrors Exception 1's ADR-12 §2 + ADR-13 §2 discipline
  verbatim. Permanent failure → `BRIEF_FETCH_FAILED` (HTTP 502 — per
  ADR-18 §7).
- **No circuit breaker.** docs-api is a healthy compose-network BC
  with no observed availability issues; the breaker discipline is
  reserved for spark-inference-gateway (`spark-gateway`) and the
  sidecar (`rhino3dm-bridge`). If docs-api availability becomes a
  real concern, M8.1 adds a `docs-api` breaker. P0 does not.

### §A08.13. Allowed-channels table (post-A08.11 + A08.12)

| Direction | Channel | Notes |
|---|---|---|
| client (browser) → gateway | HTTPS (HTTP in dev) | Cookie session |
| gateway → any BC `-api` | HTTP (compose-internal) | Per ADR-07 routing |
| BC → Kafka → BC | Kafka events | Per ADR-03 envelope |
| BC → external (`spark-inference-gateway`) | HTTP via Spring AI | Per ADR-04 |
| BC → Postgres (`postgres-playground`) | JDBC | Per ADR-05 |
| BC → OpenSearch (`opensearch-playground`) | HTTP (REST) | Per ADR-05 amendment |
| gateway → Redis (`redis-playground`) | Redis protocol | Spring Session (ADR-07) |
| `docs-api` → Redis (`redis-playground`) — `view:*` + `docs:lock:*` namespaces | Redis protocol | Sanctioned (ADR-12 amendment + M6.1 amendment A08.2) |
| `docs-api` → `identity-api` `/internal/users/by-google-sub/{sub}` | HTTP (compose-internal) | Sanctioned (ADR-12 amendment 2026-05-17) — Exception 3 |
| `docs-api` → `minio-playground:9000` | HTTP / S3 protocol | Sanctioned (M6.1 amendment A08.3) |
| `rag-chat-api` → cross-schema SELECT into `docs.*` + `identity.*` | JDBC (cross-schema) | Sanctioned (ADR-14 amendment; M6.1-narrowed to 2 schemas — A08.5) |
| `rag-chat-api` → tool BC `-api` `/internal/tools/<name>` | HTTP (compose-internal) | Sanctioned (M7 amendment §A08.8) — Exception 4. Sub-row table below. |
| **`massing-gen-api`** → `docs-api` `/internal/docs/public/{id}` + `/internal/docs/public/{id}/body` | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.12) — Exception 5.** Brief body read for `generate_massing` tool. |
| **`massing-gen-api`** → `rhino3dm-bridge:4000` | **HTTP (compose-internal)** | **Sanctioned (this amendment §A08.14, informational) — external-sidecar direction, not BC-to-BC.** Resilience4j `rhino3dm-bridge` breaker per ADR-18 §17. |

Exception 4 sub-row table (post-A08.11):

| Method | Path | Caller | Callee | Purpose |
|---|---|---|---|---|
| `POST` | `/internal/tools/generate-massing` | `rag-chat-api` | `massing-gen-api` | Brief PDF → `.3dm` massing (M8). |

### §A08.14. New external-sidecar direction (informational)

The `rhino3dm-bridge` sidecar is a compose-internal external service
(like `spark-inference-gateway` and `minio-playground`), not a sibling
BC. The `massing-gen-api → rhino3dm-bridge` HTTP direction is
classified the same way the M6.1 `docs-api → minio-playground`
direction was — a sanctioned external-service direction, not a
BC-to-BC HTTP exception. It does not count against the BC-to-BC
exception count.

The cumulative BC-to-BC exception count after M8:

- Exception 1 (rag-ingestion → docs-api): **retired** in M6.1.
- Exception 2 (rag-ingestion → Redis lock): **retired** in M6.1
  (folded into docs-owned Redis namespace).
- Exception 3 (docs-api → identity-api): **active**, 1 sub-row.
- Exception 4 (rag-chat-api → tool BCs): **active**, **1 sub-row**
  (`massing-gen` — added by this amendment).
- Exception 5 (massing-gen-api → docs-api): **active, NEW by this
  amendment**, 1 sub-row.

Net M8 effect on the BC-to-BC exception count: **+1** new exception
type (Exception 5), **+1** sub-row under Exception 4 (`generate_massing`).

### §A08.15. Future-exception discipline (unchanged)

Every future BC-to-BC HTTP path still requires a fresh ADR amendment
row. M9+ tool BCs add their own Exception 4 sub-row; any future BC
needing docs-api body access adds its own Exception 5-style
amendment (the precedent established here is "fresh exception per
caller BC", not "Exception 5 grows sub-rows" — different from
Exception 4's template grammar, which is BC-driven by definition).

See `docs/adr/18-m8-massing-gen.md` §5 + §17 for the full M8
inter-service specification.

---

## Amendment 2026-05-22 (Python flip)

> This amendment is appended to ADR-18 as a new amendment block after
> the embedded §A01 / §A05 / §A08 amendment blocks above. The body of
> ADR-18 (§1–§23 + Consequences + §A01.6–§A01.10 + §A05.5–§A05.8 +
> §A08.11–§A08.15) was written under the assumption that
> `massing-gen` is a Java Spring Boot quadruplet. The decision now
> flips the BC's implementation language to Python/FastAPI. This
> amendment is **forward-only** — the body above is preserved
> verbatim for audit; sections superseded here are listed in §A18.5.
> The HTTP contract, the schema, the port pin, and the cross-BC
> integration points (Exception 4 sub-row, Exception 5) are
> **language-neutral and unchanged**.

### §A18.1. Decision flip — Python/FastAPI replaces Java Spring Boot quadruplet

**Decision: M8's `massing-gen` BC is implemented as a single
Python/FastAPI service, not a Java Spring Boot four-module
quadruplet. The new top-level directory is `services/massing-gen/`
(the existing `backend/` directory remains Java-only). The HTTP
contract, the `arch` schema DDL, the port pin (18083), and the
compose service name (`massing-gen-api`) are preserved verbatim.**

**Rationale:**

- **User familiarity.** The owner has primary fluency in Python; a
  Python codebase is faster to evolve for the architect-domain BC
  whose prompts, JSON schema, and rhino3dm interop will need
  frequent iteration during the M8 P0 polish loop.
- **`rhino3dm.py` is first-class.** McNeel ships `rhino3dm` as a
  Python wheel (`pip install rhino3dm`) with no transitive C
  toolchain visible to the user. In contrast, the Java side would
  always require an external sidecar (the now-retired
  `rhino3dm-bridge` Node container) because the JVM has no first-class
  rhino3dm binding. Python eliminates the sidecar entirely.
- **Sidecar retirement is a strict win.** One fewer container in
  compose, one fewer process to health-check, one fewer Resilience4j
  breaker, one fewer Dockerfile to maintain. The
  `rhino3dm-bridge` sidecar's `/serialize` HTTP hop becomes an
  in-process Python function call.
- **The BC's external surface is already small.** `massing-gen` has
  one inbound HTTP endpoint (`POST /internal/tools/generate-massing`),
  one inbound user-facing endpoint (`GET /api/arch/outputs/{id}`),
  one outbound HTTP path (Exception 5 to docs-api), and one outbound
  LLM call (spark-inference-gateway). Polyglot risk is bounded.

### §A18.2. Library — rhino3dm.js sidecar retired, rhino3dm.py imported in-process

**Decision: the `rhino3dm-bridge` Node 18-alpine sidecar is retired.
The `.3dm` serialization path is replaced by a direct `import rhino3dm`
in the FastAPI service.**

| Concern | Pre-flip (Java + Node sidecar) | Post-flip (Python in-process) |
|---|---|---|
| `.3dm` library | `rhino3dm@8.4.0` (npm) in `rhino3dm-bridge` sidecar | `rhino3dm` (PyPI; pin latest stable at implementer-time, currently `8.x` line) imported directly into FastAPI process |
| Container | `node:18-alpine` + `rhino3dm-bridge` compose service on port 4000 | **Retired.** No sidecar. |
| HTTP hop | `massing-gen-api` → `rhino3dm-bridge:4000/serialize` (compose-internal) | None — in-process function call |
| Resilience4j `rhino3dm-bridge` breaker (ADR-18 §17) | Per-sidecar circuit breaker named `rhino3dm-bridge` | **Retired** (no remote call to wrap). In-process exceptions are caught by the use-case's normal try/except + mapped to `SIDECAR_FAILED` (kept for tool-error vocabulary stability, but the error class now means "rhino3dm library raised" rather than "sidecar HTTP 5xx") |
| `PLAYGROUND_RHINO3DM_BRIDGE_URL` env var | `http://rhino3dm-bridge:4000` | **Retired** |
| `PLAYGROUND_RHINO3DM_BRIDGE_TIMEOUT_MS` env var | `30000` | **Retired** (no remote call to time out) |
| Compose service block for `rhino3dm-bridge` | required | **Removed** from `infra/docker-compose.yml` |
| `infra/sidecars/rhino3dm-bridge/` directory | Dockerfile + `package.json` + `index.js` | **Deleted** |

### §A18.3. Implementation stack pin

**Decision: the `services/massing-gen/` FastAPI service uses the
following stack pins. Exact versions are advisory (latest stable at
implementer time); the implementer chooses pin freshness within the
Python 3.12 family.**

| Layer | Pin (advisory) | Replaces |
|---|---|---|
| Language | **Python 3.12** | JDK 21 LTS (Java) |
| Web framework | **FastAPI** (latest stable, currently 0.115.x) | Spring Boot 3.3.x (Spring Web MVC + Springdoc OpenAPI) |
| ASGI server | **uvicorn** (with `[standard]` extras for `httptools` + `uvloop`) | Embedded Tomcat |
| Schema / domain models | **Pydantic v2** (latest stable, currently 2.9.x) | networknt JSON Schema validator (§9) + Java records |
| DB ORM / driver | **SQLAlchemy 2.x** (async session, `psycopg[binary]` 3.x driver) — or hand-rolled `psycopg` if the implementer prefers thin SQL | Spring Data JPA + Hibernate (`PostgreSQLDialect`) |
| HTTP client (outbound) | **httpx** (latest stable, currently 0.27.x) | Spring `WebClient` (reactive Netty) |
| `.3dm` serialization | **rhino3dm** (PyPI; latest stable; in-process import) | rhino3dm.js npm package in Node sidecar (§11 above) |
| Logging | **structlog** (latest stable) — JSON-structured logs | Spring Boot Logback + JSON encoder |
| Metrics exposition | **prometheus-fastapi-instrumentator** (latest stable) | Micrometer (Spring Boot Actuator) |
| Testing | **pytest** (latest stable) + `pytest-asyncio` + `respx` (httpx WireMock equivalent) | JUnit 5 + WireMock + Testcontainers |
| Migration tool | **Alembic** (preferred) OR hand-rolled SQL (acceptable for P0; see §A18.7) | Flyway |
| Package manager | **uv** or **pip** (implementer choice; `pyproject.toml` is the source of truth) | Gradle 8.10.x + version catalog |
| Container base | **`python:3.12-slim`** | implicit JRE 21 base in Java fat-jar image |

The fat-jar invariant of `*-api` modules in ADR-01 is replaced by a
single `python:3.12-slim`-based Docker image that runs
`uvicorn app.main:app --host 0.0.0.0 --port 18083`.

### §A18.4. File layout — `services/massing-gen/`

**Decision: M8 lives at the new top-level `services/` directory. The
`backend/` directory remains Java-only.**

```
services/
└── massing-gen/
    ├── pyproject.toml            # uv or pip; declares deps (fastapi, uvicorn, pydantic, sqlalchemy, httpx, rhino3dm, alembic, ...)
    ├── Dockerfile                # FROM python:3.12-slim; copies app/, installs deps, runs uvicorn on 18083
    ├── README.md                 # implementer-owned local-dev notes (optional)
    ├── app/                      # the FastAPI application package
    │   ├── __init__.py
    │   ├── main.py               # FastAPI() instance + router includes + uvicorn entrypoint
    │   ├── domain/               # Pydantic models + pure-Python algorithm (Spring-free invariant reborn as "no FastAPI/SQLAlchemy imports here")
    │   │   ├── program.py        # Program, Room, SiteFootprint, RoomBox (Pydantic models or @dataclass; implementer choice)
    │   │   ├── errors.py         # MassingErrorCode enum + MassingError exception class
    │   │   ├── algorithm.py      # RectangularFirstFitMassingAlgorithm (the §8 logic, ported verbatim)
    │   │   └── summary.py        # Korean-fixed summary format (§5 — "%d실 · %d층 · 총 %.0f m²")
    │   ├── application/          # use-case orchestrators; may import domain + httpx clients
    │   │   ├── generate_massing.py    # GenerateMassingUseCase (the §1 orchestrator)
    │   │   └── brief_extractor.py     # BriefProgramExtractor (the §4 LLM call wrapper)
    │   ├── infrastructure/       # adapters: HTTP clients, DB repositories, rhino3dm serializer
    │   │   ├── docs_client.py    # HttpBriefBodyAdapter equivalent (Exception 5 caller — calls docs-api via httpx)
    │   │   ├── llm_client.py     # SparkInferenceGateway client (httpx → /v1/chat/completions; replaces Spring AI ChatClient)
    │   │   ├── rhino_serializer.py    # in-process rhino3dm.File3dm builder (replaces Rhino3dmAdapter + sidecar HTTP)
    │   │   └── arch_outputs_repo.py   # SQLAlchemy session + arch.outputs CRUD
    │   ├── api/                  # FastAPI routers (HTTP edge)
    │   │   ├── tools.py          # POST /internal/tools/generate-massing
    │   │   └── outputs.py        # GET /api/arch/outputs/{id}
    │   └── config.py             # Pydantic Settings — env-var loading (mirrors §19's env-var table, minus rhino3dm-bridge)
    ├── alembic/                  # OPTIONAL — only present if implementer chooses Alembic over hand-rolled SQL
    │   ├── env.py
    │   └── versions/
    │       └── 202605230001_arch_outputs.py    # equivalent of V202605230001__arch_outputs.sql
    ├── schema.sql                # OPTIONAL alternative — hand-rolled idempotent DDL (CREATE SCHEMA IF NOT EXISTS arch; CREATE TABLE IF NOT EXISTS arch.outputs ...); see §A18.7
    ├── prompts/                  # the §10 Korean extraction prompt files (system/user/fewshot)
    │   ├── brief_extraction_system.txt
    │   ├── brief_extraction_user.txt
    │   └── brief_extraction_fewshot.json
    ├── schemas/                  # the §9 JSON Schema (still loaded at runtime, even though Pydantic does the heavy lifting)
    │   └── program.schema.json
    └── tests/                    # pytest test root
        ├── conftest.py
        ├── test_algorithm.py     # ports MassingAlgorithmTest
        ├── test_summary.py       # ports MassingSummaryTest
        ├── test_brief_extractor.py    # ports SpringAiBriefExtractorAdapterTest (uses respx to mock spark-gateway)
        ├── test_docs_client.py   # ports HttpBriefBodyAdapterTest (uses respx to mock docs-api)
        ├── test_rhino_serializer.py   # in-process rhino3dm.File3dm assertion (parses output via rhino3dm.File3dm.Read)
        ├── test_generate_massing_usecase.py    # ports GenerateMassingUseCaseTest
        ├── test_tools_router.py  # ports GenerateMassingControllerTest (uses FastAPI TestClient)
        └── test_outputs_router.py     # ports ArchOutputDownloadControllerTest
```

The DDD layering invariant from ADR-02 carries over as a **directory
convention**, not a build-graph rule. Python has no `*-domain`-module
classpath that excludes FastAPI; the implementer enforces "domain
modules don't import fastapi/sqlalchemy/httpx" via lint rules
(`import-linter` or `ruff` `TID252`-style rules) or by code review.
ArchUnit's Java-classpath enforcement is not replicable in Python; the
discipline downgrades to "review-enforced", matching ADR-02's original
"ArchUnit recommended, not mandated" posture.

### §A18.5. Original ADR-18 sections — supersession map

| Section | Status under Python flip | Replacement |
|---|---|---|
| §1 (Module placement — Java quadruplet) | **Retired** | Replaced by §A18.3 + §A18.4 (single FastAPI service at `services/massing-gen/`) |
| §2 (Port 18083) | **Preserved** | `massing-gen-api` container still binds 18083 |
| §3 (Q-E — body fetch over Exception 5) | **Preserved** | HTTP contract language-neutral; httpx replaces WebClient (§A18.3) |
| §4 (Q-C — Spring AI ChatClient) | **Retired** | Replaced by §A18.3 — httpx call to spark-inference-gateway's OpenAI-compatible `/v1/chat/completions` endpoint. Same model (Qwen3-32B), same base URL, same temperature/max-tokens config; Spring AI's wrapper is not used because there is no Spring AI. |
| §5 (Q-B — Korean summary format) | **Preserved** | Format string `"%d실 · %d층 · 총 %.0f m²"` carried over to `app/domain/summary.py` |
| §6 (Q-D — `<CODE>: <message>` prefix grammar) | **Preserved** | Wire-level; produced by Python the same way Java would |
| §7 (MassingErrorCode enum + HTTP mapping) | **Preserved (port to Python)** | `app/domain/errors.py` enum + a FastAPI exception handler that maps to the same HTTP statuses (404 / 403 / 422 / 502 / 504 / 500). The `BRIEF_FETCH_FAILED` code is added (§A08.12's reliability discipline maps permanent docs-api failure to that code). |
| §8 (MassingAlgorithm — over-area + maxFloors=10) | **Preserved (port to Python)** | Same algorithm, same defaults, same throw-on-over-area policy. Lives in `app/domain/algorithm.py`. |
| §9 (JSON Schema + networknt validator) | **Retired** | Replaced by **Pydantic v2** as the primary validator. The `programJson.schema.json` file is preserved (kept at `services/massing-gen/schemas/program.schema.json` for audit + as a system-prompt inlining source per §10), but the LLM output validation in code uses Pydantic's `TypeAdapter.validate_json(...)` against the equivalent Pydantic model. The two are kept in sync at PR-review time. Schema-validation failure → same `BRIEF_EXTRACTION_FAILED` enum value. |
| §10 (Korean prompt template) | **Preserved** | Prompt files move to `services/massing-gen/prompts/`. The system prompt still inlines `program.schema.json` verbatim — the LLM is instructed by the JSON Schema regardless of whether Python or Java validates the output. |
| §11 (rhino3dm-bridge Node sidecar) | **Retired** | Replaced by §A18.2 — in-process `import rhino3dm`. Box-coordinate convention (the table in §11 — x/y/z/width/depth/height/roomName/floor) is preserved verbatim as the layout the Python `rhino_serializer` builds. |
| §12 (BYTEA storage in arch.outputs.file_bytes) | **Preserved** | SQLAlchemy `LargeBinary` column maps to BYTEA the same way JPA `@Lob` did. |
| §13 (Orphan cleanup — untouched) | **Preserved** | Policy is language-neutral. |
| §14 (BC name — `massing-gen`) | **Preserved** | Hostname `massing-gen-api`, repo dir name `massing-gen`. |
| §15 (4 descriptor params) | **Preserved** | ToolCatalog descriptor lives in Java (rag-chat-domain) and is unchanged; M8 (Python) receives those params on the HTTP body. |
| §16 (No per-tool rate limit) | **Preserved** | M4 chat-level token bucket still covers. |
| §17 (rhino3dm-bridge breaker) | **Retired** | No remote sidecar to wrap. See §A18.6 for the broader breaker policy. |
| §18 (`arch.outputs` DDL) | **Preserved (port to Alembic / SQL)** | DDL bytes identical; the migration mechanic is Alembic or hand-rolled SQL (§A18.7). |
| §19 (env-var knobs) | **Mostly preserved** | The two `PLAYGROUND_RHINO3DM_BRIDGE_*` vars are retired (§A18.2). All other env vars — `SERVER_PORT`, `PLAYGROUND_MASSING_LLM_MODEL`, `PLAYGROUND_MASSING_MAX_FLOORS`, `PLAYGROUND_DOCS_API_URL`, `PLAYGROUND_DOCS_BODY_FETCH_TIMEOUT_MS`, `SPRING_AI_OPENAI_BASE_URL`, `SPRING_AI_OPENAI_API_KEY` — keep their names and values. The two Spring-AI-prefixed keys (`SPRING_AI_OPENAI_*`) keep their names for cross-BC env-var uniformity even though there is no Spring AI in M8 (the keys are re-read by Python's `app/config.py` and passed to httpx). |
| §20 (MassingTool descriptor) | **Preserved** | Lives in Java (rag-chat-domain). Endpoint URL `http://massing-gen-api:18083/internal/tools/generate-massing` unchanged. |
| §21 (Wire-shape contracts) | **Preserved** | HTTP request / response / `Content-Disposition` shapes are language-neutral. |
| §22 (Test surface) | **Preserved conceptually; retired in Java form** | The 13 Java test classes are not created. The intent of each — visibility check, `BRIEF_NOT_READY`, over-area, summary format, error-prefix wire shape, owner-only download — is reimplemented in pytest under `services/massing-gen/tests/` per §A18.9. |
| §23 (Rollout commit topology) | **Retired** | The Java-specific commit suggestion (module scaffolding, Flyway migration, etc.) is replaced by an implementer-determined Python topology. The PR ships off the same `worktree-m8-massing-gen` branch. |

### §A18.6. Circuit breakers retired in P0

**Decision: M8 P0 ships with no circuit breakers. Both outbound HTTP
calls (to docs-api via Exception 5 and to spark-inference-gateway for
extraction) rely solely on httpx timeouts + try/except blocks.**

**Rationale:**

- **Resilience4j is JVM-only.** Python equivalents exist
  (`pybreaker`, `purgatory`), but none are mature in the same way.
- **The breaker pattern's value at M8 P0 scale is low.** A burst of
  failing massing-gen calls is rate-limited by M4's per-user
  chat-level token bucket (60/hour). The breaker's primary value
  in Java land was guarding shared upstream resources from
  amplification (M4's `spark-gateway` breaker is shared across
  ChatTurnService + BriefExtractor); with M8 in Python and rag-chat
  in Java, the two cannot share a breaker process anyway.
- **httpx timeouts are sufficient first-line protection.**
  - docs-api call: `httpx.AsyncClient(timeout=5.0)` per
    Exception 5's reliability discipline. Permanent failure
    (e.g., 3 timeouts in a row) → `BRIEF_FETCH_FAILED` (HTTP 502).
  - spark-inference-gateway call: `httpx.AsyncClient(timeout=60.0)`
    matching the descriptor's 60s budget. Permanent failure → maps
    to `SIDECAR_FAILED` (the error vocabulary kept for stability —
    see §A18.2).

**Future hook:** if operator-observed spark-gateway saturation
appears in M5 dashboards, M8.1 introduces `pybreaker` (or any mature
Python breaker library) around the LLM client. The threshold
defaults would mirror ADR-14 §4's `spark-gateway` Java breaker
(50% / 60 s sliding window / 30 s OPEN). M8 P0 does not implement
this.

### §A18.7. Migration tool — Alembic (preferred) or hand-rolled SQL

**Decision: the implementer chooses between Alembic and hand-rolled
idempotent SQL for the `arch.outputs` migration. Alembic is the
recommended path for forward compatibility; hand-rolled SQL is
acceptable for M8 P0 because the BC ships with exactly one migration.**

| Option | Pros | Cons |
|---|---|---|
| **Alembic** (recommended) | Versioned migration history (`alembic_version` table mirrors Flyway's `flyway_<schema>` pattern); future schema changes get a free upgrade path; aligns with the Java BCs' Flyway discipline | One extra dependency + one extra directory + one extra config file to maintain for a single P0 migration |
| **Hand-rolled SQL** (acceptable) | Zero extra dependency; one `schema.sql` file containing `CREATE SCHEMA IF NOT EXISTS arch; CREATE TABLE IF NOT EXISTS arch.outputs (...)`; runs at container startup via a one-liner shell or via psycopg in `app/main.py`'s startup event | No migration history table; second migration would require introducing Alembic anyway (the M8.1 hook below) |

**Forward hook:** if M8.1 adds any schema change (a column on
`arch.outputs`, a new table), the implementer migrates to Alembic
at that point. The transition is non-destructive — the existing
`arch.outputs` table is bound to Alembic by stamping
`alembic_version` to the initial revision.

The DDL bytes are identical in both options — the `arch.outputs`
columns + indexes from §18 (and ratified in A05.6) carry over
unchanged.

### §A18.8. Observability — prometheus-fastapi-instrumentator + structlog

**Decision: M8 wires `prometheus-fastapi-instrumentator` for the
HTTP-metrics surface and `structlog` for structured JSON logging.
M5's Prometheus scrape config picks up `massing-gen-api:18083/metrics`
the same way it picks up Java BCs' Spring Boot Actuator `/actuator/prometheus`.**

| Concern | Pin |
|---|---|
| Metrics endpoint | `GET /metrics` (default for `prometheus-fastapi-instrumentator`) |
| Scrape config | Prometheus `static_configs:` entry already covers `massing-gen-api:18083`; the path differs from Spring Boot Actuator's `/actuator/prometheus`, so M5's `infra/prometheus/prometheus.yml` gains a per-job override `metrics_path: /metrics` for the `massing-gen-api` job |
| Default metric series | HTTP request count / duration / size histograms by route + status; resource metrics (memory, CPU) via Prometheus's standard process collectors auto-registered by the instrumentator |
| Logging | `structlog` configured to emit JSON to stdout; Loki (M5's log aggregator per ADR-15) ingests via docker-compose's stdout pipe + Alloy |
| M5 dashboard impact | The "BC health" table on the M5 dashboard gains a row for `massing-gen-api`. The metric ids differ slightly (e.g., `http_requests_total` from FastAPI vs `http_server_requests_seconds_count` from Spring Boot), so M5's PromQL whitelist (ADR-15 §"metric-id whitelist") adds the FastAPI equivalents. The dashboard renders the same shape; the underlying query strings are BC-language-aware. M5 implementer addresses this in the next M5 maintenance pass — no M5 ADR amendment yet. |

### §A18.9. Testing — pytest

**Decision: the Java test surface from §22 is reimplemented in pytest
under `services/massing-gen/tests/`. Test intents (algorithm
correctness, error-code mapping, owner-only download semantics,
Korean summary format, JSON-schema validation, brief visibility
enforcement) carry over 1:1; class names and assertion grammars
change.**

| Test file | Java predecessor (§22) | Notes |
|---|---|---|
| `test_algorithm.py` | `MassingAlgorithmTest` | Six assertions: (a) total box area ≥ sum room area, (b) floor-count formula = `ceil(totalArea/siteArea)`, (c) box inside site footprint, (d) no per-floor overlap, (e) over-area input raises `MassingError(MASSING_ALGORITHM_FAILED)`, (f) edge cases. |
| `test_summary.py` | `MassingSummaryTest` | Korean format `"12실 · 3층 · 총 480 m²"`; locale-independent (use Python `format(..., locale=None)` equivalent or `f"{x:.0f}"` with explicit grouping disabled). |
| `test_brief_extractor.py` | `SpringAiBriefExtractorAdapterTest` | `respx` mocks spark-inference-gateway's `/v1/chat/completions`. Happy / empty-rooms / non-JSON / extra-fields / 5xx burst cases. Note: "5xx burst → circuit-open" assertion is **retired** because M8 P0 has no breaker (§A18.6). The remaining case is "5xx → returns `SIDECAR_FAILED`-class error after retries exhausted". |
| `test_docs_client.py` | `HttpBriefBodyAdapterTest` | `respx` mocks docs-api's `/internal/docs/public/{id}` + `/internal/docs/public/{id}/body`. Happy / `BRIEF_NOT_READY` / `BRIEF_NOT_ACCESSIBLE` / `BRIEF_NOT_FOUND` / header-forwarding cases. |
| `test_rhino_serializer.py` | `Rhino3dmAdapterTest` | No HTTP mock — the serializer is in-process. Test calls `rhino_serializer.serialize([RoomBox(...), ...])` → asserts (a) bytes non-empty, (b) `rhino3dm.File3dm.Read(bytes)` round-trips, (c) layer names match `roomName`, (d) `floor` user-text present, (e) box geometry within site bounds. The Java side's "breaker OPEN" test is retired. |
| `test_generate_massing_usecase.py` | `GenerateMassingUseCaseTest` | Mocks all three ports (docs-client, llm-client, rhino-serializer). Asserts orchestrator wires error propagation correctly and persists `arch.outputs` row. |
| `test_tools_router.py` | `GenerateMassingControllerTest` | FastAPI `TestClient`; missing `briefDocId` → 400; happy-path response shape; `<CODE>: <message>` prefix in error body. |
| `test_outputs_router.py` | `ArchOutputDownloadControllerTest` | Owner sees 200 + bytes; non-owner sees 404; `Content-Disposition` filename pattern. |

The Java-side `MassingGenE2ETest` (which spun up the real
`rhino3dm-bridge` sidecar via Testcontainers) is **retired** —
there is no sidecar to spin up. The equivalent "actually opens in
Rhino" gate becomes a manual implementer-side check during PR
review, plus the `test_rhino_serializer.py` round-trip assertion.

The Java-side `MassingToolDescriptorTest` + `ToolCatalogIntegrationTest`
(in `rag-chat-domain`) are **preserved unchanged** — those tests live
on the Java side and verify the descriptor that points at
`massing-gen-api:18083/internal/tools/generate-massing`. The descriptor
URL is language-neutral.
