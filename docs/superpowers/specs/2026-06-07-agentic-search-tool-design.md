# Agentic Search — search_documents 도구화 (SP2) — Design Spec

**Date**: 2026-06-07
**Milestone**: M9 (agentic-RAG 재구성)
**Prerequisites**: SP1 개명 (PR #235), 도구 스트리밍 (PR #233)

---

## Problem

chat이 매 턴 무조건 검색한다 — 인사말에도 embed+pgvector가 돌고, 무관한
컨텍스트가 프롬프트에 주입된다. 또한 chat이 docs 도메인을 침범 중이다:
쿼리 임베딩(`SparkInferenceEmbeddingAdapter`), pgvector 직접 읽기
(`PgvectorChunkRetrievalAdapter`), 인용 타이틀 해석
(`CrossSchemaCitationResolverAdapter`)이 chat 모듈에 산다. 검색은 docs
스키마 소유자(docs-api)가 제공하는 **도구**여야 하고, chat은 순수 대화
오케스트레이터여야 한다.

## Decision (브레인스톰 A안)

검색을 `search_documents` 도구로 추출하되, **글로벌 [N] 인용 체계를
보존**한다: 도구 결과의 청크에 턴-누적 위치를 부여해 기존
`CitationExtractor` → renumber → `message_citations` 영속 → FE 인용
아코디언을 무변경 재사용한다. 바뀌는 것은 "retrieved 리스트의 출처"뿐.

---

## D1. docs-api — `POST /internal/tools/search-documents`

- **위치**: docs-api (MVC). `/internal/**` 정신은 기존 InternalDocumentController와
  동일 (compose 내부망 전용, gateway 미노출). 단 visibility 필터에 호출자가
  필요하므로 `X-User-Id` 헤더 필수 (없으면 400).
- **요청** (JSON): `{"query": string(필수), "topK": int(기본 6, 1..20),
  "documentId": uuid|null(선택 — 해당 문서 내 한정)}`
- **응답**: 도구 NDJSON wire — 검색은 ~수백ms라 **터미널-only 한 줄**:

```jsonl
{"event":"result","result":{"results":[{"position":1,"documentId":"...","chunkIndex":3,"title":"...","excerpt":"...","visibility":"private"}],"totalFound":6}}
```

  실패는 `{"event":"error","code","message","status"}` (예:
  `SEARCH_EMBEDDING_FAILED` 502). progress/heartbeat 불필요 — 디스패처는
  터미널-only 스트림을 이미 수용 (idle 30s ≫ 검색 시간).
- **구현**: 쿼리 임베딩은 기존 `SparkInferenceEmbeddingAdapter`에 단건
  경로(`embedQuery(String) -> float[]`) 추가 (배치 1로 재사용). 검색 SQL은
  chat의 `PgvectorChunkRetrievalAdapter`에서 **이전** (HNSW ef_search 설정
  포함, visibility 필터 동일: `public OR (user_id=? AND private)`).
  `documentId` 지정 시 `AND document_id = ?`. excerpt는 기존 per-chunk
  400토큰 예산으로 head-truncate. title은 docs.documents 조인으로 동봉.
- **ToolCatalog 등록**: name `search_documents`, displayName `문서 검색`,
  endpoint `http://docs-api:18082/internal/tools/search-documents`,
  idle 30s / total 60s. description은 massing의 STRICT TRIGGER 스타일:
  "사용자 질문이 업로드된 문서의 내용에 관한 것일 때 호출. 일상 대화·일반
  지식·이미 이 턴에서 검색한 내용의 재서술엔 호출하지 마라. 질문이 특정
  문서를 지칭하면 [YOUR DOCUMENTS]의 id로 documentId를 지정하라."

## D2. chat — 파이프라인 검색 제거 + 인용 누적기

**삭제** (chat 모듈):
- `EmbeddingPort` + `SparkInferenceEmbeddingAdapter`
- `ChunkRetrievalPort` + `PgvectorChunkRetrievalAdapter`
- `CrossSchemaCitationResolverAdapter` (+ `SessionService.CitationResolver`
  port) — 단, 이 어댑터는 **히스토리 리로드** 시 인용 title/excerpt를
  해석하는 데도 쓰이므로 단순 삭제 불가. **인용 스냅샷 영속으로 대체**:
  `chat.message_citations`에 `title`/`excerpt`/`visibility` 컬럼 추가
  (Flyway 신규 마이그레이션 — 데이터 리셋 직후라 레거시 행 없음),
  영속 시점에 누적기의 `RetrievedChunk` 값을 그대로 저장,
  `SessionService.loadDetail`은 자기 테이블만 읽음. 부수 효과: 문서가
  삭제돼도 인용은 마지막 스냅샷을 유지 (기존 stale-null 처리보다 나은
  UX — FE `isStaleCitation`은 신규 행에서 발동 안 함, 코드 무변경)
- `ChatTurnService`의 retrieval 단계 (embedQuery/retrieve 호출,
  `EMBEDDING_FAILED`/`RETRIEVAL_FAILED` 에러 경로) + retrieval `phase`
  이벤트 발신
- `PromptTemplate`의 `retrieved` 파라미터 + `[RETRIEVED CONTEXT]` 블록 +
  per-chunk 토큰 예산 파라미터 (시스템/매니페스트/히스토리/현재 턴만 남음)

**유지**: `[YOUR DOCUMENTS]` manifest + `CrossSchemaUserDocumentManifestAdapter`
(도구 인자 선택용 — docs-api REST화는 out of scope), 시스템 프롬프트의
tool-honesty 단락, rate limit/락.

**인용 누적기 (핵심 신규)**:
- `ChatTurnService`가 턴마다 `TurnCitations` 누적기를 들고 시작 (카운터 0).
- `search_documents`의 tool_result 수신 시: 결과의 `position`(per-call
  1..k)을 **글로벌 위치로 재번호** (offset = 누적 카운터), LLM-visible
  result body의 position 필드도 동일하게 재작성, 각 결과를
  `RetrievedChunk(position=global, documentId, chunkIndex, title, excerpt,
  visibility)`로 누적기에 추가.
- 턴 종료 시 누적기 내용을 기존 renumber/persist 경로에 `retrieved`로 공급
  — `extractMarkers`/`renumberCitations`/`saveCitations`/done-이벤트
  citations 무변경.
- 검색 0회 턴: 누적기 빈 채로 → citations 빈 배열 (현행 빈 인용과 동일).
- massing 등 비검색 도구의 결과는 누적기에 안 들어감 (도구 name으로 분기).

**시스템 프롬프트 grounding 교체**: 기존 "Cite every factual claim with
[N] markers ... RETRIEVED CONTEXT" 단락을 →
"문서 내용에 근거한 주장은 이 턴에서 search_documents로 확인한 결과만
[N]으로 인용하라. 검색하지 않은 문서 내용을 단정하지 마라. 일상 대화와
일반 지식에는 검색이 필요 없다." (한국어/영어 혼용은 기존 프롬프트 언어
관습을 따름 — 구현 시 기존 영어 프롬프트면 영어로.)

