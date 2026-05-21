# ADR-16: M6 Docs PDF Support — Implementation Decisions

## Status
Accepted

## Context

The M6 PRD (`docs/prd/M6-docs-pdf.md`, ships as PR #187 ahead of this
ADR) pins the bounded context, the upload-surface MIME extension
(`.pdf` accepted alongside `.md`), the storage shape (extracted text
written to the existing `docs.documents.body` column, a new
`mime_type` column for source labelling), and the M3 ingestion
invariant (M3 sees PDF-derived bodies as Markdown — no M3 code change).
It deliberately defers **16 implementation-shape questions** to this
per-milestone ADR (PRD §"Open Questions for ADR-16") plus the cap
on the new error-code surface in `DocsErrorCode`.

ADR-15 did the same job for M5 (Prometheus/Loki/Alloy pins, PromQL
whitelist, port number, observability self-monitoring policy). ADR-14
did it for M4 (Spring AI streaming, Redisson rate limiter, chat
schema, cross-schema SELECT exception). ADR-13 did it for M3 (chunking
parameters, retry curves, DLQ topology, ingestion-complete signal).
ADR-12 did it for M2 (BlockNote, OpenSearch projector, body-fetch
internal HTTP route, 1 MB body size cap). This ADR-16 follows the
same shape — pin libraries with concrete coordinates, pin the OCR
fallback gating logic, pin the new error-code rows, capture each
deferred PRD question with a definite answer.

The M6 BC change is, in shape, **the smallest BC modification since
M2's S3 — a pure extension of the existing docs BC**:

- **No new BC.** docs-api remains the only entry point; docs-infra
  gains two new adapters (`PdfExtractorAdapter`, `VisionOcrAdapter`)
  and one new column on the existing `docs.documents` table.
- **No new Kafka surface.** `docs.document.uploaded` is published
  exactly as M2 ships it. M3's three consumers (uploaded /
  visibility-changed / deleted) are not modified. The PDF-derived
  `body` text is indistinguishable from authored Markdown at the
  event boundary — by design (PRD §"Decision 12 — M3 ingestion no
  change").
- **No new ADR-08 HTTP exception.** docs-api's Vision LLM call goes
  through Spring AI's OpenAI-compatible client to the existing
  `spark-inference-gateway` endpoint pinned by ADR-04 — which is on
  the "external dependency of the BC, not a sibling BC" side of
  ADR-08's "no BC-to-BC HTTP" rule. ADR-08 is **not** amended by
  this ADR.
- **No ADR-09 amendment.** The route surface (`POST /api/docs`
  multipart) is unchanged — the PDF branch is a content-type fork
  inside an existing route. The public/private allowlist matrix is
  untouched.
- **No `pgvector` change.** M3's chunk schema and HNSW index are
  unaffected.

What M6 does change:

- **One Flyway migration** on the `docs` schema — `mime_type` column
  added (default `text/markdown`; PDF uploads write `application/pdf`).
- **One library added** — Apache PDFBox 3.0.x.
- **One Spring AI shape extended** — first use of multimodal
  `Media`-attached `UserMessage` in this codebase (text→vision). A
  short amendment to ADR-04 is appended at the bottom of this ADR
  documenting the Vision modality introduction; the `Qwen3-VL` swap
  is informational because the spark-inference-gateway can serve
  text + vision from the same OpenAI-compatible API surface.
- **Five new error codes** in `DocsErrorCode` mapped to 400 / 413
  per ADR-11's hierarchy.
- **Three-tier input validation** on every multipart upload
  (extension → Content-Type → magic bytes).
- **Body cap raised from 1 MB to 10 MB** for **all** documents
  (PRD §"Body cap" — Markdown-authored docs benefit too; the cap
  raise is uniform, not PDF-conditional).

Like ADR-12 / ADR-13 / ADR-14 / ADR-15, none of the decisions below
supersede a transverse ADR's invariants; they fill in implementation
details inside the envelopes ADR-01 v2, ADR-02, ADR-04, ADR-05,
ADR-08, ADR-09, and ADR-11 defined. One amendment — to ADR-04 (a
**Vision modality** addendum, informational, no semantic change) — is
captured at the bottom of this ADR and appended inline to that file
in the same PR. ADR-00's index gains a row for ADR-16; module count
is unchanged (no new module, no new runnable).

## Decision

### 1. Apache PDFBox version pin — `org.apache.pdfbox:pdfbox:3.0.4`

**Decision:** **`org.apache.pdfbox:pdfbox:3.0.4`** — the latest 3.0.x
stable patch on Maven Central at the time of this ADR. The 3.0 line
is the current stable major; it ships ASF-published JDK 21 compatibility
notes (PDFBox 3.x is the first release line that drops the legacy
`java.applet` references that complicated 2.x on modern JDKs).

| Concern | Pin |
|---|---|
| Coordinate | `org.apache.pdfbox:pdfbox:3.0.4` |
| License | Apache 2.0 (compatible with this project's license posture; same as commonmark-java, JTokkit on the M3 side) |
| Java compatibility | JDK 21 LTS (the project's target per ADR-01) |
| Transitive deps | `org.apache.pdfbox:fontbox`, `commons-logging:commons-logging` |
| **NOT used** | `org.apache.pdfbox:pdfbox-tools` — the CLI utilities package. The BC needs the library API, not the `Main`-class entry points. Excluding `pdfbox-tools` keeps the fat-jar lean. |
| **NOT used** | `org.apache.pdfbox:pdfbox-app` — same reason. |

**Coordinate placement:** added directly to `backend/docs/docs-infra/build.gradle.kts`'s
`dependencies` block (the project does not yet have a `gradle/libs.versions.toml`
catalog — the M2 / M3 / M4 / M5 ADRs all added their library coordinates
inline in the same way). When a future ADR introduces a version
catalog, PDFBox migrates to it; until then, the inline declaration
is consistent with M2 / M3 / M4 / M5.

```kotlin
// backend/docs/docs-infra/build.gradle.kts (excerpt — M6 addition)
dependencies {
    // ... existing M2 / M3 / M4 / M5 entries ...

    // M6: PDF text extraction (ADR-16 §1) + page rendering for Vision OCR (§3)
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
}
```

**`commons-logging` adapter note (ADR-11 / Spring Boot inheritance):**
PDFBox depends on `commons-logging` (JCL). Spring Boot's `spring-jcl`
module already replaces JCL with an SLF4J bridge on the classpath of
every BC's `-api` module via the Spring Boot starter (the dependency
is part of `spring-core` which transits everywhere). PDFBox's JCL
calls are redirected to SLF4J without any explicit binding step;
operator should see PDFBox INFO/WARN logs flowing through the same
Logback pipeline that emits the structured JSON M1's ADR-10 §8 pins.
**No explicit exclusion or bridging is needed** — Spring Boot's JCL
shim handles it.

**Considered alternatives:**

- **PDFBox 2.0.32** (the last 2.x release). Rejected — the 2.x line
  is in maintenance-only mode; 3.x is the active line and the one
  the ASF Security advisories track first. JDK 21 works on 2.x but
  2.x's reflection patterns trigger more `--add-opens` warnings on
  modern JDKs.
- **iText 7** (`com.itextpdf:itext7-core`). Rejected — AGPL license
  (commercial-only alternative for closed-source). The playground
  is a personal project and not commercially distributed, but the
  AGPL surface affects any future open-sourcing posture. Apache 2.0
  is the lower-friction choice.
- **Apache Tika** (`org.apache.tika:tika-core` + `tika-parsers-standard-package`).
  Rejected — Tika is a content-extraction abstraction over many
  formats including PDF; underneath it ships its own PDFBox bundle.
  M6 P0 only handles `.pdf`, so the Tika abstraction is dead weight
  (tens of MB of fat-jar size for a format range the BC does not
  accept). If M6.x grows to handle `.docx` / `.pptx` (PRD §"Out of
  scope (P2)" — `.docx`, `.pptx`, other binary doc formats), revisit
  Tika at that time.
- **Marker** (Python-based PDF→Markdown library, runs as a sidecar).
  Rejected — adds a Python container to compose for a feature
  PDFBox covers in-process. The PRD §"Decision 1 — PDFBox" + §"Decision 2 —
  Vision OCR hybrid" combination already covers Marker's
  layout-awareness via the OCR fallback, without the new container.

### 2. Vision LLM call mechanism — Spring AI 1.0 `ChatClient` with `Media`-attached `UserMessage`

**Decision:** docs-infra's `VisionOcrAdapter` uses **Spring AI 1.0's
multimodal API** — `UserMessage` constructed with `Media` parts — via
the same `ChatClient` bean configured against `spark-inference-gateway`
per ADR-04 (amended below). **No direct WebClient HTTP construction**
— the BC stays inside Spring AI's abstraction so the model swap
(Qwen3-VL ↔ Qwen3-30B-A3B-text ↔ future models) is one property
change, not an adapter rewrite.

```java
// backend/docs/docs-infra/.../VisionOcrAdapter.java (sketch — exact
// implementation in the Stage 3 implementer's hands)

@Component
public class VisionOcrAdapter implements VisionOcrPort {

    private final ChatClient visionChat;
    private final VisionOcrProperties props;

    public VisionOcrAdapter(ChatClient.Builder builder, VisionOcrProperties props) {
        this.visionChat = builder
            .defaultSystem("이 페이지를 정확한 markdown으로 변환해줘. "
                + "표는 markdown table로, heading은 #/##로, 본문은 그대로. "
                + "추가 설명 없이 markdown만 출력. 코드블록(```)으로 감싸지 말 것.")
            .build();
        this.props = props;
    }

    @Override
    public String renderToMarkdown(byte[] pngBytes) {
        Media image = Media.builder()
            .mimeType(MimeTypeUtils.IMAGE_PNG)
            .data(pngBytes)
            .build();
        UserMessage userMessage = UserMessage.builder()
            .text("(blank — image carries the content)")
            .media(image)
            .build();
        ChatOptions options = ChatOptions.builder()
            .model(props.visionModel())    // resolved from SPRING_AI_VISION_MODEL
            .temperature(0.1)              // OCR is near-deterministic; 0.1 leaves tiny robustness slack vs strict 0.0
            .build();
        String raw = visionChat.prompt()
            .messages(userMessage)
            .options(options)
            .call()
            .content();
        return stripCodeFence(raw);
    }

    private static String stripCodeFence(String raw) {
        // If model wrapped its output in ```markdown ... ``` or ``` ... ```,
        // strip the fence. Defensive — the system prompt asks NOT to fence,
        // but small Korean Vision models occasionally do.
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && closingFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, closingFence).strip();
            }
        }
        return trimmed;
    }
}
```

**Why Spring AI's `Media` + `UserMessage` and not raw WebClient:**

- **One LLM client abstraction across the codebase.** M3 (embeddings),
  M4 (chat streaming + auto-title), and now M6 (vision OCR) all go
  through the same `ChatClient` / `EmbeddingModel` beans. Adding a
  parallel WebClient hand-rolled adapter for OCR would bifurcate the
  LLM call site shape — every observability hook, every Resilience4j
  wrap, every timeout policy would need two implementations.
- **Spring AI 1.0 GA pins `Media`** (per ADR-04's GA pin). The
  multimodal API is part of the 1.0 contract; it is not the
  pre-1.0-snapshot moving target it was during 0.8.x.
- **Model swap is a property override.** Switching from
  `qwen3-vl-30b-a3b` to a hypothetical future `qwen3-vl-72b` is
  changing `SPRING_AI_VISION_MODEL` in `application.yml` /
  `docker-compose.yml`. No code change.

**Configuration shape:**

```yaml
# backend/docs/docs-api/src/main/resources/application.yml (excerpt — M6 addition)
playground:
  docs:
    pdf:
      ocr-fallback-threshold-chars: 30   # PDFBox-extracted text length below
                                         # this triggers Vision OCR for the
                                         # page (per §3 below)
      max-pages-total: 200               # hard cap on PDF page count
      max-pages-ocr: 30                  # hard cap on OCR-fallback page count
      render-dpi: 150                    # PDFBox PDFRenderer DPI for OCR images
      vision-model: ${SPRING_AI_VISION_MODEL:qwen3-vl-30b-a3b}
      vision-timeout-seconds: 30         # per-page Vision LLM timeout
      vision-retry-attempts: 1           # 1 retry on transient errors
                                         # (so 1 initial + 1 retry = 2 attempts)
