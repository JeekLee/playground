# M3.1 Markdown-aware Chunker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace M3's fixed-window `MarkdownChunker` with a commonmark-java AST-driven chunker that preserves heading/block boundaries, persists a `heading_path` array on every chunk row, and ships a `reembed` Spring profile that migrates the existing corpus to the new boundaries in a single operator run.

**Architecture:** New chunker = `MarkdownAwareChunker` (orchestrator) → `SectionBuilder` (CommonMark AST → sections w/ headingPath) → `WindowNormalizer` (token-budget pack/split per section, fence+table atomic, oversize paragraph via `SentenceSplitter`). On commonmark parse failure the orchestrator falls back to the existing token-window algorithm. Persistence adds `heading_path text[]` to `rag.document_chunks`; a `ReembedCommandLineRunner` under the `reembed` Spring profile iterates `SELECT DISTINCT document_id` and re-runs the chunk+embed+replaceAll pipeline under the existing per-document Redisson lock.

**Tech Stack:** Java 21 + Spring Boot 3 + JUnit Jupiter + AssertJ + Testcontainers Postgres. New libs: `org.commonmark:commonmark:0.22.0` + `commonmark-ext-gfm-tables` + `commonmark-ext-gfm-strikethrough`. JDK `java.text.BreakIterator` for Korean sentence splitting (no new dep). Existing JTokkit stays.

**Spec:** `docs/superpowers/specs/2026-05-19-m3p1-markdown-aware-chunker-design.md`. Read it once before starting; every task references a section.

---

## File Structure

Files to create / modify (organized by module).

### `rag-ingestion-domain` (pure-Java, ADR-02 leaf)

| Path | Action | Responsibility |
|---|---|---|
| `build.gradle.kts` | modify | add commonmark deps |
| `domain/service/ChunkingPolicy.java` | modify | add `maxOversizeFenceTokens`, `preserveHeadingPath` |
| `domain/service/MarkdownChunker.java` | **delete** | superseded |
| `domain/service/MarkdownAwareChunker.java` | create | top-level chunker + parse-fallback |
| `domain/service/SectionBuilder.java` | create | CommonMark AST → `List<Section>` |
| `domain/service/Section.java` | create | record `(List<String> headingPath, List<Node> blocks)` |
| `domain/service/WindowNormalizer.java` | create | `List<Section>` → `List<ChunkDraft>` |
| `domain/service/SentenceSplitter.java` | create | interface |
| `domain/service/JdkBreakIteratorSentenceSplitter.java` | create | default impl |
| `domain/model/vo/ChunkDraft.java` | create | record `(ChunkText text, List<String> headingPath)` |
| `domain/model/DocumentChunk.java` | modify | add `headingPath` field |

Tests (under `src/test/java`):

| Path | Action |
|---|---|
| `domain/service/MarkdownChunkerTest.java` | **delete** |
| `domain/service/SectionBuilderTest.java` | create |
| `domain/service/WindowNormalizerTest.java` | create |
| `domain/service/MarkdownAwareChunkerTest.java` | create |
| `domain/service/JdkBreakIteratorSentenceSplitterTest.java` | create |
| `domain/model/DocumentChunkTest.java` | modify if exists (otherwise no-op) |

Fixtures: `src/test/resources/chunker-fixtures/01-empty.md` … `08-heading-prefix-overflow.md` + matching `.expected.json` files.

### `rag-ingestion-infra` (Spring/JPA glue)

| Path | Action | Responsibility |
|---|---|---|
| `infrastructure/config/ChunkingProperties.java` | modify | bind new fields |
| `infrastructure/config/ChunkingConfig.java` | modify | wire `MarkdownAwareChunker` bean |
| `infrastructure/persistence/DocumentChunkJpaEntity.java` | modify | add `headingPath` column |
| `infrastructure/persistence/ChunkRepositoryJdbcAdapter.java` | modify | INSERT/SELECT `heading_path` |
| `src/main/resources/db/migration/V202605200003__add_chunk_heading_path.sql` | create | Flyway ALTER |

### `rag-ingestion-app` (use cases)

| Path | Action | Responsibility |
|---|---|---|
| `application/service/IngestionService.java` | modify | consume `List<ChunkDraft>`, propagate headingPath |
| `application/service/ReembedService.java` | create | per-document re-ingest entry point (shared by runner + tests) |
| `application/service/ReembedCommandLineRunner.java` | create | `@Profile("reembed")` CLI entry |
| `application/service/ReembedProperties.java` | create | `@ConfigurationProperties` for reembed scope/throttle |

Tests:

| Path | Action |
|---|---|
| `application/service/IngestionServiceTest.java` | modify | assert headingPath propagation |
| `application/service/ReembedServiceTest.java` | create |
| `application/service/ReembedCommandLineRunnerIntegrationTest.java` | create |

### `rag-ingestion-api`

| Path | Action |
|---|---|
| `src/main/resources/application.yml` | modify — add `chunk.max-oversize-fence-tokens` / `chunk.preserve-heading-path` / `reembed.*` defaults |

### Doc amendments (no code)

| Path | Action |
|---|---|
| `docs/adr/13-m3-rag-ingestion.md` | §1 / §6 / §7 |
| `docs/prd/M3-rag-ingestion.md` | M3.1 bucket / Acceptance criteria / P2 trim |
| `docs/prd/M4-rag-chat.md` | one-line note on heading_path availability |
| `docs/roadmap.md` | §M3 schema bullet |

---

## Task 1: Add commonmark-java dependencies

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-domain/build.gradle.kts`

- [ ] **Step 1: Add the three commonmark coordinates**

Replace the `dependencies { ... }` block with:

```kotlin
dependencies {
    // ADR-13 §1 — JTokkit (cl100k-base) tokenizer for the markdown chunker.
    // Pure-Java, zero transitive deps; allowed in -domain per ADR-13 §4 since
    // it is a leaf algorithm library (no Spring / JPA / Kafka coupling).
    implementation("com.knuddels:jtokkit:1.1.0")

    // ADR-13 §1 (M3.1 amendment) — CommonMark AST parser for the markdown-aware
    // chunker. Same "leaf algorithm library" rationale as JTokkit; no Spring
    // / Jackson / framework coupling. GFM tables + strikethrough extensions are
    // the only dialects the M2 corpus uses today.
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
}
```

- [ ] **Step 2: Verify the build resolves**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/build.gradle.kts
git commit -m "m3p1-chunker: add commonmark-java + GFM tables/strikethrough deps to -domain"
```

---

