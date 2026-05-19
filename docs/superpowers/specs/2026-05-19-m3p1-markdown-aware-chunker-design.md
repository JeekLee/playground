# Design: M3.1 — Markdown-aware Chunker + Heading-path Metadata + Re-embed Job

> **Status:** brainstorm-approved 2026-05-19. Next step: writing-plans.
>
> **Scope:** swap M3's `MarkdownChunker` (fixed token-window) for a markdown-aware
> chunker that splits on heading + block boundaries, carries `heading_path` on
> every chunk row, and ships an operator-triggered `reembed` CLI that brings
> the existing `rag.document_chunks` corpus onto the new boundaries in one
> manual run.
>
> **Source documents pre-read during brainstorm:**
> - `docs/adr/13-m3-rag-ingestion.md` §1 (chunker decision — alternative (c) "Semantic chunking" deferred to M3.1)
> - `docs/adr/13-m3-rag-ingestion.md` §6 (metrics), §7 (backfill CLI pattern this re-embed CLI mirrors)
> - `docs/prd/M3-rag-ingestion.md` §"M3.1 (same milestone bucket)" — re-embedding job already listed; §"Out of scope → P2" line on adaptive chunking is to be moved/deleted
> - `backend/rag-ingestion/rag-ingestion-domain/src/main/java/com/playground/ragingestion/domain/service/MarkdownChunker.java` (the existing implementation being replaced)
> - `backend/rag-ingestion/rag-ingestion-app/src/main/java/com/playground/ragingestion/application/service/IngestionService.java` lines 110–175 (`ingestInTx` is the only caller of `chunker.chunk`)

## 한 줄 요약

문서를 헤딩/블록 의미 단위로 잘라 chunk마다 `heading_path` 메타데이터를 같이
저장하고, 운영자 발화로 기존 corpus를 새 boundary로 한 번에 재임베딩한다.

## Why this work, now

ADR-13 §1은 M3 P0에서 의도적으로 단순한 800-token sliding window chunker를
채택하고 의미 단위 chunker를 "nice M3.1 follow-up"으로 defer했다. 그 시점의
이유는 (1) GFM 펜스/테이블/헤딩 트리 인식이 알고리즘 분기 케이스를 크게
늘리고 (2) M3 P0의 critical path는 `(user_id, visibility)` invariant 안정화에
있었다는 두 가지였다.

지금 이 작업을 끌어올리는 이유:

1. **Citation UX 한계가 드러남.** M4 chat이 인용 텍스트를 노출하지만 사용자가
   "이게 어느 섹션의 발췌인지" 알 수 없다. 본문 텍스트 안에 헤딩이 우연히
   포함된 chunk가 아니면 출처 위치가 불투명.