```

A `DocsPdfProperties` `@ConfigurationProperties` POJO in `docs-infra`
binds these (Spring Boot type forbidden in `-app` / `-domain` per
ADR-02). The `VisionOcrPort` interface lives in `docs-app` / `docs-domain`
(see §10 below — module placement); the adapter implementing it sits
in `docs-infra`.

**Why NOT use the LLM for everything (pure-LLM path, no PDFBox):**
Considered and rejected. Page-by-page Vision OCR is **5-15 seconds
per page** at 150 DPI on a 30B model; a 200-page PDF would take
20+ minutes synchronously. PDFBox extracts a 200-page text-PDF in
sub-second. The hybrid (§3) reserves the LLM for the pages PDFBox
fails on, keeping happy-path latency in the same range as M2's
Markdown uploads.

### 3. Hybrid OCR algorithm — per-page PDFBox-first, fallback to Vision if extracted text < 30 chars

**Decision:** per-page evaluation of PDFBox-extracted text;
fallback to Vision LLM only on pages where PDFBox returns
near-empty content (configurable threshold, default 30 characters).
The fallback is **per-page**, not whole-document — a mixed PDF
with a scanned cover page and 199 text pages incurs **one** OCR
call, not 200.

**Algorithm (pseudo-code, pinned in `docs-domain`'s
`PdfExtractionPolicy` / `docs-app`'s `ExtractPdfTextUseCase`):**

```java
// docs-app — ExtractPdfTextUseCase.extract(byte[] pdfBytes) sketch.
// Pre-conditions: input passed §4 three-tier validation (extension,
// Content-Type, magic bytes). Post-conditions: returns Markdown
// joined across pages, or throws a DocsErrorCode-tagged exception.

PDDocument doc;
try {
    doc = Loader.loadPDF(pdfBytes);
} catch (InvalidPasswordException e) {
    ExceptionCreator.of(DocsErrorCode.PDF_ENCRYPTED).throwIt();
} catch (IOException e) {
    ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
}

int totalPages = doc.getNumberOfPages();
if (totalPages > MAX_PAGES_TOTAL) {                  // default 200
    ExceptionCreator.of(DocsErrorCode.PDF_TOO_MANY_PAGES,
        totalPages, MAX_PAGES_TOTAL).throwIt();
}

PDFTextStripper stripper = new PDFTextStripper();
stripper.setSortByPosition(true);                    // reading-order
PDFRenderer renderer = new PDFRenderer(doc);

List<String> pageMd = new ArrayList<>(totalPages);
int ocrFallbacks = 0;

for (int p = 0; p < totalPages; p++) {
    stripper.setStartPage(p + 1);                    // PDFBox is 1-indexed
    stripper.setEndPage(p + 1);
    String text = stripper.getText(doc)
        .replace("\f", "\n\n")                       // form-feed → para break
        .strip();
    if (text.length() >= OCR_FALLBACK_THRESHOLD) {   // default 30 chars
        pageMd.add(text);
    } else {
        ocrFallbacks++;
        if (ocrFallbacks > MAX_PAGES_OCR) {          // default 30
            ExceptionCreator.of(DocsErrorCode.PDF_TOO_MANY_OCR_PAGES,
                ocrFallbacks, MAX_PAGES_OCR).throwIt();
        }
        BufferedImage img = renderer.renderImageWithDPI(
            p, RENDER_DPI, ImageType.RGB);           // default 150
        byte[] pngBytes = toPng(img);
        try {
            String visionMd = visionOcrPort
                .renderToMarkdownWithRetry(pngBytes, RETRY_ATTEMPTS);
            pageMd.add(visionMd);
        } catch (VisionTimeoutException | VisionGatewayException e) {
            log.warn("Vision OCR failed for page {} after retries; "
                + "page contributes empty markdown. cause={}", p, e);
            pageMd.add("");                          // continue rather than fail
        }
    }
}

return String.join("\n\n", pageMd);                  // page boundary = \n\n
```

**Per-page guarantees:**

- `setSortByPosition(true)` is set once before the per-page loop —
  PDFBox extracts text in visual reading order rather than the
  underlying content-stream order. This matters for two-column briefs
  (rare in M6 corpora, but cheap insurance).
- `\f` (form-feed) characters that PDFBox emits between content
  blocks within a page are normalized to `\n\n` — a Markdown
  paragraph break is the closest semantic match. The page-to-page
  join (`String.join("\n\n", pageMd)`) uses the same separator, so
  the final body has uniform paragraph breaks regardless of source
  (within-page form-feed vs. across-page boundary).
- Pages where PDFBox returns < 30 characters (after `.strip()`) are
  treated as image-only and dispatched to Vision OCR. The threshold
  catches scanned-image pages (PDFBox returns 0–10 characters of
  noise) and decorative-cover-page-only-with-page-number pages
  (PDFBox returns `"3"` or similar).

**Why 30 characters and not 0:**

- PDFBox returns junk on some image-only pages — `\f`, a page
  number, an artifact-text fragment from an OCR'd-then-rasterized
  source PDF. A pure-zero threshold under-triggers OCR on those
  pages (the page is image-only but PDFBox returned "Page 12" so we
  treat it as text-extractable).
- 30 characters is approximately one short sentence in Korean — any
  legitimate page of brief content far exceeds it. Cover pages with
  just a title and a date typically run 40-80 characters and stay
  on the PDFBox path.
- Tunable via `playground.docs.pdf.ocr-fallback-threshold-chars`.
  Operator can crank to 100 if a corpus turns out to need more
  aggressive fallback, or to 5 if the corpus is text-heavy and the
  default over-triggers OCR.

**Per-page Vision retry:**

- 1 initial attempt + 1 retry = 2 attempts maximum. Transient errors
  (HTTP 5xx from `spark-inference-gateway`, `TimeoutException`,
  `ConnectException`, `IOException`) are retryable; 4xx is not.
- Both attempts failing → that one page contributes an empty
  string to `pageMd`. **The entire upload does not fail** — a
  60-page PDF with one OCR-unreachable page yields a body with
  one paragraph missing, not a 503.
- Implementer's note: the retry is intra-page; if the Vision LLM
  is fully down (5xx on every call) **and** the PDF has more than
  one OCR-fallback page, the retry budget is exhausted per-page
  but each page is still attempted. A 30-page-OCR PDF against a
  fully-down Vision LLM would emit 30 WARN logs and an empty body
  — a degenerate but bounded outcome.

**Why "skip the failed page and continue" rather than 503-fail
the upload:** the PRD §"Decision 9 — Image-only PDF handled by
hybrid automatically" implies graceful degradation. The user has
already burned their upload attempt; the most user-actionable
outcome is "doc is created but some pages are empty" — they can
re-upload after the Vision LLM recovers, or accept the partial
extraction.

**Considered alternatives (PRD §"Open question — gating strategy"
sub-options):**

- **Option (a): single-cap on total pages only** (e.g., total ≤ 200
  for any PDF, regardless of OCR fallback). Rejected — under-bounds
  the Vision LLM cost. A 200-page scanned PDF would trigger 200
  Vision calls at ~10 s each = ~30 minutes of LLM work behind one
  HTTP upload, blocking the request thread and the user.
- **Option (b): two-cap** — `total ≤ 200` AND `ocrFallbacks ≤ 30`
  (chosen, above). Adds one error code (`PDF_TOO_MANY_OCR_PAGES`)
  to the 5-code PRD count, bringing the total new codes to 6 (see §5).
- **Option (c): gating-by-discovery** — process page-by-page, on
  the Nth OCR fallback abort if total pages > N × some-ratio.
  Rejected — runtime-coupled limit is harder to reason about and
  to test. The two-cap option is statically bound at request
  acceptance.

The PRD's "단순화 채택 권장" recommendation aligns with option (b);
this ADR adopts it.

### 4. Three-tier input validation — extension, Content-Type, magic bytes

**Decision:** every multipart upload to `POST /api/docs` (existing
`createMultipart` controller method) runs three validation tiers
**in order**, cheap-first. Failure at any tier returns `400` with
`INVALID_FILE_TYPE`. The tiers are:

| Tier | Check | What it catches |
|---|---|---|
| 1 | **Extension** — `filename.toLowerCase()` ends with `.md`, `.markdown`, or `.pdf` | Wrong-suffix uploads (.docx, .txt, .html). Cheap — no body read. |
| 2 | **Content-Type** — request `Content-Type` is one of `text/markdown`, `text/plain` (legacy MD), `application/pdf` | Stale extensions that survived a rename; conscious mis-labelling. Cheap — no body read. |
| 3 | **Magic bytes** (PDF only) — first 5 bytes of the uploaded body equal `%PDF-` (ASCII `25 50 44 46 2D`) | Renamed `.exe` / `.zip` / `.jpg` masquerading as `.pdf`. Requires a 5-byte peek into the stream. |

**Tier order rationale:**

- Tier 1 (extension) catches the most common user mistake — picking
  the wrong file in the OS dialog. ~99% of malformed inputs are
  caught here without any byte read.
- Tier 2 (Content-Type) catches the rare case where the OS / browser
  sent a mis-matched `Content-Type` header for a correctly-named
  file. Cheap.
- Tier 3 (magic bytes) is the safety check against deliberate
  mis-labelling (uploading a `.exe` as `evil.pdf`). The first 5
  bytes are read into a small buffer; if the check passes, the
  rest of the upload proceeds normally.

**Implementation placement:** the three tiers live in
`DocumentController.createMultipart(...)` (the existing
`docs-api` method) **before** the body-read happens. Tier 3
reads `file.getInputStream()` for 5 bytes, mark/reset (Spring's
`MultipartFile.getInputStream()` is repeatable on the default
`StandardMultipartFile` backing — read-then-replay is safe via
`file.getBytes()` once for the 5-byte sniff plus a full read for
PDFBox, since the upload is bounded by §5's 25 MB cap).

**For `.md` uploads:** tier 3 is skipped (no magic bytes for plain
text). Tiers 1 + 2 are sufficient.

**Error code:** all three tiers throw the **same**
`INVALID_FILE_TYPE` error code (per §5) — the user does not need
to know which tier rejected them; the actionable advice ("upload a
.md or .pdf file") is identical.

**Why tier 3 is PDF-only:** there is no canonical magic-byte
signature for "Markdown" — the format is plain text. Tier 1 + tier 2
are the only signals available for `.md`.

**Edge case — `.pdf` filename with `application/octet-stream`
Content-Type:** some clients (curl, certain Android browsers) send
`application/octet-stream` for any binary upload regardless of
extension. Tier 2 rejects this — `application/octet-stream` is not
in the accepted list. **Acceptable in P0** — the workaround is for
the client to set `-H "Content-Type: application/pdf"` (curl) or
upgrade the browser. The PRD's spec on "3단 입력 검증" matches this
strictness; a P1 amendment could relax tier 2 to "Content-Type
optional if extension + magic bytes both pass" if real users hit it.

### 5. New `DocsErrorCode` rows — 6 codes (5 from PRD + 1 from §3 two-cap)

**Decision:** six new error-code enum constants added to
`DocsErrorCode` (per ADR-11 — `<BC>-<SUBSYSTEM>-<NNN>` format,
`@MappedTo` annotation pins HTTP status). All six are PDF-subsystem
codes (`DOCS-PDF-NNN`).

| Constant | Code | HTTP status | `@MappedTo` | Default message template |
|---|---|---|---|---|
| `INVALID_FILE_TYPE` | `DOCS-PDF-001` | 400 | `BadRequestException` | `Uploaded file is not a supported format — only .md and .pdf are accepted` |
| `PDF_CORRUPTED` | `DOCS-PDF-002` | 400 | `BadRequestException` | `Could not read this PDF — the file appears corrupted or malformed` |
| `PDF_ENCRYPTED` | `DOCS-PDF-003` | 400 | `BadRequestException` | `Could not read this PDF — the file is password-protected` |
| `PDF_TOO_MANY_PAGES` | `DOCS-PDF-004` | 400 | `BadRequestException` | `PDF has {0} pages; the maximum is {1}` |
| `PDF_TOO_MANY_OCR_PAGES` | `DOCS-PDF-005` | 400 | `BadRequestException` | `PDF has {0} pages requiring OCR; the maximum is {1}. Try a text-based PDF or split the file.` |
| `FILE_TOO_LARGE` | `DOCS-PDF-006` | 413 | `BadRequestException` (mapped at the advice to **413** in the response — see note) | `Uploaded file size {0} bytes exceeds the maximum {1} bytes` |

**Note on `FILE_TOO_LARGE` → 413:** ADR-11's six subclasses pin
status at the class level. `BadRequestException` → 400. To return
413 (Payload Too Large) instead, we have two options:

- **Option A — introduce a `PayloadTooLargeException` subclass** in
  `shared-kernel`. Cost: a 7th subclass in the hierarchy; touches
  ADR-11.
- **Option B — keep `BadRequestException` but let Spring's
  `MaxUploadSizeExceededException` advice handler intercept first**
  (which already maps to 413 via Spring's defaults). The
  `FILE_TOO_LARGE` enum row becomes a fallback path for explicit
  size checks the controller does before invoking PDFBox.

**This ADR picks Option B** — the practical 413 surface comes from
Spring's built-in `MaxUploadSizeExceededException` advice (already
handled by the shared advice in ADR-11's mapping table). The
`FILE_TOO_LARGE` enum row exists for **defense-in-depth checks the
controller does explicitly** (e.g., a future stream-level guard
that aborts before Spring's multipart resolver buffers the entire
upload). When triggered, it returns 400 by ADR-11's class mapping.
The user-facing semantics are identical — either way the upload was
rejected for being too large; the body's `errorCode` field
distinguishes the cause for tooling.

If a future M6.x decides 413-from-explicit-check is operationally
material (i.e., the frontend wants to detect "size" separately from
"type"), a `PayloadTooLargeException` subclass lands in ADR-11
amendment then.

**`DocsErrorCode.java` after this ADR** (delta from the M2 listing
above — appended at the end of the enum, preserving the existing
constants):

```java
// docs-domain/src/main/java/com/playground/docs/domain/exception/DocsErrorCode.java
// (additions below the existing SEARCH_UNAVAILABLE constant)

