# Agentic Search Tool (SP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검색을 docs-api의 `search_documents` 도구로 추출하고 chat의 파이프라인 검색을 제거 — LLM이 필요할 때 검색하며, 글로벌 [N] 인용 체계와 인용 영속/표시는 보존(스냅샷 영속으로 전환).

**Architecture:** docs-api가 자기 스키마를 자기가 검색(임베딩 단건 경로 + pgvector SQL 이전), NDJSON 터미널-only 도구 응답. chat은 검색 도구 결과를 턴-누적 카운터로 글로벌 재번호해 기존 CitationExtractor→renumber→persist 경로에 공급하고, 인용 스냅샷(title/excerpt/visibility)을 자기 테이블에 영속해 cross-schema resolver를 제거한다.

**Tech Stack:** Spring MVC(docs-api) + WebFlux(chat), pgvector HNSW, Flyway, 기존 NDJSON 도구 디스패처, React.

**Spec:** `docs/superpowers/specs/2026-06-07-agentic-search-tool-design.md`

**Worktree:** `git worktree add .claude/worktrees/agentic-search -b worktree-agentic-search main` + `cp infra/.env .claude/worktrees/agentic-search/infra/.env` 후 진입. Java: `backend/springboot/`에서 gradlew. FE: `pnpm install --frozen-lockfile` 1회.

**구현자 공통 지침**: 각 태스크의 코드 블록은 목표 형태다 — 명시된 현재 파일을 먼저 전부 읽고 실제 구조/헬퍼에 맞춰 적응하되, wire shape·시그니처·동작 계약은 그대로. 적응한 부분은 보고서에 명시.

---

## File Structure

| 영역 | File | Action | Responsibility |
|------|------|--------|----------------|
| docs | `docs-infra/.../ingestion/external/SparkInferenceEmbeddingAdapter.java` | Modify | `embedQuery(String) -> float[]` 단건 경로 |
| docs | `docs-app/.../search/SearchDocumentsService.java` | Create | 검색 유스케이스 (visibility/topK/documentId) |
| docs | `docs-app/.../search/QueryEmbeddingPort.java` + `ChunkSearchPort.java` | Create | 포트 |
| docs | `docs-infra/.../search/PgvectorChunkSearchAdapter.java` | Create | chat의 검색 SQL 이전 + title 조인 + documentId 필터 |
| docs | `docs-api/.../controller/SearchToolController.java` | Create | `POST /internal/tools/search-documents` NDJSON 터미널-only |
| chat | `chat-domain/.../tool/SearchTool.java` | Create | ToolDescriptor (문서 검색) |
| chat | `chat-domain/.../tool/ToolCatalog.java` | Modify | SearchTool 등록 |
| chat | `chat-app/.../service/TurnCitationAccumulator.java` | Create | 글로벌 재번호 + RetrievedChunk 누적 |
| chat | `chat-app/.../service/ChatTurnService.java` | Modify | retrieval 단계 삭제 + search 결과 누적/재작성 + phase 발신 삭제 |
| chat | `chat-domain/.../service/PromptTemplate.java` | Modify | RETRIEVED CONTEXT 제거 + SYSTEM_PROMPT 교체 |
| chat | `chat-app/.../properties/ChatProperties.java` | Modify | retrievalK/retrievalBlockTokenBudget/perChunkTokenBudget 삭제 |
| chat | 삭제: `EmbeddingPort`, `ChunkRetrievalPort`, `SparkInferenceEmbeddingAdapter`(chat), `PgvectorChunkRetrievalAdapter`, `CrossSchemaCitationResolverAdapter` | Delete | doc-도메인 침범 제거 |
| chat | Flyway `V202606070001__citation_snapshot_columns.sql` | Create | title/excerpt/visibility 컬럼 |
| chat | `MessageCitation` 도메인 + JPA 엔티티 + 영속/조회 | Modify | 스냅샷 저장·읽기 |
| chat | `SessionService.java` | Modify | CitationResolver 제거, 자기 테이블 읽기 |
| FE | `features/chat-tool-card/GenericResultCard.tsx` | Create | 비등록 도구 결과 미니 카드 |
| FE | `ToolCardList.tsx` | Modify | 비등록 result/error 제네릭 fallback |
| docs(문서) | spec/design-doc 노트 | Modify | Task 5 |