2. **품질 회귀의 hotspot은 코드 펜스.** 현재 chunker는 펜스를 opaque 토큰
   스트림으로 다루기 때문에 800-token 경계가 펜스 중간에 떨어지면 닫히지 않은
   ```` ``` ````으로 split된다. 임베딩 자체는 동작하지만 citation 렌더링에서
   markdown이 깨져 보임.
3. **재임베딩 잡은 이미 M3.1 bucket에 잡혀 있음.** 알고리즘을 바꾸는 작업과
   기존 row를 새 boundary로 옮기는 작업은 같은 단위로 묶는 게 자연스럽다.

M3 P0의 invariant — chunk 행마다 `(user_id, visibility)` non-null, 단일 SQL
필터로 corpus 분리 — 는 이 작업에서 손대지 않는다.

## 결정 사항 요약

| 결정 | 값 |
|---|---|
| 알고리즘 | commonmark-java AST → section build → window normalize. 펜스/테이블 atomic, oversize paragraph는 sentence split |
| 라이브러리 | `org.commonmark:commonmark:0.22.x` + GFM tables/strikethrough 확장. 문장 분할은 JDK `BreakIterator` (의존성 0) |
| 새 컬럼 | `rag.document_chunks.heading_path text[] NOT NULL DEFAULT '{}'` |
| 도메인 모델 | `DocumentChunk.headingPath: List<String>` 필드 추가 |
| Backfill 전략 | M3.1의 "Re-embedding job"을 이번 작업과 함께 구현. 운영자 발화 단발 실행 |
| Cross-section pack | 안 함 (간단한 invariant: section은 최소 1 chunk) |
| Heading-aware overlap | 같은 section의 2번째 chunk부터 `> Context: A > B > C` blockquote 형태 prefix prepend |
| Parse failure 정책 | commonmark가 실패하면 (기존 알고리즘과 동일한) token-window fallback. 인제스트 자체는 절대 실패시키지 않음 |
| 코드 변경 범위 | `backend/rag-ingestion/` 한 BC. 다른 BC source tree 0 라인 |

## 모듈 배치 & 컴포넌트

### 신규 / 변경 파일

| 파일 | 모듈 | 변경 종류 |
|---|---|---|
| `MarkdownChunker.java` | rag-ingestion-domain | **삭제** (대체) |
| `MarkdownAwareChunker.java` | rag-ingestion-domain | **신규** — 진입점 |
| `SectionBuilder.java` | rag-ingestion-domain | 신규 — CommonMark AST → `List<Section>` |
| `WindowNormalizer.java` | rag-ingestion-domain | 신규 — section → `List<ChunkDraft>` |
| `SentenceSplitter.java` (interface) + `JdkBreakIteratorSentenceSplitter.java` | rag-ingestion-domain | 신규 — oversize paragraph만 처리 |
| `ChunkingPolicy.java` | rag-ingestion-domain | **수정** — 필드 2개 추가 |
| `ChunkText.java` | rag-ingestion-domain | 그대로 |
| `ChunkDraft.java` (record) | rag-ingestion-domain | 신규 — `(ChunkText text, List<String> headingPath)` |
| `DocumentChunk.java` | rag-ingestion-domain | **수정** — `headingPath: List<String>` 필드 |
| `ChunkingProperties.java` | rag-ingestion-infra | **수정** — 신규 토글 바인딩 |
| `DocumentChunkJpaEntity.java` | rag-ingestion-infra | **수정** — `headingPath` 컬럼 |
| `ChunkRepositoryJdbcAdapter.java` | rag-ingestion-infra | **수정** — INSERT/SELECT 컬럼 추가 |
| `V202605200003__add_chunk_heading_path.sql` (Flyway) | rag-ingestion-infra | 신규 마이그레이션 |
| `IngestionService.java` | rag-ingestion-app | **수정** — chunker 출력 = `List<ChunkDraft>` 로 받아 `DocumentChunk` 빌드 |
| `ReembedCommandLineRunner.java` | rag-ingestion-app | 신규 — `reembed` profile에서만 활성 |
| `application.yml` | rag-ingestion-api | **수정** — 신규 프로퍼티 default |

### 모듈 레이어 준수

- `commonmark-java` + `commonmark-ext-gfm-tables` + `commonmark-ext-gfm-strikethrough`를
  `-domain`의 `dependencies { implementation ... }`에 추가 (JTokkit과 동격의
  leaf 라이브러리, ADR-02 layering 위반 없음).
- `SentenceSplitter`는 도메인 포트, `JdkBreakIteratorSentenceSplitter`가 도메인
  안의 기본 구현. Spring 의존성 없음.
- 재임베딩 잡은 `CommandLineRunner` Bean → `-app` 위치. ADR-13 §7의 backfill CLI와
  같은 패턴.

### `ChunkingPolicy` 필드

```java
record ChunkingPolicy(
    int sizeTokens,                 // 기존, default 800
    int overlapTokens,              // 기존, default 120 — 의미 변경 (§"Heading-aware prefix" 참고)
    int minChunkTokens,             // 기존, default 64
    String tokenizer,               // 기존
    int maxOversizeFenceTokens,     // 신규, default = sizeTokens
    boolean preserveHeadingPath     // 신규, default true
)
```

`overlapTokens`의 의미가 정상 경로에서는 바뀜: 기존엔 토큰 슬라이딩 stride,
신규엔 chunk 시작 heading-prefix에 허용되는 최대 토큰 예산. `sizeTokens`를
넘지 않는 한도에서 상위 헤딩을 위에서부터 prepend한다.

> **Fallback 경로 한정:** commonmark parse 실패 시 token-window fallback이
> 동작하는데, 그 경로는 AST가 없어 heading-prefix를 만들지 못한다. 따라서
> fallback은 같은 `overlapTokens` 값을 **기존 의미(슬라이딩 stride)**로 재사용한다
> — 같은 숫자, 경로별로 다른 의미. 의도적인 단순화(별도 필드를 추가하지 않음).
> 이 경로는 운영상 거의 발생하지 않으며 메트릭 `chunker.parse_fallback`이
> 가시화한다.

### 호출 그래프

```
IngestionService.ingestInTx()
  ├─ chunker.chunk(body) : List<ChunkDraft>
  │   ├─ SectionBuilder.build(body) : List<Section>
  │   │     └─ commonmark-java Parser → heading tree 순회
  │   └─ WindowNormalizer.normalize(sections) : List<ChunkDraft>
  │         ├─ JTokkit tokenize
  │         ├─ block-atomic pack/split (fence/table 보존)
  │         ├─ oversize paragraph → SentenceSplitter.split()
  │         └─ heading-aware prefix injection
  ├─ embeddingPort.embed(drafts.map(text)) : List<Embedding>
  └─ chunkRepository.replaceAll(documentId, [DocumentChunk with headingPath])
