# Manifest 추출 (SP3a of agentic-RAG 재구성) — Design Spec

**Date**: 2026-06-08
**Milestone**: M9 (agentic-RAG 재구성)
**Prerequisites**: SP1 개명 (PR #235), SP2 search 도구화 (PR #236)
**Sibling**: SP3b = corpus-무관 citation 모델 (별도 spec, 후속)

---

## Problem

SP2가 *검색* read 경로를 `search_documents` 도구로 추출했지만, `[YOUR
DOCUMENTS]` **manifest** read 경로는 chat이 docs 스키마를 직접 읽는 형태로
남았다. 이 하나의 직접 읽기가 세 갈래 doc-도메인 결합을 chat에 끌고 온다:

1. `CrossSchemaUserDocumentManifestAdapter` — `SELECT ... FROM docs.documents`
   직접 SQL (chat이 docs 스키마 live 읽기).
2. `UserDocumentRef`가 `mimeType`/`extractionStatus`(docs 스키마 컬럼)를
   chat-domain에 모델링.
3. `PromptTemplate`이 `[mime, status]`를 프롬프트로 해석·포맷 — "문서엔
   추출상태가 있다"는 docs-도메인 표현 지식이 chat에 거주.

또한 이 읽기 때문에 datasource `search_path`에 `docs`가 포함된다.

manifest의 본래 목적은 LLM이 사용자의 자연어 문서 언급을 도구 인자
(`search_documents.documentId` / `generate_massing.briefDocId`)용
`documentId`로 매핑하게 하는 것이다. 그 목적엔 **`title` + `id`면
충분**하고, `mimeType`/`extractionStatus`는 의도 없이 들어간 잉여
필드였다 (사용자 확인).

## Decisions

1. **manifest를 `{id, title}`로 축소** — `mimeType`/`extractionStatus` 제거.
   doc 메타데이터가 사라지므로 "누가 그것을 포맷·소유하나"라는 긴장 자체가
   소멸한다. `title`(사람용 라벨) + `id`(불투명 참조)는 chat이 정당하게
   들고 있는 essentials.
2. **manifest read를 docs-api 소유 내부 엔드포인트로 이관** — chat은
   WebClient로 호출, docs 스키마 직접 접근 0.
3. **포맷은 chat에 잔존** — 프롬프트 라인 모양(`N. "title" id=...`)은 chat의
   프롬프트 관심사다. 단 doc 메타데이터 지식은 더 이상 없다.
4. **search_path에서 docs 제거** — manifest가 chat의 마지막 `docs.*` 읽기였다.
   identity(display-name)는 별개 BC seam이라 잔존 (SP3 범위 밖).

---

## D1. docs-api — 내부 manifest 엔드포인트

- **위치**: `InternalDocumentController`에 추가 (기존 `/internal/docs` 컨벤션
  재사용 — 게이트웨이 미노출, compose-내부망, 무인증 read).
- **계약**:

```
GET /internal/docs/manifest?userId={uuid}&limit={n}
→ 200 {"documents":[{"id":"<uuid>","title":"..."}]}
```

  - 정렬·제한: `created_at ASC`, `LIMIT n` (현행 manifest SQL과 동일 의미).
  - `userId`는 호출자(chat)가 명시 — `/internal/**`은 X-User 헤더를 읽지
    않는다는 InternalDocumentController 컨벤션과 일관 (대상 user를 인자로 전달).
  - `userId` 미지정/파싱 불가 → 400. 빈 결과 → `{"documents":[]}` (정상 200).
- **구현**: `DocumentRepository`에 user-scoped 프로젝션 쿼리 추가 —
  `SELECT id, title FROM docs.documents WHERE user_id = ? ORDER BY created_at
  ASC LIMIT ?` (기존 user-scoped 쿼리 패턴 따름). 응답은 `{id, title}` 전용
  경량 DTO — feed/like/페이지네이션 등 리치 필드 미포함.

## D2. chat — JdbcTemplate 어댑터 → WebClient 어댑터

- **삭제**: `CrossSchemaUserDocumentManifestAdapter` (chat-infra, docs.documents
  직접 SQL).
- **신설**: 동일 포트 `UserDocumentManifestPort`를 구현하는 WebClient 어댑터.
  - `GET http://docs-api:18082/internal/docs/manifest?userId&limit` 호출.
  - 엔드포인트 base URL은 env override (도구 URL 패턴과 동일, 예
    `PLAYGROUND_DOCS_INTERNAL_URL` — 실제 키명은 기존 관습 확인 후 결정).
  - **포트 인터페이스 무변경** → `ChatTurnService`/`PromptTemplate` 호출부
    영향 최소 (반환 타입 `UserDocumentRef`만 슬림화 — D3).
- **graceful degradation 보존**: 호출 실패·타임아웃·non-2xx → 빈 리스트 반환
  → `[YOUR DOCUMENTS]` 섹션 생략, 턴 계속. (현행 JdbcTemplate 어댑터의
  "lookup 실패 시 빈 리스트" 동작과 동일.) 짧은 타임아웃 적용 (manifest는
  프롬프트 조립 전 동기 조회라 빠르게 포기해야 함 — 수백ms 수준).

## D3. chat-domain — 모델 슬림화

- `UserDocumentRef(int ordinal, DocumentId documentId, String title, String
  mimeType, String extractionStatus)` →
  **`UserDocumentRef(int ordinal, DocumentId documentId, String title)`**.
- `PromptTemplate`의 `[YOUR DOCUMENTS]` 포맷에서 `mimeType`/`extractionStatus`
  사용처 제거 → 라인은 `N. "{title}" id={documentId}` (현행에서 `[mime,
  status]` 토막만 빠짐). 섹션 헤더·번호·`id=` 접미는 유지.

## D4. config — search_path 정리

- `chat-api/application.yml`의
  `connection-init-sql: "SET search_path TO chat,docs,identity,public"`
  → **`"SET search_path TO chat,identity,public"`**.
- 근거: manifest 제거 후 chat은 `docs.*`를 전혀 읽지 않는다 (SP2에서
  citation resolver 삭제 + 검색 도구화로 docs 읽기는 manifest만 남아 있었음).
  identity는 `IdentityDisplayNameAdapter`(display-name)용으로 유지.

## D5. 데이터 흐름

```
ChatTurnService (턴 시작 — descriptors 비어있지 않을 때만)
  └─ UserDocumentManifestPort.recentForUser(userId, limit)
       └─ WebClientUserDocumentManifestAdapter
            └─ GET docs-api /internal/docs/manifest?userId&limit → [{id,title}]
  └─ PromptTemplate.assemble(history, message, documents)
       └─ [YOUR DOCUMENTS]\n 1. "title" id=<uuid>\n ...
```

## D6. 에러·정합

| 상황 | 동작 |
|------|------|
| manifest 호출 실패/타임아웃/non-2xx | 빈 리스트 → 섹션 생략, 턴 계속 (현행과 동일) |
| 빈 문서 | `{"documents":[]}` → 섹션 생략 |
| 도구 없는 턴 | manifest 호출 자체 안 함 (현행: 도구 있을 때만 [YOUR DOCUMENTS] 주입) |
| docs-api manifest 400 (userId 불량) | chat은 빈 리스트로 취급 (호출자 자기 userId라 실서비스 도달 불가) |

## D7. 테스트

- docs-api: manifest 엔드포인트 단위 (정렬 created_at ASC·limit 클램프/적용
  ·빈 결과·`{id,title}` shape·userId 400) + 리포지토리 프로젝션 쿼리.
- chat: WebClient 어댑터 (성공 시퀀스 → `UserDocumentRef` 매핑·정렬 보존,
  실패/타임아웃/non-2xx → 빈 리스트), `PromptTemplate` (슬림 라인·mime/status
  부재), 기존 manifest 주입 회귀 (도구 있을 때만·빈 문서 생략).
- 잔존 grep: `grep -rn "docs\.documents\|mimeType\|extractionStatus\|
  CrossSchemaUserDocumentManifest" backend/springboot/chat --include="*.java"`
  → 0 (manifest 관련 docs 결합 전무; identity 어댑터는 별개).
- 정합: 클린 스택 부팅 — search_path에서 docs 제거 후 chat 기동·도구 턴 정상,
  manifest 주입 동작 (수동 또는 통합).

---

## Out of Scope

- **SP3b** — corpus-무관 citation 모델 (`message_citations` 스키마 일반화,
  `MessageCitation`/`CitationDto`/`RetrievedChunk` + `search_documents` 반환
  형상 + FE `CitationAccordion`). 별도 spec.
- **identity.users 결합** — `IdentityDisplayNameAdapter`의 cross-schema read.
  docs가 아닌 identity BC seam이라 동형이지만 별개. search_path의 `identity`
  잔존 사유.
- 도구 인자용 문서 readiness(extraction_status) 노출 — manifest 축소로 제거.
  실제로 LLM이 미준비 문서를 골라 도구가 실패하는 사례가 빈번하면 후속에서
  재도입 검토.
- docs-api manifest 페이지네이션/필터/정렬 옵션 — `{id,title}` + created_at
  ASC + limit 고정.