---

### Task 1: docs-api — 검색 유스케이스 + NDJSON 도구 엔드포인트 (TDD)

선독: `docs/docs-infra/.../ingestion/external/SparkInferenceEmbeddingAdapter.java`, **chat의** `chat-infra/.../persistence/PgvectorChunkRetrievalAdapter.java`(SQL 이전 원본 — 이 태스크에선 삭제하지 않음), `docs-api/.../controller/InternalDocumentController.java`(internal 컨벤션), docs-api의 기존 테스트 스타일.

- [ ] **Step 1: 실패 테스트** — `docs-app` 단위 테스트 `SearchDocumentsServiceTest`(포트 fake):

```java
class SearchDocumentsServiceTest {
    // Fake ports: QueryEmbeddingPort returns fixed vector; ChunkSearchPort
    // records (userId, embedding, k, documentId) and returns scripted rows.

    @Test
    void clampsTopKInto1to20() { /* topK 0 → 1, 50 → 20 — fake에 기록된 k 단언 */ }

    @Test
    void passesDocumentIdFilterThrough() { /* documentId 지정 시 fake에 전달 단언 */ }

    @Test
    void mapsRowsToResultsWithPositions1toK() {
        // fake가 3행 반환 → results position 1,2,3 + title/excerpt/visibility 매핑
    }

    @Test
    void emptyResultsIsNormal() { /* 0행 → results=[], totalFound=0 */ }
}
```

`docs-api` 컨트롤러 테스트 `SearchToolControllerTest`(MockMvc):

```java
    @Test
    void requiresUserIdHeader() { /* X-User-Id 없음 → 400 */ }

    @Test
    void returnsNdjsonTerminalResult() {
        // POST {"query":"면적","topK":3} + X-User-Id →
        // content-type application/x-ndjson, body 1줄 {"event":"result","result":{...,"summary":"면적 — N건"}}
    }

    @Test
    void embeddingFailureMapsToErrorEvent() {
        // QueryEmbeddingPort throw → {"event":"error","code":"SEARCH_EMBEDDING_FAILED","status":502}
        // HTTP는 200 (스트림 계약 — 터미널 error 이벤트)
    }
```

- [ ] **Step 2: 실패 확인** — 해당 모듈 테스트 실행, 컴파일 실패 확인.

- [ ] **Step 3: 구현**

`docs-app` 포트/서비스 (`com.playground.docs.search` 패키지 — app/infra/api 각 모듈에 신설; 기존 docs 패키지 관습 — `com.playground.docs.ingestion.application.*` — 을 읽고 동형으로):

```java
public interface QueryEmbeddingPort {
    float[] embedQuery(String query);
}

public interface ChunkSearchPort {
    record Row(UUID documentId, int chunkIndex, String title, String text,
               String visibility) {}
    List<Row> search(UUID callerId, float[] embedding, int k, UUID documentIdOrNull);
}

@Service
public class SearchDocumentsService {
    // excerpt는 chat의 구 per-chunk 예산과 동일 의도 — 문자 기준 단순화: 600자 head-truncate.
    private static final int EXCERPT_CHARS = 600;
    private static final int MIN_K = 1, MAX_K = 20, DEFAULT_K = 6;

    public record Result(int position, UUID documentId, int chunkIndex,
                         String title, String excerpt, String visibility) {}
    public record SearchOutcome(List<Result> results, int totalFound, String summary) {}

    public SearchOutcome search(UUID callerId, String query, Integer topK, UUID documentId) {
        int k = Math.max(MIN_K, Math.min(MAX_K, topK == null ? DEFAULT_K : topK));
        float[] embedding = queryEmbeddingPort.embedQuery(query);
        List<ChunkSearchPort.Row> rows = chunkSearchPort.search(callerId, embedding, k, documentId);
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            String excerpt = r.text().length() > EXCERPT_CHARS
                    ? r.text().substring(0, EXCERPT_CHARS) : r.text();
            results.add(new Result(i + 1, r.documentId(), r.chunkIndex(),
                    r.title(), excerpt, r.visibility()));
        }
        return new SearchOutcome(results, results.size(),
                query + " — " + results.size() + "건");
    }
}
```