```

## 알고리즘

### Section build

`record Section(List<String> headingPath, List<Node> blocks)`

1. `commonmark.parser.Parser`를 GFM tables + strikethrough + fenced code 확장과
   함께 구성, 본문 파싱.
2. 루트 자식을 순회하며 `headingStack: Map<Level, String>` 유지.
3. `Heading(level=L)` 노드: stack을 `L-1`까지 pop → 현재 텍스트를 `L`에 push →
   **새 Section 시작** (`headingPath` = stack 1..L 평탄화).
4. 비-Heading 블록(Paragraph, FencedCodeBlock, IndentedCodeBlock, Table,
   BulletList, OrderedList, BlockQuote, ThematicBreak)은 현재 Section의
   `blocks`에 append.
5. **Heading 노드 자체는 Section의 첫 block으로 포함** — 임베딩이 헤딩
   텍스트를 자연스럽게 보도록.
6. 첫 헤딩 이전의 leading content → `headingPath = []`인 Section. 헤딩 없는
   문서 = 단일 root Section.

### Window normalize

각 Section은 최소 1 chunk를 차지한다(**cross-section pack 없음**) — 의미 단위
보존 강화.

```
draftBuf = []
draftTokens = 0
for block in section.blocks:
    bt = jtokkitTokenCount(renderToMarkdown(block))
    if bt > sizeTokens:
        flush(draftBuf)
        emitOversizeBlock(block)
        continue
    if draftTokens + bt > sizeTokens:
        flush(draftBuf)
    draftBuf.append(block)
    draftTokens += bt
flush(draftBuf)
mergeTrailingIfShort(section.chunks, minChunkTokens)
```

`flush`는 buffered block들을 하나의 `ChunkDraft(text, headingPath)`로 묶는다.
**section의 2번째 chunk부터** heading-aware prefix가 prepend된다 (아래 참고).

### Oversize block

| 블록 | 처리 |
|---|---|
| `FencedCodeBlock` / `IndentedCodeBlock` | 토큰 ≤ `maxOversizeFenceTokens`: atomic 단일 chunk. 초과: 펜스 안에서 line-group split, 각 split chunk를 원본 언어 태그로 다시 ``` ``` 감싸서 valid markdown 유지 |
| `Table` (GFM) | 같은 규칙. split 시 **header row + separator를 모든 chunk에 반복** prepend |
| `Paragraph` | `SentenceSplitter.split(text, Locale.KOREAN)` → 문장 리스트 → 같은 greedy pack 재귀. 단일 sentence > sizeTokens면 토큰 윈도우 폴백 + WARN 로그 |
| `BulletList` / `OrderedList` | top-level item 단위 split. item 자체가 oversize면 child block을 재귀 |
| `BlockQuote` | child block을 재귀 처리, 결과 chunk마다 `>` prefix 보존 |

### Heading-aware prefix

Section이 N(>1)개 chunk로 split되면 chunk 2..N의 본문 앞에 삽입:

```
> Context: <h1> > <h2> > <h3>