@MappedTo(BadRequestException.class)
INVALID_FILE_TYPE("DOCS-PDF-001",
    "Uploaded file is not a supported format — only .md and .pdf are accepted"),

@MappedTo(BadRequestException.class)
PDF_CORRUPTED("DOCS-PDF-002",
    "Could not read this PDF — the file appears corrupted or malformed"),

@MappedTo(BadRequestException.class)
PDF_ENCRYPTED("DOCS-PDF-003",
    "Could not read this PDF — the file is password-protected"),

@MappedTo(BadRequestException.class)
PDF_TOO_MANY_PAGES("DOCS-PDF-004",
    "PDF has {0} pages; the maximum is {1}"),

@MappedTo(BadRequestException.class)
PDF_TOO_MANY_OCR_PAGES("DOCS-PDF-005",
    "PDF has {0} pages requiring OCR; the maximum is {1}. "
    + "Try a text-based PDF or split the file."),

@MappedTo(BadRequestException.class)
FILE_TOO_LARGE("DOCS-PDF-006",
    "Uploaded file size {0} bytes exceeds the maximum {1} bytes");
```

Frontend `i18n` consumes these codes for localized error rendering;
the M6 design doc §6.2 ("Error states") references the same codes.

### 6. PDF page rendering for Vision OCR — `PDFRenderer.renderImageWithDPI(p, 150, ImageType.RGB)`

**Decision:** when the OCR fallback triggers for a page (per §3), the
adapter renders that page via PDFBox's `PDFRenderer.renderImageWithDPI(pageIndex, 150, ImageType.RGB)`.
The resulting `BufferedImage` is serialized to PNG via `ImageIO.write(img, "PNG", out)`.
The PNG bytes are the `Media` payload for the Vision call (§2).

| Concern | Pin |
|---|---|
| Render API | `PDFRenderer.renderImageWithDPI(pageIndex, dpi, ImageType)` |
| DPI | **150** (PRD §"Decision 13 — render-dpi 150 trade-off") — balances OCR readability vs. payload size + Vision latency. 96 = noticeable accuracy drop on small Korean text; 300 = ~3× payload bytes for marginal accuracy gain. |
| Color depth | **`ImageType.RGB`** — Vision models do better with color signal even when the source is grayscale (preserves diagram colorings, table cell highlights). `BINARY` was rejected as too aggressive — loses light-pencil annotations in scanned briefs. `GRAY` was rejected as middle-ground without clear win. |
| Output format | **PNG** — lossless, palette-friendly. PNG bytes are typically 200-800 KB per page at 150 DPI for A4. Vision model accepts PNG natively (OpenAI image API contract). |
| In-memory or temp file | **In-memory** — `ByteArrayOutputStream`, never touches disk. PNG bytes flow directly into `Media.builder().data(pngBytes)`. |
| `BufferedImage` lifecycle | The adapter calls `img.flush()` after PNG serialization to release the off-heap pixel buffer. (PDFBox's `PDFRenderer` allocates a large `int[]` array per page; the GC will eventually reclaim it, but `flush()` is the documented way to be polite.) |

**Memory budget:** at 150 DPI / RGB / A4-portrait, the in-memory
`BufferedImage` is approximately `1240 × 1754 × 4 bytes` = ~8.7 MB
per page. Together with PNG serialization buffer (~1 MB), peak
per-page heap is ~10 MB. For a 30-OCR-page worst case **sequenced**
(per the algorithm in §3), peak is one page at a time = ~10 MB —
the loop releases each `BufferedImage` before allocating the next.

**Concurrency caveat:** the per-page loop is single-threaded by
construction in §3. If a future M6.x parallelizes OCR across pages
(say, 5 concurrent Vision calls), the memory budget jumps to 5 ×
10 MB = ~50 MB — still acceptable on the personal-scale JVM but
worth noting. The §13 throttling decision keeps this single-threaded
in M6 P0.

**Considered alternatives:**

- **300 DPI rendering.** Rejected — 3× the bytes (≈30 MB
  `BufferedImage`, ≈800 KB-2 MB PNG) for under 5% Vision accuracy
  delta on the brief-style PDFs that motivate M6.
- **96 DPI (screen-default).** Rejected — Korean Hangul on small
  subheadings (10-11 pt source) reads as smudged glyphs at 96 DPI;
  the Vision LLM hallucinates characters. 150 DPI is the floor for
  reliable Korean OCR.
- **JPEG instead of PNG.** Rejected — lossy compression introduces
  artifacts that confuse Vision models, especially around thin
  glyph strokes. PNG's larger size is acceptable given the Vision
  call is the dominant latency cost anyway (network + inference ≫
  PNG serialization).
- **Skip rendering — send the original PDF page bytes** (one page
  of a PDF, re-exported). Rejected — Spring AI's `Media` accepts
  `application/pdf` per the OpenAI contract but Qwen3-VL's PDF
  support is less robust than its image support. The "rasterize
  first, send image" route is the lower-risk path; revisit if Qwen3-VL
  ships better PDF support in a later model.

### 7. Vision call timeout, retry, and prompt

**Decision:** per-page Vision call wraps the `ChatClient.call()` in
a `Mono`-style timeout (Spring AI's `ChatClient` returns synchronous
results in this adapter — wrapped via `WebClient`'s reactive timeout
internally; per-call timeout is enforced via Spring AI's `ChatOptions`
`timeout` and as a belt-and-suspenders by a `CompletableFuture`
wrapper in the adapter). Both timeout-source layers are configured
to **30 seconds** per page.

| Concern | Pin |
|---|---|
| Per-page timeout | **30 seconds** (`playground.docs.pdf.vision-timeout-seconds`) |
| Retry attempts | **1 retry** (so 1 initial + 1 retry = 2 attempts maximum) |
| Retry classification | retryable: 5xx from `spark-inference-gateway`, `TimeoutException`, `ConnectException`, `IOException`. Non-retryable: 4xx (400 invalid input — payload structure error; 413 image too large — likely a degenerate page like a 64MB embedded photo, fail fast). |
| Backoff before retry | 1 second fixed delay (single retry — no exponential ladder needed) |
| Failure handling | both attempts failing → that page contributes an empty markdown string; loop continues for remaining pages |
| **Per-page max-tokens** | **1200** (`spring.ai.openai.chat.options.max-tokens` on docs-api). A normal-page markdown lands at ~800-1100 completion tokens; 1200 leaves slack for dense text/table pages but caps runaway-loop blowups (see amendment 2026-05-21). |
| **Per-call frequency penalty** | **0.5** (`spring.ai.openai.chat.options.frequency-penalty` on docs-api). Conservative mid-value within OpenAI's `[-2.0, 2.0]` range. Depresses repeated-token logits — kills the "same map label emitted 20×" hallucination on image-heavy pages without measurably affecting normal-page output (see amendment 2026-05-21). |
| **Timeout-source layer** | Spring AI 1.0 wraps WebClient under the hood; setting `responseTimeout` on the WebClient bean (via `WebClient.Builder.exchangeStrategies(...)` indirectly + reactor netty `HttpClient.responseTimeout(Duration.ofSeconds(30))`) is the implementer-owned hook. Belt-and-suspenders `CompletableFuture.orTimeout(...)` in the adapter is also OK. |

**System prompt (pinned — implementer transcribes verbatim):**

```
이 페이지를 정확한 markdown으로 변환해줘. 표는 markdown table로, heading은
#/##로, 본문은 그대로. 추가 설명 없이 markdown만 출력. 코드블록(```)으로
감싸지 말 것.
```

**User prompt (per-page, image carries the content):**

The user message text is a one-line marker (e.g., `(blank — image
carries the content)`); the actual content is the attached PNG
`Media` part. Some OpenAI-compatible servers reject zero-length
user-message text — the marker dodges that without adding semantic
load.

**Response post-processing (in `VisionOcrAdapter`):**

- Strip leading/trailing whitespace.
- If the response is wrapped in a triple-backtick code fence (with
  or without `markdown` language tag), strip the fence. The system
  prompt asks NOT to fence, but small Vision models occasionally do;
  the strip is defensive.
- If the response is JSON-wrapped (`{"markdown": "..."}` or similar),
  **reject** — log WARN, throw a typed exception that retries
  classify as non-retryable, page contributes empty. This is rare
  in practice; the system prompt is clear.
- The cleaned string is the page's contribution to `body`.

**Why temperature 0.1 (not 0.0):** OCR is a near-deterministic task — the
same image should yield approximately the same Markdown. Strict 0.0 was
the initial proposal, but the implementer pinned 0.1 in `docs-api`'s
`application.yml`, and the post-2026-05-21 bench against the real KFI
brief PDF showed 0.1 produces stable, high-fidelity output (heading +
markdown table extraction matched the source closely on text pages
p1/p28; the only quality issue surfaced was the runaway-loop on
image-heavy p26, which is unrelated to temperature and is addressed by
the `frequency-penalty` + `max-tokens` knobs documented in §7's pin
table and the 2026-05-21 amendment). 0.1 is mid-conservative enough to
keep regression testing meaningful while leaving a hair of robustness
against tokenizer edge cases — for OCR pipelines this is the more
common operating point than strict zero.

### 8. Caps — multipart size, total pages, OCR pages

**Decision:** three caps, all enforced at request acceptance time
(before PDFBox is loaded with the body).

| Cap | Value | Enforced by | Failure mode |
|---|---|---|---|
| Multipart file size | **25 MB** (`spring.servlet.multipart.max-file-size = 25MB`) | Spring multipart resolver | Spring's `MaxUploadSizeExceededException` → 413 via ADR-11 default advice |
| Multipart request size | **26 MB** (`spring.servlet.multipart.max-request-size = 26MB`) | Spring multipart resolver | same as above (with 1 MB headroom for envelope) |
| Total PDF page count | **200 pages** (`playground.docs.pdf.max-pages-total`) | `ExtractPdfTextUseCase` (after PDFBox `loadPDF`, before per-page loop) | `DocsErrorCode.PDF_TOO_MANY_PAGES` → 400 |
| OCR-fallback page count | **30 pages** (`playground.docs.pdf.max-pages-ocr`) | `ExtractPdfTextUseCase` (during per-page loop, on the `ocrFallbacks++` increment) | `DocsErrorCode.PDF_TOO_MANY_OCR_PAGES` → 400 |

**Multipart configuration (in `docs-api`'s `application.yml`):**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 25MB
      max-request-size: 26MB
      file-size-threshold: 1MB     # spill to disk for files > 1 MB rather
                                   # than keeping the whole upload in heap;
                                   # PDFBox can read from a temp-file stream
```