`docs-infra` 어댑터: `SparkInferenceEmbeddingAdapter`에 추가 —

```java
    /** 검색 쿼리 단건 임베딩 (search_documents 도구 — agentic-search spec D1). */
    public float[] embedQuery(String query) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(query));
        return response.getResults().get(0).getOutput();
    }
```

(반환 타입은 Spring AI 버전에 따라 float[]/List<Double> — 기존 embed()의 변환 방식을 그대로 따라 어댑터에서 float[]로. `QueryEmbeddingPort` 구현체로 등록 — 기존 클래스가 EmbeddingPort 구현이면 인터페이스 둘 다 구현.)

`PgvectorChunkSearchAdapter` — chat의 SQL을 이전 + title 조인 + 필터:

```java
    private static final String SQL = """
            SELECT c.document_id, c.chunk_index, c.text, c.visibility,
                   d.title
            FROM docs.document_chunks c
            JOIN docs.documents d ON d.id = c.document_id
            WHERE (c.visibility = 'public' OR (c.user_id = ? AND c.visibility = 'private'))
              AND (?::uuid IS NULL OR c.document_id = ?::uuid)
            ORDER BY c.embedding <=> ?::public.vector
            LIMIT ?
            """;
```

(ef_search 세팅 등 chat 어댑터의 HNSW 튜닝 코드를 그대로 이전. 벡터 직렬화 헬퍼도 동일 이전.)

`docs-api` 컨트롤러:

```java
@RestController
@RequestMapping("/internal/tools")
public class SearchToolController {

    public record SearchRequest(String query, Integer topK, UUID documentId) {}

    @PostMapping(value = "/search-documents", produces = "application/x-ndjson")
    public ResponseEntity<String> search(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SearchRequest req) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (req.query() == null || req.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var outcome = service.search(UUID.fromString(userId), req.query(),
                    req.topK(), req.documentId());
            // 검색은 ~수백ms — 터미널-only 한 줄이면 도구 스트림 계약 충족
            // (progress/heartbeat 불필요, 디스패처 idle 30s ≫ 검색 시간).
            return ResponseEntity.ok(ndjsonLine(Map.of(
                    "event", "result", "result", outcome)));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ndjsonLine(Map.of(
                    "event", "error", "code", "SEARCH_EMBEDDING_FAILED",
                    "message", String.valueOf(e.getMessage()), "status", 502)));
        }
    }
    // ndjsonLine: ObjectMapper.writeValueAsString + "\n"
}
```

- [ ] **Step 4: 통과 + docs 모듈 빌드** — `./gradlew :docs:docs-app:test :docs:docs-api:test :docs:docs-infra:test :docs:docs-api:build`(모듈 좌표는 settings.gradle 확인) → green.

- [ ] **Step 5: Commit** — `feat(docs): search_documents internal tool — query embedding + pgvector search`

---

### Task 2: chat — SearchTool 등록 + 인용 누적기 + retrieval 제거 (TDD)

선독 필수: `ChatTurnService.java` 전체, `PromptTemplate.java`, `ToolCatalog.java`, `ChatProperties.java`, `RetrievedChunk.java`, 기존 ChatTurnService/PromptTemplate 테스트들.

- [ ] **Step 1: SearchTool + 카탈로그**

```java
public final class SearchTool {
    private static final String INPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","required":["query"],"additionalProperties":false,
             "properties":{
               "query":{"type":"string","minLength":1,
                 "description":"검색할 내용 — 사용자 질문의 핵심을 한국어 그대로"},
               "topK":{"type":"integer","minimum":1,"maximum":20,
                 "description":"결과 수 (기본 6)"},
               "documentId":{"type":"string","format":"uuid",
                 "description":"특정 문서로 한정할 때 [YOUR DOCUMENTS]의 id"}}}
            """;
    private static final String DESCRIPTION = """
            Search the user's uploaded documents by meaning (pgvector). \
            STRICT TRIGGER: call when the user's question concerns the CONTENT \
            of uploaded documents (면적, 요구사항, 일정, "문서에 뭐라고 적혀있어" 류). \
            DO NOT call for casual conversation, general knowledge, or to restate \
            results already retrieved in THIS turn. When the user names a specific \
            document, set documentId from the [YOUR DOCUMENTS] list. \
            Results carry [N] positions — cite facts from them as [N] in your answer. \
            Never assert document contents you have not retrieved this turn.""";
    public static final ToolDescriptor SEARCH = new ToolDescriptor(
            "search_documents", "문서 검색", DESCRIPTION, INPUT_SCHEMA,
            resolveEndpoint(), Duration.ofSeconds(30), Duration.ofSeconds(60));
    // resolveEndpoint: env PLAYGROUND_SEARCH_TOOL_URL 오버라이드, 기본
    // http://docs-api:18082/internal/tools/search-documents (MassingTool 패턴)
}
```

