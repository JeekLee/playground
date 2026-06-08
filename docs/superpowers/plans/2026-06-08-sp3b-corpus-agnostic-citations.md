# SP3b — Corpus-무관 Citation 모델 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** chat의 citation 모델을 document/chunk 전용에서 corpus-무관 `SourceRef{sourceType,title,content,uri}`로 일반화 — 도구가 generic citation을 방출하고 chat은 해석 없이 복사·영속·전송하며, FE는 `uri`로 균일 렌더한다.

**Architecture:** shared-kernel에 `SourceRef` 원자(ChatStreamEvent 옆)를 두고 docs-api 검색 도구가 이를 방출(`uri=${ORIGIN}/docs/{id}`). chat-domain `RetrievedChunk`/`MessageCitation`은 SourceRef를 합성, `TurnCitationAccumulator`는 corpus-맹목 복사, 영속/wire/FE는 documentId/chunkIndex/visibility를 폐기하고 source_type/content/uri로 전환. `[N]` 번호 기계는 무변경.

**Tech Stack:** Java 21 + Spring (shared-kernel record, docs MVC, chat WebFlux), Flyway, JDBC, Next.js + TypeScript.

**Spec:** `docs/superpowers/specs/2026-06-08-sp3b-corpus-agnostic-citations-design.md`

**Worktree:** `EnterWorktree({name:"sp3b-citations"})` 후 `cp infra/.env .claude/worktrees/sp3b-citations/infra/.env`. Java: `backend/springboot`에서 `./gradlew`. FE: `pnpm`. 최종 재빌드(Task 5)는 worktree 루트에서 `--env-file infra/.env`.

**원자적 배포 불변식:** Task 2(docs-api wire 변경)와 Task 3(chat 소비 변경)는 **같은 PR/배포**로 나간다. 각 태스크는 **빌드 green**을 유지하지만, docs-api 새 wire를 chat 구 누적기가 읽으면 런타임 불일치가 나므로 단독 배포 금지 (통합 검증은 Task 5).

**구현자 공통 지침:** 코드 블록은 목표 형태다 — 명시된 현재 파일을 먼저 읽고 실제 구조에 맞춰 적응하되, 타입 시그니처·wire 필드명·동작 계약은 그대로. 적응은 보고.

---

## File Structure

| 영역 | File | Action | 책임 |
|------|------|--------|------|
| shared-kernel | `shared/chat/SourceRef.java` | Create | corpus-무관 citation 원자 |
| docs-app | `search/.../SearchDocumentsService.java` | Modify | Result→SourceRef, ORIGIN 주입, uri 조립 |
| docs-api | `resources/application.yml` | Modify | `playground.docs.public-origin` 바인딩 |
| infra | `docker-compose.yml`, `.env.example` | Modify | `PLAYGROUND_PUBLIC_ORIGIN` (docs-api) |
| chat-domain | `model/RetrievedChunk.java`, `model/MessageCitation.java` | Modify | SourceRef 합성 |
| chat-domain | `model/id/DocumentId.java`, `enums/Visibility.java` | Delete? | 고아면 삭제 (grep) |
| chat-app | `service/TurnCitationAccumulator.java` | Modify | corpus-맹목 absorb |
| chat-app | `service/ChatTurnService.java` | Modify | persistAssistantAndDone 재작성 |
| chat-app | `dto/CitationDto.java` | Modify | 평면 `{n,sourceType,title,content,uri}` |
| chat-app | `service/SessionService.java`, `dto/SessionDetailView.java` | Modify | loadDetail→CitationDto, CitationView 삭제 |
| chat-api | `dto/SessionResponses.java` | Modify | CitationDto 통합 |
| chat-infra | `persistence/MessageRepositoryJdbcAdapter.java` | Modify | INSERT/SELECT 컬럼 (실 I/O) |
| chat-infra | `persistence/MessageCitationJpaEntity.java` | Modify | 수동 미러 |
| chat-infra | `db/migration/V202606080001__citations_corpus_agnostic.sql` | Create | 스키마 전환 |
| FE | `shared/api/chat.ts` | Modify | MessageCitationDto, CitationVisibility 삭제 |
| FE | `widgets/citation-accordion/CitationAccordion.tsx` | Modify | href=uri, key=n |
| docs | design-doc 노트 | Modify | Task 5 |

---

### Task 1: shared-kernel — `SourceRef` 원자 (TDD)