**`file-size-threshold` 1 MB:** Spring's default is 0 (everything
in heap). For 25 MB worst-case PDFs, in-heap buffering is OK on
this BC's JVM but `file-size-threshold: 1MB` spills larger uploads
to a temp file, reducing heap pressure. PDFBox's `Loader.loadPDF(File)`
works against the spilled file with zero-copy — the implementer's
choice of `loadPDF(byte[])` vs `loadPDF(File)` is one of memory-vs-disk
trade-off; either is acceptable for M6 P0.

**Why 25 MB and not 10 MB / 50 MB:** the PRD pinned 25 MB. M2's
Markdown body cap is 1 MB; PDFs of 25 MB cover the realistic upper
bound of design briefs (high-resolution scanned 100-page briefs
land around 15-20 MB). 50 MB enters the territory of "this is a
print-ready PDF with embedded raster catalogues" which is out of
M6's target use case.

**Why 200 pages and not 100 / 500:** PRD pin. 100 is too tight
(some competition briefs run 50-150 pages with image-heavy
appendices). 500 is too generous — at 5 seconds per page (PDFBox
extract + DB write), 500 pages = ~40 minutes wall-clock for a
worst-case PDFBox-text-only document, which is unreasonable behind
an HTTP request.

**Why 30 OCR pages and not 50 / 10:** PRD pin (PRD §"Decision 3 —
30 pages OCR cap"). At 30 s/page Vision timeout, 30 pages worst-case
= 15 minutes of Vision latency — borderline but bounded. 50 pages
pushes worst-case to 25 minutes which is the request-thread
exhaustion territory.

**Cap-overflow signal flow (worked example):**

| Scenario | Result |
|---|---|
| User uploads a 30 MB PDF | Spring rejects with 413 before docs-api sees the body (multipart cap) |
| User uploads a 10 MB PDF with 250 text pages | `loadPDF` succeeds; page-count check fires; 400 `PDF_TOO_MANY_PAGES` |
| User uploads a 10 MB PDF with 50 pages, 35 of which are scanned | per-page loop counts ocrFallbacks; on the 31st OCR page, 400 `PDF_TOO_MANY_OCR_PAGES`. Crucially: the user gets the error after pages 1-30 have already been processed (wasted Vision LLM calls). **This is a deliberate trade-off** — single-pass discovery is simpler than two-pass (count first, then extract). 30 wasted Vision calls is at most 30 × $cost/call which is bearable; alternative two-pass would always pay the page-count pass even when the PDF passes. |
| User uploads a 10 MB PDF with 30 pages, all scanned | per-page loop runs all 30 OCR calls; succeeds; body is the concatenated Vision Markdown |

**The "one-pass discovery wastes Vision calls" trade-off is
acceptable** because the operator-facing cost is dollars-per-PDF
not user-facing latency degradation (the call still fails fast on
page 31, the user sees a 400 in seconds-of-Vision-latency, not in
minutes). M6.1 may revisit with a "quick-scan first page only,
count text-coverage ratio, then decide" pre-pass; out of scope for
M6 P0.

### 9. Flyway migration — `V202605210001__add_mime_type.sql`

**Decision:** one migration on the `docs` schema, adding the
`mime_type` column with a CHECK constraint and a default. Backfill
of existing rows is automatic via the `DEFAULT` clause — existing
Markdown-authored documents get `mime_type = 'text/markdown'`
without an explicit UPDATE.

**File path:**
`backend/docs/docs-infra/src/main/resources/db/migration/V202605210001__add_mime_type.sql`

**Date convention:** `V202605210001` follows the existing M2
migration naming (`V202605180001__create_documents.sql`,
`V202605200001__create_document_likes.sql`) — `Vyyyymmddnnnn`
with the 21st being the date this ADR lands. If the implementer
opens this migration on a different day, they use that day's date
(the convention is "the day the migration file is authored",
not "the day the issue was opened").

**Migration content:**

```sql
-- ADR-16 §9 + M6 PRD — docs.documents gains a mime_type column to
-- distinguish PDF-derived bodies from authored Markdown.
--
-- The column defaults to 'text/markdown' so all existing rows are
-- backfilled at migration time without an explicit UPDATE statement.
-- New uploads set the column per the request's Content-Type
-- (text/markdown or application/pdf) in the docs-app service.
--
-- CHECK constraint pins the allowed values — defense in depth
-- against future code that might write an unexpected MIME string.

SET search_path = docs, public;

ALTER TABLE docs.documents
    ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'text/markdown';

ALTER TABLE docs.documents
    ADD CONSTRAINT documents_mime_type_chk
    CHECK (mime_type IN ('text/markdown', 'application/pdf'));

-- No index — mime_type is low-cardinality (2 values for M6 P0) and
-- no query filters on it (the frontend renders the (PDF) badge from
-- the row's mime_type, not from a query). Adding an index would
-- inflate write costs without query benefit. Revisit if a future
-- M6.x adds a mine-list filter "show only PDF documents".

COMMENT ON COLUMN docs.documents.mime_type IS
    'Source MIME type of the original upload. text/markdown for authored MD; '
    || 'application/pdf for PDF uploads whose extracted text now lives in body. '
    || 'Drives the (PDF) badge on the frontend doc detail surface per M6 design.';
```

**DDL-transaction note:** Postgres runs the `ALTER TABLE ... ADD
COLUMN ... DEFAULT 'text/markdown'` as a metadata-only update in
Postgres 11+ when the default is a constant. For the existing
docs corpus (tens to low hundreds of rows in personal scale), this
is sub-second. The migration is safe to run on a live system.

**Why `mime_type` and not `pdf_page_count` / `source_format` /
`is_pdf bool`:**

- `mime_type` is industry-standard nomenclature; `pdf_page_count`
  is too narrow (encodes only PDFs, not future `.docx` / `.pptx`
  ranges); `is_pdf bool` does not future-proof at all.
- `pdf_page_count` was explicitly **not adopted** in the PRD
  (PRD §"Decision 4 — mime_type column, pdf_page_count not introduced").
  The M6 design doc's "12 pages" meta-row tail is implementer
  optional — if shown, the page count can be derived at upload
  time and stored in a future column, but M6 P0 does not surface
  it (the design doc §2.6 says "if persisted drives the `(12 pages)`
  meta extra" — implementer's call; P0 says "show as 'PDF source'
  without page count" is acceptable).

**No corresponding Java change to the existing M2 Flyway history
table** — Flyway's `flyway_history` (under `flyway_docs` per
ADR-05) tracks this migration like any other.

### 10. Module placement — adapters in `docs-infra`, port in `docs-app`

**Decision (per ADR-02's layering rules — `-domain` no Spring, `-app`
has ports, `-infra` has adapters):**

| Component | Module | Type | Notes |
|---|---|---|---|
| `PdfExtractorPort` interface | `docs-app` | Java interface | Defined by the use case; no Spring import. |
| `VisionOcrPort` interface | `docs-app` | Java interface | Same as above. |
| `PdfExtractorAdapter` (implements `PdfExtractorPort` using PDFBox `Loader.loadPDF` + `PDFTextStripper` + `PDFRenderer`) | `docs-infra` | `@Component` | PDFBox is on `docs-infra`'s classpath only. |
| `VisionOcrAdapter` (implements `VisionOcrPort` using Spring AI `ChatClient` + `Media`) | `docs-infra` | `@Component` | Uses the existing ChatClient bean from Spring AI's auto-config (configured per ADR-04 against `spark-inference-gateway`). |
| `ExtractPdfTextUseCase` (orchestrates per-page loop, applies caps from §8, dispatches OCR fallback via §3 algorithm) | `docs-app` | `@Service` | Pure Spring service; depends on the two ports above. |
| `DocsPdfProperties` (`@ConfigurationProperties` for DPI / threshold / caps / model name) | `docs-infra` | `@ConfigurationProperties` | Bound under `playground.docs.pdf.*`. |
| `PdfExtractionPolicy` constants (default threshold, default DPI, default caps — fall-back if `DocsPdfProperties` is somehow not loaded) | `docs-domain` | Java constants class | Pure-Java; no Spring. |
| MIME-type value object (`MimeType` — `TEXT_MARKDOWN` and `APPLICATION_PDF` constants) | `docs-domain` | enum | Defense against magic-string sprawl; mapped to `mime_type` column at the entity layer in `-infra`. |
| `DocsErrorCode` (M6 additions — 6 new constants per §5) | `docs-domain` | enum addition | Existing enum from M2; ADR-16 adds 6 rows. |
| `DocumentController.createMultipart(...)` (M6 additions — three-tier validation per §4, dispatches to PDF or MD path) | `docs-api` | controller method edit | M2 method extended; same `POST /api/docs` multipart endpoint. |
| Flyway migration | `docs-infra/src/main/resources/db/migration/` | SQL | Per ADR-05 — runs against `docs` schema at boot. |

**Why `docs-app` owns the use case and not `docs-infra`:**

- ADR-02 places use-case orchestrators in the `-app` layer. The PDF
  extraction is an orchestrator over two ports (PDFBox extraction
  + Vision OCR); the per-page loop, the cap checks, the threshold
  comparison, and the error-code throws are application-layer
  logic. They depend only on port interfaces, not on PDFBox or
  Spring AI directly.
- This makes the use case **unit-testable without PDFBox or Spring
  AI on the classpath** — Mockito mocks of both ports + a synthetic
  per-page text/Vision-result sequence is the unit-test shape.

**Why both ports in `docs-app` and not in `docs-domain`:**

- Per ADR-02, port interfaces "live in `-app`'s `application/port/`
  package" (this is the convention M3's `EmbeddingPort`,
  `BodyFetchPort`, `DistributedLockPort` follow). The domain layer
  stays Spring-free and dependency-free; the application layer
  owns the contract between domain logic and infrastructure.
- M3 (ADR-13 §4 Component placement table) explicitly places port
  interfaces in `-app`. M6 follows the same convention.

**Why `MimeType` enum in `docs-domain` and not `docs-app`:**

- It's a value object — pure Java, no Spring, no dependencies. The
  `docs-domain` layer is the right home (consistent with `Visibility`,
  `DocumentPath`, `DocumentTitle`, `DocumentBody` value objects M2
  ships in `docs-domain`).
- The DB column type binding (`mime_type TEXT` → `MimeType` enum)
  happens at the JPA entity layer in `docs-infra` via Hibernate's
  `@Enumerated(EnumType.STRING)` — same pattern as `Visibility`.

**No new module is introduced.** The four-module quadruplet for the
docs BC (api / app / domain / infra) is unchanged; M6 adds files
inside the existing modules. The "module count" line in ADR-00 is
**not bumped** by this ADR.

### 11. Body cap raised — 1 MB → 10 MB (uniform, not PDF-conditional)

**Decision:** the `DocumentBody.MAX_OCTET_LENGTH` is raised from
**1,048,576 (1 MB)** to **10,485,760 (10 MB)**. The DB CHECK
constraint on `docs.documents.body` is raised accordingly via a
companion migration in the same M6 ADR window
(`V202605210002__raise_body_cap.sql`).

The raise applies to **all documents**, not just PDF-derived ones.
Reasoning:

- PDF-derived Markdown of a 25 MB / 200-page brief can exceed 1 MB
  trivially. A 100-page text-PDF produces ~3-5 MB of UTF-8 Markdown
  body — Korean text averages 2.5 bytes/char and 100 pages of
  brief-density content runs 200-400k characters.
- Authoring a PDF-conditional cap (`mime_type='application/pdf'`
  → 10 MB, else 1 MB) couples two unrelated concerns and creates
  a confusing user surface (the editor allows up to 1 MB Markdown
  but a PDF-derived 5 MB body is fine — discoverability is bad).
- 10 MB is well within Postgres's TEXT-column comfort zone (Postgres
  TOAST handles values up to ~1 GB per row; 10 MB is in the
  no-issue range).