`ToolCatalog.DESCRIPTORS`에 `SearchTool.SEARCH` 추가.

- [ ] **Step 2: 실패 테스트 — TurnCitationAccumulator**

```java
class TurnCitationAccumulatorTest {
    @Test
    void renumbersAcrossSearches() {
        var acc = new TurnCitationAccumulator();
        JsonNode first = searchResultBody(3);   // positions 1..3 — 헬퍼: results 배열 JSON
        JsonNode rewritten1 = acc.absorb(first);
        assertThat(positions(rewritten1)).containsExactly(1, 2, 3);
        JsonNode second = searchResultBody(2);
        JsonNode rewritten2 = acc.absorb(second);
        assertThat(positions(rewritten2)).containsExactly(4, 5);  // 글로벌 재번호
        assertThat(acc.retrieved()).hasSize(5);
        assertThat(acc.retrieved().get(3).position()).isEqualTo(4);
    }

    @Test
    void mapsFieldsIntoRetrievedChunk() {
        // documentId/chunkIndex/title/excerpt(→text)/visibility 매핑 검증.
        // RetrievedChunk.chunkOwner는 누적기 경로에선 호출자 자신으로 못 정하므로
        // — RetrievedChunk의 검증 규칙을 읽고: owner가 필수면 caller UUID를
        // absorb(caller, body)로 받아 채운다 (구현 시 확인·적응, 보고).
    }

    @Test
    void emptyResultsAbsorbsNothing() { /* results=[] → retrieved() 불변, body 그대로 */ }
}
```

- [ ] **Step 3: 구현 — TurnCitationAccumulator**

```java
/** 턴 단위 검색 인용 누적기 (agentic-search spec D2).
 *  search_documents 결과의 per-call position(1..k)을 글로벌 위치로 재번호해
 *  LLM-visible body를 재작성하고, RetrievedChunk로 누적한다. 턴 종료 시
 *  retrieved()가 기존 renumber/persist 경로의 입력이 된다. */
public class TurnCitationAccumulator {
    private final List<RetrievedChunk> retrieved = new ArrayList<>();

    /** result body(JsonNode)를 재번호한 사본으로 반환 + 청크 누적. */
    public JsonNode absorb(UserId caller, JsonNode resultBody) {
        JsonNode results = resultBody.path("results");
        if (!results.isArray() || results.isEmpty()) return resultBody;
        ObjectNode copy = resultBody.deepCopy();
        ArrayNode arr = (ArrayNode) copy.get("results");
        for (JsonNode item : arr) {
            int global = retrieved.size() + 1;
            ((ObjectNode) item).put("position", global);
            retrieved.add(new RetrievedChunk(
                    global,
                    UUID.fromString(item.path("documentId").asText()),
                    item.path("chunkIndex").asInt(),
                    item.path("excerpt").asText(""),
                    item.path("title").asText(null),
                    /* chunkOwner — RetrievedChunk 생성 규칙에 맞춰 적응 */ caller,
                    item.path("visibility").asText("private")));
        }
        return copy;
    }

    public List<RetrievedChunk> retrieved() { return List.copyOf(retrieved); }
}
```

(RetrievedChunk 생성자 시그니처/검증은 실제 record를 읽고 정합 — 필드 순서가 다르면 맞춘다.)

- [ ] **Step 4: ChatTurnService 통합 + retrieval 제거**

읽은 현재 코드 기준으로:

1. **삭제**: `embeddingPort`/`chunkRetrievalPort` 필드·생성자 파라미터, retrieval 블록(≈226-240: embedQuery/retrieve/EMBEDDING_FAILED·RETRIEVAL_FAILED), retrieval `phase` 이벤트 발신, `properties.retrievalK()`/`perChunkTokenBudget()` 사용처.
2. **prompt 호출**: `promptTemplate.assemble(truncatedHistory, request.message(), promptDocuments)` — retrieved/예산 파라미터 제거된 새 시그니처 (Task 4에서 PromptTemplate 변경과 함께 — 같은 커밋).
3. **누적기**: 턴 시작 시 `TurnCitationAccumulator acc = new TurnCitationAccumulator();` — `handleToolInvocation`의 Success 분기에서:

```java
        Object llmVisibleBody = s.body();
        if ("search_documents".equals(desc.name()) && s.body() instanceof JsonNode bodyNode) {
            // 글로벌 재번호 + 누적 — SSE/LLM 양쪽 모두 재번호된 body를 본다.
            llmVisibleBody = acc.absorb(callerUserId, bodyNode);
        }
```

   이후 SSE 발신(enrichResultForSse)·truncateForLlm 모두 `llmVisibleBody` 사용. (callerUserId는 메서드 컨텍스트의 사용자 — 실제 변수명 적응.)
4. **턴 종료**: `renumberCitations`/`persistAssistantAndDone`에 공급되던 `retrieved`를 `acc.retrieved()`로 교체. 검색 0회면 빈 리스트 → citations 빈 배열 (기존 경로가 처리).
5. massing 등 다른 도구: absorb 미적용 (name 분기) — 누적 제외.

- [ ] **Step 5: PromptTemplate + SYSTEM_PROMPT + ChatProperties**

- `assemble`에서 `retrieved`/`perChunkTokenBudget` 파라미터와 `[RETRIEVED CONTEXT]` 블록 제거 (오버로드 정리 — 남는 시그니처: `assemble(List<Message> history, String currentUserMessage, List<UserDocumentRef> documents)`).
- `SYSTEM_PROMPT` 교체 (기존 영어 관습 유지, tool-honesty 단락은 유지):

```java
    public static final String SYSTEM_PROMPT =
            "You are a helpful assistant for the user's playground workspace.\n"
                    + "Claims about the user's documents must cite [N] markers that\n"
                    + "refer ONLY to search_documents results retrieved in THIS turn.\n"
                    + "Never assert document contents you have not retrieved this\n"
                    + "turn — call search_documents first. Casual conversation and\n"
                    + "general knowledge need no search and no citations.\n"
                    + "When tools are available: a request to create, generate, or\n"
                    + "REGENERATE an artifact is only satisfied by invoking the matching\n"
                    + "tool in THIS turn. Never state that a file or model was created,\n"
                    + "regenerated, or delivered unless the tool ran in this turn — an\n"
                    + "earlier tool result in the conversation does not satisfy a new\n"
                    + "request. If you did not invoke the tool, say so instead.";
```

- `ChatProperties`: `retrievalK`/`retrievalBlockTokenBudget`/`perChunkTokenBudget` 필드·생성자·접근자 삭제 + application.yml의 대응 키 삭제 + 관련 테스트 갱신.
- chat 모듈에서 파일 삭제: `EmbeddingPort`, `ChunkRetrievalPort`, `SparkInferenceEmbeddingAdapter`(chat-infra), `PgvectorChunkRetrievalAdapter`(chat-infra — SQL은 Task 1이 이미 docs로 이전). 관련 에러코드(`EMBEDDING_FAILED`/`RETRIEVAL_FAILED`)는 enum에서 사용처가 사라지면 함께 삭제하되 wire 영향(FE가 코드 문자열 참조?) grep 후 결정·보고.

- [ ] **Step 6: 테스트 정비 + 게이트** — 기존 ChatTurnService/PromptTemplate 테스트에서 retrieval 픽스처 제거·적응, 누적기 신규 테스트, `./gradlew :chat:chat-api:test :chat:chat-app:test :chat:chat-domain:test :chat:chat-infra:test` green. 잔존 grep: `grep -rn "ChunkRetrieval\|RETRIEVED CONTEXT\|embedQuery\|retrievalK" backend/springboot/chat --include="*.java" --include="*.yml"` → 0.