선독: `backend/springboot/shared-kernel/src/main/java/com/playground/shared/chat/ChatStreamEvent.java`(이웃 패턴), `shared-kernel/build.gradle.kts`(테스트 deps: junit-jupiter, assertj).

**Files:**
- Create: `shared-kernel/src/main/java/com/playground/shared/chat/SourceRef.java`
- Test: `shared-kernel/src/test/java/com/playground/shared/chat/SourceRefTest.java`

- [ ] **Step 1: 실패 테스트**

```java
package com.playground.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class SourceRefTest {
    @Test
    void holdsAllFields() {
        SourceRef s = new SourceRef("document", "KFI 지침서", "본문 발췌", "https://x/docs/abc");
        assertThat(s.sourceType()).isEqualTo("document");
        assertThat(s.title()).isEqualTo("KFI 지침서");
        assertThat(s.content()).isEqualTo("본문 발췌");
        assertThat(s.uri()).isEqualTo("https://x/docs/abc");
    }

    @Test
    void allowsNullTitleContentUri() {
        SourceRef s = new SourceRef("document", null, null, null);
        assertThat(s.title()).isNull();
        assertThat(s.content()).isNull();
        assertThat(s.uri()).isNull();
    }

    @Test
    void rejectsBlankSourceType() {
        assertThatThrownBy(() -> new SourceRef("  ", "t", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef(null, "t", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 실패 확인** — `cd backend/springboot && ./gradlew :shared-kernel:test` → 컴파일 실패.

- [ ] **Step 3: 구현**

```java
package com.playground.shared.chat;

/**
 * BC-중립 corpus-무관 citation 원자 (SP3b spec D1). 한 소스 참조: 무슨 종류인지
 * ({@code sourceType}), 사람용 라벨({@code title}), 인용된 텍스트({@code content}),
 * 접근 절대 URL({@code uri}). 마커 번호 [N]/position은 포함하지 않는다 — 그것은
 * renumber 기계 + 영속 PK가 소유하는 상태이지 소스의 속성이 아니다.
 *
 * <p>{@code ChatStreamEvent} 옆에 둔다 (BC-중립 wire 계약). 검색 도구(docs-api)가
 * 방출하고 chat 누적기가 해석 없이 복사한다 — 새 corpus(web 등)는 sourceType만
 * 바꿔 같은 형상으로 흘려보낸다.
 */
public record SourceRef(String sourceType, String title, String content, String uri) {
    public SourceRef {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        // title/content/uri는 null·빈 문자열 허용 — 조합 검증 없음.
    }
}
```

- [ ] **Step 4: 통과** — `./gradlew :shared-kernel:test` green.

- [ ] **Step 5: Commit** — `feat(shared-kernel): SourceRef — corpus-agnostic citation atom`

---

### Task 2: docs-api — 검색 도구가 SourceRef 방출 + ORIGIN 설정 (TDD)

선독: `docs-app/.../search/application/service/SearchDocumentsService.java`(전체), `docs-infra/.../storage/MinioClientConfig.java`(@Value 패턴), `docs-api/.../resources/application.yml`(`playground.docs:` 섹션), `docs-api/.../controller/SearchToolController.java`(resultEvent), 기존 `SearchDocumentsServiceTest`/`SearchToolControllerTest`.

**Files:**
- Modify: `docs-app/.../search/application/service/SearchDocumentsService.java`
- Modify: `docs-api/src/main/resources/application.yml`
- Modify: `infra/docker-compose.yml`, `infra/.env.example`
- Test: `SearchDocumentsServiceTest`, `SearchToolControllerTest`

- [ ] **Step 1: 실패 테스트** — `SearchDocumentsServiceTest`에 (fake ports + publicOrigin 주입):

```java
    @Test
    void emitsSourceRefWithAbsoluteDocUriAndNoVisibility() {
        // fake ChunkSearchPort가 documentId=d1, chunkIndex=3, title="A", text="본문", visibility="private" 1행 반환
        var svc = new SearchDocumentsService(fixedEmbedding, fakeSearch, "https://playground.jeeklee.com");
        SearchDocumentsService.SearchOutcome out = svc.search(caller, "q", 6, null);
        assertThat(out.results()).hasSize(1);
        SourceRef s = out.results().get(0);
        assertThat(s.sourceType()).isEqualTo("document");
        assertThat(s.title()).isEqualTo("A");
        assertThat(s.content()).isEqualTo("본문");
        assertThat(s.uri()).isEqualTo("https://playground.jeeklee.com/docs/" + d1);
        // visibility/position/documentId/chunkIndex는 SourceRef에 없음 (컴파일로 보장)
        assertThat(out.totalFound()).isEqualTo(1);
        assertThat(out.summary()).isEqualTo("q — 1건");
    }

    @Test
    void truncatesContentTo600() {
        // text 길이 700 → content 600
        var svc = new SearchDocumentsService(fixedEmbedding, fakeSearch700, "https://o");
        assertThat(svc.search(caller, "q", 6, null).results().get(0).content()).hasSize(600);
    }