## D3. FE

- 검색 도구 in-flight: 제네릭 ToolRunCard 자동 (FE 0줄).
- **제네릭 결과 카드 신설**: `ToolCardList`의 비등록 도구 result 경로
  (현재 null 렌더 — Task 5 리뷰가 지적한 비대칭)를 미니 카드로 채움 —
  아이콘 Cog + `displayName` + 한 줄 요약. search의 요약은 result의
  `totalFound` 기반 "N건 검색됨"... 단 도구-범용이어야 하므로: result
  body에 `summary` 필드가 있으면 그것을, 없으면 "완료"를 표시 (massing
  전례 — summary는 도구가 제공). **search 도구 result에 `summary:
  "{query} — N건"` 포함**.
- tool_error 경로: 비등록 도구도 기존 MassingErrorCard 휴리스틱이 아닌
  제네릭 에러 카드로... 현행 ToolCardList의 error 분기 확인 후 동일
  원칙(제네릭 fallback) 적용.
- retrieval phase 라벨("참고 문서 확인 중") 발신이 사라지므로 FE의 잔존
  처리 무변경 (이벤트가 안 올 뿐).

## D4. 에러·정합

| 상황 | 동작 |
|------|------|
| 임베딩/검색 실패 (docs-api) | error 이벤트 (502/500) → tool_error — 턴은 계속 (LLM이 검색 실패를 보고 답변 조정) |
| 검색 결과 0건 | result에 빈 results + totalFound 0 — 정상 터미널 (LLM이 "문서에 없음"을 알 수 있음) |
| LLM이 존재하지 않는 [N] 인용 | 기존 renumber가 무시 (byPosition 미스 — 현행과 동일) |
| 검색 없이 문서 주장 | 프롬프트 가드 (구조 강제는 불가 — regen-honesty와 같은 한계, 모니터링) |