- [ ] **Step 7: Commit** — `feat(chat)!: agentic search — SearchTool + citation accumulator, pipeline retrieval removed`

---

### Task 3: chat — 인용 스냅샷 영속 + resolver 삭제 (TDD)

선독: `MessageCitation` 도메인 타입, `MessageCitationJpaEntity`, `MessageRepositoryJdbcAdapter`(saveCitations/조회), `SessionService.java`, `CrossSchemaCitationResolverAdapter`, chat의 Flyway 디렉토리.

- [ ] **Step 1: Flyway** — `V202606070001__citation_snapshot_columns.sql`:

```sql
-- agentic-search spec D2: 인용 스냅샷 영속 — 히스토리 리로드가 docs 스키마를
-- 읽지 않도록 영속 시점 값을 동결한다. 데이터 리셋(2026-06-07) 직후라 레거시
-- 행 없음.
ALTER TABLE chat.message_citations
    ADD COLUMN title      TEXT NULL,
    ADD COLUMN excerpt    TEXT NULL,
    ADD COLUMN visibility TEXT NULL;
```

- [ ] **Step 2: 실패 테스트** — 기존 citation 영속/조회 테스트 스타일을 따라: saveCitations가 title/excerpt/visibility를 저장하고, `SessionService.loadDetail`이 resolver 없이 스냅샷 값으로 `CitationView`를 채우는지. (기존 SessionService 테스트의 resolver fake를 제거하는 형태로 재작성.)

- [ ] **Step 3: 구현**

- `MessageCitation` 도메인에 `title/excerpt/visibility` 필드 추가 (nullable).
- 영속: `persistAssistantAndDone`의 `MessageCitation` 생성 시 누적기 `RetrievedChunk`의 `title()`/`text()`(excerpt)/`visibility()` 동봉; JDBC INSERT/SELECT 컬럼 추가.
- `SessionService.loadDetail`: `CitationResolver` 파라미터/루프 제거 — `CitationView(position, documentId, chunkIndex, title, excerpt, deleted=false)`를 스냅샷에서 직접. `CitationResolver` 인터페이스 + `CrossSchemaCitationResolverAdapter` 삭제. (FE `isStaleCitation`(title null)은 스냅샷 행에선 발동 안 함 — FE 무변경.)

- [ ] **Step 4: 게이트** — chat 4모듈 테스트 green + `grep -rn "CitationResolver\|docs\.documents\|docs\.document_chunks" backend/springboot/chat --include="*.java"` → manifest 어댑터(`CrossSchemaUserDocumentManifestAdapter` — spec상 유지)만 잔존.

- [ ] **Step 5: Commit** — `feat(chat): citation snapshot persistence — cross-schema resolver removed`

---

### Task 4: FE — 제네릭 결과/에러 카드

선독: `ToolCardList.tsx`, `ToolResultCard.tsx`, `ToolRunCard.tsx`, `MassingErrorCard.tsx`, `entities/chat` 타입.

- [ ] **Step 1: GenericResultCard 생성**

```tsx
'use client';

import { Cog } from 'lucide-react';
import type { ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * 비등록 도구의 제네릭 결과 카드 (agentic-search spec D3) — 결과 카드가
 * 따로 없는 도구(search_documents 등)의 완료 상태. 도구가 result body에
 * `summary`를 주면 그것을, 없으면 "완료"를 표시. in-flight의 제네릭
 * ToolRunCard와 대칭 — 새 도구는 FE 0줄로 시작~완료가 보인다.
 */
export function GenericResultCard({
  state,
}: {
  state: Extract<ToolCardState, { kind: 'result' }>;
}) {
  const name = state.displayName ?? state.toolCall.name;
  return (
    <ToolResultCard
      ariaLabel={`Tool result: ${name}`}
      icon={<Cog size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={<span className="text-[14px] font-semibold text-text">{name}</span>}
      summary={
        <span className="font-medium text-text">
          {state.toolResult.summary ?? '완료'}
        </span>
      }
      primaryAction={null}
      footer={null}
    />
  );
}
```

(`displayName`이 result 상태에 없으면 — 타입 확인: in_flight에만 있으면 result 변형에도 optional 추가 + useChatStream의 result 승격 시 displayName 보존. 적응·보고.)