```

  `SearchToolControllerTest` (MockMvc): NDJSON result 줄의 `$.result.results[0]`에 `sourceType`/`title`/`content`/`uri` 존재, `documentId`/`chunkIndex`/`visibility` **부재**.

  **기존 테스트 갱신(필수 — 안 하면 컴파일 실패)**: `SearchDocumentsServiceTest`는 현재
  `SearchDocumentsService.Result`를 import·생성하고 `r.position()/documentId()/chunkIndex()/
  excerpt()/visibility()`를 단언한다. `Result` 레코드 삭제로 이 참조가 전부 깨지므로 —
  해당 import 제거 + 단언을 `outcome.results().get(0).{sourceType,title,content,uri}()`로 교체
  + `SearchDocumentsService(embed, search)` 2-인자 생성자 호출을 `(embed, search, publicOrigin)`
  3-인자로 갱신. `SearchToolControllerTest`도 result 줄의 기대 필드를 새 형상으로.

- [ ] **Step 2: 실패 확인** — `./gradlew :docs:docs-app:test :docs:docs-api:test` → 컴파일/단언 실패.

- [ ] **Step 3: 구현 — SearchDocumentsService**

`Result` 레코드 삭제, `SourceRef` 방출. publicOrigin 생성자 주입:

```java
import com.playground.shared.chat.SourceRef;
// ...
@Service
public class SearchDocumentsService {
    static final int EXCERPT_CHARS = 600;
    static final int MIN_K = 1, MAX_K = 20, DEFAULT_K = 6;

    private final QueryEmbeddingPort queryEmbeddingPort;
    private final ChunkSearchPort chunkSearchPort;
    private final String publicOrigin;

    public SearchDocumentsService(
            QueryEmbeddingPort queryEmbeddingPort, ChunkSearchPort chunkSearchPort,
            @org.springframework.beans.factory.annotation.Value("${playground.docs.public-origin}") String publicOrigin) {
        this.queryEmbeddingPort = queryEmbeddingPort;
        this.chunkSearchPort = chunkSearchPort;
        this.publicOrigin = publicOrigin;
    }

    /** 터미널 도구 결과: SourceRef 히트 + 반환 수 + 요약. (SP3b spec D2) */
    public record SearchOutcome(List<SourceRef> results, int totalFound, String summary) {}

    public SearchOutcome search(UUID callerId, String query, Integer topK, UUID documentId) {
        int k = Math.max(MIN_K, Math.min(MAX_K, topK == null ? DEFAULT_K : topK));
        float[] embedding = queryEmbeddingPort.embedQuery(query);
        List<ChunkSearchPort.Row> rows = chunkSearchPort.search(callerId, embedding, k, documentId);
        List<SourceRef> results = new ArrayList<>(rows.size());
        for (ChunkSearchPort.Row row : rows) {
            results.add(new SourceRef(
                    "document",
                    row.title(),
                    excerpt(row.text()),
                    publicOrigin + "/docs/" + row.documentId()));
        }
        return new SearchOutcome(results, results.size(), query + " — " + results.size() + "건");
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        return text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS);
    }
}
```

  (position 미방출 — 누적기가 턴-글로벌 부여. visibility 미방출 — 필터는 ChunkSearchPort 잔존. `Result` 레코드 + 그 import 제거.)

- [ ] **Step 4: 구현 — ORIGIN 설정**

`docs-api/src/main/resources/application.yml`의 `playground.docs:` 아래 추가 (default 없음 = 필수; 미설정 시 placeholder 미해결로 기동 실패):

```yaml
  docs:
    public-origin: ${PLAYGROUND_PUBLIC_ORIGIN}
    minio:
      # ... (기존)