## D5. 테스트

- docs-api: 검색 서비스 단위 (visibility 필터·documentId 필터·topK 클램프
  ·0건·title 조인), 임베딩 단건 경로, 컨트롤러 (X-User-Id 필수, NDJSON
  터미널 shape)
- chat: 누적 재번호 (2회 검색 → [1..6]+[7..12], LLM body 재작성 검증),
  검색 0회 턴 citations 빈, 비검색 도구 누적 제외, 기존 citation 영속
  회귀, 삭제 잔존 grep (`ChunkRetrieval|RETRIEVED CONTEXT|embedQuery|
  CitationResolver`)
- FE: typecheck/lint/build + 제네릭 결과 카드
- E2E: 문서 질문 → 검색 카드 + 인용 아코디언; "안녕" → 검색 0회;
  매싱 + 검색 멀티-도구 턴

## Out of Scope

- manifest 조회의 docs-api REST화 (cross-schema read 1개 잔존 수용)
- 하이브리드 검색 (BM25+벡터), 검색 캐싱, re-ranking
- ADR-14 정식 어멘드 (spec이 기록)
- 강제 first-search / tool_choice 강제

---

## 구현 중 확정된 deviation (2026-06-07)

1. **excerpt 예산**: "per-chunk 400토큰"(D1) 대신 **600자 head-truncate**
   (문자 기준 단순화 — docs-api는 chat의 TokenCounter를 갖지 않음).
2. **Done 이벤트 excerpt 정합**: 라이브 CitationDto의 구 160자 트렁케이션
   (`shortExcerpt`)을 제거 — 스냅샷 영속값과 라이브 이벤트가 동일한 전문
   (≤600자)을 싣는다 (히스토리 리로드와 라이브 표시 불일치 제거).
3. **ef_search**: docs-api에 설정 표면이 없어 어댑터 상수 40으로 고정
   (chat의 구 기본값과 동일). 튜닝 필요 시 properties 바인딩은 후속.
4. **에러 코드 단일화**: docs-api 도구 핸들러의 catch-all이 임베딩·검색
   실패 모두 `SEARCH_EMBEDDING_FAILED`(502)로 보고 — D4의 단일 행과 정합,
   코드명만 임베딩 편향 (장애 분류는 docs-api 로그가 담당).
5. **visibility 스냅샷은 현재 write-only**: `message_citations.visibility`는
   영속되지만 히스토리 리로드 `CitationView`엔 아직 미노출 (라이브 done
   이벤트만 visibility 동봉). 필요 시 후속에서 view에 배선.
6. **chat의 EMBEDDING_FAILED/RETRIEVAL_FAILED 에러코드 삭제** — 파이프라인
   검색 제거로 생산처 소멸, FE 참조 없음 확인 후 enum에서 제거.