<chunk body>
```

- 예산: `headingPrefixTokens + bodyTokens ≤ sizeTokens`. prefix가
  `overlapTokens`(120)를 초과하면 **가장 깊은 헤딩부터 유지하고 위쪽 헤딩
  drop**. `## API > ### Auth > #### Refresh`가 길면 `### Auth > #### Refresh`로
  단축.
- `headingPath = []`인 root section은 prefix 없음.

### Edge cases (명시적 결정)

| 케이스 | 처리 |
|---|---|
| 빈 본문 | `List.of()` 반환 (현재와 동일) |
| 헤딩 없는 문서 | 단일 root Section, `headingPath = []` |
| YAML frontmatter (`---...---`) | CommonMark 기본 동작 (thematic break + paragraphs). 별도 strip 없음 |
| Inline HTML | 텍스트로 보존, 임베딩에 그대로 |
| 단일 문장 > `sizeTokens` | 토큰 윈도우 폴백, WARN 로그 + 메트릭 카운터 |
| 중첩 fence (` ```` ` 표기) | commonmark가 정확히 파싱하므로 atomic 보존 |
| 한 헤딩 아래 본문이 `< minChunkTokens` | 단일 chunk로 emit. cross-section pack 안 함 |

### 출력 invariant (테스트에서 단언)

- 모든 chunk의 토큰 수 ≤ `sizeTokens + overlapTokens` (단, oversize fence가
  atomic으로 유지되는 케이스는 예외).
- 모든 chunk가 valid markdown (commonmark로 재파싱 가능).
- 같은 section의 chunk들은 동일한 `headingPath` 보유.
- 빈 body → `[]`, 비어있지 않은 body → 최소 1 chunk.

## 스키마 / 도메인 / 영속화

### Flyway 마이그레이션

```sql
-- backend/rag-ingestion/rag-ingestion-infra/src/main/resources/db/migration/V202605200003__add_chunk_heading_path.sql
ALTER TABLE rag.document_chunks
ADD COLUMN heading_path text[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN rag.document_chunks.heading_path IS
  'Compact heading breadcrumb: ARRAY[h1,h2,...] for the section that owns this chunk. Empty array = pre-heading content or pre-migration row.';
```

- **NOT NULL + DEFAULT '{}'**: 기존 row가 destructive하게 깨지지 않고 새 INSERT의
  컬럼 누락도 안전.
- 인덱스 없음 — heading_path는 filter 키가 아니라 UI 렌더링 메타데이터.
- Postgres `text[]` 선택 이유: ordering 보존 + ARRAY 비교/포함 연산 native +
  JDBC mapping 1줄.

### 도메인 모델 변경

```java
public record DocumentChunk(
    ChunkId id,
    UserId userId,
    Visibility visibility,
    ChunkText text,
    Embedding embedding,
    List<String> headingPath,   // 신규, never null (empty list OK)
    BodyChecksum bodyChecksum,
    Instant createdAt
) {
    public DocumentChunk {
        Objects.requireNonNull(headingPath, "headingPath");
        headingPath = List.copyOf(headingPath);
    }
}
```

`ChunkText`는 그대로. `ChunkDraft(text, headingPath)` record가 chunker →
IngestionService 사이의 운반체.

### JPA / JDBC

`DocumentChunkJpaEntity`에 필드 추가:

```java
@Column(name = "heading_path", nullable = false, columnDefinition = "text[]")
@JdbcTypeCode(SqlTypes.ARRAY)
private String[] headingPath;
```

`ChunkRepositoryJdbcAdapter`의 bulk INSERT에 한 줄 추가:
`PreparedStatement.setArray(idx, conn.createArrayOf("text", headingPath.toArray(String[]::new)))`.
SELECT 매퍼도 동일.

### IngestionService 변경

`ingestInTx()` 137~167줄 패치:

```java
List<ChunkDraft> drafts = chunker.chunk(body.body());
if (drafts.isEmpty()) {
    chunkRepository.deleteAll(event.documentId());
    publishIngested(event, 0, fetchedChecksum);
    return;
}
List<ChunkText> texts = drafts.stream().map(ChunkDraft::text).toList();
List<Embedding> embeddings = embeddingPort.embed(texts);
// ...size check...
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
```

기존 checksum-match 단락 경로는 그대로(본문 동일하면 chunker도 돌지 않음).