```

`infra/docker-compose.yml`의 docs-api `environment:`에 추가 (compose는 dev 기본값 제공 → 컨테이너에선 항상 설정됨):

```yaml
      PLAYGROUND_PUBLIC_ORIGIN: ${PLAYGROUND_PUBLIC_ORIGIN:-https://playground.jeeklee.com}
```

`infra/.env.example`에 추가:

```bash
# --- SP3b: citation 절대 URL의 공개 origin (docs-api) ---
# citation uri = ${PLAYGROUND_PUBLIC_ORIGIN}/docs/{id}. 앱에서 필수 — docs-api는
# 미설정 시 기동 실패. 클라우드플레어 터널/커스텀 도메인에 맞춰 설정.
PLAYGROUND_PUBLIC_ORIGIN=https://playground.jeeklee.com
```

  `infra/.env`(실파일)는 직접 수정하지 말고, 없으면 보고 (사용자가 추가) — 단 compose 기본값이 있어 기동은 됨.

- [ ] **Step 5: 통과 + 빌드** — `./gradlew :docs:docs-app:test :docs:docs-api:test :docs:docs-api:build` green. (SearchToolController는 무변경 — `resultEvent`가 `outcome`을 그대로 직렬화, Jackson이 SourceRef record 처리.)

- [ ] **Step 6: Commit** — `feat(docs)!: search tool emits corpus-agnostic SourceRef (absolute uri via ORIGIN)`

---

### Task 3: chat — citation 모델 corpus-무관 전환 (한 커밋, arity 연쇄)

선독 필수: `chat-domain/.../model/RetrievedChunk.java`, `MessageCitation.java`; `chat-app/.../service/TurnCitationAccumulator.java`, `ChatTurnService.java`(persistAssistantAndDone ~452-539, renumberCitations, CitedChunk/CitationRenumber, handleToolInvocation의 `acc.absorb(...)` 호출부), `dto/CitationDto.java`, `service/SessionService.java`(loadDetail), `dto/SessionDetailView.java`; `chat-api/.../dto/SessionResponses.java`; `chat-infra/.../persistence/MessageRepositoryJdbcAdapter.java`, `MessageCitationJpaEntity.java`, `db/migration/` 디렉토리; 기존 `TurnCitationAccumulatorTest`/`ChatTurnServiceTest`/`SessionServiceTest`. **arity 변경이 4모듈로 연쇄하므로 한 커밋에서 빌드 green 유지.**

**Files:** (위 File Structure의 chat-* 전부)

- [ ] **Step 1: chat-domain 재형성**

`RetrievedChunk.java`:
```java
package com.playground.chat.domain.model;

import com.playground.shared.chat.SourceRef;
import java.util.Objects;

/** 턴 누적용 검색 히트 (SP3b spec D3): 턴-글로벌 [N] position + corpus-무관 source. */
public record RetrievedChunk(int position, SourceRef source) {
    public RetrievedChunk {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1, got " + position);
        Objects.requireNonNull(source, "source");
    }
}
```

`MessageCitation.java`:
```java
package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.MessageId;
import com.playground.shared.chat.SourceRef;
import java.util.Objects;

/** chat.message_citations 한 행 (SP3b spec D3). 인용된 [N]만 영속 (cite-persistence). */
public record MessageCitation(MessageId messageId, int position, SourceRef source) {
    public MessageCitation {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(source, "source");
        if (position < 1) throw new IllegalArgumentException("position must be >= 1, got " + position);
    }
}
```

- [ ] **Step 2: chat-app CitationDto 평면화**

`dto/CitationDto.java`:
```java
package com.playground.chat.application.dto;

/**
 * done SSE 이벤트 + history 양쪽의 citation wire (SP3b spec D4/D5). SourceRef 4필드를
 * 평면 미러 + 마커 번호 n. title/content/uri는 nullable(Jackson이 null 직렬화, FE optional).
 */
public record CitationDto(int n, String sourceType, String title, String content, String uri) {}
```

- [ ] **Step 3: 실패 테스트 — TurnCitationAccumulator corpus-맹목**

`TurnCitationAccumulatorTest` 재작성 (caller 파라미터 제거 — chunkOwner 삭제로 불요):
```java
    @Test
    void copiesGenericFieldsBlindAndInjectsGlobalPosition() {
        var acc = new TurnCitationAccumulator();
        JsonNode body = json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"A\",\"content\":\"본문A\",\"uri\":\"https://o/docs/d1\"}]}");
        JsonNode rewritten = acc.absorb(body);
        assertThat(rewritten.path("results").get(0).path("position").asInt()).isEqualTo(1);
        assertThat(acc.retrieved()).hasSize(1);
        RetrievedChunk c = acc.retrieved().get(0);
        assertThat(c.position()).isEqualTo(1);
        assertThat(c.source().sourceType()).isEqualTo("document");
        assertThat(c.source().title()).isEqualTo("A");
        assertThat(c.source().content()).isEqualTo("본문A");
        assertThat(c.source().uri()).isEqualTo("https://o/docs/d1");
    }

    @Test
    void renumbersAcrossTwoSearchesGlobally() {
        var acc = new TurnCitationAccumulator();
        acc.absorb(json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"A\",\"content\":\"x\",\"uri\":\"u1\"},{\"sourceType\":\"document\",\"title\":\"B\",\"content\":\"y\",\"uri\":\"u2\"}]}"));
        JsonNode second = acc.absorb(json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"C\",\"content\":\"z\",\"uri\":\"u3\"}]}"));
        assertThat(second.path("results").get(0).path("position").asInt()).isEqualTo(3); // 글로벌
        assertThat(acc.retrieved()).hasSize(3);
        assertThat(acc.retrieved().get(2).position()).isEqualTo(3);
    }

    @Test
    void emptyResultsNoop() {
        var acc = new TurnCitationAccumulator();
        JsonNode body = json("{\"results\":[]}");
        assertThat(acc.absorb(body)).isSameAs(body);
        assertThat(acc.retrieved()).isEmpty();
    }