- The frontend (BlockNote editor) currently doesn't enforce a
  per-character cap; raising the server-side cap doesn't require
  a corresponding frontend cap raise. Truly oversized Markdown
  authoring (>10 MB) is an edge case that lands a 400.

**Migration content (companion to §9's migration):**

```sql
-- ADR-16 §11 — raise body cap from 1 MB to 10 MB for both authored
-- Markdown and PDF-derived bodies. The previous M2 cap (1 MB) was
-- chosen for the Markdown-only corpus; M6's PDF extraction routinely
-- produces multi-MB bodies for long competition briefs.

SET search_path = docs, public;

ALTER TABLE docs.documents
    DROP CONSTRAINT documents_body_size_chk;

ALTER TABLE docs.documents
    ADD CONSTRAINT documents_body_size_chk
    CHECK (octet_length(body) <= 10485760);
```

**Companion Java change:** `DocumentBody.MAX_OCTET_LENGTH` is
updated from `1_048_576` to `10_485_760` (the constant in
`docs-domain`). The error message in `DocsErrorCode.BODY_TOO_LARGE`
("Document body exceeds maximum size (1 MB)") is updated to "(10 MB)".

**Why not a per-format cap (1 MB Markdown, 10 MB PDF):** considered
and rejected as above — confusing UX, no clear technical motivation
for keeping the cap on Markdown narrow.

**Why not "no cap" / "100 MB" / "1 GB":** the cap exists to bound
the worst-case `body` payload on every read path (frontend doc
detail render, RAG embedding, OpenSearch projection). A 100 MB
Markdown body would melt the frontend editor and inflate every
Kafka envelope retrieving it. 10 MB is the conservative middle.

**Effect on M3 (rag-ingestion):** M3's `MaxInMemorySize` for the
body-fetch WebClient (ADR-13 §11 — "explicitly set to 2 MB, 2× the
body cap") needs to be raised in lockstep when this ADR's body cap
takes effect. **Implementation note for the M6 implementer PR**:
update `rag-ingestion-infra`'s WebClient config to `maxInMemorySize:
12 MB` (2× the new 10 MB cap, preserving the same "2× cap"
headroom convention). This is a one-line config change in
`rag-ingestion`'s `application.yml`; M6's PR set includes it.

**Effect on M3 chunking:** the chunker handles the larger body
gracefully (M3's MarkdownAwareChunker is streaming-friendly within
a single in-memory string; the chunk count just grows proportionally
to body size). A 10 MB body produces ~2500-3000 chunks at 800
tokens each — within M3's "up to ~50k chunks total" corpus budget
even for a few large PDFs.

**Effect on M3 retention / cost:** ~10× more chunks per PDF means
~10× more embedding calls per PDF upload. At BGE-M3 batch size 32
(ADR-13 §2), a 3000-chunk PDF takes ~94 batches × ~10 s/batch ≈
15 minutes of background embedding work (asynchronous behind the
Kafka consumer; the user's upload returns immediately after PDF
extraction). Acceptable for M6 P0; revisit if real-world corpora
push end-to-end ingestion latency past the 30 s SLO from ADR-13 §6
(the SLO is P95 per event; a single fat-PDF event blowing past
that is a WARN, not a 503).

### 12. Concurrency, throttling, and resource budget

**Decision:** M6 P0 ships with **no explicit upload throttle** —
docs-api uses Tomcat's default thread pool (200 worker threads per
Spring Boot defaults) and accepts whatever concurrency Spring's
multipart resolver + the per-request PDFBox load can sustain.
Worst-case heap budget is **~300 MB transient per upload**
(25 MB multipart buffer + ~10 MB BufferedImage during OCR render +
~30 MB PDFBox internal buffers for a 200-page PDF + ~10 MB Markdown
extracted body buffer + ~250 MB JVM baseline). With concurrent
uploads of 4, peak heap is ~1.2 GB transient — within the BC's
configured JVM heap (the per-BC JVM default for docs-api is `Xmx
1.5g` per the M1 base + M2 raise; see infra requirements).

| Concern | Pin |
|---|---|
| Upload concurrency limit | **None explicit in M6 P0** — defer to Tomcat's worker pool defaults |
| Per-upload thread | Tomcat worker thread; the entire PDF extraction (PDFBox + Vision LLM calls + DB write) happens on the request thread. **Long-running request** — up to 30 s × 30 OCR pages = 15 min worst case for the extreme PDF; acceptable because the user sees a progress indicator (frontend's Uploading… overlay per design doc §6.2). |
| WebClient pool for Vision LLM | Reactor Netty default shared HttpClient — already configured by ADR-04's Spring AI starter. No M6 reconfiguration needed. |
| Tomcat `connection-timeout` / `keep-alive-timeout` | Defaults (60 s). Note: a 15-min upload exceeds the default 60 s **idle** timeout but not the **read** timeout — Tomcat's accept-loop is fine as long as bytes are being read or the request is actively processing. The implementer should confirm via end-to-end smoke test with a 20-page OCR PDF (which takes 5-10 min) that the request doesn't get reaped mid-extraction. If it does, raise `server.tomcat.connection-timeout: 600000` (10 min) in `docs-api`'s `application.yml`. |

**Why no explicit throttle in P0:**

- M6 acceptance covers the "single user uploads one PDF" path. The
  concurrent-upload corner case (operator + a few visitors each
  uploading PDFs simultaneously) is real but rare at personal-scale;
  Tomcat's default pool absorbs it.
- Adding a `Semaphore` or Resilience4j Bulkhead is a defensive
  measure better justified by observed OOMs than by speculation.
  M6.1 can add a bulkhead (e.g., 4 concurrent PDF extractions max,
  queue depth 0, reject with 503 on overflow) if monitoring shows
  it's needed.

**Why no async / background dispatch:**

- Considered: accept the upload, queue the extraction work, return
  202 Accepted with a polling URL. Rejected for M6 P0:
  - The frontend design doc §6.2 ("Upload succeeds, navigate to
    `/docs/{id}`") expects synchronous completion — the doc detail
    page renders the extracted body immediately on first visit.
  - The user-visible flow is "upload → see the doc page". A
    202-then-poll inserts a loading state on the doc page that
    doesn't exist in the M2 baseline.
  - Worst-case 15-minute uploads are degenerate; typical PDFs are
    text-only or have 1-2 OCR pages, completing in seconds.

If observed latency demands it, M6.x amends this decision to
introduce an async extraction job. Out of scope for M6 P0.

### 13. Disposal of original PDF bytes

**Decision:** per PRD §"Decision 7 — 원본 PDF 바이트 폐기" — the
original PDF binary is **discarded** after extraction. Only the
derived `body` text (Markdown) and the `mime_type = 'application/pdf'`
flag are persisted.

- The upload's `MultipartFile` is read once: tier 3 magic-bytes
  check consumes the first 5 bytes (via `getBytes()` peek or
  `getInputStream` head-read), then the full body goes to PDFBox.
- After PDFBox returns the extracted Markdown, the `byte[]` /
  `PDDocument` references go out of scope and are GC-eligible.
- **No filesystem write** of the original PDF.
- **No `docs.document_attachments` table** — that table is reserved
  for M2.1's binary-attachment feature per ADR-05 amendment; M6 P0
  does not provision a `documents.binary_blob` column or a sibling
  table.

**Implication:** the original PDF is not recoverable from the
playground. If the operator wants to "re-extract under a better
Vision model in 6 months", they need to re-upload the source PDF.
M8's massing-gen surfaces this same constraint (the `arch.outputs`
table stores the .3dm, not the source PDF — per the M8 spec).

**Why discard and not store:**

- Storage budget — at 25 MB worst-case per PDF × hundreds of
  uploads/year, the binary corpus would dwarf the text corpus
  within months.
- Privacy/cleanup — discarding binary means the operator has only
  one place to "delete the doc" (the `docs.documents` row). Storing
  binary in a second table (or object store) introduces cleanup
  questions (cascade on doc-delete, orphan-detection, etc.) that
  are M2.1's concern.
- M8's massing-gen reads the body, not the binary. The massing
  algorithm needs the extracted room program (a JSON shape derived
  from `body`), not the original PDF rendering.

**Future M6.x reversal:** if M8's massing tooling shows demand for
re-running extraction on the original PDF (e.g., to try a better
Vision model retroactively), M6.x or M8.x lands an
`docs.document_originals` table (or object-store reservation per
ADR-05 amendment) with the original bytes. The current discard is
**deliberate P0 simplicity**, not a permanent posture.

### 14. Test strategy — three layers, real PDFBox + WireMock Vision

**Decision:**

| Test layer | Stack | What it covers |
|---|---|---|
| **`docs-domain` unit tests** | Pure JUnit 5 | `MimeType` enum, `PdfExtractionPolicy` constants, the threshold-comparison and cap-comparison invariants if any are extracted to pure functions. No PDFBox import in this layer. |
| **`docs-app` slice tests** | Spring Boot slice (`@SpringBootTest(classes = ...App.class)`) + Mockito for `PdfExtractorPort` + `VisionOcrPort` | `ExtractPdfTextUseCase` behavior — given a mocked `PdfExtractorPort.extractPage(n)` returning various strings ("", "Page 12", "real content", ...), assert the per-page dispatch decision (PDFBox path vs OCR fallback) and the cap-enforcement throws. Tests both 6 new error codes' triggering paths. |
| **`docs-infra` integration tests** | **Testcontainers** `pgvector/pgvector:pg16` (Postgres, for the Flyway migration assertions). **PDFBox: real, in-process** (against checked-in PDF fixtures — see below). **Vision LLM: WireMock** (stubs `POST /v1/chat/completions` returning canned vision responses; lets infra tests run without the Vision model deployed). | Real PDFBox extraction on real PDF bytes — assert text-PDF round-trips correctly; assert scanned-PDF triggers OCR; assert encrypted-PDF throws `PDF_ENCRYPTED`. WireMock simulates Vision success / Vision 500 / Vision timeout / Vision JSON-wrapped malformed response. |
| **`docs-api` end-to-end smoke** | Full `@SpringBootTest` with all of the above + WireMock + the actual `DocumentController` multipart endpoint exercised via `TestRestTemplate.exchange(...)`. | End-to-end: HTTP multipart POST with .pdf body, assert 201 response with `mime_type=application/pdf`, assert `docs.documents.body` contains the expected extracted text. The 3-tier validation path tests are also here (POST a `.exe` renamed `.pdf` → assert 400 `INVALID_FILE_TYPE`). |
| **Manual E2E (post-model-deployment)** | Real `spark-inference-gateway` + real Qwen3-VL model | One-time validation when the `qwen3-vl-30b-a3b` model lands on the gateway. Two PDF fixtures: a 5-page Korean text brief + a 1-page scanned cover with the rest text. Manual review of the extracted Markdown for fidelity. |

**Test PDF fixtures** (committed under
`backend/docs/docs-infra/src/test/resources/pdf-fixtures/`):

- `text-only-3pages.pdf` — a small text-only Korean PDF, 3 pages,
  generated via LaTeX or LibreOffice. Hits the PDFBox-only path for
  all 3 pages.
- `scanned-cover-text-body-3pages.pdf` — page 1 scanned, pages 2-3
  text. Hits OCR-fallback for page 1, PDFBox for pages 2-3.
- `encrypted-3pages.pdf` — password-protected. Tests
  `PDF_ENCRYPTED` throw.
- `corrupted-truncated.pdf` — first 1 KB of a real PDF (rest cut
  off). Tests `PDF_CORRUPTED` throw.
- `oversized-201pages.pdf` — 201 pages, text-only, with minimal
  content per page. Tests `PDF_TOO_MANY_PAGES` throw.

The fixtures are checked in (~50-200 KB each per fixture — total <
1 MB committed under `test/resources`). The "oversized 201 pages"
fixture is generated programmatically in a one-shot script (committed
as `generate-fixtures.sh` for reproducibility) — pages 30+ are blank
to keep the file under 500 KB.

**WireMock stub locations:**

- `backend/docs/docs-infra/src/test/resources/wiremock/spark-inference-gateway/vision-success-korean-md.json`
- `backend/docs/docs-infra/src/test/resources/wiremock/spark-inference-gateway/vision-500-error.json`
- `backend/docs/docs-infra/src/test/resources/wiremock/spark-inference-gateway/vision-malformed-json-wrapper.json`

**Vision-model-down operations note:** until the
`qwen3-vl-30b-a3b` model is actually loaded by the operator's
spark-inference-gateway (download in flight as of this ADR's
landing), the M6 integration tests on the Vision path **rely on
WireMock**. The manual E2E suite is the only path that exercises
the real model; the implementer's Stage 3 PR can land before the
model is available — backend smoke tests stay green via WireMock.
The manual E2E becomes an acceptance gate when the model arrives.

**Why real PDFBox and not mocked:** PDFBox is a library, not a
network call — mocking it would test "did I call the mock right?"
not "does the BC extract the right text from a real PDF?". Real
fixtures + real PDFBox is the only way to surface
form-feed / sort-by-position / Korean-character handling bugs.

**Why WireMock and not real Vision LLM in CI:** the Vision model is
GPU-heavy (a 30B-parameter Vision model) and not portable to CI
runners. WireMock stubs the HTTP shape, which is the BC's contract;
real-model accuracy is a separate manual validation step.

### 15. Frontend file picker and badge

**Decision:** the M6 PRD + design doc pins these visual changes;
this ADR confirms the backend contract they encode.

- **File picker accepts** `.md, .markdown, .pdf` (the `accept`
  attribute on the `<input type="file">` element).
- **POST `/api/docs` multipart** receives the same three forms it
  receives in M2 (`text/markdown` + `text/plain` + now
  `application/pdf` as the third Content-Type). The 3-tier
  validation in §4 enforces the contract.
- **Doc detail surface** (M2's `/docs/{id}`) renders an inline
  `(PDF)` badge next to the title when `mime_type === 'application/pdf'`.
  The frontend uses the existing `accent.soft` + `accent` token
  pair (M6 design §4.4 — zero new tokens).
- **No new frontend route.** All three modified surfaces (`/docs/new`
  dropdown, `/docs/{id}` detail, `/docs/public/{slug}` detail) are
  existing M2 routes with surgical changes.

**Backend response contract (relevant excerpts from the M2 detail
response, M6 additions only):**

| Field | M2 baseline | M6 addition |
|---|---|---|
| `id` | UUID | (unchanged) |
| `title` | string | (unchanged) |
| `body` | string (Markdown) | (unchanged — for PDF docs this is the extracted Markdown) |
| `mimeType` | (not present) | **new** — `"text/markdown"` or `"application/pdf"` |
| `pdfPageCount` | (not present) | **not added in P0** — see §9's rationale ("`pdf_page_count` 비도입") |

The `mimeType` field is added to the `DocumentDetailResponse` and
`MyDocumentListItemResponse` DTOs (in `docs-api`). Backward-compat
on the frontend: M2 frontend code that doesn't read `mimeType`
continues to work unchanged; M6 frontend code reads `mimeType` and
conditionally renders the badge.

### 16. Operational notes — model availability + rollout

**Decision (operational, not architectural — captured here so the
implementer's Stage 3 PR isn't blocked):**

| Concern | Posture |
|---|---|
| `qwen3-vl-30b-a3b` model availability | Model download is in flight on the operator's spark-inference-gateway as of this ADR's authoring. Backend integration tests (§14) bypass the real model via WireMock. Manual E2E is gated on model availability. |
| Property `SPRING_AI_VISION_MODEL` default | `qwen3-vl-30b-a3b` (per PRD §"Decision 13"). If the served model id differs (e.g., `qwen3-vl-30b-a3b-instruct`), override via env var per ADR-04's operational note — **the ADR is not changed**. |
| Single vLLM instance for text + vision | The PRD §"Decision 13" notes the spark-inference-gateway will run a single vLLM instance serving both Qwen3-30B-A3B (text — used by M4 / M3) and Qwen3-VL-30B-A3B (vision — used by M6). The two model ids may be aliased on the gateway or served by two parallel vLLM instances; either is OK from the BC's perspective — `SPRING_AI_VISION_MODEL` selects the vision endpoint, `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` selects the text endpoint (the existing M4 setting). |
| Rollout sequencing | The M6 backend PR (this ADR's implementer follow-up) can land before the model deployment; backend tests stay green via WireMock. The frontend PR (badge + `.pdf` accept) can land independently — until the backend ships, `.pdf` uploads return whatever the M2 controller currently returns (likely a 400 from the missing-MIME branch or an empty body — not catastrophic but not pretty). Recommended sequencing: backend first, then frontend, then real-model E2E. |
| Backfill of existing M2 documents | None — existing rows are MD-authored and get `mime_type='text/markdown'` via the migration's DEFAULT. No upload re-processing happens; no Kafka re-emission. |

## Additional decisions (not in PRD's question list but architect-owned)

### A. No new port assignment, no new compose service

**Decision:** docs-api continues to bind **port 18082** (per ADR-01
v2 + ADR-12 §A). No new compose service is added. The compose
service block for docs-api in `infra/docker-compose.yml` requires
**no changes** from M6 — Spring AI's `extra_hosts:
host.docker.internal:host-gateway` and the
`SPRING_AI_OPENAI_BASE_URL` env var are already configured (M3
shipped them; docs-api inherits the same compose pattern as part
of M6's vision-call introduction).

**docs-api compose block additions (in the M6 implementer PR):**

```yaml
# infra/docker-compose.yml — docs-api service block additions for M6
docs-api:
  # ... existing M2 environment vars and depends_on ...
  extra_hosts:
    - "host.docker.internal:host-gateway"   # per ADR-04 — reach spark-inference-gateway
  environment:
    # ... existing M2 vars ...
    SPRING_AI_OPENAI_BASE_URL: ${SPRING_AI_OPENAI_BASE_URL:-http://host.docker.internal:10080}
    SPRING_AI_OPENAI_API_KEY: ${SPRING_AI_OPENAI_API_KEY:-dummy-not-used}
    SPRING_AI_VISION_MODEL: ${SPRING_AI_VISION_MODEL:-qwen3-vl-30b-a3b}
  networks:
    - default
    - spark-inference-net   # per ADR-04 2026-05-20 amendment — compose-network attach
```

The `spark-inference-net` attach mirrors what ADR-04's 2026-05-20
amendment did for rag-ingestion-api / rag-chat-api / metrics-api;
docs-api joins the same external bridge so its Vision LLM calls
reach `spark-inference-gateway:8000` directly (no host-loopback
hop). The `extra_hosts` fallback is kept for the same reason
described in ADR-04's amendment.

### B. Library versions consolidated (M6 additions only)

Inherited from M1 / M2 / M4 / M5 where applicable; M6-specific
additions:

| Coordinate | Version | Source / pin reason |
|---|---|---|
| `org.apache.pdfbox:pdfbox` | **3.0.4** | Pinned in §1 — latest 3.0.x stable, ASF Apache 2.0 |
| (transitive) `org.apache.pdfbox:fontbox` | 3.0.4 (matched by `pdfbox` BOM) | Required for font subsetting — automatic transit |
| (transitive) `commons-logging:commons-logging` | (BOM-managed) | Bridged to SLF4J by Spring Boot's `spring-jcl`; no explicit binding |
| `org.springframework.ai:spring-ai-openai-spring-boot-starter` | Spring AI **1.0.0 GA** (per ADR-04 + ADR-13 + ADR-14) | **Already on docs-infra's classpath?** No — currently docs-infra does not have Spring AI. **M6 adds it.** Coordinate same as M3 / M4. |
| `com.github.tomakehurst:wiremock-jre8-standalone` | (WireMock — test scope) **3.x** (match M3's pin) | Tests only |
| **NOT used** | `org.apache.pdfbox:pdfbox-tools` / `pdfbox-app` | Per §1 — CLI dependencies excluded |

**Where Spring AI lands in docs-infra:** Spring AI is a runtime
dependency of `docs-infra` going forward (M6 is the first time
docs-infra calls an LLM). The build.gradle.kts change is:

```kotlin
// backend/docs/docs-infra/build.gradle.kts (M6 addition)
dependencies {
    // ... existing M2 entries ...

    // M6: Spring AI 1.0 GA for Vision OCR (ADR-04 + ADR-16 §2).
    // Same coordinate M3 (rag-ingestion-infra) and M4 (rag-chat-infra) use.
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")

    // M6: PDFBox for PDF text extraction + page rendering (ADR-16 §1 + §6).
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
}
```

The Spring AI BOM is already imported at the buildSrc level (since
M3); `docs-infra` picking up the starter pulls the BOM-managed
versions of every transitive Spring AI artifact.

### C. ADR-08 — no amendment

**Confirmation:** ADR-08 is **not** amended by ADR-16. The
documented `BC → spark-inference-gateway HTTP via Spring AI` row in
ADR-08's allowed-channels table (under "BC -> external
(`spark-inference-gateway`)") already covers docs-api's new Vision
LLM call path. No new sanctioned exception is needed — the rule was
"any BC may call spark-inference-gateway via Spring AI", and M6's
docs-api becomes the fourth BC to exercise it (after
rag-ingestion-api per ADR-13, rag-chat-api per ADR-14, and
metrics-api per ADR-15's HEAD probe).

The PRD §"Decision 11" already declared this; the ADR confirms it
formally.

### D. ADR-09 — no amendment

**Confirmation:** ADR-09 (public route policy) is **not** amended.
The upload route `POST /api/docs` is already in the authenticated
allowlist (per M2). M6 extends the route's content negotiation
inside the existing slot, not the route allowlist itself. The
`(PDF)` badge surfaces on the same public detail route
`/docs/public/{slug}` that M2 ships — the route's auth posture is
unchanged.

### E. ADR-05 — no schema-per-BC change

**Confirmation:** ADR-05 (data-store) is **not** amended. The
`mime_type` column is an additive ALTER on the existing `docs.documents`
table inside the existing `docs` schema. No new schema, no new
table, no new index. Flyway tracks the migration like any other
docs schema migration.

### F. ADR-11 — additive only, no hierarchy change

**Confirmation:** ADR-11 (shared exception hierarchy) is **not**
amended in shape. M6 adds 6 new `DocsErrorCode` enum constants per
§5 — that's normal BC vocabulary growth that ADR-11 explicitly
sanctions ("Each BC's `-domain` module ships subclasses for its
domain failures"). No new HTTP-typed exception subclass is
introduced; all 6 codes map to `BadRequestException` (400), per the
six-subclass set ADR-11 froze.

### G. M3 ingestion invariant — no code change in M3

**Confirmation (also pinned by PRD §"Decision 12"):** M3
(rag-ingestion) is **not** touched by M6's PR set. The
`docs.document.uploaded` event payload is identical for PDF-derived
documents as for Markdown-authored ones — the `body` field is
Markdown either way (the PDF source is opaque to M3 by design).
M3's chunker, embedder, and indexer all process the PDF-derived
body without distinguishing it from authored MD.

The one piece of M3 config that needs updating in lockstep is the
`MaxInMemorySize` for the body-fetch WebClient — see §11 for the
12 MB raise. This is a single-line `application.yml` edit, not a
code change.

### H. Amendment to ADR-04 — Vision modality

This ADR appends a Vision modality amendment to ADR-04. See
`Amendment to ADR-04` block below. The amendment is informational —
the ADR-04 invariants (Spring AI 1.0 GA pin, `spark-inference-gateway`
base URL, OpenAI-compatible endpoint, no fallback model) are all
preserved.

## Open questions (deferred to implementer)

This ADR closes every open question the PRD §"Open Questions for
ADR-16" listed (16 items + one PRD-level "단순화 채택 권장" for the
cap strategy). The implementer's remaining choices are limited to
the small style/observability items below:

1. **PNG serialization library choice.** §6 specifies "PNG via
   `ImageIO.write`". Implementer may swap to `PNGImageEncoder`
   (faster, less safe-for-JPEG-source-images) if profiling shows
   ImageIO's PNG encoder is the bottleneck. **Default: ImageIO** —
   JDK-bundled, zero new dep.

2. **Micrometer metric names.** The 6 new code paths (text-only
   extraction success, OCR-fallback fired, Vision retry, Vision
   timeout, Vision malformed, cap-overflow) should each have a
   counter. Naming convention follows ADR-13 §6's
   `playground.<bc>.<subsystem>.<measurement>` shape:
   - `playground.docs.pdf.extraction.duration` (Timer, label
     `outcome` ∈ {success, partial, failed})
   - `playground.docs.pdf.ocr_fallback.pages` (DistributionSummary)
   - `playground.docs.pdf.vision.duration` (Timer, label `outcome`
     ∈ {success, retry, timeout, malformed, failed})
   - `playground.docs.pdf.cap.exceeded` (Counter, label `cap` ∈
     {total_pages, ocr_pages, file_size})

   Implementer wires via Micrometer's `@Timed` / `Counter.builder()`
   in the use case + adapter. M5's dashboard picks them up via
   `/actuator/prometheus`.

3. **Log-level discipline.** ADR-11 §"logLevel" pins per-subclass
   levels. The Vision-failure WARN log (§3) should include
   `documentId, pageIndex, attempt, cause` as structured fields.
   Implementer's choice on log formatter (existing Logback JSON
   encoder M1 ADR-10 §8 ships).

4. **OpenSearch projector behavior for PDF-derived docs.** M2's
   `DocsSearchProjector` (ADR-12 §5) writes `title` and `body` to
   `docs-v1` index. For PDF-derived docs, the body is the extracted
   Markdown — which is what the projector wants for the search
   surface. **No projector change required.** Implementer should
   smoke-test that a PDF-uploaded doc is searchable in `/docs?q=...`
   exactly like a Markdown-authored one.

5. **Frontend page-count surface (`(12 pages)` meta tail).** The
   design doc §2.6 marks this as implementer-optional. ADR-16 §9
   explicitly does NOT add a `pdf_page_count` column to the schema
   (per PRD §"Decision 4"). If the implementer wants to surface
   the page count, they derive it at upload time and pass it
   through the `DocumentDetailResponse` DTO as a transient field
   (not persisted), or skip the meta tail entirely. **Default
   recommendation:** skip — the badge is enough; the meta tail is
   icing.

6. **Health check on PDFBox load.** docs-api's `/actuator/health`
   does not need a PDF-extraction sub-indicator. PDFBox is a
   library; its readiness is a function of the JVM being up
   (already covered by the existing Spring Boot Actuator default).
   No M6 health-indicator addition.

7. **Korean PDF corpus E2E.** §14's "Manual E2E (post-model-deployment)"
   is gated on `qwen3-vl-30b-a3b` availability. The operator runs
   this once when the model lands and shares findings on issue
   #162; if accuracy is unacceptable, ADR-16 amends to pin a
   different vision model or to raise DPI.

Everything else is decided above.

## Consequences

- **Positive:** M6 ships in a single PR-set (backend + frontend +
  this ADR + the PRD that lives in #187) with concrete library
  pins, port choices, and error-code rows the implementer
  transcribes — minimal Stage 3 ambiguity.
- **Positive:** PDFBox + Spring AI hybrid keeps the happy-path
  latency in the same band as M2's Markdown uploads (sub-second for
  text-PDFs) while gracefully degrading on scanned content.
- **Positive:** The two-cap strategy (200 total + 30 OCR) bounds
  worst-case latency and worst-case Vision LLM cost at request
  acceptance time — no runaway uploads.
- **Positive:** M3 ingestion is unchanged — PDF-derived bodies are
  opaque to M3 at the event boundary. No M3 PR is needed.
- **Positive:** ADR-08 / ADR-09 / ADR-05 / ADR-11 are all
  preserved as-is — the architectural envelope absorbs M6 without
  amendment.
- **Negative:** Body cap raise (1 MB → 10 MB) is uniform, affecting
  Markdown-authored docs too. This is intentional (per §11
  rationale) but is a one-way change — operators with strict
  1 MB-cap policies elsewhere (backups, cache fits) must adjust.
- **Negative:** Original PDF bytes are discarded — re-extraction
  under a future better Vision model requires re-upload. Acceptable
  P0 simplicity; future M6.x or M8.x may add an originals table.
- **Negative:** Vision-LLM-dependent uploads have wide latency
  variance (1 s for text PDFs, up to 15 min for 30-OCR-page worst
  case). The frontend's `Uploading…` indicator mitigates UX impact
  but is not a hard guarantee.
- **Negative:** PDFBox in-process means docs-api JVM heap pressure
  scales with concurrent uploads. No throttle in P0 — relies on
  Tomcat defaults + M6.1 monitoring.

## Related

- ADR-01 v2 (Gradle structure) — docs-api remains port 18082;
  module quadruplet unchanged
- ADR-02 (DDD layering) — `PdfExtractorPort` / `VisionOcrPort` in
  `docs-app`; adapters in `docs-infra`; constants in `docs-domain`
- ADR-04 (Spring AI + spark-inference-gateway) — Vision modality
  amendment (below)
- ADR-05 (data store) — `mime_type` column ALTER inside the
  existing `docs` schema; no schema-per-BC change
- ADR-08 (inter-service comms) — no amendment; Vision LLM call is
  the existing `BC → spark-inference-gateway via Spring AI` row
- ADR-09 (public route policy) — no amendment; route allowlist
  unchanged
- ADR-11 (shared exception hierarchy) — additive only (6 new
  `DocsErrorCode` enum rows)
- ADR-12 (M2 docs) — body cap originally pinned at 1 MB; this ADR
  raises to 10 MB per §11
- ADR-13 (M3 rag-ingestion) — `maxInMemorySize` config raise to
  12 MB in lockstep per §11
- M6 PRD: `docs/prd/M6-docs-pdf.md` (ships as PR #187 ahead of
  this ADR)
- M6 design doc: `docs/design/M6-M8-brief-to-massing.md` (already
  merged)
- Spec: `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §4
  (M6 scope)

---

## Amendment to ADR-04 (2026-05-21, ADR-16) — Vision modality introduction

ADR-16 introduces the **first use of Spring AI's multimodal
(vision) API** in this codebase. ADR-04's Spring AI 1.0 GA pin,
`spark-inference-gateway` base URL, OpenAI-compatible endpoint
shape, and "no fallback model" consequence are all **unchanged**.
The amendment is informational, recording the modality and the
new vision-model property.

- **Vision modality** — M6 invokes `ChatClient.prompt().messages(UserMessage.builder().media(...).build()).options(ChatOptions.builder().model(...).build()).call().content()`
  with a PNG attached as `Media`. This is the Spring AI 1.0 GA
  multimodal API per the OpenAI image-input contract. Base URL
  (`http://spark-inference-gateway:8000` via the
  `spark-inference-net` bridge per ADR-04's 2026-05-20 amendment,
  with `host.docker.internal:10080` as fallback) is unchanged.
- **Vision model property** — `SPRING_AI_VISION_MODEL` env var
  (defaulting to `qwen3-vl-30b-a3b`) selects the vision endpoint
  on `spark-inference-gateway`. The existing text-chat property
  `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` (and the lower-level
  `SPRING_AI_CHAT_MODEL` env that drives it) is now defaulted to
  the same `qwen3-vl-30b-a3b` ID — as of the 2026-05-21 swap the
  single VL-MoE vLLM instance serves both text and vision via the
  one OpenAI-compatible endpoint, so the BC code is identical and
  the gateway routes one model name to one backend. (Two parallel
  vLLM instances behind the gateway with distinct model names
  remains a supported topology if the operator ever wants to split
  the workload.)
- **Model availability sequencing** — at the time of this
  amendment, `qwen3-vl-30b-a3b` is downloading on the operator's
  spark-inference-gateway. Backend tests use WireMock until the
  model lands (per ADR-16 §14 + §16); a manual E2E gates the
  full M6 acceptance.
- **Which services use Vision** — only `docs-api` (via
  `docs-infra`'s `VisionOcrAdapter`) at M6. `rag-chat` and
  `rag-ingestion` continue to use text-only Spring AI calls; the
  text vs. vision split is per-call, not per-BC.
- **No fallback model** — ADR-04's existing consequence ("if
  `spark-inference-gateway` is down, M3/M4 fail") extends to M6's
  Vision path. If the gateway is unreachable during a PDF upload's
  OCR fallback, the affected pages contribute empty markdown per
  ADR-16 §3 (graceful per-page degradation), not a full upload
  failure. This is a per-page softer surface than M3/M4's
  per-call hard surface — but is still bounded by ADR-04's "no
  fallback model" invariant.

See `docs/adr/16-m6-docs-pdf.md` §2 + §6 + §7 + §16 for the full
specification.

## Amendment 2026-05-22 — M6.1 async extraction + all-page Vision + page cap 100

This amendment substantially supersedes ADR-16's hybrid extraction
algorithm and synchronous-request-thread shape. It is the M6.1 companion
to **ADR-12's amendment 2026-05-22** (the M6.1 master amendment that
collapses rag-ingestion into docs); read that first for the BC-level
context. This block captures only the M6-PDF-specific shifts.

### A16.1. Hybrid PDFBox-first algorithm — retired

**Decision (supersedes §3):** the per-page PDFBox-text-extraction path
is **dropped**. Every PDF page goes through Vision OCR (Qwen3-VL).
PDFBox is retained for **page rendering only** (`PDFRenderer.renderImageWithDPI`
per §6, unchanged); `PDFTextStripper` is no longer used.

**Why the hybrid loses:** the post-2026-05-21 bench surfaced that
PDFBox-extracted text from text-layer pages is clean character-wise but
**structurally flat** — no heading semantics, no table structure, footnotes
folded into body, two-column reading order collapsed to a single stream.
For Korean architectural briefs (the primary M6 corpus), structure is
the load-bearing signal for downstream retrieval. Vision OCR preserves
heading depth + tables + figure captions naturally because the model
reads the rendered page, not the underlying content stream.

**Algorithm shape after M6.1** (replaces §3's pseudo-code):

```java
// docs-app — ExtractionWorkflow.runPdf(byte[] pdfBytes) sketch.
PDDocument doc;
try {
    doc = Loader.loadPDF(pdfBytes);
} catch (InvalidPasswordException e) {
    failExtraction(docId, "PDF_ENCRYPTED");
    return;
} catch (IOException e) {
    failExtraction(docId, "PDF_CORRUPTED");
    return;
}

int totalPages = doc.getNumberOfPages();
if (totalPages > MAX_PAGES_TOTAL) {                  // 100 per A16.2
    failExtraction(docId, "PDF_TOO_MANY_PAGES: " + totalPages + " > " + MAX_PAGES_TOTAL);
    return;
}

PDFRenderer renderer = new PDFRenderer(doc);

// Render + Vision call per page, parallelized across the dedicated
// ExtractionExecutor (A12.6 in ADR-12 amendment — N=5).
CompletableFuture<String>[] perPage = new CompletableFuture[totalPages];
for (int p = 0; p < totalPages; p++) {
    final int pageIndex = p;
    perPage[p] = CompletableFuture.supplyAsync(() -> {
        BufferedImage img = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
        byte[] pngBytes = toPng(img);
        try {
            return visionOcrPort.renderToMarkdownWithRetry(pngBytes, RETRY_ATTEMPTS);
        } catch (VisionTimeoutException | VisionGatewayException e) {
            log.warn("Vision OCR failed for page {} after retries; empty contribution. cause={}", pageIndex, e);
            return "";   // graceful per-page degradation — preserved from §3
        }
    }, extractionExecutor);
}

CompletableFuture.allOf(perPage).join();
return Arrays.stream(perPage).map(CompletableFuture::join).collect(joining("\n\n"));
```

The order-preserving join (`Arrays.stream(perPage)` indexed) keeps page
order intact regardless of completion order. Per-page Vision retry +
empty-markdown-fallback semantics are preserved verbatim from §3.

**`OCR_FALLBACK_THRESHOLD` retired** (was 30 characters per §3). The
`playground.docs.pdf.ocr-fallback-threshold-chars` property is removed.

**ADR-16 §10's `PdfExtractorAdapter` simplifies:** it no longer wraps
`PDFTextStripper`. Its only PDFBox surface area is `PDFRenderer` for
page-image rendering. The unit-test matrix shrinks accordingly (the
"PDFBox returns short text → triggers OCR" branch is gone; every
test fixture goes straight to Vision OCR).

### A16.2. Page cap — single 100 cap (200/30 two-cap retired)

**Decision (supersedes §8):** the dual `max-pages-total: 200` /
`max-pages-ocr: 30` strategy is retired. M6.1 pins **`max-pages-total:
100`** as the single cap. The `max-pages-ocr` property is removed; the
`DocsErrorCode.PDF_TOO_MANY_OCR_PAGES` (`DOCS-PDF-005`) enum constant is
retired (the migration that removes it is folded into the M6.1 PR set —
implementer's call whether to keep the enum constant for backward-compat
of any logged error codes; the architect recommends deletion since no
runtime path can produce it after this amendment).

**Why 100:** at ~5-10s/page Vision wall time × 100 pages ÷ 5 parallel =
~2-3 min worst-case extraction wall time. 200 pages would push to
~4-7 min, into the "user gives up and reloads" territory even with the
async SSE shape.

**Enforcement location:** moves from "sync request thread inside
`DocumentController.createMultipart`" to "async worker inside
`ExtractionWorkflow.runPdf` after PDFBox `loadPDF`". On overflow, the
worker writes `extraction_status='failed'`, `extraction_reason='PDF_TOO_MANY_PAGES: {N} > 100'`,
broadcasts SSE `failed`, commits. No `docs.document.uploaded` event
publishes — the chunker + embedder never run for a failed extraction.

### A16.3. Extraction lives in the async worker, not the request thread

**Decision (supersedes §12):** the M6 P0 "synchronous extraction on the
request thread" pattern is retired. The `POST /api/docs` controller now
returns `201 Created` immediately after the multipart-to-MinIO stream
and the `documents` row INSERT; extraction work happens on the dedicated
`ExtractionExecutor` (ADR-12 amendment A12.6 — N=5). See ADR-12 amendment
A12.5 for the full step-by-step.

The original §12 concerns (Tomcat thread starvation, 60s idle timeout)
are eliminated. Worst-case extraction wall time (2-3 min per A16.2) is
absorbed by the async pipeline; the user sees an "Analyzing..." overlay
streamed via SSE.

### A16.4. Original PDF bytes — retained in MinIO (partial reversal of §13)

**Decision (partially supersedes §13):** the M6 P0 "discard original
PDF bytes" policy is retired. M6.1 streams the upload's bytes to the
new `minio-playground` sidecar (ADR-12 amendment A12.4); the original
file lives in MinIO for the lifetime of the `docs.documents` row,
cascade-deleted on row removal.

This unblocks two real requirements:
1. The async worker (A16.3) can fetch the blob *after* the upload
   request thread has returned.
2. The user-visible "download the source PDF" affordance becomes
   trivial — `GET /api/docs/{id}/source` streams from MinIO with the
   doc's visibility check applied.

§13's "no `docs.document_attachments` table" rationale is preserved (no
new Postgres table; MinIO is the binary store). §13's "no
`documents.binary_blob` column" is preserved (no in-row binary in
Postgres). The reversal is narrow: original bytes are retained, just in
MinIO rather than Postgres.

### A16.5. Vision LLM options — per-page timeout raised 30s → 60s

**Decision (refines §7):** the per-page Vision call timeout rises from
**30 seconds to 60 seconds**. The other knobs (temperature 0.1,
max-tokens 1200, frequency-penalty 0.5, retry 1, fallback empty
markdown) are preserved verbatim.

**Rationale:** the post-2026-05-21 bench (ADR-16 §7's lower amendment
block) reduced worst-case page latency from 70s → 13s, comfortably
under 30s. But the **all-page** path (A16.1) submits every page,
including pages that would previously have been handled by PDFBox in
sub-second. Some pages (dense-image figure pages, mixed-script
multi-table pages) still hover near 20-25s. A 30s cap with 1 retry
risks two consecutive timeouts on a single page that's just slow, not
broken. 60s gives a 4× safety margin without changing the budget
materially (60s × 100 pages ÷ 5 parallel = 1200s worst case = 20 min
absolute degenerate ceiling, which is a ceiling-of-ceilings, not a P95).

§7's option table is amended:

| Concern | Pre-M6.1 pin | M6.1 pin | Reason |
|---|---|---|---|
| Per-page timeout | 30s | **60s** | All-page submission widens the per-page latency distribution |
| Other knobs | (preserved) | (preserved) | — |

### A16.6. Module placement — adapters re-homed into the docs quadruplet

**Decision (refines §10):** with rag-ingestion absorbed into docs
(ADR-12 amendment A12.1), the M6 PDF adapters stay in `docs-infra`
unchanged in shape. The `PdfExtractorAdapter` simplifies (drops
`PDFTextStripper`) per A16.1. The `VisionOcrAdapter` is unchanged.
Both now live in the consolidated `backend/docs/docs-infra/` module
alongside the embedding + pgvector + Redisson adapters that move from
the retired `rag-ingestion-infra`.

The `ExtractPdfTextUseCase` (in `docs-app`) is **renamed** to
`ExtractionWorkflow.runPdf(...)` to reflect its new role as the worker
that runs *after* the upload commits, rather than the use case the
controller invokes on the request thread. The MD branch becomes
`ExtractionWorkflow.runMarkdown(...)`. Both share the same
`ExtractionWorkflow` orchestrator that handles the post-extraction
steps (DB update, SSE broadcast, Kafka outbox publish).

### A16.7. ADR-08 — no longer "no amendment"; rag-ingestion exception retired

**Decision (refines §C — "ADR-08 no amendment" confirmation):** the
ADR-08 amendment 2026-05-22 (M6.1) **retires Exception 1** (rag-ingestion
→ docs-api `/internal/docs/public/{id}/body` HTTP for body fetch). With
the BCs consolidated, the body fetch is an in-process JPA SELECT — not
HTTP, not cross-BC. The `/internal/docs/public/{id}/body` route on
docs-api is kept defensively (a future BC may revive it under a new
ADR-08 exception) but has no caller in M6.1.

The "BC → spark-inference-gateway via Spring AI" row in ADR-08's
allowed-channels table is preserved unchanged. M6.1's Vision LLM call
stays inside that sanctioned envelope.

### A16.8. Test strategy — async test shapes

**Decision (refines §14):** the four-layer test pyramid carries over,
but two of the layers' assertions shift to match the async pipeline:

| Layer | M6 P0 (pre-M6.1) | M6.1 |
|---|---|---|
| `docs-app` slice | "Given a mocked `PdfExtractorPort` returning various strings, assert the per-page dispatch decision (PDFBox vs OCR)" | "Given a mocked `VisionOcrPort` returning per-page markdown, assert the page-order-preserving join + the page-cap-exceeded transition to `failed`" |
| `docs-infra` integration | "Real PDFBox extraction; PDFBox-extracts-text path tested against text-fixtures" | "Real PDFBox **rendering only**; every page goes to WireMock-stubbed Vision. The text-extract-only fixtures (`text-only-3pages.pdf`) now exercise the all-page Vision-via-WireMock path" |
| `docs-api` end-to-end | "POST multipart .pdf → assert 201 + body in row" | "POST multipart .pdf → assert 201 + `extraction_status='processing'` immediately; subscribe to SSE; assert `completed` event arrives; assert body in row after `completed`" |
| Manual E2E | "Manual review of extracted Markdown on real Qwen3-VL deployment" | (unchanged) |

The `text-only-3pages.pdf` and `scanned-cover-text-body-3pages.pdf`
fixtures stay relevant — they just exercise different paths (no longer
"hybrid PDFBox + OCR per page"; now "all pages OCR"). The
`encrypted-3pages.pdf` and `corrupted-truncated.pdf` fixtures continue
to test the failure paths (now landing in `extraction_status='failed'`
instead of throwing on the request thread).

The `oversized-201pages.pdf` fixture is **resized** to
`oversized-101pages.pdf` to match the new 100-cap.

### A16.9. Consequences (M6.1-specific, additive to ADR-16's original)

- **Positive:** structural fidelity of extracted markdown improves uniformly across the corpus (heading hierarchy, tables, two-column reading order, footnotes). Downstream chunking + retrieval benefits.
- **Positive:** the request thread is freed within milliseconds of the multipart-to-MinIO stream completing. Tomcat thread pool pressure under concurrent uploads drops to near-zero.
- **Positive:** original PDF bytes are recoverable. Future re-extraction under better Vision models is one job away, not a re-upload campaign.
- **Negative:** every page incurs a Vision LLM call. At personal scale this is acceptable (dollars), at audience scale it would force re-introducing the hybrid path or a cheaper text-only model for pre-classification.
- **Negative:** the per-PDF wall time bound grows from "~5s for text-only PDFs" (M6 P0 hybrid happy path) to "~2-3 min for any PDF up to the page cap" (M6.1 all-page). Acceptable behind the async + SSE shape; the user is no longer waiting on the request.
- **Negative:** the M6 P0 cap (200 / 30) is tightened to 100. Some real briefs (architecture competition packages routinely run 80-120 pages with image-heavy appendices) sit right at the new ceiling. If real-world uploads bunch against the cap, M6.2 can raise to 150 once the Vision LLM serving budget supports it.

### A16.10. ADR-04 — preserved as-is; docs-api becomes sole Vision user

**Decision (refines ADR-04 amendment):** the 2026-05-21 amendment to
ADR-04 (Vision modality introduction) is preserved verbatim. M6.1
narrows the operator-facing statement: **docs-api is now the sole BC
exercising the Vision modality** (rag-chat and rag-ingestion never used
it; rag-ingestion no longer exists). This is informational; the ADR-04
invariants (Spring AI 1.0 GA pin, `spark-inference-gateway` base URL,
no fallback model) are unaffected.

---

## Original amendment 2026-05-21 (post-bench) — Vision OCR option tuning

> The pre-M6.1 amendment below is preserved for history. Its Vision-LLM
> option pins (`max-tokens: 1200`, `frequency-penalty: 0.5`,
> temperature 0.1) carry over verbatim into the M6.1 algorithm; only the
> per-page timeout is raised (30s → 60s per A16.5 above).

Empirical bench against the operator's real 55-page Korean brief PDF
(KFI 청사 확충·이전사업 설계공모 지침서; HWP-export, text layer
sound, 3 OCR-fallback pages out of 55 = 5.5%) surfaced a Vision-LLM
failure mode the initial §7 spec did not cover: **image-heavy pages
(maps, site diagrams) cause the model to hallucinate runaway repeated
tokens** — e.g., the same road label `오송생명7로` emitted ~20 times
in sequence — until `max_tokens` is exhausted. Worst-case page (p26,
two-image site-overview map) ran 70 s wall time and consumed all 2000
completion tokens before stop.

Two yml-level safeguards have been added on the docs-api
`spring.ai.openai.chat.options` block (Vision-only path; docs-api's
only `ChatClient` consumer is `VisionOcrAdapter`):

- **`max-tokens: 1200`** — normal-page markdown is ~800-1100 tokens;
  the 1200 cap leaves slack for dense text/table pages while bounding
  worst-case completion tokens.
- **`frequency-penalty: 0.5`** — depresses repeated-token logits.
  Conservative mid-value within OpenAI's `[-2.0, 2.0]` range; no
  measurable impact on normal-page markdown quality observed in
  bench.

Re-bench results on the same three OCR pages (p1 표지, p26 지도,
p28 현장사진):

|                          | Before     | After      | Δ      |
|--------------------------|------------|------------|--------|
| Total latency            | 76.0 s     | 17.0 s     | -77 %  |
| Average per page         | 25.3 s     | 5.7 s      | -77 %  |
| Completion tokens        | 2095       | 489        | -77 %  |
| p26 alone (worst case)   | 70.2 s     | 13.4 s     | -81 %  |

All three pages stay well inside the §7 30-second per-page timeout
(which remains the final safety net — these knobs just push the bad
path off the worst cliff). Normal-page output (p1 표지, p28
현장사진) is visually unchanged before/after; only the runaway-loop
hallucination on p26 is bounded.

The §7 Vision-call options table above carries the same two rows
inline so the implementer-facing pin is in one place. The bench
inputs + outputs live at `/tmp/m6-pdf-ocr-bench-v2.out` on the
operator's machine (transient; the repro recipe is in the PR #190
commit message `docs-api: cap Vision OCR max-tokens + add
frequency-penalty`).