## Task 2: Extend `ChunkingPolicy` with two new fields

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/ChunkingPolicy.java`
- Test: existing `ChunkingPolicyTest.java` if present; otherwise create.

- [ ] **Step 1: Write the failing tests**

Create or extend `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/ChunkingPolicyTest.java`:

```java
package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ChunkingPolicyTest {

    @Test
    void default_policy_matches_adr_13_pins() {
        ChunkingPolicy p = ChunkingPolicy.DEFAULT;
        assertThat(p.sizeTokens()).isEqualTo(800);
        assertThat(p.overlapTokens()).isEqualTo(120);
        assertThat(p.minChunkTokens()).isEqualTo(64);
        assertThat(p.tokenizer()).isEqualTo("cl100k-base");
        assertThat(p.maxOversizeFenceTokens()).isEqualTo(800);
        assertThat(p.preserveHeadingPath()).isTrue();
    }

    @Test
    void max_oversize_fence_tokens_must_be_positive() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ChunkingPolicy(800, 120, 64, "cl100k-base", 0, true));
    }

    @Test
    void stride_still_derives_from_size_minus_overlap() {
        // Fallback path still uses (size - overlap) as a sliding stride —
        // the field's meaning is dual; see spec §"Fallback 경로 한정".
        ChunkingPolicy p = ChunkingPolicy.DEFAULT;
        assertThat(p.stride()).isEqualTo(680);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*ChunkingPolicyTest*"`
Expected: FAIL — `maxOversizeFenceTokens()` / `preserveHeadingPath()` not defined.

- [ ] **Step 3: Replace `ChunkingPolicy.java` with the extended record**

```java
package com.playground.ragingestion.domain.service;

/**
 * Immutable chunking parameters per ADR-13 §1 (M3.1 amendment). Six fields:
 * the four ADR-13 P0 tunables plus two M3.1 additions —
 * {@code maxOversizeFenceTokens} (line-split threshold for oversized fences /
 * tables) and {@code preserveHeadingPath} (toggle for the heading-aware
 * prefix; disable to fall back to plain block packing).
 *
 * <p>{@code overlapTokens} is dual-meaning: in the normal markdown-aware path
 * it caps the heading-prefix budget injected at chunk start; in the
 * parse-fallback path it serves the historical role of "token-window stride"
 * via {@link #stride()}. Spec §"Fallback 경로 한정".
 */
public record ChunkingPolicy(
        int sizeTokens,
        int overlapTokens,
        int minChunkTokens,
        String tokenizer,
        int maxOversizeFenceTokens,
        boolean preserveHeadingPath
) {

    public static final ChunkingPolicy DEFAULT = new ChunkingPolicy(
            800,
            120,
            64,
            "cl100k-base",
            800,
            true);

    public ChunkingPolicy {
        if (sizeTokens <= 0) {
            throw new IllegalArgumentException("sizeTokens must be positive, got " + sizeTokens);
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens must be non-negative, got " + overlapTokens);
        }
        if (overlapTokens >= sizeTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens (" + overlapTokens + ") must be strictly less than sizeTokens (" + sizeTokens + ")");
        }
        if (minChunkTokens <= 0 || minChunkTokens >= sizeTokens) {
            throw new IllegalArgumentException(
                    "minChunkTokens (" + minChunkTokens + ") must be in (0, sizeTokens)");
        }
        if (tokenizer == null || tokenizer.isBlank()) {
            throw new IllegalArgumentException("tokenizer must not be blank");
        }
        if (maxOversizeFenceTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxOversizeFenceTokens must be positive, got " + maxOversizeFenceTokens);
        }
    }

    /** Stride used by the parse-fallback token-window path. */
    public int stride() {
        return sizeTokens - overlapTokens;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*ChunkingPolicyTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Verify nothing else breaks**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:compileJava`
Expected: BUILD SUCCESSFUL. If the existing `MarkdownChunker` was constructed via `new ChunkingPolicy(800, 120, 64, "cl100k-base")` anywhere, the 4-arg ctor no longer exists — those call sites fail to compile. Tasks 8 and 13 will repair them; for now use `ChunkingPolicy.DEFAULT` as the temporary stand-in.

If compilation breaks because `new ChunkingPolicy(int,int,int,String)` was used elsewhere, replace those four-arg call sites with `ChunkingPolicy.DEFAULT` or pass the two new fields explicitly. List the files in the commit.

- [ ] **Step 6: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/ChunkingPolicy.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/ChunkingPolicyTest.java
git commit -m "m3p1-chunker: extend ChunkingPolicy with maxOversizeFenceTokens + preserveHeadingPath"
```

---

## Task 3: Create `ChunkDraft` record

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/model/vo/ChunkDraft.java`

This record is the chunker → IngestionService transport vehicle. Tiny, no test — covered transitively by `SectionBuilderTest` / `WindowNormalizerTest`.

- [ ] **Step 1: Create the record**

```java
package com.playground.ragingestion.domain.model.vo;

import java.util.List;
import java.util.Objects;

/**
 * In-flight chunker output: a chunk's raw text plus the heading breadcrumb
 * (h1, h2, h3, ...) of the section it came from. Carried from
 * {@code MarkdownAwareChunker} through {@code IngestionService} so the
 * embedding step and {@code DocumentChunk} construction can stay zero-copy
 * — neither needs to re-derive {@code headingPath} from the AST.
 *
 * <p>{@code headingPath} is never null and is held as an immutable copy.
 * Empty list = chunk belongs to a section above the first heading (or the
 * document has no headings at all).
 */
public record ChunkDraft(ChunkText text, List<String> headingPath) {

    public ChunkDraft {
        Objects.requireNonNull(text, "ChunkDraft.text must not be null");
        Objects.requireNonNull(headingPath, "ChunkDraft.headingPath must not be null");
        headingPath = List.copyOf(headingPath);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/model/vo/ChunkDraft.java
git commit -m "m3p1-chunker: add ChunkDraft VO for chunker → service transport"
```

---

## Task 4: `SentenceSplitter` port + JDK BreakIterator implementation

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/SentenceSplitter.java`
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/JdkBreakIteratorSentenceSplitter.java`
- Test: `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/JdkBreakIteratorSentenceSplitterTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class JdkBreakIteratorSentenceSplitterTest {

    private final SentenceSplitter splitter = new JdkBreakIteratorSentenceSplitter();

    @Test
    void korean_text_splits_on_sentence_terminators() {
        String input = "한국어 문장입니다. 두 번째 문장이에요. 그리고 세 번째!";
        List<String> sentences = splitter.split(input, Locale.KOREAN);

        assertThat(sentences).hasSize(3);
        assertThat(sentences.get(0).trim()).isEqualTo("한국어 문장입니다.");
        assertThat(sentences.get(1).trim()).isEqualTo("두 번째 문장이에요.");
        assertThat(sentences.get(2).trim()).isEqualTo("그리고 세 번째!");
    }

    @Test
    void english_text_splits_on_periods_and_exclamation() {
        List<String> sentences = splitter.split(
                "The quick brown fox. It jumps over. Done!", Locale.ENGLISH);
        assertThat(sentences).hasSize(3);
    }

    @Test
    void empty_input_returns_empty_list() {
        assertThat(splitter.split("", Locale.KOREAN)).isEmpty();
        assertThat(splitter.split("   \n  ", Locale.KOREAN)).isEmpty();
    }

    @Test
    void single_sentence_returns_one_element() {
        List<String> sentences = splitter.split("이건 한 문장.", Locale.KOREAN);
        assertThat(sentences).hasSize(1);
    }

    @Test
    void output_concatenation_equals_input_verbatim() {
        // The splitter must not lose characters. Used as an invariant by the
        // WindowNormalizer's oversize-paragraph path so the final chunk
        // text round-trips byte-for-byte.
        String input = "첫 번째.\n두 번째!\n  세 번째?  ";
        String joined = String.join("", splitter.split(input, Locale.KOREAN));
        assertThat(joined).isEqualTo(input);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*JdkBreakIteratorSentenceSplitterTest*"`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create the interface**

```java
package com.playground.ragingestion.domain.service;

import java.util.List;
import java.util.Locale;

/**
 * Splits a paragraph into sentences. Invoked by {@link WindowNormalizer}
 * only when a single CommonMark paragraph exceeds
 * {@link ChunkingPolicy#sizeTokens()} — the common case skips this entirely
 * and packs whole blocks.
 *
 * <p>Implementations must preserve all characters of the input: concatenating
 * the returned list yields the original string verbatim (whitespace and line
 * breaks attached to whichever sentence they border). This invariant keeps
 * the eventual chunk text exactly reconstructable.
 */
public interface SentenceSplitter {

    List<String> split(String paragraph, Locale locale);
}
```

- [ ] **Step 4: Create the JDK implementation**

```java
package com.playground.ragingestion.domain.service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link SentenceSplitter} backed by the JDK's
 * {@link BreakIterator#getSentenceInstance(Locale)} — no external dependency.
 * Korean accuracy is adequate for the oversize-paragraph fallback path; an
 * ICU4J-backed splitter can replace this later behind the same interface
 * without touching {@link WindowNormalizer}.
 */
public final class JdkBreakIteratorSentenceSplitter implements SentenceSplitter {

    @Override
    public List<String> split(String paragraph, Locale locale) {
        if (paragraph == null || paragraph.isBlank()) {
            return List.of();
        }
        BreakIterator it = BreakIterator.getSentenceInstance(locale);
        it.setText(paragraph);
        List<String> out = new ArrayList<>();
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            out.add(paragraph.substring(start, end));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*JdkBreakIteratorSentenceSplitterTest*"`
Expected: PASS (5 tests). If the round-trip test fails because BreakIterator emits whitespace-only trailing segments, accept those segments as-is (they preserve the concatenation invariant — do NOT filter them out).

- [ ] **Step 6: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/SentenceSplitter.java \
        backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/JdkBreakIteratorSentenceSplitter.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/JdkBreakIteratorSentenceSplitterTest.java
git commit -m "m3p1-chunker: add SentenceSplitter port + JDK BreakIterator impl"
```

---

## Task 5: `Section` record + `SectionBuilder`

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/Section.java`
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/SectionBuilder.java`
- Test: `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/SectionBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.commonmark.node.Heading;
import org.junit.jupiter.api.Test;

class SectionBuilderTest {

    private final SectionBuilder builder = new SectionBuilder();

    @Test
    void empty_body_yields_no_sections() {
        assertThat(builder.build("")).isEmpty();
    }

    @Test
    void document_with_no_headings_is_one_root_section_with_empty_path() {
        String md = "Plain paragraph one.\n\nPlain paragraph two.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).headingPath()).isEmpty();
        assertThat(sections.get(0).blocks()).hasSize(2);
    }

    @Test
    void content_before_first_heading_is_a_pathless_section() {
        String md = "Intro paragraph.\n\n# First heading\n\nBody under H1.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).headingPath()).isEmpty();
        assertThat(sections.get(1).headingPath()).containsExactly("First heading");
    }

    @Test
    void nested_headings_build_a_path_stack() {
        String md = """
                # Top
                p1
                ## Mid
                p2
                ### Deep
                p3
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).headingPath()).containsExactly("Top");
        assertThat(sections.get(1).headingPath()).containsExactly("Top", "Mid");
        assertThat(sections.get(2).headingPath()).containsExactly("Top", "Mid", "Deep");
    }

    @Test
    void sibling_headings_pop_back_to_correct_depth() {
        String md = """
                # A
                a1
                ## A1
                a1-body
                # B
                b1
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).headingPath()).containsExactly("A");
        assertThat(sections.get(1).headingPath()).containsExactly("A", "A1");
        assertThat(sections.get(2).headingPath()).containsExactly("B");
    }

    @Test
    void heading_level_skip_h1_to_h3_keeps_intermediate_slots_empty_string() {
        // Edge case: author skips H2. headingPath becomes ["A", "", "Deep"]
        // so the depth conveys what the writer wrote (don't silently rename).
        String md = """
                # A
                ### Deep
                body
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(1).headingPath()).containsExactly("A", "", "Deep");
    }

    @Test
    void heading_node_itself_is_included_as_the_first_block_of_its_section() {
        // The Heading is preserved as a block (not stripped) so the embedding
        // model sees the heading text inline with the section content.
        String md = "# Title\n\nBody.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).blocks().get(0)).isInstanceOf(Heading.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*SectionBuilderTest*"`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create the `Section` record**

```java
package com.playground.ragingestion.domain.service;

import java.util.List;
import java.util.Objects;
import org.commonmark.node.Node;

/**
 * One heading-bounded segment of a markdown document. Built by
 * {@link SectionBuilder} and consumed by {@link WindowNormalizer}.
 *
 * <p>{@code headingPath} is the sequence h1..hN where N is the depth of the
 * deepest heading in scope when this section started. Empty list = leading
 * content before any heading, or a document with no headings at all.
 *
 * <p>{@code blocks} contains the section's body in document order, including
 * the bounding {@code Heading} node as the first element (so embedding sees
 * the heading text). For the root pathless section the first block is
 * whatever paragraph / fence / list led the document.
 */
public record Section(List<String> headingPath, List<Node> blocks) {

    public Section {
        Objects.requireNonNull(headingPath, "Section.headingPath must not be null");
        Objects.requireNonNull(blocks, "Section.blocks must not be null");
        headingPath = List.copyOf(headingPath);
        // blocks intentionally not copied — Node is a mutable AST type and
        // re-parenting would corrupt the parser's invariants.
    }
}
```

- [ ] **Step 4: Create the `SectionBuilder`**

```java
package com.playground.ragingestion.domain.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * Walks the top level of a CommonMark AST and groups blocks into
 * {@link Section}s separated by {@link Heading} nodes. GFM tables +
 * strikethrough are enabled.
 *
 * <p>Stateless after construction: the {@link Parser} instance is reused
 * across documents (commonmark-java parsers are thread-safe).
 */
public final class SectionBuilder {

    private final Parser parser;

    public SectionBuilder() {
        this.parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create()))
                .build();
    }

    public List<Section> build(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Node root = parser.parse(body);
        if (root.getFirstChild() == null) {
            return List.of();
        }

        List<Section> sections = new ArrayList<>();
        // Heading text by 1-based level; positions deeper than the most
        // recent heading are reset by the pop step in pushHeading().
        String[] stack = new String[6];

        List<String> currentPath = List.of();
        List<Node> currentBlocks = new ArrayList<>();

        for (Node child = root.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading h) {
                // Flush whatever section was building.
                if (!currentBlocks.isEmpty()) {
                    sections.add(new Section(currentPath, currentBlocks));
                }
                int level = clamp(h.getLevel(), 1, 6);
                stack[level - 1] = headingText(h);
                // Reset deeper slots; this is how sibling headings pop back.
                for (int i = level; i < 6; i++) {
                    stack[i] = null;
                }
                currentPath = snapshotPath(stack, level);
                currentBlocks = new ArrayList<>();
                currentBlocks.add(h);
            } else {
                currentBlocks.add(child);
            }
        }
        if (!currentBlocks.isEmpty()) {
            sections.add(new Section(currentPath, currentBlocks));
        }
        return List.copyOf(sections);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String headingText(Heading h) {
        StringBuilder sb = new StringBuilder();
        for (Node n = h.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Text t) {
                sb.append(t.getLiteral());
            } else {
                // Non-Text inline content (code span, emphasis) — pull its
                // child text greedily. Good enough for breadcrumbs.
                for (Node inner = n.getFirstChild(); inner != null; inner = inner.getNext()) {
                    if (inner instanceof Text t) {
                        sb.append(t.getLiteral());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static List<String> snapshotPath(String[] stack, int upToLevel) {
        List<String> out = new ArrayList<>(upToLevel);
        for (int i = 0; i < upToLevel; i++) {
            out.add(stack[i] == null ? "" : stack[i]);
        }
        return out;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*SectionBuilderTest*"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/Section.java \
        backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/SectionBuilder.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/SectionBuilderTest.java
git commit -m "m3p1-chunker: add Section record + SectionBuilder (CommonMark AST → sections)"
```

---

## Task 6: `WindowNormalizer` — the algorithm core

This is the largest task. Split it into three sub-commits to keep diffs reviewable: (6a) skeleton + basic block packing, (6b) oversize block handling (fence/table/paragraph), (6c) heading-aware prefix + trailing merge.

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/WindowNormalizer.java`
- Test: `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/WindowNormalizerTest.java`

### Task 6a: Skeleton + greedy block pack

- [ ] **Step 1: Write the first batch of failing tests**

```java
package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowNormalizerTest {

    private final SectionBuilder builder = new SectionBuilder();
    private final WindowNormalizer normalizer = new WindowNormalizer(
            ChunkingPolicy.DEFAULT, new JdkBreakIteratorSentenceSplitter());

    @Test
    void single_small_section_yields_one_chunk_with_full_heading_path() {
        String md = "# Title\n\nShort body.";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).headingPath()).containsExactly("Title");
        assertThat(drafts.get(0).text().value()).contains("Short body.");
        assertThat(drafts.get(0).text().value()).contains("Title");  // heading inlined
    }

    @Test
    void no_section_input_yields_empty_output() {
        assertThat(normalizer.normalize(List.of())).isEmpty();
    }

    @Test
    void multiple_small_sections_each_become_own_chunk_no_cross_section_pack() {
        String md = """
                # A
                a body
                # B
                b body
                # C
                c body
                """;
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));

        assertThat(drafts).hasSize(3);
        assertThat(drafts.get(0).headingPath()).containsExactly("A");
        assertThat(drafts.get(1).headingPath()).containsExactly("B");
        assertThat(drafts.get(2).headingPath()).containsExactly("C");
    }

    @Test
    void section_with_many_paragraphs_packs_until_size_then_flushes() {
        // Build a section whose paragraphs sum to ~2400 tokens — should yield
        // three chunks under the 800-token cap.
        StringBuilder md = new StringBuilder("# Big section\n\n");
        String para = "The quick brown fox jumps over the lazy dog. ".repeat(40); // ~360 tokens
        for (int i = 0; i < 7; i++) {
            md.append(para).append("\n\n");
        }
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));

        assertThat(drafts).hasSizeBetween(3, 4);
        drafts.forEach(d -> {
            assertThat(d.headingPath()).containsExactly("Big section");
            assertThat(d.text().value()).isNotEmpty();
        });
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: FAIL — `WindowNormalizer` does not exist.

- [ ] **Step 3: Create the skeleton with the basic block pack path**

```java
package com.playground.ragingestion.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.renderer.text.TextContentRenderer;
import org.commonmark.ext.gfm.tables.TableBlock;

/**
 * Turns a {@link Section} list into a {@link ChunkDraft} list per ADR-13
 * §1 (M3.1 amendment). Section boundaries are never crossed (each section
 * is ≥ 1 chunk); within a section blocks are packed greedily up to
 * {@link ChunkingPolicy#sizeTokens()} with fence / table / oversize-paragraph
 * special cases.
 *
 * <p>Block-to-text rendering uses commonmark-java's {@link TextContentRenderer}
 * for paragraphs / lists / blockquotes and source-text reconstruction for
 * fenced code (so the ``` ``` markers and language tag round-trip). The
 * rendering preserves enough markdown structure that the resulting chunk
 * re-parses cleanly with the same parser — an invariant asserted by
 * {@code MarkdownAwareChunkerTest}.
 */
public final class WindowNormalizer {

    private final ChunkingPolicy policy;
    private final SentenceSplitter sentenceSplitter;
    private final Encoding encoding;
    private final TextContentRenderer textRenderer;

    public WindowNormalizer(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
        this.policy = policy;
        this.sentenceSplitter = sentenceSplitter;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
        this.textRenderer = TextContentRenderer.builder().build();
    }

    public List<ChunkDraft> normalize(List<Section> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }
        List<ChunkDraft> out = new ArrayList<>();
        for (Section section : sections) {
            normalizeOne(section, out);
        }
        return List.copyOf(out);
    }

    private void normalizeOne(Section section, List<ChunkDraft> out) {
        int sizeTokens = policy.sizeTokens();
        List<Node> buf = new ArrayList<>();
        int bufTokens = 0;
        int sectionChunkIndex = 0;

        for (Node block : section.blocks()) {
            String rendered = renderBlock(block);
            int bt = tokenCount(rendered);

            if (bt > sizeTokens) {
                if (!buf.isEmpty()) {
                    out.add(buildDraft(section, buf, sectionChunkIndex++));
                    buf.clear();
                    bufTokens = 0;
                }
                emitOversize(section, block, sectionChunkIndex, out);
                sectionChunkIndex = out.size() - countSectionChunks(out, section);
                continue;
            }
            if (bufTokens + bt > sizeTokens) {
                out.add(buildDraft(section, buf, sectionChunkIndex++));
                buf.clear();
                bufTokens = 0;
            }
            buf.add(block);
            bufTokens += bt;
        }
        if (!buf.isEmpty()) {
            out.add(buildDraft(section, buf, sectionChunkIndex++));
        }
    }

    private int countSectionChunks(List<ChunkDraft> out, Section section) {
        int n = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).headingPath().equals(section.headingPath())) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    private ChunkDraft buildDraft(Section section, List<Node> blocks, int sectionChunkIndex) {
        StringBuilder sb = new StringBuilder();
        for (Node b : blocks) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(renderBlock(b));
        }
        // Heading-aware prefix is added in Task 6c. For now leave the body
        // as-is.
        String text = sb.toString();
        return new ChunkDraft(ChunkText.of(text), section.headingPath());
    }

    // Stub — completed in Task 6b.
    private void emitOversize(Section section, Node block, int sectionChunkIndex, List<ChunkDraft> out) {
        // Fallback: tokenize the block's text and slide a fixed window. This
        // is the safety net for Task 6a; Task 6b replaces with proper
        // fence/table/paragraph handling.
        String text = renderBlock(block);
        var tokens = encoding.encode(text);
        int total = tokens.size();
        int sz = policy.sizeTokens();
        int stride = policy.stride();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + sz, total);
            var slice = new com.knuddels.jtokkit.api.IntArrayList(end - start);
            for (int i = start; i < end; i++) slice.add(tokens.get(i));
            out.add(new ChunkDraft(ChunkText.of(encoding.decode(slice)), section.headingPath()));
            if (end == total) break;
            start += stride;
        }
    }

    String renderBlock(Node block) {
        if (block instanceof FencedCodeBlock fcb) {
            String info = fcb.getInfo() == null ? "" : fcb.getInfo();
            String literal = fcb.getLiteral() == null ? "" : fcb.getLiteral();
            return "```" + info + "\n" + literal + "```";
        }
        if (block instanceof IndentedCodeBlock icb) {
            String literal = icb.getLiteral() == null ? "" : icb.getLiteral();
            StringBuilder sb = new StringBuilder();
            for (String line : literal.split("\n", -1)) {
                sb.append("    ").append(line).append("\n");
            }
            return sb.toString();
        }
        if (block instanceof org.commonmark.node.Heading h) {
            // Preserve the markdown markers so the chunk text re-parses as a
            // heading (and so the embedding model sees the heading shape).
            String text = textRenderer.render(h);
            return "#".repeat(Math.max(1, Math.min(6, h.getLevel()))) + " " + text;
        }
        // Paragraph / BulletList / OrderedList / BlockQuote / TableBlock:
        // TextContentRenderer handles them. For TableBlock the GFM extension
        // re-emits a pipe-delimited grid; preserve that.
        return textRenderer.render(block);
    }

    int tokenCount(String text) {
        return encoding.encode(text).size();
    }

    // Markers for Task 6b: implementer adds Paragraph / FencedCodeBlock /
    // TableBlock / BulletList / OrderedList / BlockQuote-specific oversize
    // branches and removes the generic fallback above.
    @SuppressWarnings("unused")
    private void splitOversizeParagraph(Paragraph p, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeFence(FencedCodeBlock fcb, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeTable(TableBlock tb, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeList(Node list, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b — handles BulletList and OrderedList
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 4: Run tests to verify the 6a subset passes**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: 4 tests PASS (the four from Step 1).

- [ ] **Step 5: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/WindowNormalizer.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/WindowNormalizerTest.java
git commit -m "m3p1-chunker: WindowNormalizer skeleton + greedy block pack within sections"
```

### Task 6b: Oversize block handling (fence / table / paragraph / list / blockquote)

- [ ] **Step 1: Add failing tests for each oversize branch**

Append to `WindowNormalizerTest.java`:

```java
    @Test
    void oversize_fence_stays_atomic_when_within_maxOversizeFenceTokens() {
        // sizeTokens=800, maxOversizeFenceTokens=800 (default).
        // A 700-token fence is bigger than typical section pack but ≤ max,
        // so it lands as one chunk.
        String content = "x ".repeat(700);  // ~700 tokens
        String md = "# H\n\n```\n" + content + "\n```\n";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).text().value()).startsWith("# H").contains("```");
    }

    @Test
    void oversize_fence_beyond_threshold_splits_into_valid_fences() {
        // Fence has ~1600 tokens — exceeds maxOversizeFenceTokens (800).
        // Each split chunk must re-open with ``` and the original language.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 800; i++) body.append("line").append(i).append("\n");
        String md = "# Code\n\n```python\n" + body + "```\n";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSizeGreaterThan(1);
        drafts.subList(1, drafts.size()).forEach(d -> {
            assertThat(d.text().value()).contains("```python");
            assertThat(d.text().value().trim()).endsWith("```");
        });
    }

    @Test
    void oversize_paragraph_splits_on_sentences() {
        // One paragraph, no sentence > sizeTokens.
        String sent = "이것은 한 문장입니다. ".repeat(1);
        StringBuilder big = new StringBuilder("# P\n\n");
        for (int i = 0; i < 200; i++) big.append(sent);  // many sentences in one paragraph
        big.append("\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(big.toString()));
        assertThat(drafts).hasSizeGreaterThan(1);
        // Every chunk should end on a sentence boundary (terminator char).
        drafts.forEach(d -> {
            String t = d.text().value().trim();
            assertThat(t).matches(".*[.!?]\\s*");
        });
    }

    @Test
    void single_sentence_larger_than_sizeTokens_falls_back_to_token_window() {
        // Pathological: one sentence ~1600 tokens with no terminator until end.
        String giant = "word ".repeat(1600) + ".";
        String md = "# G\n\n" + giant;
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSizeGreaterThan(1);
        // Ensure no chunk is wildly larger than sizeTokens + overlap budget.
        drafts.forEach(d -> assertThat(d.text().value().split("\\s+").length)
                .isLessThan(ChunkingPolicy.DEFAULT.sizeTokens() + 50));
    }

    @Test
    void gfm_table_oversize_repeats_header_row_on_each_split() {
        StringBuilder md = new StringBuilder("# T\n\n| col1 | col2 |\n|---|---|\n");
        for (int i = 0; i < 600; i++) md.append("| r").append(i).append(" | v").append(i).append(" |\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        if (drafts.size() > 1) {
            drafts.subList(1, drafts.size()).forEach(d ->
                    assertThat(d.text().value()).contains("| col1 | col2 |"));
        }
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: 5 new tests FAIL (the originals still PASS).

- [ ] **Step 3: Replace `emitOversize` and implement the four split helpers**

In `WindowNormalizer.java`, replace the stubbed `emitOversize` body and the four `splitOversize*` methods. Drop the `@SuppressWarnings("unused")` annotations as you implement them.

```java
    private void emitOversize(Section section, Node block, int sectionChunkIndex, List<ChunkDraft> out) {
        if (block instanceof FencedCodeBlock fcb) {
            splitOversizeFence(fcb, section, out);
        } else if (block instanceof TableBlock tb) {
            splitOversizeTable(tb, section, out);
        } else if (block instanceof Paragraph p) {
            splitOversizeParagraph(p, section, out);
        } else if (block instanceof BulletList || block instanceof OrderedList) {
            splitOversizeList(block, section, out);
        } else {
            // Generic fallback for BlockQuote / IndentedCodeBlock / unknown:
            // tokenize the rendered text and slide a fixed window.
            tokenWindowSlice(renderBlock(block), section, out);
        }
    }

    private void splitOversizeFence(FencedCodeBlock fcb, Section section, List<ChunkDraft> out) {
        String info = fcb.getInfo() == null ? "" : fcb.getInfo();
        String literal = fcb.getLiteral() == null ? "" : fcb.getLiteral();
        int total = tokenCount(literal);
        int maxFence = policy.maxOversizeFenceTokens();

        if (total <= maxFence) {
            // Atomic — emit as one chunk even if larger than sizeTokens.
            String text = "```" + info + "\n" + literal + "```";
            out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
            return;
        }

        // Line-group split. Budget per chunk = sizeTokens minus the fence
        // markers' overhead (a handful of tokens). Group lines greedily.
        String[] lines = literal.split("\n", -1);
        int sz = policy.sizeTokens() - 16;  // budget for ```lang\n + \n```
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (String line : lines) {
            int lt = tokenCount(line + "\n");
            if (!buf.isEmpty() && bufTok + lt > sz) {
                flushFenceBuf(buf, info, section, out);
                buf.clear();
                bufTok = 0;
            }
            buf.add(line);
            bufTok += lt;
        }
        if (!buf.isEmpty()) flushFenceBuf(buf, info, section, out);
    }

    private void flushFenceBuf(List<String> lines, String info, Section section, List<ChunkDraft> out) {
        String body = String.join("\n", lines);
        String text = "```" + info + "\n" + body + "\n```";
        out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
    }

    private void splitOversizeTable(TableBlock tb, Section section, List<ChunkDraft> out) {
        // GFM TableBlock renders to a pipe-delimited grid via TextContentRenderer.
        // Split on newlines and treat the first two lines as the header.
        String rendered = textRenderer.render(tb);
        String[] lines = rendered.split("\n", -1);
        if (lines.length <= 2) {
            // Header-only table — re-emit as a single chunk regardless of size.
            out.add(new ChunkDraft(ChunkText.of(rendered), section.headingPath()));
            return;
        }
        String header = lines[0] + "\n" + lines[1];
        int sz = policy.sizeTokens() - tokenCount(header + "\n");
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (int i = 2; i < lines.length; i++) {
            int lt = tokenCount(lines[i] + "\n");
            if (!buf.isEmpty() && bufTok + lt > sz) {
                flushTableBuf(buf, header, section, out);
                buf.clear();
                bufTok = 0;
            }
            buf.add(lines[i]);
            bufTok += lt;
        }
        if (!buf.isEmpty()) flushTableBuf(buf, header, section, out);
    }

    private void flushTableBuf(List<String> rows, String header, Section section, List<ChunkDraft> out) {
        String text = header + "\n" + String.join("\n", rows);
        out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
    }

    private void splitOversizeParagraph(Paragraph p, Section section, List<ChunkDraft> out) {
        String text = textRenderer.render(p);
        List<String> sentences = sentenceSplitter.split(text, Locale.KOREAN);
        if (sentences.isEmpty()) {
            // Defensive — should not happen since we entered this branch
            // because the paragraph itself was oversize.
            tokenWindowSlice(text, section, out);
            return;
        }

        int sz = policy.sizeTokens();
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (String sentence : sentences) {
            int st = tokenCount(sentence);
            if (st > sz) {
                // Single sentence overrun — flush whatever's buffered, then
                // fall back to a raw token window for this sentence.
                if (!buf.isEmpty()) {
                    out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
                    buf.clear();
                    bufTok = 0;
                }
                tokenWindowSlice(sentence, section, out);
                continue;
            }
            if (!buf.isEmpty() && bufTok + st > sz) {
                out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
                buf.clear();
                bufTok = 0;
            }
            buf.add(sentence);
            bufTok += st;
        }
        if (!buf.isEmpty()) {
            out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
        }
    }

    private void splitOversizeList(Node list, Section section, List<ChunkDraft> out) {
        int sz = policy.sizeTokens();
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String rendered = textRenderer.render(item);
            int it = tokenCount(rendered);
            if (it > sz) {
                // Recurse into item children.
                if (!buf.isEmpty()) {
                    out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
                    buf.clear();
                    bufTok = 0;
                }
                for (Node inner = item.getFirstChild(); inner != null; inner = inner.getNext()) {
                    if (tokenCount(renderBlock(inner)) > sz) {
                        emitOversize(section, inner, 0, out);
                    } else {
                        out.add(new ChunkDraft(ChunkText.of(renderBlock(inner)), section.headingPath()));
                    }
                }
                continue;
            }
            if (!buf.isEmpty() && bufTok + it > sz) {
                out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
                buf.clear();
                bufTok = 0;
            }
            buf.add(rendered);
            bufTok += it;
        }
        if (!buf.isEmpty()) {
            out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
        }
    }

    private void tokenWindowSlice(String text, Section section, List<ChunkDraft> out) {
        var tokens = encoding.encode(text);
        int total = tokens.size();
        int sz = policy.sizeTokens();
        int stride = policy.stride();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + sz, total);
            var slice = new com.knuddels.jtokkit.api.IntArrayList(end - start);
            for (int i = start; i < end; i++) slice.add(tokens.get(i));
            out.add(new ChunkDraft(ChunkText.of(encoding.decode(slice)), section.headingPath()));
            if (end == total) break;
            start += stride;
        }
    }
```

- [ ] **Step 4: Run tests to verify the 6b subset passes**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/WindowNormalizer.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/WindowNormalizerTest.java
git commit -m "m3p1-chunker: WindowNormalizer oversize block handling (fence/table/paragraph/list)"
```

### Task 6c: Heading-aware prefix + trailing-merge

- [ ] **Step 1: Add failing tests**

```java
    @Test
    void second_and_later_chunks_of_a_section_get_heading_prefix() {
        // Force a section to split into ≥ 2 chunks.
        StringBuilder md = new StringBuilder("# Outer\n\n## Inner\n\n");
        String para = "Word ".repeat(200);  // ~200 tokens
        for (int i = 0; i < 8; i++) md.append(para).append("\n\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        assertThat(drafts.size()).isGreaterThan(1);
        // First chunk has the heading inline naturally.
        assertThat(drafts.get(0).text().value()).contains("Inner");
        // Subsequent chunks open with the breadcrumb prefix.
        for (int i = 1; i < drafts.size(); i++) {
            assertThat(drafts.get(i).text().value())
                    .startsWith("> Context: ")
                    .contains("Outer")
                    .contains("Inner");
        }
    }

    @Test
    void heading_prefix_drops_top_levels_when_budget_overflows() {
        // overlapTokens default 120 → if the breadcrumb exceeds, drop top headings.
        String h1 = "A ".repeat(200);      // ~200 tokens
        String h2 = "B ".repeat(10);
        String h3 = "C ".repeat(5);
        StringBuilder md = new StringBuilder("# ").append(h1).append("\n## ").append(h2)
                .append("\n### ").append(h3).append("\n\n");
        String para = "Word ".repeat(200);
        for (int i = 0; i < 8; i++) md.append(para).append("\n\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        // Verify the second chunk's prefix does NOT contain the giant h1.
        if (drafts.size() > 1) {
            String secondPrefix = drafts.get(1).text().value();
            assertThat(secondPrefix).startsWith("> Context: ");
            assertThat(secondPrefix.split("\n", 2)[0].length()).isLessThan(600);
        }
    }

    @Test
    void short_trailing_chunk_in_a_section_merges_into_previous() {
        // Trailing-merge: a section ending with a < minChunkTokens remainder
        // gets folded into the previous chunk in the same section.
        // Easiest to verify: count chunks for a known-shape section.
        StringBuilder md = new StringBuilder("# X\n\n");
        String big = "Word ".repeat(700);  // ~700 tokens — fits in 1 chunk
        md.append(big).append("\n\n");
        md.append("a b c.\n");             // ~3 tokens — would be a tiny trailer
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).text().value()).endsWith("a b c.");
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: 3 new tests FAIL (the previous 9 still PASS).

- [ ] **Step 3: Wire heading-prefix + trailing-merge**

In `WindowNormalizer.normalizeOne(...)`, replace `buildDraft(section, buf, sectionChunkIndex++)` calls with a helper that injects the prefix on `sectionChunkIndex > 0`. Also add a post-pass per section that merges trailing chunks shorter than `minChunkTokens`.

Replace the existing `normalizeOne(...)` with:

```java
    private void normalizeOne(Section section, List<ChunkDraft> out) {
        int sizeTokens = policy.sizeTokens();
        List<Node> buf = new ArrayList<>();
        int bufTokens = 0;
        int sectionStart = out.size();

        for (Node block : section.blocks()) {
            String rendered = renderBlock(block);
            int bt = tokenCount(rendered);

            if (bt > sizeTokens) {
                if (!buf.isEmpty()) {
                    out.add(buildDraft(section, buf));
                    buf.clear();
                    bufTokens = 0;
                }
                emitOversize(section, block, 0, out);
                continue;
            }
            if (bufTokens + bt > sizeTokens) {
                out.add(buildDraft(section, buf));
                buf.clear();
                bufTokens = 0;
            }
            buf.add(block);
            bufTokens += bt;
        }
        if (!buf.isEmpty()) {
            out.add(buildDraft(section, buf));
        }

        // Apply heading-aware prefix to chunks 2..N of this section.
        if (policy.preserveHeadingPath() && !section.headingPath().isEmpty()) {
            for (int i = sectionStart + 1; i < out.size(); i++) {
                ChunkDraft d = out.get(i);
                String prefix = renderHeadingPrefix(section.headingPath());
                String body = d.text().value();
                String prefixed = prefix + "\n\n" + body;
                out.set(i, new ChunkDraft(ChunkText.of(prefixed), section.headingPath()));
            }
        }

        // Trailing-merge: if last chunk in this section is shorter than
        // minChunkTokens AND another chunk in this section exists, merge it.
        if (out.size() - sectionStart >= 2) {
            ChunkDraft last = out.get(out.size() - 1);
            if (tokenCount(last.text().value()) < policy.minChunkTokens()) {
                ChunkDraft prev = out.get(out.size() - 2);
                String merged = prev.text().value() + "\n\n" + last.text().value();
                out.set(out.size() - 2, new ChunkDraft(ChunkText.of(merged), section.headingPath()));
                out.remove(out.size() - 1);
            }
        }
    }

    private ChunkDraft buildDraft(Section section, List<Node> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Node b : blocks) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(renderBlock(b));
        }
        return new ChunkDraft(ChunkText.of(sb.toString()), section.headingPath());
    }

    private String renderHeadingPrefix(List<String> headingPath) {
        int budget = policy.overlapTokens();
        // Start from the full path; drop top-level entries until the
        // rendered prefix fits the budget.
        int from = 0;
        while (from < headingPath.size()) {
            String candidate = "> Context: " + String.join(" > ", headingPath.subList(from, headingPath.size()));
            if (tokenCount(candidate) <= budget) {
                return candidate;
            }
            from++;
        }
        // All single-heading prefixes exceeded the budget — emit the deepest only.
        String deepest = headingPath.get(headingPath.size() - 1);
        return "> Context: " + deepest;
    }
```

Also remove the now-unused `countSectionChunks(...)` helper.

- [ ] **Step 4: Run tests to verify all 12 pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*WindowNormalizerTest*"`
Expected: 12 tests PASS. If "short trailing chunk" test produces 2 chunks instead of 1, the merge logic missed — verify `minChunkTokens=64` and that the trailing `a b c.` paragraph really tokenizes to < 64.

- [ ] **Step 5: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/WindowNormalizer.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/WindowNormalizerTest.java
git commit -m "m3p1-chunker: WindowNormalizer heading-aware prefix + trailing-merge"
```

---

## Task 7: `MarkdownAwareChunker` (top-level orchestrator + parse-fallback)

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/MarkdownAwareChunker.java`
- Test: `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/MarkdownAwareChunkerTest.java`
- Fixtures: `backend/rag-ingestion/rag-ingestion-domain/src/test/resources/chunker-fixtures/01-empty.md` (and 02–08)

- [ ] **Step 1: Write the failing test**

```java
package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownAwareChunkerTest {

    private final MarkdownAwareChunker chunker = new MarkdownAwareChunker(
            ChunkingPolicy.DEFAULT, new JdkBreakIteratorSentenceSplitter());

    @Test
    void empty_body_yields_no_chunks() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void small_no_heading_body_yields_one_root_section_chunk() {
        List<ChunkDraft> drafts = chunker.chunk("Hello playground.");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).headingPath()).isEmpty();
        assertThat(drafts.get(0).text().value()).contains("Hello playground");
    }

    @Test
    void chunks_re_parse_as_valid_commonmark() {
        // Invariant: each chunk must round-trip through the parser without
        // exception. The "unclosed fence" failure mode of the old chunker
        // is what this test guards against.
        String md = loadFixture("05-oversize-fence.md");
        List<ChunkDraft> drafts = chunker.chunk(md);
        org.commonmark.parser.Parser p = org.commonmark.parser.Parser.builder().build();
        drafts.forEach(d -> p.parse(d.text().value()));
    }

    @Test
    void parse_fallback_kicks_in_when_commonmark_throws() {
        // commonmark-java is permissive; we synthesize the failure path by
        // injecting a stub parser via a package-private ctor used only by
        // tests.
        MarkdownAwareChunker failing = MarkdownAwareChunker.forTesting(
                ChunkingPolicy.DEFAULT,
                new JdkBreakIteratorSentenceSplitter(),
                body -> { throw new IllegalStateException("synthetic parse failure"); });

        List<ChunkDraft> drafts = failing.chunk("Some body that the new chunker rejects.");
        assertThat(drafts).isNotEmpty();
        drafts.forEach(d -> assertThat(d.headingPath()).isEmpty());
    }

    private String loadFixture(String name) {
        try {
            Path p = Path.of("src/test/resources/chunker-fixtures", name);
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Create the fixture files**

For each fixture, write a short markdown body covering the named edge case:

```
backend/rag-ingestion/rag-ingestion-domain/src/test/resources/chunker-fixtures/
├── 01-empty.md                          # zero bytes
├── 02-no-headings.md                    # 2-3 paragraphs, no #
├── 03-flat-h1.md                        # # A / body / # B / body
├── 04-nested-headings.md                # # / ## / ###
├── 05-oversize-fence.md                 # ```python with > 1600 tokens
├── 06-oversize-paragraph-ko.md          # one Korean paragraph, > 800 tokens, many sentences
├── 07-gfm-table.md                      # | header | + > 600 rows
└── 08-heading-prefix-overflow.md        # very long h1 + small body
```

Use realistic content but keep size modest — Korean prose for fixture 06, repeated `print("...")` lines for fixture 05. Don't bother with `.expected.json` files in this task (those are an over-engineering — the test asserts invariants, not byte-for-byte output). The spec mentioned them as illustrative; we drop them here to keep maintenance cheap.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*MarkdownAwareChunkerTest*"`
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Create `MarkdownAwareChunker`**

```java
package com.playground.ragingestion.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level markdown-aware chunker per ADR-13 §1 (M3.1 amendment).
 * Composes {@link SectionBuilder} + {@link WindowNormalizer}; on CommonMark
 * parse exception falls back to the historical token-window algorithm
 * (single root section, no heading metadata).
 *
 * <p>Stateless after construction. The {@link SentenceSplitter} dependency
 * is forwarded into {@link WindowNormalizer}; the {@link Encoding} is
 * loaded once here for the fallback path.
 */
public final class MarkdownAwareChunker {

    private static final Logger log = LoggerFactory.getLogger(MarkdownAwareChunker.class);

    private final ChunkingPolicy policy;
    private final SectionBuilder sectionBuilder;
    private final WindowNormalizer windowNormalizer;
    private final Encoding encoding;
    private final Function<String, List<Section>> parseHook;

    public MarkdownAwareChunker(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
        this(policy, sentenceSplitter, null);
    }

    static MarkdownAwareChunker forTesting(
            ChunkingPolicy policy,
            SentenceSplitter sentenceSplitter,
            Function<String, List<Section>> parseHook) {
        return new MarkdownAwareChunker(policy, sentenceSplitter, parseHook);
    }

    private MarkdownAwareChunker(
            ChunkingPolicy policy,
            SentenceSplitter sentenceSplitter,
            Function<String, List<Section>> parseHook) {
        this.policy = policy;
        this.sectionBuilder = new SectionBuilder();
        this.windowNormalizer = new WindowNormalizer(policy, sentenceSplitter);
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
        this.parseHook = parseHook;
    }

    public ChunkingPolicy policy() {
        return policy;
    }

    public List<ChunkDraft> chunk(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        try {
            List<Section> sections = parseHook != null
                    ? parseHook.apply(body)
                    : sectionBuilder.build(body);
            return windowNormalizer.normalize(sections);
        } catch (RuntimeException ex) {
            log.error("rag-ingestion: markdown-aware parse failed — falling back to token-window. cause={}", ex.toString());
            return fallback(body);
        }
    }

    private List<ChunkDraft> fallback(String body) {
        IntArrayList tokens = encoding.encode(body);
        int total = tokens.size();
        if (total == 0) {
            return List.of();
        }
        int size = policy.sizeTokens();
        int stride = policy.stride();
        int minTokens = policy.minChunkTokens();
        List<ChunkDraft> drafts = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + size, total);
            drafts.add(new ChunkDraft(ChunkText.of(decodeRange(tokens, start, end)), List.of()));
            if (end == total) break;
            start += stride;
        }
        if (drafts.size() >= 2) {
            int lastStart = (drafts.size() - 1) * stride;
            int lastLength = total - lastStart;
            if (lastLength < minTokens) {
                int prevStart = (drafts.size() - 2) * stride;
                drafts.remove(drafts.size() - 1);
                drafts.remove(drafts.size() - 1);
                drafts.add(new ChunkDraft(ChunkText.of(decodeRange(tokens, prevStart, total)), List.of()));
            }
        }
        return List.copyOf(drafts);
    }

    private String decodeRange(IntArrayList tokens, int fromInclusive, int toExclusive) {
        IntArrayList slice = new IntArrayList(toExclusive - fromInclusive);
        for (int i = fromInclusive; i < toExclusive; i++) slice.add(tokens.get(i));
        return encoding.decode(slice);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-domain:test --tests "*MarkdownAwareChunkerTest*"`
Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/MarkdownAwareChunker.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/MarkdownAwareChunkerTest.java \
        backend/rag-ingestion/rag-ingestion-domain/src/test/resources/chunker-fixtures/
git commit -m "m3p1-chunker: MarkdownAwareChunker orchestrator + parse-fallback + fixtures"
```

---

## Task 8: Retire the old `MarkdownChunker` and rewire `ChunkingConfig`

**Files:**
- Delete: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/MarkdownChunker.java`
- Delete: `backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/MarkdownChunkerTest.java`
- Modify: `backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/config/ChunkingConfig.java`

- [ ] **Step 1: Delete the old chunker + its test**

```bash
git rm backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/MarkdownChunker.java \
       backend/rag-ingestion/rag-ingestion-domain/src/test/java/com/playground/ragingestion/domain/service/MarkdownChunkerTest.java
```

- [ ] **Step 2: Rewire `ChunkingConfig`**

Replace the file contents with:

```java
package com.playground.ragingestion.infrastructure.config;

import com.playground.ragingestion.domain.service.JdkBreakIteratorSentenceSplitter;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
import com.playground.ragingestion.domain.service.SentenceSplitter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ChunkingProperties} (typed bindings from {@code application.yml}
 * + env vars) into a singleton {@link MarkdownAwareChunker} bean per ADR-13
 * §1 (M3.1 amendment). The chunker is stateless once constructed; reuse is
 * safe across threads.
 */
@Configuration
@EnableConfigurationProperties(ChunkingProperties.class)
public class ChunkingConfig {

    @Bean
    public SentenceSplitter sentenceSplitter() {
        return new JdkBreakIteratorSentenceSplitter();
    }

    @Bean
    public MarkdownAwareChunker markdownAwareChunker(
            ChunkingProperties properties, SentenceSplitter sentenceSplitter) {
        return new MarkdownAwareChunker(properties.toPolicy(), sentenceSplitter);
    }
}
```

- [ ] **Step 3: Verify compilation breaks pointing at the next task**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:compileJava`
Expected: FAIL — `IngestionService` still has `private final MarkdownChunker chunker;`. This is intentional; Task 14 repairs it. Continue with the rest of the plan; the build will be green again after Task 14.

To unblock intermediate tasks that need the full module to compile (Task 9 onward), apply this **temporary** shim in `IngestionService.java`:

```java
// TEMPORARY (revert in Task 14): adapt to MarkdownAwareChunker so the
// module still compiles between tasks 8 and 14.
private final com.playground.ragingestion.domain.service.MarkdownAwareChunker chunker;
```

Change the field type and constructor parameter type from `MarkdownChunker` → `MarkdownAwareChunker`. Replace `chunker.chunk(body.body())` with the temporary `chunker.chunk(body.body()).stream().map(d -> d.text()).toList()` so it still returns `List<ChunkText>`. This shim is replaced cleanly in Task 14.

- [ ] **Step 4: Verify the shim compiles**

Run: `./gradlew :rag-ingestion:rag-ingestion:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "m3p1-chunker: retire MarkdownChunker + wire MarkdownAwareChunker bean"
```

---

## Task 9: Flyway migration — add `heading_path` column

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/V202605200003__add_chunk_heading_path.sql`

- [ ] **Step 1: Write the migration**

```sql
-- ADR-13 §1 (M3.1 amendment) — heading breadcrumb metadata for each chunk.
-- text[] over jsonb because we only need ordered string elements and
-- Postgres array predicates / sub-arrays are first-class. NOT NULL with
-- DEFAULT '{}' so the existing rows (pre-reembed) read as "no heading
-- breadcrumb"; the reembed CLI overwrites them with real paths.
ALTER TABLE rag.document_chunks
ADD COLUMN heading_path TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN rag.document_chunks.heading_path IS
    'Compact heading breadcrumb: ARRAY[h1, h2, ...] for the section that '
    'owns this chunk. Empty array = pre-heading content or pre-migration row.';
```

- [ ] **Step 2: Validate the migration applies in a fresh container**

Run: `./gradlew :rag-ingestion:rag-ingestion-infra:test --tests "*Migration*"` if an existing Flyway smoke test is present. Otherwise, defer validation to Task 15's integration test (Testcontainers).

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/V202605200003__add_chunk_heading_path.sql
git commit -m "m3p1-chunker: Flyway migration — add heading_path text[] column"
```

---

## Task 10: Extend `DocumentChunk` record with `headingPath`

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/model/DocumentChunk.java`

- [ ] **Step 1: Patch the record**

```java
package com.playground.ragingestion.domain.model;

import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.ChunkId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DocumentChunk(
        ChunkId id,
        AuthorId userId,
        Visibility visibility,
        ChunkText text,
        Embedding embedding,
        List<String> headingPath,
        BodyChecksum bodyChecksum,
        Instant createdAt
) {

    public DocumentChunk {
        Objects.requireNonNull(id, "DocumentChunk.id must not be null");
        Objects.requireNonNull(userId, "DocumentChunk.userId must not be null");
        Objects.requireNonNull(visibility, "DocumentChunk.visibility must not be null");
        Objects.requireNonNull(text, "DocumentChunk.text must not be null");
        Objects.requireNonNull(embedding, "DocumentChunk.embedding must not be null");
        Objects.requireNonNull(headingPath, "DocumentChunk.headingPath must not be null");
        Objects.requireNonNull(bodyChecksum, "DocumentChunk.bodyChecksum must not be null");
        Objects.requireNonNull(createdAt, "DocumentChunk.createdAt must not be null");
        headingPath = List.copyOf(headingPath);
    }

    public DocumentId documentId() {
        return id.documentId();
    }

    public int chunkIndex() {
        return id.chunkIndex();
    }
}
```

- [ ] **Step 2: Verify compile breaks at remaining call sites**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:compileJava :rag-ingestion:rag-ingestion-infra:compileJava`
Expected: FAIL — `IngestionService` and `ChunkRepositoryJdbcAdapter` construct `DocumentChunk` without the new param. These are fixed in Tasks 12 and 14 respectively. To unblock, update those call sites to pass `List.of()` as a temporary placeholder for `headingPath`.

Apply temporary one-line edits:
- `IngestionService.java`: in the `new DocumentChunk(...)` call inside `ingestInTx`, insert `List.of(),` before `fetchedChecksum`.
- `ChunkRepositoryJdbcAdapter.java`: no construction of `DocumentChunk` happens there (it persists, doesn't construct); skip.
- `IngestionServiceTest.java`: any test constructing `DocumentChunk` gets the same `List.of(),` insertion.

- [ ] **Step 3: Verify the module compiles**

Run: `./gradlew :rag-ingestion:rag-ingestion:compileJava :rag-ingestion:rag-ingestion:compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/model/DocumentChunk.java \
        backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/IngestionService.java \
        backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/IngestionServiceTest.java
git commit -m "m3p1-chunker: add headingPath field to DocumentChunk record (placeholder wiring)"
```

---

## Task 11: Extend `DocumentChunkJpaEntity` with `headingPath`

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/persistence/DocumentChunkJpaEntity.java`

- [ ] **Step 1: Add the column field**

Insert the field, getter, setter, and update the constructor signature.

```java
@Column(name = "heading_path", nullable = false, columnDefinition = "text[]")
@JdbcTypeCode(SqlTypes.ARRAY)
private String[] headingPath;
```

Add the imports:

```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
```

Update the file end-to-end:

1. Add the field declaration above (with the `JdbcTypeCode` import).
2. Add `String[] headingPath` as the new last positional parameter of the existing all-args constructor, assigned with `this.headingPath = headingPath;`.
3. Add the getter `public String[] getHeadingPath()` and setter `public void setHeadingPath(String[] v)`.
4. Update the existing no-arg constructor and any internal builders — there are no callers of the all-args ctor in production code (the entity is hydrated by Hibernate from the column reads), but tests that construct the entity directly should be updated to pass `new String[0]` for `headingPath`.

- [ ] **Step 2: Verify build**

Run: `./gradlew :rag-ingestion:rag-ingestion-infra:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/persistence/DocumentChunkJpaEntity.java
git commit -m "m3p1-chunker: extend DocumentChunkJpaEntity with heading_path column"
```

---

## Task 12: Update `ChunkRepositoryJdbcAdapter` INSERT + SELECT

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/persistence/ChunkRepositoryJdbcAdapter.java`

- [ ] **Step 1: Update the bulk INSERT to bind heading_path**

In `replaceAll(...)`, change the INSERT SQL and add the parameter binding:

```java
        jdbc.batchUpdate(
                "INSERT INTO rag.document_chunks "
                        + "(document_id, chunk_index, user_id, visibility, embedding, text, heading_path, body_checksum) "
                        + "VALUES (?, ?, ?, ?, ?::public.vector, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        DocumentChunk chunk = chunks.get(i);
                        ps.setObject(1, chunk.documentId().value());
                        ps.setInt(2, chunk.chunkIndex());
                        ps.setObject(3, chunk.userId().value());
                        ps.setString(4, chunk.visibility().wireValue());
                        ps.setString(5, new PGvector(chunk.embedding().values()).toString());
                        ps.setString(6, chunk.text().value());
                        ps.setArray(7, ps.getConnection().createArrayOf(
                                "text", chunk.headingPath().toArray(new String[0])));
                        ps.setString(8, chunk.bodyChecksum().value());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :rag-ingestion:rag-ingestion-infra:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/persistence/ChunkRepositoryJdbcAdapter.java
git commit -m "m3p1-chunker: persist heading_path via JdbcTemplate.replaceAll"
```

---

## Task 13: `ChunkingProperties` — bind new fields

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/config/ChunkingProperties.java`

- [ ] **Step 1: Add the two new fields and update `toPolicy()`**

```java
package com.playground.ragingestion.infrastructure.config;

import com.playground.ragingestion.domain.service.ChunkingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "playground.rag-ingestion.chunk")
public class ChunkingProperties {

    private int sizeTokens = 800;
    private int overlapTokens = 120;
    private int minChunkTokens = 64;
    private String tokenizer = "cl100k-base";
    private int maxOversizeFenceTokens = 800;
    private boolean preserveHeadingPath = true;

    // existing getters/setters for the original four fields ...

    public int getMaxOversizeFenceTokens() { return maxOversizeFenceTokens; }
    public void setMaxOversizeFenceTokens(int v) { this.maxOversizeFenceTokens = v; }
    public boolean isPreserveHeadingPath() { return preserveHeadingPath; }
    public void setPreserveHeadingPath(boolean v) { this.preserveHeadingPath = v; }

    public ChunkingPolicy toPolicy() {
        return new ChunkingPolicy(
                sizeTokens, overlapTokens, minChunkTokens, tokenizer,
                maxOversizeFenceTokens, preserveHeadingPath);
    }
}
```

(Keep all existing accessors verbatim; show only the additions and the new `toPolicy` body. Final file should be a tidy `ConfigurationProperties` POJO with no other behavior.)

- [ ] **Step 2: Verify build**

Run: `./gradlew :rag-ingestion:rag-ingestion-infra:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/config/ChunkingProperties.java
git commit -m "m3p1-chunker: bind maxOversizeFenceTokens + preserveHeadingPath properties"
```

---

## Task 14: Wire `IngestionService` to consume `List<ChunkDraft>`

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/IngestionService.java`

- [ ] **Step 1: Replace the shim from Task 8 with the proper wiring**

Locate the chunker invocation inside `ingestInTx(...)` (around line 137) and rewrite the construct-DocumentChunk loop:

```java
        List<ChunkDraft> drafts = chunker.chunk(body.body());
        if (drafts.isEmpty()) {
            chunkRepository.deleteAll(event.documentId());
            log.info(String.format(
                    "rag-ingestion: empty body documentId=%s userId=%s — purged stale chunks",
                    event.documentId(), event.userId()));
            publishIngested(event, 0, fetchedChecksum);
            return;
        }

        List<ChunkText> texts = drafts.stream().map(ChunkDraft::text).toList();
        List<Embedding> embeddings = embeddingPort.embed(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding port returned " + embeddings.size() + " vectors for " + texts.size() + " chunks "
                            + "(document=" + event.documentId() + ")");
        }

        Instant now = Instant.now(clock);
        List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            chunks.add(new DocumentChunk(
                    ChunkId.of(event.documentId(), i),
                    event.userId(),
                    event.visibility(),
                    d.text(),
                    embeddings.get(i),
                    d.headingPath(),
                    fetchedChecksum,
                    now));
        }
        chunkRepository.replaceAll(event.documentId(), chunks);
```

Add the import: `import com.playground.ragingestion.domain.model.vo.ChunkDraft;` and ensure the field type is `private final MarkdownAwareChunker chunker;` (not `MarkdownChunker`).

- [ ] **Step 2: Update `IngestionServiceTest` to reflect the new API**

The existing test mocks `MarkdownChunker.chunk` — replace with mocks of `MarkdownAwareChunker.chunk` returning `List<ChunkDraft>`. Assertions on chunk content stay the same; add an assertion that `DocumentChunk.headingPath()` is propagated from the draft.

Example mock setup:

```java
when(chunker.chunk(anyString())).thenReturn(List.of(
        new ChunkDraft(ChunkText.of("first"), List.of("A", "B")),
        new ChunkDraft(ChunkText.of("second"), List.of("A", "B"))));
```

Add an assertion in the existing "successful ingest" test:

```java
ArgumentCaptor<List<DocumentChunk>> cap = ArgumentCaptor.forClass(List.class);
verify(chunkRepository).replaceAll(eq(event.documentId()), cap.capture());
assertThat(cap.getValue()).allSatisfy(chunk ->
        assertThat(chunk.headingPath()).containsExactly("A", "B"));
```

- [ ] **Step 3: Run the unit tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:test --tests "*IngestionServiceTest*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/IngestionService.java \
        backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/IngestionServiceTest.java
git commit -m "m3p1-chunker: IngestionService consumes ChunkDraft + propagates headingPath"
```

---

## Task 15: Integration test — ingest persists `heading_path`

**Files:**
- Modify: existing `backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/IngestionServiceIntegrationTest.java` (or create if absent — assume present per the M3 P0 spec)

- [ ] **Step 1: Add a new integration test method**

If the integration test class doesn't exist yet (only the unit test does), create a thin one in the `-infra` or `-app` test source set that exercises the full chain through Testcontainers Postgres. Mirror existing M3 integration test patterns; check `backend/rag-ingestion/rag-ingestion-infra/src/test/java/...` first for setup.

Add the case:

```java
@Test
void ingest_persists_heading_path_for_each_chunk() {
    // Given a document body with two H1 sections.
    String body = "# Section A\n\nBody A.\n\n# Section B\n\nBody B.\n";
    UUID docId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    seedDocsApiStub(docId, userId, body, "checksum-A");

    // When the uploaded event is handled.
    ingestionService.handleUploaded(new DocumentUploadedEvent(docId, userId, Visibility.PRIVATE, "checksum-A"));

    // Then both chunks exist and carry the correct heading_path.
    List<HeadingPathRow> rows = jdbc.query(
            "SELECT chunk_index, heading_path FROM rag.document_chunks WHERE document_id = ? ORDER BY chunk_index",
            (rs, i) -> new HeadingPathRow(
                    rs.getInt("chunk_index"),
                    (String[]) rs.getArray("heading_path").getArray()),
            docId);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).headingPath).containsExactly("Section A");
    assertThat(rows.get(1).headingPath).containsExactly("Section B");
}

record HeadingPathRow(int chunkIndex, String[] headingPath) {}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:integrationTest --tests "*IngestionServiceIntegrationTest*"` (use whichever Gradle task the existing integration test class is bound to; if it shares the default `test` task, that command applies).
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/IngestionServiceIntegrationTest.java
git commit -m "m3p1-chunker: integration test asserts heading_path persists for each chunk"
```

---

## Task 16: `ReembedProperties` config binding

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedProperties.java`

- [ ] **Step 1: Create the properties class**

```java
package com.playground.ragingestion.application.service;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-tunables for the {@code reembed} Spring profile's CLI run per
 * ADR-13 §7 (M3.1 amendment). Only consumed when the application boots with
 * {@code spring.profiles.active=reembed}; ignored otherwise.
 */
@ConfigurationProperties(prefix = "playground.rag-ingestion.reembed")
public class ReembedProperties {

    /** {@code all}, {@code user}, or {@code document}. */
    private String scope = "all";
    private UUID userId;
    private UUID documentId;
    private long interDocumentDelayMillis = 0;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public long getInterDocumentDelayMillis() { return interDocumentDelayMillis; }
    public void setInterDocumentDelayMillis(long ms) { this.interDocumentDelayMillis = ms; }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedProperties.java
git commit -m "m3p1-chunker: add ReembedProperties config binding"
```

---

## Task 17: `ReembedService` — per-document re-ingest

Pull the lock-acquire / fetch / chunk / embed / replaceAll loop out into a service so both the runner and a unit test can drive it.

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedService.java`
- Test: `backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/ReembedServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.playground.ragingestion.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.ragingestion.application.port.EmbeddingPort;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReembedServiceTest {

    @Mock MarkdownAwareChunker chunker;
    @Mock EmbeddingPort embeddingPort;
    @Mock ChunkRepository chunkRepository;
    @Mock BodyFetchPort bodyFetchPort;
    @Mock DistributedLockPort lockPort;
    @InjectMocks ReembedService service;

    @Test
    void reembed_single_document_runs_lock_fetch_chunk_embed_replaceAll() {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(bodyFetchPort.fetchBody(any())).thenReturn(stubBody(userId));
        when(chunker.chunk(anyString())).thenReturn(List.of(
                new ChunkDraft(ChunkText.of("body"), List.of("H"))));
        when(embeddingPort.embed(any())).thenReturn(List.of(Embedding.of(new float[]{0.1f})));
        when(lockPort.runWithLock(anyString(), any(), any(), any())).thenAnswer(inv -> {
            return ((java.util.concurrent.Callable<?>) inv.getArgument(3)).call();
        });

        ReembedService.Outcome o = service.reembedOne(DocumentId.of(docId));

        assertThat(o).isEqualTo(ReembedService.Outcome.SUCCESS);
        verify(chunkRepository).replaceAll(eq(DocumentId.of(docId)), any());
    }

    @Test
    void docs_api_404_marks_document_as_skipped() {
        when(bodyFetchPort.fetchBody(any())).thenThrow(new DocumentGoneException("404"));
        when(lockPort.runWithLock(anyString(), any(), any(), any())).thenAnswer(inv -> {
            return ((java.util.concurrent.Callable<?>) inv.getArgument(3)).call();
        });
        ReembedService.Outcome o = service.reembedOne(DocumentId.of(UUID.randomUUID()));
        assertThat(o).isEqualTo(ReembedService.Outcome.SKIPPED);
        verify(chunkRepository, never()).replaceAll(any(), any());
    }

    @Test
    void embedding_permanent_failure_marks_as_failed() {
        when(bodyFetchPort.fetchBody(any())).thenReturn(stubBody(UUID.randomUUID()));
        when(chunker.chunk(anyString())).thenReturn(List.of(new ChunkDraft(ChunkText.of("x"), List.of())));
        when(embeddingPort.embed(any())).thenThrow(new RuntimeException("gateway down"));
        when(lockPort.runWithLock(anyString(), any(), any(), any())).thenAnswer(inv -> {
            return ((java.util.concurrent.Callable<?>) inv.getArgument(3)).call();
        });
        ReembedService.Outcome o = service.reembedOne(DocumentId.of(UUID.randomUUID()));
        assertThat(o).isEqualTo(ReembedService.Outcome.FAILED);
    }

    private DocumentBody stubBody(UUID userId) {
        return new DocumentBody("# H\n\nbody", BodyChecksum.of("c"));
    }
}
```

(Adjust imports / mock types to match the actual `BodyFetchPort` / `DistributedLockPort` interfaces present in `-app/application/port/...`. If the port names differ, fix the test to match.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:test --tests "*ReembedServiceTest*"`
Expected: FAIL — `ReembedService` does not exist.

- [ ] **Step 3: Create `ReembedService`**

```java
package com.playground.ragingestion.application.service;

import com.playground.ragingestion.application.port.EmbeddingPort;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.ChunkId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-document re-ingest pipeline used by {@link ReembedCommandLineRunner}.
 * Shares the same Redisson lock + replaceAll transaction discipline as
 * {@link IngestionService} so live {@code docs.document.uploaded} events
 * arriving mid-job serialize correctly.
 *
 * <p>Re-ingest path skips the checksum-match shortcut on purpose — the
 * whole point of the job is to re-chunk + re-embed every row in the corpus,
 * regardless of whether the body changed.
 */
@Service
public class ReembedService {

    private static final Logger log = LoggerFactory.getLogger(ReembedService.class);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(30);
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    public enum Outcome { SUCCESS, SKIPPED, FAILED }

    private final MarkdownAwareChunker chunker;
    private final EmbeddingPort embeddingPort;
    private final ChunkRepository chunkRepository;
    private final BodyFetchPort bodyFetchPort;
    private final DistributedLockPort lockPort;
    private final Clock clock;

    public ReembedService(
            MarkdownAwareChunker chunker,
            EmbeddingPort embeddingPort,
            ChunkRepository chunkRepository,
            BodyFetchPort bodyFetchPort,
            DistributedLockPort lockPort,
            Clock clock) {
        this.chunker = chunker;
        this.embeddingPort = embeddingPort;
        this.chunkRepository = chunkRepository;
        this.bodyFetchPort = bodyFetchPort;
        this.lockPort = lockPort;
        this.clock = clock;
    }

    public Outcome reembedOne(DocumentId documentId) {
        String lockKey = "rag-ingestion:lock:document:" + documentId.value();
        try {
            return lockPort.runWithLock(lockKey, LOCK_WAIT, LOCK_LEASE, () -> reembedInTx(documentId));
        } catch (DocumentGoneException gone) {
            log.info("rag-ingestion: reembed skipped — document gone documentId={}", documentId.value());
            return Outcome.SKIPPED;
        } catch (RuntimeException ex) {
            log.error("rag-ingestion: reembed failed documentId={} cause={}", documentId.value(), ex.toString());
            return Outcome.FAILED;
        }
    }

    private Outcome reembedInTx(DocumentId documentId) {
        DocumentBody body = bodyFetchPort.fetchBody(documentId);
        List<ChunkDraft> drafts = chunker.chunk(body.body());
        if (drafts.isEmpty()) {
            chunkRepository.deleteAll(documentId);
            return Outcome.SUCCESS;
        }
        List<ChunkText> texts = drafts.stream().map(ChunkDraft::text).toList();
        List<Embedding> embeddings = embeddingPort.embed(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding port returned " + embeddings.size() + " vectors for " + texts.size() + " chunks");
        }
        Instant now = Instant.now(clock);
        // userId + visibility come from the existing chunk rows — reembed
        // never changes them. Pull from the first surviving row, or from
        // docs-api metadata if no row exists yet.
        ExistingChunkMeta meta = chunkRepository.findMetaForDocument(documentId)
                .orElseGet(() -> bodyFetchPort.fetchMetadata(documentId));
        List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            chunks.add(new DocumentChunk(
                    ChunkId.of(documentId.value(), i),
                    meta.userId(),
                    meta.visibility(),
                    d.text(),
                    embeddings.get(i),
                    d.headingPath(),
                    body.bodyChecksum(),
                    now));
        }
        chunkRepository.replaceAll(documentId, chunks);
        return Outcome.SUCCESS;
    }
}
```

`findMetaForDocument` and `fetchMetadata` are new methods. If they don't exist on the existing ports, add them as part of this task:

- `ChunkRepository.findMetaForDocument(DocumentId) : Optional<ExistingChunkMeta>` — SELECT user_id, visibility FROM rag.document_chunks WHERE document_id = ? LIMIT 1.
- `BodyFetchPort.fetchMetadata(DocumentId) : ExistingChunkMeta` — `GET /internal/docs/public/{id}` (per ADR-12 §2, the auxiliary metadata route).
- `ExistingChunkMeta` record in `-app` `port` package: `(AuthorId userId, Visibility visibility)`.

Implement those interface methods + their JDBC adapter / WebClient adapter side as needed (small additions, paralleling the existing patterns).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:test --tests "*ReembedServiceTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "m3p1-chunker: ReembedService — per-document re-ingest under the doc lock"
```

---

## Task 18: `ReembedCommandLineRunner` — the CLI entry point

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedCommandLineRunner.java`

- [ ] **Step 1: Create the runner**

```java
package com.playground.ragingestion.application.service;

import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.model.id.DocumentId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * CLI entry point for the {@code reembed} Spring profile per ADR-13 §7
 * (M3.1 amendment). Iterates the document candidate set (scope-filtered)
 * and runs {@link ReembedService#reembedOne} per document, exiting with
 * code 0 on success and 2 if any document failed.
 *
 * <p>Activated by {@code --spring.profiles.active=reembed}; absent that
 * profile the bean is not created and normal Kafka consumer wiring runs
 * unchanged.
 */
@Configuration
@Profile("reembed")
@EnableConfigurationProperties(ReembedProperties.class)
public class ReembedCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReembedCommandLineRunner.class);

    private final ReembedProperties properties;
    private final ReembedService service;
    private final ChunkRepository chunkRepository;
    private final ConfigurableApplicationContext ctx;

    public ReembedCommandLineRunner(
            ReembedProperties properties,
            ReembedService service,
            ChunkRepository chunkRepository,
            ConfigurableApplicationContext ctx) {
        this.properties = properties;
        this.service = service;
        this.chunkRepository = chunkRepository;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> ids = resolveCandidates();
        log.info("rag-ingestion: reembed start scope={} candidates={}", properties.getScope(), ids.size());

        int success = 0;
        int skipped = 0;
        int failed = 0;
        long start = System.currentTimeMillis();
        for (UUID id : ids) {
            ReembedService.Outcome o = service.reembedOne(DocumentId.of(id));
            switch (o) {
                case SUCCESS -> success++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
            log.info("rag-ingestion: reembed documentId={} outcome={}", id, o);
            if (properties.getInterDocumentDelayMillis() > 0) {
                try {
                    Thread.sleep(properties.getInterDocumentDelayMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("rag-ingestion: reembed done processed={} success={} skipped={} failed={} durationMs={}",
                ids.size(), success, skipped, failed, elapsed);

        int exit = failed > 0 ? 2 : 0;
        System.exit(SpringApplication.exit(ctx, () -> exit));
    }

    private List<UUID> resolveCandidates() {
        return switch (properties.getScope().toLowerCase()) {
            case "document" -> {
                if (properties.getDocumentId() == null) {
                    throw new IllegalArgumentException("scope=document requires --playground.rag-ingestion.reembed.document-id");
                }
                yield List.of(properties.getDocumentId());
            }
            case "user" -> {
                if (properties.getUserId() == null) {
                    throw new IllegalArgumentException("scope=user requires --playground.rag-ingestion.reembed.user-id");
                }
                yield chunkRepository.findDistinctDocumentIdsByUser(properties.getUserId());
            }
            case "all" -> chunkRepository.findAllDistinctDocumentIds();
            default -> throw new IllegalArgumentException("Unknown reembed scope: " + properties.getScope());
        };
    }
}
```

Add the two new repository methods to `ChunkRepository` and its `ChunkRepositoryJdbcAdapter`:

- `List<UUID> findAllDistinctDocumentIds()` — `SELECT DISTINCT document_id FROM rag.document_chunks ORDER BY document_id`.
- `List<UUID> findDistinctDocumentIdsByUser(UUID userId)` — same with `WHERE user_id = ?`.

Add the missing `SpringApplication` import: `import org.springframework.boot.SpringApplication;`.

- [ ] **Step 2: Verify build**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "m3p1-chunker: ReembedCommandLineRunner — CLI entry under reembed profile"
```

---

## Task 19: Wire `application.yml` defaults

**Files:**
- Modify: `backend/rag-ingestion/rag-ingestion-api/src/main/resources/application.yml`

- [ ] **Step 1: Add the two new chunk fields + reembed block**

Under `playground.rag-ingestion.chunk:` add:

```yaml
playground:
  rag-ingestion:
    chunk:
      size-tokens: 800
      overlap-tokens: 120
      min-chunk-tokens: 64
      tokenizer: cl100k-base
      max-oversize-fence-tokens: 800        # M3.1 — line-split threshold for oversized fences/tables
      preserve-heading-path: true           # M3.1 — heading-aware prefix toggle
    reembed:                                # M3.1 — only consumed under `reembed` Spring profile
      scope: all
      user-id: null
      document-id: null
      inter-document-delay-millis: 0
```

(Preserve any other `playground.rag-ingestion.*` keys that already exist; show only the additions above.)

- [ ] **Step 2: Verify the application starts in default profile**

Run: `./gradlew :rag-ingestion:rag-ingestion-api:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-api/src/main/resources/application.yml
git commit -m "m3p1-chunker: application.yml defaults for new chunk + reembed properties"
```

---

## Task 20: Integration test — `ReembedCommandLineRunner` end-to-end

**Files:**
- Create: `backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/ReembedCommandLineRunnerIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

```java
package com.playground.ragingestion.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the rag-ingestion application under the {@code reembed} profile,
 * seeds two documents with stale chunks (no heading_path), and asserts the
 * runner rewrites every row with markdown-aware boundaries + populated
 * heading_path.
 */
@SpringBootTest
@ActiveProfiles({"reembed", "test"})
class ReembedCommandLineRunnerIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void reembed_rewrites_all_rows_with_heading_path() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        seedStaleChunks(docA, "# Section A\n\nBody A.");
        seedStaleChunks(docB, "# Section B\n\nBody B.");

        // Runner fires on application start (via ApplicationRunner). After the
        // ApplicationContext finishes startup the test continues here.

        List<String[]> rows = jdbc.query(
                "SELECT heading_path FROM rag.document_chunks ORDER BY document_id, chunk_index",
                (rs, i) -> (String[]) rs.getArray("heading_path").getArray());

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(hp -> assertThat(hp).isNotEmpty());
    }

    private void seedStaleChunks(UUID documentId, String body) {
        // Existing M3 integration test utilities should already expose a
        // helper to insert a chunk row directly. If not, build one inline:
        // INSERT into rag.document_chunks with embedding = '[0,0,...]'::vector,
        // text = body, heading_path = '{}'.
    }
}
```

Test wiring details to resolve at implementation time:
- Stub `BodyFetchPort` so it returns the body matching `documentId`.
- Stub `EmbeddingPort` to return a deterministic vector.
- The `test` profile likely already has equivalent stubs; reuse them.

- [ ] **Step 2: Run the test**

Run: `./gradlew :rag-ingestion:rag-ingestion-app:test --tests "*ReembedCommandLineRunnerIntegrationTest*"`
Expected: PASS. If `System.exit(...)` aborts the test JVM, replace the exit call in the runner with `SpringApplication.exit(ctx, ...)` only (it doesn't actually call `System.exit` when the context shuts down cleanly in tests). If tests still abort, gate the exit call on a property `playground.rag-ingestion.reembed.exit-on-completion=true` (default true; tests set false).

- [ ] **Step 3: Commit**

```bash
git add backend/rag-ingestion/rag-ingestion-app/src/test/java/com/playground/ragingestion/application/service/ReembedCommandLineRunnerIntegrationTest.java \
        backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedCommandLineRunner.java \
        backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/ReembedProperties.java
git commit -m "m3p1-chunker: end-to-end integration test for reembed CLI"
```

---

## Task 21: Add Micrometer metrics for chunker + reembed

**Files:**
- Modify: `MarkdownAwareChunker.java` — accept an optional `MeterRegistry` (or a thin `ChunkerMetrics` port to keep `-domain` Spring-free).
- Modify: `ReembedCommandLineRunner.java` — record per-outcome counter + total Timer.

Preserve ADR-02 layering: `-domain` doesn't import `io.micrometer.*`. Define a tiny `ChunkerMetrics` interface in `-domain` and a Micrometer-backed adapter in `-infra`.

- [ ] **Step 1: Create the port in `-domain`**

```java
// rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/ChunkerMetrics.java
package com.playground.ragingestion.domain.service;

import java.time.Duration;

public interface ChunkerMetrics {

    void recordDuration(Duration d, Outcome outcome);
    void incOversizeFenceSplit();
    void incOversizeSentenceFallback();
    void incParseFallback();

    enum Outcome { SUCCESS, PARSE_FALLBACK }

    /** No-op implementation for unit tests + non-metric paths. */
    ChunkerMetrics NOOP = new ChunkerMetrics() {
        public void recordDuration(Duration d, Outcome o) {}
        public void incOversizeFenceSplit() {}
        public void incOversizeSentenceFallback() {}
        public void incParseFallback() {}
    };
}
```

- [ ] **Step 2: Wire `ChunkerMetrics` into `MarkdownAwareChunker` + `WindowNormalizer`**

`MarkdownAwareChunker` gains a third ctor arg (`ChunkerMetrics`, defaulting to `NOOP` via overload). Wrap `chunk(body)` body with timing + outcome tagging. `WindowNormalizer` accepts metrics via constructor and calls `incOversizeFenceSplit()` / `incOversizeSentenceFallback()` at the corresponding paths.

Show the chunker change explicitly:

```java
public MarkdownAwareChunker(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
    this(policy, sentenceSplitter, ChunkerMetrics.NOOP, null);
}
public MarkdownAwareChunker(ChunkingPolicy policy, SentenceSplitter sentenceSplitter, ChunkerMetrics metrics) {
    this(policy, sentenceSplitter, metrics, null);
}
// existing forTesting(...) ctor passes ChunkerMetrics.NOOP

public List<ChunkDraft> chunk(String body) {
    if (body == null || body.isEmpty()) return List.of();
    long t0 = System.nanoTime();
    try {
        List<Section> sections = parseHook != null ? parseHook.apply(body) : sectionBuilder.build(body);
        List<ChunkDraft> drafts = windowNormalizer.normalize(sections);
        metrics.recordDuration(Duration.ofNanos(System.nanoTime() - t0), ChunkerMetrics.Outcome.SUCCESS);
        return drafts;
    } catch (RuntimeException ex) {
        log.error("rag-ingestion: markdown-aware parse failed — falling back to token-window. cause={}", ex.toString());
        metrics.incParseFallback();
        List<ChunkDraft> drafts = fallback(body);
        metrics.recordDuration(Duration.ofNanos(System.nanoTime() - t0), ChunkerMetrics.Outcome.PARSE_FALLBACK);
        return drafts;
    }
}
```

Similar small wiring inside `WindowNormalizer.splitOversizeFence` (call `metrics.incOversizeFenceSplit()` when entering the line-split branch) and `WindowNormalizer.splitOversizeParagraph` (call `metrics.incOversizeSentenceFallback()` when the single-sentence-overrun path fires).

- [ ] **Step 3: Create the Micrometer-backed adapter in `-infra`**

```java
// rag-ingestion-infra/src/main/java/com/playground/ragingestion/infrastructure/metrics/MicrometerChunkerMetrics.java
package com.playground.ragingestion.infrastructure.metrics;

import com.playground.ragingestion.domain.service.ChunkerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class MicrometerChunkerMetrics implements ChunkerMetrics {

    private final MeterRegistry registry;

    public MicrometerChunkerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordDuration(Duration d, Outcome outcome) {
        Timer.builder("playground.rag_ingestion.chunker.duration")
                .tags("outcome", outcome.name().toLowerCase())
                .register(registry)
                .record(d);
    }
    @Override public void incOversizeFenceSplit() {
        registry.counter("playground.rag_ingestion.chunker.oversize_fence_split").increment();
    }
    @Override public void incOversizeSentenceFallback() {
        registry.counter("playground.rag_ingestion.chunker.oversize_sentence_fallback").increment();
    }
    @Override public void incParseFallback() {
        registry.counter("playground.rag_ingestion.chunker.parse_fallback").increment();
    }
}
```

- [ ] **Step 4: Update `ChunkingConfig` to inject the adapter into the chunker**

```java
@Bean
public MarkdownAwareChunker markdownAwareChunker(
        ChunkingProperties properties,
        SentenceSplitter sentenceSplitter,
        ChunkerMetrics chunkerMetrics) {
    return new MarkdownAwareChunker(properties.toPolicy(), sentenceSplitter, chunkerMetrics);
}
```

- [ ] **Step 5: Add reembed metrics in `ReembedCommandLineRunner`**

Inject `MeterRegistry` (no port needed — runner is `-app` and Micrometer is an existing infra dependency). After each `service.reembedOne(...)`, increment `playground.rag_ingestion.reembed.documents` with tag `outcome=success|skipped|failed`. Wrap the entire run in a `Timer` named `playground.rag_ingestion.reembed.duration`.

- [ ] **Step 6: Run all tests**

Run: `./gradlew :rag-ingestion:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "m3p1-chunker: Micrometer metrics for chunker + reembed"
```

---

## Task 22: Amend ADR-13 §1, §6, §7

**Files:**
- Modify: `docs/adr/13-m3-rag-ingestion.md`

- [ ] **Step 1: §1 Chunking decision**

Replace alternative (c) "Semantic chunking — Rejected for M3 P0" paragraph with an "M3.1 amendment" block noting that the markdown-aware chunker is now the default and the original token-window algorithm is retained as the parse-fallback path. Add the two new fields (`maxOversizeFenceTokens`, `preserveHeadingPath`) to the parameter table.

- [ ] **Step 2: §6 Metrics**

Add four rows to the metrics table:

| Metric | Type | Labels |
|---|---|---|
| `playground.rag_ingestion.chunker.duration` | Timer | `outcome` |
| `playground.rag_ingestion.chunker.oversize_fence_split` | Counter | — |
| `playground.rag_ingestion.chunker.oversize_sentence_fallback` | Counter | — |
| `playground.rag_ingestion.chunker.parse_fallback` | Counter | — |
| `playground.rag_ingestion.reembed.documents` | Counter | `outcome` |
| `playground.rag_ingestion.reembed.duration` | Timer | — |

- [ ] **Step 3: §7 Operator runners**

Below the existing backfill profile entry, add the `reembed` profile entry with the invocation example and scope options table.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/13-m3-rag-ingestion.md
git commit -m "m3p1-chunker: ADR-13 amendments — §1 markdown-aware, §6 new metrics, §7 reembed profile"
```

---

## Task 23: Amend M3 / M4 PRD + roadmap

**Files:**
- Modify: `docs/prd/M3-rag-ingestion.md`
- Modify: `docs/prd/M4-rag-chat.md`
- Modify: `docs/roadmap.md`

- [ ] **Step 1: M3 PRD**

In §"M3.1 (same milestone bucket, cycle slack 있으면 ship)":
- Mark "Re-embedding job (chunking 파라미터 또는 모델 변경 대응)" as done (checkbox `[x]`).
- Add a new line: "Markdown-aware chunker + `heading_path` 컬럼 (shipped 2026-05-19)".

In §"P2":
- Delete the line "Adaptive chunk size (semantic chunking, layout-aware)" — superseded by the M3.1 work.

In §"Acceptance criteria → Schema":
- Add `heading_path text[] NOT NULL DEFAULT '{}'` to the column list.

- [ ] **Step 2: M4 PRD**

Add a single bullet near §"Retrieval" or §"Citation": "Citation 렌더링은 `rag.document_chunks.heading_path` (M3.1에서 추가) 컬럼을 활용해 발췌의 섹션 경로를 표시할 수 있다. M4 frontend 작업은 별도 follow-up."

- [ ] **Step 3: roadmap.md**

In §M3 schema bullet, append `+ heading_path text[]` to the chunk row column list.

- [ ] **Step 4: Commit**

```bash
git add docs/prd/M3-rag-ingestion.md docs/prd/M4-rag-chat.md docs/roadmap.md
git commit -m "m3p1-chunker: amend M3 PRD / M4 PRD / roadmap for markdown-aware chunker work"
```

---

## Task 24: Open the PR

- [ ] **Step 1: Verify the whole module is green**

Run: `./gradlew :rag-ingestion:check`
Expected: BUILD SUCCESSFUL. All unit + integration tests pass.

- [ ] **Step 2: Push the branch**

Run: `git push -u origin worktree-m3p1-markdown-aware-chunker-spec`

- [ ] **Step 3: Open the PR**

```bash
gh pr create --title "m3p1: markdown-aware chunker + heading_path + reembed CLI" --body "$(cat <<'EOF'
## Summary

- Replace fixed-window MarkdownChunker with commonmark-java AST-driven chunker (section build + window normalize + sentence fallback)
- Add `heading_path text[]` column to `rag.document_chunks` for citation breadcrumbs
- Ship `reembed` Spring profile + operator CLI to migrate existing corpus to new boundaries

## Spec / Plan

- Spec: `docs/superpowers/specs/2026-05-19-m3p1-markdown-aware-chunker-design.md`
- Plan: `docs/superpowers/plans/2026-05-19-m3p1-markdown-aware-chunker.md`

## Test plan

- [ ] `./gradlew :rag-ingestion:check` — all unit + integration tests pass
- [ ] Manual: bring up dev compose, upload a markdown doc with headings/fences/tables, verify `heading_path` populates per chunk
- [ ] Manual: run `bootRun --args="--spring.profiles.active=reembed"` against a populated dev DB, verify summary line shows `failed=0`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Report the PR URL**

Done.

---

## Spec coverage self-check

| Spec section | Task(s) |
|---|---|
| Algorithm — SectionBuilder | 5 |
| Algorithm — WindowNormalizer (block pack / fence / table / paragraph / list / blockquote) | 6a, 6b, 6c |
| Algorithm — Heading-aware prefix | 6c |
| Algorithm — Trailing merge | 6c |
| Algorithm — Edge cases (empty, no-headings, frontmatter, oversize sentence, nested fence) | covered by 5, 6, 7 tests |
| Parse-fallback | 7 |
| `ChunkingPolicy` new fields | 2 |
| `ChunkDraft` record | 3 |
| `SentenceSplitter` port + JDK impl | 4 |
| `DocumentChunk.headingPath` | 10 |
| Flyway migration | 9 |
| `DocumentChunkJpaEntity` column | 11 |
| `ChunkRepositoryJdbcAdapter` INSERT/SELECT | 12 |
| `ChunkingProperties` + bean wiring | 13, 8 |
| `IngestionService` chunker output handling | 14 |
| Ingest integration test | 15 |
| Re-embed CLI (`ReembedService` + `ReembedCommandLineRunner` + `ReembedProperties`) | 16, 17, 18 |
| `application.yml` defaults | 19 |
| Re-embed CLI integration test | 20 |
| Metrics (chunker + reembed) | 21 |
| ADR-13 amendments §1 / §6 / §7 | 22 |
| M3 PRD / M4 PRD / roadmap amendments | 23 |
| PR open + verify | 24 |