```

- [ ] **Step 4: 구현 — TurnCitationAccumulator (corpus-맹목)**

```java
public class TurnCitationAccumulator {
    private final List<RetrievedChunk> retrieved = new ArrayList<>();

    /** search 결과 body의 generic citation을 해석 없이 복사 + 글로벌 position 주입. */
    public JsonNode absorb(JsonNode resultBody) {
        JsonNode results = resultBody.path("results");
        if (!results.isArray() || results.isEmpty()) return resultBody;
        ObjectNode copy = resultBody.deepCopy();
        ArrayNode arr = (ArrayNode) copy.get("results");
        for (JsonNode item : arr) {
            int global = retrieved.size() + 1;
            ((ObjectNode) item).put("position", global);  // LLM-visible — [N] 인용용
            retrieved.add(new RetrievedChunk(global, new SourceRef(
                    item.path("sourceType").asText(),
                    item.path("title").asText(null),
                    item.path("content").asText(null),
                    item.path("uri").asText(null))));
        }
        return copy;
    }

    public List<RetrievedChunk> retrieved() { return List.copyOf(retrieved); }
}
```

  (documentId/chunkIndex/visibility를 **이름으로 읽지 않음** = corpus-맹목. `item.path("sourceType").asText()`가 빈 문자열이면 SourceRef 생성자가 던짐 — 정상 도구는 항상 채움.)

- [ ] **Step 5: 구현 — ChatTurnService.absorb 호출부 + persistAssistantAndDone**

`grep -n "acc.absorb(" backend/springboot/chat/chat-app/.../ChatTurnService.java`로 호출부 확정
(단일 호출부 예상 — `handleToolInvocation` 내). `acc.absorb(userCtx.userId(), s.body())` →
`acc.absorb(s.body())` (caller 인자 제거 — `userCtx`의 다른 사용처는 영향 없음 확인).
(참고: chat-api는 이미 chat:chat-app에 compile 의존 — `build.gradle.kts:22`, SessionDetailView
소비 선례 — 이므로 Step 7의 CitationDto 재사용에 gradle 변경 불요.)

`persistAssistantAndDone`의 citation 루프 재작성 (`c.chunk`는 `RetrievedChunk`, 이제 `.source()` 합성 접근):
```java
            List<MessageCitation> toPersist = new java.util.ArrayList<>();
            List<CitationDto> wireCitations = new java.util.ArrayList<>();
            for (CitedChunk c : renumber.cited) {
                SourceRef s = c.chunk.source();
                toPersist.add(new MessageCitation(persisted.id(), c.newN, s));
                wireCitations.add(new CitationDto(c.newN, s.sourceType(), s.title(), s.content(), s.uri()));
            }
```
  (기존 `c.chunk.visibility()/documentId()/chunkIndex()/text()/title()` 읽던 부분 전부 대체. `String visibility = ...` 줄 삭제. Done 이벤트는 wireCitations 그대로.)

- [ ] **Step 6: 구현 — SessionService.loadDetail + SessionDetailView (CitationView 삭제)**

`SessionDetailView.java`: `CitationView` record 삭제, `MessageView.citations` 타입을 `List<CitationDto>`로:
```java
import com.playground.chat.application.dto.CitationDto;
// ...
    public record MessageView(
            MessageId id, Role role, String content, Instant createdAt,
            Integer tokensIn, Integer tokensOut,
            List<CitationDto> citations, Optional<Attachment> attachment) {}
    // CitationView 제거