## 재임베딩 잡 (`reembed` Spring profile)

| 측면 | 결정 |
|---|---|
| 진입점 | `ReembedCommandLineRunner` (only `@Profile("reembed")`, `-app` 모듈) |
| 호출 | `./gradlew :rag-ingestion:rag-ingestion-api:bootRun --args="--spring.profiles.active=reembed --playground.rag-ingestion.reembed.scope=all"` |
| 스코프 | `all` (default) / `user:<userId>` / `document:<documentId>` |
| 후보 ID 수집 | `SELECT DISTINCT document_id FROM rag.document_chunks` (+ scope filter) |
| 처리 흐름 | document마다 `lockPort.runWithLock(...)` 안에서 `bodyFetchPort.fetchBody → chunker.chunk → embeddingPort.embed → chunkRepository.replaceAll → publishIngested` |
| Idempotency | 동일 lock + replaceAll 트랜잭션 → live `uploaded` 이벤트와 직렬화. 잡 종료 후 live 이벤트는 checksum 매치로 skip 또는 자연 재실행 |
| Throttle | `inter-document-delay-millis` (default 0) — gateway가 단일 인스턴스라 너무 빠르면 GPU 큐 포화. M2 corpus 규모(<100 docs)에선 0 OK |
| 실패 정책 | per-document try/catch — 404/410/5xx 등은 ERROR 로그 + 카운터 증가 후 다음 document로 continue. 잡 전체 abort 안 함 |
| 종료 | 마지막 document 처리 후 `System.exit(0)`. failed > 0이면 exit 2 |
| 재실행 안전 | 체크포인트 없음 — 중간 crash 시 처음부터. document 단위 처리가 idempotent라 안전 |

이벤트 외 부수 효과 없음 — 정상 ingest와 동일하게 `rag.document.ingested` emit.

## 배포 순서

1. Flyway 마이그레이션 (자동) — 기존 row가 `heading_path='{}'`로 채워짐.
2. 신규 빌드 배포 — 새 `uploaded` 이벤트는 새 chunker로 처리, `heading_path`
   정상.
3. 운영자가 `reembed` 프로필로 한 번 실행 → 기존 row 전체 re-chunk + re-embed +
   `heading_path` 채움.

단계 2~3 gap 동안 일부 row는 헤딩 메타가 비어있지만 M4 citation은 "breadcrumb
없음"으로 단순 무시 — eventually consistent.

## 새 `application.yml` 항목

```yaml
playground:
  rag-ingestion:
    chunk:
      size-tokens: 800
      overlap-tokens: 120
      min-chunk-tokens: 64
      tokenizer: cl100k-base
      max-oversize-fence-tokens: 800   # 신규
      preserve-heading-path: true      # 신규
    reembed:                           # 신규 (reembed 프로필에서만 의미 있음)
      scope: all                       # all | user | document
      user-id: null
      document-id: null
      inter-document-delay-millis: 0
```

## 테스트 전략

### Unit (rag-ingestion-domain)

| 테스트 클래스 | 커버리지 |
|---|---|
| `SectionBuilderTest` | empty / no-heading / flat h1 / nested h1>h2>h3 / level skip (h1→h3) / sibling 섹션 / pre-heading content. `headingPath` 정확성 단언 |
| `WindowNormalizerTest` | section < size → 1 chunk / section > size + 문단 단위 pack / oversize fence atomic / oversize fence line-split / 문장 단위 split / 단일 거대 문장 → 토큰 윈도우 fallback / GFM 테이블 atomic + 헤더 row 반복 / heading prefix budget 초과 시 위쪽 헤딩 drop |
| `MarkdownAwareChunkerTest` | golden fixture 6~8개 (입력 `.md` + 기대 chunks `.json`). invariant: 각 chunk가 valid markdown / 총 토큰 budget 준수 / 같은 section chunk들이 동일 `headingPath` |
| `JdkBreakIteratorSentenceSplitterTest` | 한국어 (다/네/요 종결 + 마침표 + 약어 "."이 종결 아님) / 영어 / 혼합 / 단일 거대 문장 |

### Integration (rag-ingestion-app + -infra, Testcontainers)