- [ ] **Step 2: ToolCardList 재배선** — 비등록 도구의 `result` → GenericResultCard, `error` → 기존 에러 카드가 massing 전용이면 그대로 두되 비등록 error는 GenericResultCard 변형 또는 message 표시 — MassingErrorCard를 읽고 제네릭화가 1줄 수준이면 재사용, 아니면 미니 에러 렌더 추가 (적응·보고). massing 분기는 불변.

- [ ] **Step 3: SSE summary 경로 확인** — search 도구 result body의 `summary`가 `ToolResultPayload.summary`로 도달하는지 (chat.sse.ts가 body.summary를 읽는 기존 경로 — massing과 동일하므로 무변경 예상; 확인만).

- [ ] **Step 4: 게이트** — `pnpm typecheck && pnpm lint && pnpm build` clean.

- [ ] **Step 5: Commit** — `feat(frontend): generic result card for unregistered tools`

---

### Task 5: 통합 검증 + docs

- [ ] **Step 1: 재빌드** (worktree 루트, `--env-file infra/.env`):

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build docs-api chat-api frontend
```

3종 healthy 대기.

- [ ] **Step 2: 검색 도구 직접 검증** — 시딩: docs-api에 직접 텍스트 문서 업로드(컨테이너 내부에서 X-User-Id 헤더로 multipart POST — 사용자 690df887-... 재사용; 데이터 리셋 후라 사용자 행이 없으면 identity 시딩이 필요할 수 있음 → 그 경우 보고하고 사용자 로그인 후 docId/userId를 받아 진행). 인제스천 완료(chunks 생성) 대기 후:

```bash
docker exec docs쪽컨테이너 ... POST /internal/tools/search-documents {"query":"..."} → result 줄 + results 검증
```

(컨테이너명/HTTP 도구는 환경에 맞게 — agent-tools의 httpx를 빌려도 됨: `docker exec agent-tools python -c "...docs-api:18082..."`.)

- [ ] **Step 3: spec/design-doc** — spec에 구현 중 deviation 기록(있다면); `docs/design/M4-rag-chat.md` 개명 노트 아래에:

```markdown
> **2026-06-07 — Agentic Search (M9 SP2)**: 파이프라인 검색이
> `search_documents` 도구(docs-api 소유)로 대체됐다. LLM이 필요할 때
> 검색하고, 결과는 턴-누적 글로벌 [N]으로 인용·스냅샷 영속된다. 본문의
> RETRIEVED CONTEXT/retrieval 단계 서술은 작성 시점 기록. Spec:
> `docs/superpowers/specs/2026-06-07-agentic-search-tool-design.md`.
```

- [ ] **Step 4: Commit + 수동 E2E 안내** — 사용자: 로그인 → 브리프 업로드 → "이 문서에서 연면적이 얼마야?" (검색 카드 + 인용) / "안녕" (검색 0회) / 매싱 생성 (멀티 도구 턴).

---

## Self-Review

1. **Spec coverage:** D1(엔드포인트·임베딩 단건·SQL 이전·등록·excerpt·summary)→T1·T2(SearchTool); D2(삭제 목록·누적기·재번호·LLM body 재작성·0회 턴·비검색 도구 제외·스냅샷 영속·SYSTEM_PROMPT)→T2·T3; D3(제네릭 결과 카드·비대칭 해소)→T4; D4 에러 표→T1(error 이벤트)·기존 renumber(허위 [N] 무시); D5 테스트 절→각 태스크 매핑; Out of Scope 침범 없음 (manifest 어댑터 유지 — T3 게이트 grep이 명시).
2. **Placeholder scan:** "적응·보고" 지점들은 선독 지시+목표 코드 동반 — 의도된 적응. 그 외 없음.
3. **Type consistency:** `SearchOutcome(results,totalFound,summary)` T1 ↔ wire result ↔ T4 summary 소비; `TurnCitationAccumulator.absorb(caller, body) -> JsonNode` 테스트·구현·T2 Step4 호출 일치; `RetrievedChunk(position,documentId,chunkIndex,text,title,chunkOwner,visibility)` 실제 record에 적응 명시; ToolDescriptor 7-인자(displayName·idle·total) SearchTool 일치.