```

`SessionService.loadDetail`의 citation 빌드 루프:
```java
            List<CitationDto> snapshotCitations = new ArrayList<>(raw.size());
            for (MessageCitation rc : raw) {
                SourceRef s = rc.source();
                snapshotCitations.add(new CitationDto(
                        rc.position(), s.sourceType(), s.title(), s.content(), s.uri()));
            }
```
  (n = persisted dense position. `deleted` 개념 제거.)

- [ ] **Step 7: 구현 — chat-api SessionResponses (CitationDto 통합)**

`SessionResponses.CitationDto` record 삭제. `MessageDto.citations`를 `List<com.playground.chat.application.dto.CitationDto>`로. `MessageHistoryResponse.from`은 `SessionDetailView.MessageView.citations()`(이미 chat-app CitationDto)를 **그대로 통과**:
```java
            List<MessageDto> msgs = detail.messages().stream().map(m -> new MessageDto(
                    m.id().value(), m.role().wireValue(), m.content(), m.createdAt(),
                    m.tokensIn(), m.tokensOut(),
                    m.citations(),   // 이미 List<CitationDto> — 매핑 불요
                    m.attachment().map(a -> new AttachmentWire(...)).orElse(null)
            )).toList();
```
  (기존 `c.deleted() ? null : c.title()` 매핑 삭제. `MessageDto.citations` 타입 import 갱신.)

- [ ] **Step 8: 구현 — chat-infra JDBC 어댑터 (실 I/O) + JPA 미러**

`MessageRepositoryJdbcAdapter.saveCitations` INSERT:
```java
        jdbc.batchUpdate(
                "INSERT INTO chat.message_citations "
                        + "(message_id, position, source_type, title, content, uri) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                        MessageCitation c = citations.get(i);
                        ps.setObject(1, c.messageId().value());
                        ps.setInt(2, c.position());
                        ps.setString(3, c.source().sourceType());
                        ps.setString(4, c.source().title());
                        ps.setString(5, c.source().content());
                        ps.setString(6, c.source().uri());
                    }
                    @Override public int getBatchSize() { return citations.size(); }
                });
```

`findCitationsForMessages` SELECT + RowMapper:
```java
        return jdbc.query(
                "SELECT message_id, position, source_type, title, content, uri "
                        + "FROM chat.message_citations "
                        + "WHERE message_id = ANY (?::uuid[]) "
                        + "ORDER BY message_id, position",
                (RowMapper<MessageCitation>) (rs, n) -> new MessageCitation(
                        MessageId.of((UUID) rs.getObject("message_id")),
                        rs.getInt("position"),
                        new com.playground.shared.chat.SourceRef(
                                rs.getString("source_type"), rs.getString("title"),
                                rs.getString("content"), rs.getString("uri"))),
                idArray);
```
  (DocumentId import 제거.)

`MessageCitationJpaEntity` (수동 미러): `documentId`/`chunkIndex`/`visibility` 필드+@Column 제거, `sourceType`(@Column nullable=false)/`content`/`uri` 추가, 생성자 시그니처 `(UUID messageId, int position, String sourceType, String title, String content, String uri)`로 갱신.

- [ ] **Step 9: Flyway 마이그레이션**

`chat-infra/src/main/resources/db/migration/V202606080001__citations_corpus_agnostic.sql`:
```sql
-- SP3b: chat.message_citations corpus-무관 전환. 데이터 리셋(2026-06-08) 직후 — 빈 테이블.
-- forward-only (undo 없음). DEFAULT 'document' + DROP DEFAULT: NOT NULL 추가를 빈/비빈
-- 어느 상태에서도 안전하게, 이후 insert는 source_type 명시 강제.
ALTER TABLE chat.message_citations
    DROP COLUMN document_id,
    DROP COLUMN chunk_index,
    DROP COLUMN visibility,
    DROP COLUMN excerpt,
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'document',
    ADD COLUMN content     TEXT NULL,
    ADD COLUMN uri         TEXT NULL;