- `IngestionServiceIntegrationTest` — 기존 케이스에 `heading_path` 컬럼 단언 추가.
- `ReembedCommandLineRunnerIntegrationTest` — `reembed` 프로필로 띄움 → stale row seed
  → CLI 실행 → row가 새 boundary로 교체 + `heading_path` 채워짐 + `rag.document.ingested`
  이벤트 N건 emit.
- 기존 `visibility-changed` / `deleted` integration 테스트는 그대로.

### Migration

- Flyway 마이그레이션 단독 테스트: stale row가 있는 상태에서 ALTER 실행 후
  `heading_path = '{}'`로 채워졌는지 단언.

### Fixture 구성

```
backend/rag-ingestion/rag-ingestion-domain/src/test/resources/chunker-fixtures/
├── 01-empty.md / .expected.json
├── 02-no-headings.md / .expected.json
├── 03-flat-h1.md / .expected.json
├── 04-nested-headings.md / .expected.json
├── 05-oversize-fence.md / .expected.json
├── 06-oversize-paragraph-ko.md / .expected.json
├── 07-gfm-table.md / .expected.json
└── 08-heading-prefix-overflow.md / .expected.json
```

`.expected.json`은 chunk별 `{headingPath, tokenCount, textHash, validMarkdown: true}`.
텍스트 전체 대신 SHA-256 해시로 fixture 크기 절약.

## 에러 처리

| 실패 | 동작 |
|---|---|
| commonmark-java parse exception | catch → ERROR 로그 → fallback: 전체 본문을 단일 `headingPath=[]` section으로 처리해 토큰 윈도우 분할 (= 기존 알고리즘 동작). 인제스트 자체는 절대 실패시키지 않음 |
| 단일 문장 > `sizeTokens` | 그 문장만 토큰 윈도우 fallback. WARN 로그 + 메트릭 카운터 |
| Oversize fence (> `maxOversizeFenceTokens`) | 라인 단위 split. 메트릭 카운터 (성공 경로) |
| Body > 1 MB | body-fetch에서 이미 거름 (ADR-12 §4, 변경 없음) |
| 임베딩 5xx after retries | ADR-13 §2 DLQ 경로 그대로 |
| Re-embed 잡 — docs-api 404 (잡 도중 문서 삭제) | INFO 로그 + `skipped` 카운트 + continue |
| Re-embed 잡 — embedding 영구 실패 | ERROR 로그 + `failed` 카운트 + continue. 잡 전체 abort 안 함 |
| Re-embed 잡 — exit code | failed > 0 ⇒ exit 2, 아니면 0 |

## 관측 (Micrometer 메트릭, ADR-13 §6 set에 추가)

| 메트릭 | 타입 | 레이블 |
|---|---|---|
| `playground.rag_ingestion.chunker.duration` | Timer | `outcome=success\|parse_fallback` |
| `playground.rag_ingestion.chunker.oversize_fence_split` | Counter | — |
| `playground.rag_ingestion.chunker.oversize_sentence_fallback` | Counter | — |
| `playground.rag_ingestion.chunker.parse_fallback` | Counter | — |
| `playground.rag_ingestion.reembed.documents` | Counter | `outcome=success\|failed\|skipped` |
| `playground.rag_ingestion.reembed.duration` | Timer | — |

기존 `playground.rag_ingestion.chunk.count` DistributionSummary는 그대로 —
boundary 변경으로 분포가 달라지는 게 M5 대시보드에 자연스럽게 보임.

로깅: chunker는 per-document 로그 안 뱉음(IngestionService의 기존 INFO에
piggy-back). `ReembedCommandLineRunner`는 per-document INFO + 종료 시 summary
`{processed, success, failed, skipped, durationMs}`.

## 문서 업데이트 (이 PR에 포함)

- **`docs/adr/13-m3-rag-ingestion.md`**
  - §1: token-window를 fallback 경로로 보존, markdown-aware가 default임을
    명시. 신규 두 ChunkingPolicy 필드 표 추가.
  - §6: 신규 메트릭 4개를 표에 추가.
  - §7: backfill과 같은 패턴으로 `reembed` profile 항목 추가.
