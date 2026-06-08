# Corpus-무관 Citation 모델 (SP3b of agentic-RAG 재구성) — Design Spec

**Date**: 2026-06-08
**Milestone**: M9 (agentic-RAG 재구성)
**Prerequisites**: SP1 개명 (PR #235), SP2 search 도구화 (PR #236), SP3a manifest 추출 (PR #241)
**Sibling**: SP3a = manifest 추출 (입력측, 완료). 본 SP3b = citation 모델 (출력측).

---

## Problem

chat의 **출력측** citation 모델이 document/chunk에 하드와이어돼 있다 —
`message_citations(document_id UUID NOT NULL, chunk_index INT NOT NULL)`,
도메인 `RetrievedChunk`/`MessageCitation`, wire `CitationDto`, FE
`MessageCitationDto` 전부 문서 전용이다. 그래서 미래에 web_search 같은 비문서
corpus가 인용을 만들면(`{title, url, snippet}` — documentId·chunkIndex 없음)
이 파이프라인을 통과할 수 없다. M9의 "chat = corpus-무관 오케스트레이터" 목표에
어긋난다.

핵심 관찰 (SP3b context 매핑): **번호 매기기 기계는 이미 corpus-무관**이다 —
`CitationExtractor.extractMarkers` / `ChatTurnService.renumberCitations` / FE
`citationPills`는 전부 정수 position만 다룬다. 결합은 오로지 **citation의 데이터
형상**(documentId/chunkIndex/visibility)에 있다. 또한 `visibility`는 citation
경로에서 영속·전송되지만 FE가 전혀 렌더링하지 않는다 (search 필터는 docs-api
쿼리에 있음 — citation엔 불필요).

## Decisions

1. **번역 경계 = 도구-측** — docs-api 검색 도구가 citation을 **generic 형상**으로
   내보내고, chat 누적기는 그 필드를 **해석 없이 복사**한다 (corpus-맹목).
   generic 계약은 **shared-kernel**에 둔다 (`ChatStreamEvent`과 동형의 BC-중립
   wire 계약; docs-api는 이미 shared-kernel 의존).
2. **단일 citation 원자 `SourceRef`** — 4필드 `{sourceType, title, content, uri}`.
   모든 레이어가 이를 **합성**(compose)한다 (재선언하지 않음).
3. **documentId+chunkIndex → 폐기, content+uri로 대체** — 청크 포인터(chunkIndex)는
   청크 **내용**을 `content`로 스냅샷하면 잉여. 문서 식별은 `uri`(절대 링크)에 흡수.
4. **`uri` = 절대 URL** — 문서 `${ORIGIN}/docs/{id}`, web은 페이지 URL.
   `ORIGIN`은 docs-api 환경변수. 스냅샷 동결 수용 (web URL도 동일하게 시점
   동결·rot — snapshot citation의 본질).
5. **`visibility` 제거** — 미사용·docs-전용 (search 필터는 docs-api 쿼리 잔존).
6. **`[N]` 번호 기계 무변경** — extractMarkers/renumberCitations/citationPills 그대로.
   `n`/position은 SourceRef 밖 (renumber·PK 소유).
7. **데이터 리셋(2026-06-08) 직후** — `message_citations` clean 재구성, 백필 없음.

---

## D1. shared-kernel — `SourceRef` 원자

`com.playground.shared.chat.SourceRef` 신설 (`ChatStreamEvent` 옆, BC-중립 record):

```java
public record SourceRef(
        String sourceType,  // 디스크리미네이터: "document" 오늘; open string (enum 아님 — 새 corpus가 kernel 편집 없이 추가). non-blank.
        String title,       // 사람용 라벨. nullable — null = stale/deleted (FE isStaleCitation 키).
        String content,     // 인용된 텍스트 (docs: ≤600자 head-truncated 청크; web: snippet). nullable.
        String uri          // 소스 접근 절대 URL (docs: ORIGIN/docs/{id}; web: 페이지 URL). nullable (stale).
) {
    public SourceRef {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
    }
}
```

`n`/position은 **포함하지 않는다** — 마커 인덱스는 renumber 기계 + PK가 소유하는
런타임/영속 상태이지 소스의 속성이 아니다. shared-kernel이 docs-api 컴파일
경로에 이미 있으므로 gradle 편집 0 (bc-app 컨벤션 `api(project(':shared-kernel'))`).

**검증 규칙(확정)**: compact constructor는 `sourceType` non-null·non-blank만 강제
(`IllegalArgumentException`). `title`/`content`/`uri`는 null·빈 문자열 모두 허용 —
추가 검증 없음, 필드 조합 검증 없음(예: web sourceType이 uri 강제 같은 규칙 없음).
이 검증은 SourceRef를 합성하는 모든 타입(RetrievedChunk/MessageCitation/CitationDto)이
**상속**한다 — D3의 래퍼들은 position/source-non-null만 추가 검증한다.

## D2. docs-api — 검색 도구가 SourceRef 방출

- `SearchDocumentsService`가 각 검색 hit을 `SourceRef`로 매핑 (현 `Result` 레코드 대체):

```java
new SourceRef(
    "document",
    row.title(),
    excerpt(row.text()),                          // 기존 ≤600자 head-truncate (EXCERPT_CHARS=600) 유지
    publicOrigin + "/docs/" + row.documentId())   // 절대 URL
```

  - `SearchOutcome(List<SourceRef> results, int totalFound, String summary)` — totalFound/summary 의미 불변.
  - **position 미방출** — chat 누적기가 턴-글로벌 position을 부여하므로 hit엔 불필요
    (배열 순서로 충분). NDJSON `{"event":"result","result":{"results":[{sourceType,title,content,uri}],"totalFound","summary"}}`.
  - **visibility 미방출** — public/private 필터는 `ChunkSearchPort` 쿼리(caller-scoped)에 잔존, citation엔 불필요.
- **`ORIGIN` 설정**: docs-api 환경변수 `PLAYGROUND_PUBLIC_ORIGIN`
  (예 `https://playground.jeeklee.com`). infra/.env(실값) + .env.example + compose의
  docs-api 서비스에 추가. docs-api `@ConfigurationProperties`/`@Value`로 바인딩,
  SourceRef.uri 조립에 prepend.
  **필수(확정)**: 미설정 시 docs-api **기동 실패** (default 없음) — citation 링크가
  조용히 깨지는 것을 막는다. 테스트는 명시적으로 값을 주입한다. (D10과 정합.)

## D3. chat-domain — RetrievedChunk / MessageCitation 재형성

`RetrievedChunk`와 `MessageCitation`은 **SourceRef를 합성**한다 (4필드 중복 제거):

```java
public record RetrievedChunk(int position, SourceRef source) {
    public RetrievedChunk {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1, got " + position);
        Objects.requireNonNull(source, "source");
    }
}

public record MessageCitation(MessageId messageId, int position, SourceRef source) {
    public MessageCitation {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(source, "source");
        if (position < 1) throw new IllegalArgumentException("position must be >= 1, got " + position);
    }
}
```

삭제 필드: RetrievedChunk의 `documentId`/`chunkIndex`/`text`(→source.content)/
`title`(→source.title)/`chunkOwner`(accounting-only·미영속·미전송 — 완전 삭제)/
`visibility`. MessageCitation의 `documentId`/`chunkIndex`/`title`/`excerpt`/`visibility`
(→ source로 흡수). 두 타입은 **합치지 않음** (RetrievedChunk=턴 누적, MessageCitation=
영속 행 with messageId — 다른 생애주기) 단 둘 다 payload를 source로 흡수.

## D4. chat-app — 누적기 corpus-맹목 + 영속/wire

- **`TurnCitationAccumulator.absorb(caller, resultBody)`**: `results[]`의
  `{sourceType, title, content, uri}`를 **해석 없이** 복사해 `SourceRef`를 만들고
  `RetrievedChunk(global, source)`로 누적. 글로벌 position은 기존대로 LLM-visible
  copy의 각 item에 `position` 필드로 주입 (LLM이 `[N]` 인용). documentId/chunkIndex를
  **이름으로 읽지 않음** — 이것이 corpus-맹목의 핵심.
- **`ChatTurnService.persistAssistantAndDone`**: renumber 후 cited subset으로
  `MessageCitation(messageId, newN, chunk.source())` + 아래 `CitationDto`를 만든다
  (`c.chunk.documentId()/chunkIndex()/visibility()/text()/title()`를 읽던 블록 재작성 →
  `c.chunk.source()` 합성 접근). renumber/extractMarkers는 무변경.
- **`CitationDto` (done wire, 평면)**: FE 안정성을 위해 평면 record (SourceRef 합성이
  아니라 필드 미러 + n):

```java
// chat-app: com.playground.chat.application.dto.CitationDto
// title/content/uri는 nullable (SourceRef와 동일) — Jackson은 null을 JSON null로
// 직렬화하고 FE는 optional(content?/uri?, title|null)로 받는다. sourceType은 non-null.
public record CitationDto(int n, String sourceType, String title, String content, String uri) {}
```

  (`@JsonUnwrapped` 대신 평면 미러 — 기존 평면 DTO 관습 일치, 애노테이션 결합 회피.)
  `ChatStreamEvent.Done.citations`는 이미 `Object` 슬롯 → `List<CitationDto>` 그대로.
  **모듈 위상**: `CitationDto`는 **chat-app**에 정의. chat-api는 chat-app에 의존하므로
  (SessionController 등 app 서비스 사용) D5에서 이 타입을 그대로 재사용 — shared-kernel로
  올릴 필요 없음.

## D5. chat-api — done+history wire 통합

- `SessionResponses.CitationDto`(history 5필드, 이미 visibility 없음)와
  `SessionDetailView.CitationView`(`deleted` 항상 false·미사용)를 **제거**, chat-app
  `CitationDto`로 통합 — done과 history wire가 동일해진다 (visibility 드롭 후 동형).
- `SessionService.loadDetail`: 스냅샷 행을 `CitationDto(n=position, sourceType, title,
  content, uri)`로 직접 매핑. `deleted` 플래그 제거 (FE는 `title===null`로 stale 판정).

## D6. chat-infra — 영속 (실수정 위치)

- **실 I/O는 `MessageRepositoryJdbcAdapter`의 손코딩 SQL** (JPA 엔티티 아님):
  - `saveCitations` INSERT 컬럼 → `(message_id, position, source_type, title, content, uri)`,
    파라미터를 `MessageCitation.source()`에서 추출.
  - `findCitationsForMessages` SELECT + RowMapper → 위 컬럼 읽어
    `MessageCitation(messageId, position, new SourceRef(source_type, title, content, uri))`.
- `MessageCitationJpaEntity`는 **수동 미러**(라이브 I/O 아님 — 실 읽기/쓰기는 위 JDBC
  어댑터). 그래도 @Column을 새 스키마에 맞춰 갱신(source_type/content/uri 추가,
  document_id/chunk_index/visibility 제거)해 hibernate `ddl-auto=validate` 정합을 유지.
  삭제하지 않음(SP3a 선례 — chat-infra의 JPA 엔티티는 수동 미러로 유지).
- **Flyway `V202606080001__citations_corpus_agnostic.sql`** (forward-only, undo 없음 —
  데이터 파괴적·데이터 리셋 직후라 백필/롤백 불필요; 실패 시 수동 복구):

```sql
-- 데이터 리셋(2026-06-08) 직후 — 테이블 비어 있음.
-- DEFAULT 'document' + DROP DEFAULT 관용구: 빈 테이블이든(현 상황) 행이 있든 NOT NULL
-- 추가가 안전하고, 이후 insert는 source_type을 반드시 명시하게 한다.
ALTER TABLE chat.message_citations
    DROP COLUMN document_id,
    DROP COLUMN chunk_index,
    DROP COLUMN visibility,
    DROP COLUMN excerpt,                                  -- 구 ≤600자 발췌; content가 그 역할 계승
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'document',
    ADD COLUMN content     TEXT NULL,                     -- 인용 청크 텍스트 (≤600자)
    ADD COLUMN uri         TEXT NULL;
ALTER TABLE chat.message_citations ALTER COLUMN source_type DROP DEFAULT;
DROP INDEX IF EXISTS chat.chat_message_citations_by_document;  -- document_id 제거, citation→doc 역조회 소비자 없음
```

  최종: `message_citations(message_id UUID NOT NULL FK→messages ON DELETE CASCADE,
  position INT NOT NULL, source_type TEXT NOT NULL, title TEXT NULL, content TEXT NULL,
  uri TEXT NULL, PK(message_id, position))`. (RENAME 대신 DROP excerpt + ADD content —
  데이터 없으니 동일·의도 명확.)

## D7. FE — 균일 렌더

- `MessageCitationDto`(`shared/api/chat.ts`) →
  `{ n: number; sourceType: string; title: string | null; content?: string; uri?: string | null }`.
  `documentId`/`chunkIndex`/`visibility` + `CitationVisibility` 타입 삭제.
- `chat.sse.ts` done 파싱 — 새 필드 매핑 (기존 옵셔널 처리 유지).
- `CitationCard`: `href={citation.uri ?? undefined}` — **sourceType navigation 분기
  없음, 균일** (uri null이면 링크 미표시 = stale). React key `${c.n}` (n은 메시지 내
  dense 유일). 본문은 `content`를 MarkdownReader로 렌더(기존 excerpt 경로).
  `isStaleCitation = title === null` 무변경.
- `[N]` 핀 기계(`citationPills`, `onCitationClick`) 무변경 (정수 n 키).
- **비-citation 코드 불변**: SearchHitCard/CommandPalette/CommunityDocCard 등은
  docs 기능이 자체적으로 `/docs/{id}`를 만드는 것 — citation과 무관, 손대지 않음.

## D8. 정리 — 고아 value object

`DocumentId`/`Visibility` 도메인 value object는 citation 경로(RetrievedChunk/
MessageCitation) 제거 후 chat에서 고아가 된다. **예상 결과: 둘 다 완전 고아 → 삭제**
(manifest는 SP3a에서 raw `UUID` documentId를 쓰지 DocumentId VO를 안 씀; Visibility의
public/private 필터는 docs-api 쿼리 소유). 구현 시 확정:
`grep -rn "DocumentId\|Visibility" backend/springboot/chat --include="*.java"`(테스트
제외)가 0이면 삭제, 비-citation 잔존 참조가 있으면 유지하고 사유 보고.

## D9. 데이터 흐름 (corpus-맹목 달성)

```
docs-api search → SourceRef{sourceType:"document", title, content:≤600청크, uri:ORIGIN/docs/{id}}
   → NDJSON {results:[SourceRef...]}
chat absorb → SourceRef 4필드 해석 없이 복사 + position 주입 → RetrievedChunk(position, source)
[N] renumber (정수 position, 무변경) → cited subset
   → MessageCitation(messageId, newN, source) 영속 + CitationDto(n, ...source 평면) done 이벤트
FE CitationCard: <a href={uri}> 균일 (sourceType navigation 분기 0)
```

미래 web_search: `SourceRef{sourceType:"web", title, content:snippet, uri:URL}`만 방출
→ **누적기·영속·done·스키마·FE 변경 0**.

## D10. 에러·정합

| 상황 | 동작 |
|------|------|
| 검색 0건 | results 빈 → citation 0 (기존) |
| LLM이 미존재 [N] 인용 | renumber가 무시 (byPosition 미스 — 기존) |
| 문서 삭제 후 history 리로드 | 스냅샷 title/content/uri 유지 (동결); uri 링크는 404 가능 — 수용 (snapshot 본질) |
| uri null (이론상) | FE 링크 미표시 |
| ORIGIN 미설정 | docs-api 기동 실패(필수 설정) — citation 링크 깨짐 방지 |

## D11. 테스트

- shared-kernel: `SourceRef` 검증 (sourceType non-blank, 나머지 nullable).
- docs-api: `SearchDocumentsService`가 SourceRef 방출 (uri=ORIGIN+/docs/+id, content
  ≤600, sourceType="document", visibility/position 미방출), `SearchOutcome` shape,
  컨트롤러 NDJSON shape, ORIGIN 설정 바인딩.
- chat: 누적기 corpus-맹목 복사 + position 주입 + **2회 검색 글로벌 재번호** (테스트
  시나리오: 단일 턴에서 search_documents 2회 연속 호출 → 1차 k건 = [1..k], 2차 m건 =
  [k+1..k+m]; 누적기가 턴 순서로 병합·재번호하고 done/history가 그 글로벌 위치를 인용),
  renumber 회귀, persist 스냅샷(source_type/content/uri), `SessionService.loadDetail`
  스냅샷 매핑(deleted 없이), done+history wire 동형.
- chat-infra: JDBC 어댑터 INSERT/SELECT 컬럼·RowMapper 라운드트립 (실수정 위치).
- FE: typecheck/lint/build + CitationCard href=uri.
- 잔존 grep (citation 경로 한정): `grep -rn "documentId\|chunkIndex\|chunk_index\|
  document_id\|\.visibility" backend/springboot/chat --include="*.java" | grep -vi manifest`
  → 0. (manifest seam의 raw UUID documentId는 비-citation 컨텍스트라 제외; SP3a 산출물.)

---

## 구현 deviation/rationale (사용자 결정)

1. **절대 URL + ORIGIN 동결 수용** — origin 변경 시 과거 citation 링크가 깨지지만,
   web URL도 동일하게 시점 동결·rot 가능 → snapshot citation의 본질로 수용.
2. **content ≤600자 절단 유지** — full 청크는 LLM 컨텍스트·영속 확대라 별도 결정;
   기존 EXCERPT_CHARS=600 유지, 이름만 excerpt→content.
3. **locator/chunkIndex 폐기** — content 스냅샷이 인용 내용을 담으므로 청크 포인터
   불요. "청크로 점프" 미래 기능은 없음 (YAGNI).
4. **JDBC 어댑터가 영속 실수정 위치** — JPA 엔티티는 수동 미러.

## Out of Scope

- **web_search 도구 자체** — 본 SP3b는 모델을 corpus-ready로 만들 뿐, 새 도구는 안 만듦.
- **identity.users(display-name) 결합** — 별개 BC seam (SP3 전체 범위 밖).
- **ADR-14 정식 어멘드** — spec이 기록.
- **content 풀-청크화 / 청크 딥링크** — D-deviation 2·3 참조.