ALTER TABLE chat.message_citations ALTER COLUMN source_type DROP DEFAULT;
DROP INDEX IF EXISTS chat.chat_message_citations_by_document;
```
  (title 컬럼은 유지. PK(message_id, position) 불변.)

- [ ] **Step 10: DocumentId/Visibility 고아 처리**

```bash
grep -rn "DocumentId\|Visibility" backend/springboot/chat --include="*.java" | grep -v "/test/"
```
  citation 경로 외 참조가 0이면 `chat-domain/.../model/id/DocumentId.java` + `enums/Visibility.java` 삭제 + 잔존 import 정리. 비-citation 참조가 있으면 유지하고 보고.

- [ ] **Step 11: 기존 테스트 픽스처 갱신 + 게이트**

기존 테스트가 **구 형상을 생성**하므로 한 커밋에서 함께 갱신해야 컴파일된다 (구체):
- `TurnCitationAccumulatorTest`: 검색결과 JSON 헬퍼의 `documentId/chunkIndex/excerpt/visibility`
  필드를 `sourceType/title/content/uri`로 교체, 모든 `absorb(caller, body)` → `absorb(body)`.
  (Step 3의 신규 테스트가 이를 대체해도 됨.)
- `SessionServiceTest`: `MessageCitation`을 구 7-인자(messageId, position, documentId, chunkIndex,
  title, excerpt, visibility)로 만들던 곳을 **3-인자** `new MessageCitation(messageId, position,
  new SourceRef("document", title, content, uri))`로. loadDetail 단언을 CitationDto(n, sourceType,
  title, content, uri)·deleted 없음으로.
- `ChatTurnServiceTest`: citation/검색결과 픽스처 헬퍼(예: addResult류)가 구 필드를 만들면 SourceRef
  JSON으로 재작성, persist 단언을 source_type/content/uri로.
- JDBC 라운드트립 테스트가 있으면 새 컬럼으로.

**커밋 전 stale-accessor 게이트** (0이어야 함):
```bash
grep -rn "chunk\.documentId()\|chunk\.chunkIndex()\|chunk\.visibility()\|chunk\.text()\|rc\.documentId()\|rc\.excerpt()\|\.CitationView" backend/springboot/chat --include="*.java" | grep -v "/test/" | wc -l
```

빌드 게이트:
```bash
cd backend/springboot && ./gradlew :chat:chat-domain:test :chat:chat-app:test :chat:chat-infra:test :chat:chat-api:test
```
잔존 grep (citation 경로 한정):
```bash
grep -rn "documentId\|chunkIndex\|chunk_index\|document_id\|\.visibility\|\.excerpt\b" backend/springboot/chat --include="*.java" | grep -vi manifest
```
→ 0 (manifest seam 제외).

- [ ] **Step 12: Commit** — `feat(chat)!: corpus-agnostic citations — SourceRef composition, drop document/chunk/visibility`

---

### Task 4: FE — corpus-무관 citation 렌더

선독: `frontend/src/shared/api/chat.ts`(MessageCitationDto/CitationVisibility/DoneEventPayload), `chat.sse.ts`(done 파싱), `entities/chat/types.ts`(Citation/isStaleCitation), `widgets/citation-accordion/CitationAccordion.tsx`(CitationCard).

**Files:**
- Modify: `frontend/src/shared/api/chat.ts`, `widgets/citation-accordion/CitationAccordion.tsx`

- [ ] **Step 1: chat.ts 타입 전환**

```ts
// CitationVisibility 타입 삭제.
export interface MessageCitationDto {
  /** 1-indexed; [N] 마커와 매칭. */
  n: number;
  sourceType: string;
  /** null = stale/deleted (ADR-14 §11; isStaleCitation 키). */
  title: string | null;
  /** 인용 텍스트. */
  content?: string;
  /** 소스 접근 절대 URL. stale 시 부재. */
  uri?: string | null;
}
```
  `DoneEventPayload.citations`는 `MessageCitationDto[]` 그대로 (타입만 갱신됨). `entities/chat/types.ts`의 `Citation = MessageCitationDto` alias + `isStaleCitation`(title===null) **무변경**.

- [ ] **Step 2: CitationAccordion 전환**

key (line ~90): `key={`${c.n}`}` (n은 메시지 내 dense 유일).

CitationCard (lines ~105-161):
- 링크 분기 제거, `uri` 균일:
```tsx
        {!stale && citation.uri && (
          <a
            href={citation.uri}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-[3px] text-[12px] font-medium text-accent transition-opacity hover:opacity-80 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          >
            <ExternalLink size={11} aria-hidden="true" />
            <span>open</span>
          </a>
        )}