- **`docs/prd/M3-rag-ingestion.md`**
  - M3.1 bucket의 "Re-embedding job (chunking 파라미터 또는 모델 변경 대응)"
    항목을 이 작업으로 체크.
  - P2의 "Adaptive chunk size (semantic chunking, layout-aware)" 라인 제거
    (이 작업이 cover).
  - §"Acceptance criteria → Schema"에 `heading_path text[]` invariant 추가.
- **`docs/prd/M4-rag-chat.md`**: 한 줄 — citation 렌더링이 `heading_path`
  컬럼을 활용 가능하다 (소비는 별도 frontend 작업).
- **`docs/roadmap.md`** §M3: chunk schema bullet에 `heading_path` 반영.

## 코드 변경 범위 / cross-BC ripple

| BC | 코드 변경 | Runtime ripple |
|---|---|---|
| rag-ingestion | 위 §"모듈 배치" 표 전부 | — |
| docs | 0 라인 | — |
| rag-chat (M4) | 0 라인 | `rag.document.ingested` 이벤트가 reembed 잡 동안 burst 수신. 캐시(있다면) 자연스럽게 invalidate되는 의도된 부수효과. `SELECT *` 안 쓰면 컬럼 추가에 무영향 |
| frontend | 0 라인 | citation breadcrumb 렌더링은 별도 M4 frontend 작업 |
| gateway | 0 라인 | — |
| infra/compose | 0 라인 | — |

## Out of scope (이 작업에서 명시적으로 제외)

| 항목 | 이유 |
|---|---|
| M4 citation UI에서 `heading_path` breadcrumb 렌더링 | 별도 frontend 작업 (M4 follow-up) |
| Path metadata (`docs.document.moved` 컨슈머 + 컬럼) | M3.1의 별도 항목 (M2.1 의존) |
| 다른 임베딩 모델로 swap | 차원 변경은 다른 마이그레이션 |
| Frontmatter 메타데이터 추출 (author / tags) | M2.1 follow-up |
| Chunker A/B 비교 자동화 / online eval | M3.2 또는 P2 |
| 멀티모달 임베딩 (이미지/PDF) | P2 그대로 |
| 청크 deduplication / fingerprinting | M3 PRD §"Out of scope" 그대로 |

## 리스크 & 완화

| 리스크 | 완화 |
|---|---|
| commonmark가 일부 GFM 확장(예: ::: admonition)을 plain text로 처리 → 임베딩 품질 미세 저하 | M2 corpus 사용 패턴 기준 영향 작음. 발견 시 commonmark-ext 패키지 점진 추가 |
| 한국어 `BreakIterator` 정확도 | oversize paragraph 케이스가 드물어 영향 제한. `SentenceSplitter` interface 두었으니 ICU4J swap path 열려 있음 |
| 재임베딩 시간 (gateway 1-instance 병목) | M2 corpus 규모(<100 docs) 시 단일 실행 ~몇 분. throttle 설정 + 운영자 수동 발화로 통제 |
| `heading_path` 컬럼이 추후 검색 키로 필요해질 가능성 | 현 단계에 GIN 인덱스 미생성 (filter 용도 아님). 필요해지면 별도 마이그레이션 — 손쉬움 |

## Dependencies

`backend/rag-ingestion/rag-ingestion-domain/build.gradle.kts`:

```kotlin
implementation("org.commonmark:commonmark:0.22.0")
implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
```

(JTokkit, JDK `BreakIterator`는 기존 / JDK 표준이므로 추가 없음.)

## 성공 기준 (이 작업이 완료됐다고 말할 수 있는 조건)

1. 신규 `uploaded` 이벤트로 들어온 문서가 `MarkdownAwareChunker`로 분할되고
   `heading_path`가 정확히 채워진다 (integration test로 단언).
2. `reembed` CLI 한 번 실행 후 `rag.document_chunks`의 전 row가 새 boundary +
   `heading_path` 컬럼을 갖는다. 잡 summary가 `failed=0`로 마침.
3. 기존 acceptance criteria(특히 idempotency, visibility re-tag, deletion
   purge) 회귀 없음 — 기존 integration test 전부 그대로 통과.
4. commonmark parse 실패가 인제스트 자체를 실패시키지 않음 (fallback 경로
   단위 테스트로 단언).
5. ADR-13 / M3 PRD / M4 PRD / roadmap이 이 작업을 반영하도록 amend되어 있다.