```
- 본문: `citation.excerpt` → `citation.content`:
```tsx
      {!stale && citation.content && (
        <div className="line-clamp-3 text-[12px] leading-snug text-text-muted [&>*:first-child]:mt-0">
          <MarkdownReader body={citation.content} />
        </div>
      )}
```
- `isStaleCitation`(title===null) 무변경, stale 헤더 렌더 무변경.

- [ ] **Step 3: 게이트** — `cd frontend && pnpm typecheck && pnpm lint && pnpm build` clean. (`documentId`/`chunkIndex`/`visibility` 잔존 참조 0 — typecheck가 잡음.)

- [ ] **Step 4: Commit** — `feat(frontend): corpus-agnostic citation card — uri-uniform link, sourceType/content`

---

### Task 5: 통합 검증 + docs 노트

- [ ] **Step 1: 재빌드** (worktree 루트):
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build docs-api chat-api frontend
```
3종 healthy 대기.

- [ ] **Step 2: 라이브 검증** — 문서 시딩(없으면 docs-api에 직접 업로드, user UUID 확보) 후 search 도구 직접 호출:
```bash
docker exec agent-tools python -c "
import httpx, json
r = httpx.post('http://docs-api:18082/internal/tools/search-documents',
    headers={'X-User-Id':'<UUID>'}, json={'query':'연면적','topK':3}, timeout=30)
print(r.text.strip())  # results[].{sourceType:'document', title, content, uri:'https://playground.jeeklee.com/docs/...'} — documentId/chunkIndex/visibility 부재
"
```
  + 수동 E2E: 로그인 → 문서 질문 → citation 카드의 'open'이 `https://playground.jeeklee.com/docs/{id}` (view 모드)로 열리는지, 2회 검색 시 [N] 글로벌 누적되는지.

- [ ] **Step 3: design-doc 노트** — `docs/design/M4-rag-chat.md`의 SP3a 노트 아래:
```markdown
> **2026-06-08 — Corpus-무관 Citation (M9 SP3b)**: citation 모델이
> document/chunk 전용에서 `SourceRef{sourceType,title,content,uri}`(shared-kernel)로
> 일반화됐다. 검색 도구가 generic citation을 방출하고 chat은 해석 없이 복사·영속하며,
> `uri`는 절대 URL(${PLAYGROUND_PUBLIC_ORIGIN}/docs/{id}, 동결 스냅샷). FE는 uri로 균일
> 렌더. documentId/chunkIndex/visibility 폐기. Spec:
> `docs/superpowers/specs/2026-06-08-sp3b-corpus-agnostic-citations-design.md`.
```
  spec deviation 있으면 기록 (ORIGIN 키명, DocumentId/Visibility 삭제 결과 등).

- [ ] **Step 4: Commit + 수동 E2E 안내**

---

## Self-Review

1. **Spec coverage:** D1(SourceRef·검증)→T1; D2(도구 방출·ORIGIN·infra env·position/visibility 미방출)→T2; D3(RetrievedChunk/MessageCitation 합성)→T3 S1; D4(누적기 corpus-맹목·persist·CitationDto 평면)→T3 S3-5; D5(SessionService·CitationView 삭제·SessionResponses 통합)→T3 S6-7; D6(JDBC 실 I/O·JPA 미러·Flyway·undo 없음)→T3 S8-9; D7(FE uri 균일·content·CitationVisibility 삭제)→T4; D8(고아 삭제)→T3 S10; D9 흐름→전체; D10 에러표→T2(ORIGIN 필수)·기존 renumber; D11 테스트→각 태스크 + 잔존 grep(T3 S11). Out of Scope(web 도구·identity·ADR) 침범 없음.
2. **Placeholder scan:** "구현 시 적응·보고" 지점(고아 grep 결과, JDBC 테스트 하니스)은 선독+목표코드 동반 — 의도된 적응. infra/.env 직접수정 회피는 명시. 그 외 TBD 없음.
3. **Type consistency:** `SourceRef(sourceType,title,content,uri)` T1↔T2 방출↔T3 누적/영속/wire↔T4 FE 일관; `RetrievedChunk(position, SourceRef)`·`MessageCitation(MessageId, position, SourceRef)` T3 전반 일치; `CitationDto(int n, sourceType, title, content, uri)` 평면이 done+history+FE 동형; `absorb(JsonNode)` (caller 제거) 호출부 T3 S5와 일치; Flyway 컬럼(source_type/content/uri) ↔ JDBC INSERT/SELECT ↔ JPA 미러 ↔ 도메인 source() 일치.
