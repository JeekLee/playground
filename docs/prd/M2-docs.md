# PRD: M2 — Docs BC

> **Source of truth:** `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` (v5, 2026-05-17). This PRD is the user-facing translation; for any technical contract (DTO shapes, route signatures, event payloads, DB columns), the spec wins. Section references like "spec §6.1" point into that spec.
>
> **Parallel work:** the per-milestone ADR (`docs/adr/NN-m2-docs.md`) is being drafted by the architect in a separate session against the same spec; library/version/port decisions live there. The Stage-2 design refresh (`docs/design/M2-docs.md`) is also in flight separately.

## 한 줄 설명
어떤 인증된 사용자든 마크다운 문서를 작성·관리·공유할 수 있는 다중 작성자 문서 호스팅 컨텍스트를 도입하고, 커뮤니티 피드·디렉터리 계층·전문 검색·OpenGraph 공유 미리보기를 P0로 갖춘 `/docs` 단일 네임스페이스 표면을 출시한다.

## Summary

M2는 playground를 단일 작성자의 사이트에서 **다중 작성자 문서 플랫폼**으로 전환한다. M1에서 Google OAuth로 로그인한 모든 사용자는 in-app BlockNote 블록 에디터 또는 `.md` 파일 업로드로 자신의 문서를 만들고, 디렉터리(`path` 컬럼 기반의 묵시적 폴더)에 정리하고, 공개/비공개 상태를 토글하고, 공개 문서를 영구적이고 안정적인 UUID URL(`/docs/{id}`)로 공유할 수 있다.

읽기 측면에서는 익명 방문자가 **커뮤니티 피드**(`/docs` — 모든 작성자의 공개 문서)와 개별 공개 문서를 로그인 없이 읽고, Slack·KakaoTalk·X·Discord 같은 unfurler에 OpenGraph 미리보기가 정상 노출된다. 홈(`/`)은 의도적으로 좁게 큐레이트되어 **소유자(JeekLee)가 작성한 공개 문서만** 보여준다 — M2에서 owner를 특별히 다루는 유일한 표면이다.

검색은 OpenSearch 기반 전문 검색을 띄워 `⌘K` 글로벌 팔레트와 전용 `/docs/search` 페이지 모두를 통해 `mine`/`public` 스코프로 접근할 수 있게 한다. 모든 변경은 세 개의 Kafka 이벤트(`docs.document.uploaded` / `visibility-changed` / `deleted`)로 발행되어 M3(RAG-Ingestion)와 M4(RAG-Chat)가 동일한 본문을 작성자별로 임베딩·검색하도록 한다.

지금 출시하는 이유: M1이 인증을 안정화한 직후 시점이라 M3/M4의 인풋(작성자별 본문 + 가시성)이 필요하고, 사용자의 기존 git 블로그를 playground 안으로 흡수해 후속 RAG 시나리오의 첫 번째 의미 있는 corpus를 확보하기 위함이다.

## User personas

| 페르소나 | 핵심 동기 | M2에서 가능한 것 |
|---|---|---|
| **Anonymous reader** | 공유 링크를 따라오거나 홈에서 우연히 도착해서 글을 읽음 | 홈(`/`), 커뮤니티 피드(`/docs`), 단일 공개 문서(`/docs/{id}`), 공개 스코프 검색(`/docs/search?scope=public`) 모두 로그인 없이 사용. 좋아요는 로그인 유도. |
| **Authenticated author** | 자신의 문서를 작성·정리·공개/비공개 토글·삭제 | `/docs/new` · `/docs/mine` · `/docs/{id}` 편집 · `/docs/search?scope=mine` · ⌘K 검색. 자신의 디렉터리 트리, 자신의 모든 문서(공개/비공개), 자신의 like 상태를 관리. |
| **Authenticated reader** | 로그인된 상태로 다른 작성자의 공개 문서를 읽고 좋아요 | Anonymous reader 능력 + `likedByMe` 상태 + 공개 문서에 좋아요/취소. 사이드바 `Docs` 행이 자신의 `/docs/mine`로 라우팅. |
| (특수 케이스) **Owner (JeekLee)** | Authenticated author와 동일 | 홈의 `Latest documents` 섹션과 M4 public RAG chat(미래)만 owner를 특별 취급. M2의 모든 작성·열람·검색 흐름에서는 일반 사용자와 구분 없음. |

## User stories with acceptance criteria

### Anonymous reader

#### Story 1 — 커뮤니티 피드 둘러보기
> As an anonymous reader, I want to browse all public documents at `/docs` without signing in, so that I can discover content from any author the site hosts.

- [ ] `/docs`에 로그아웃 상태로 접근해도 401/리다이렉트 없이 200 OK + 공개 피드가 렌더된다 (per spec §6.1 `GET /api/docs` `Auth: optional`).
- [ ] 응답 리스트는 모든 작성자의 `visibility='public'` 문서를 포함하고 `published_at DESC`로 정렬된다.
- [ ] 각 카드에는 작성자 정보(displayName + avatar)가 노출된다 (per spec §6.4 `DocListItem.author`).
- [ ] 비공개 문서는 한 건도 노출되지 않는다 — 통합 테스트로 검증한다 (per spec §10 "Community feed correctness").
- [ ] 페이지 하단에서 cursor 기반 페이지네이션(`nextCursor`)으로 다음 페이지를 받아온다.

#### Story 2 — 공개 문서 읽기 & 공유
> As an anonymous reader, I want to open a public document at `/docs/{id}` and share its URL, so that the link unfurls correctly in Slack / KakaoTalk / X / Discord.

- [ ] `/docs/{id}` (visibility=public)에 로그아웃 상태로 접근하면 200 OK로 본문이 `unified` + `remark` + `rehype` + `shiki` 파이프라인으로 렌더된다 (per spec §12 "Editor + rendering").
- [ ] 같은 페이지가 server-side에서 `og:title`, `og:description`(=derived excerpt), `og:url`, `og:type=article`, `og:publishedTime`, `og:authors[]` 그리고 Twitter Card(`summary_large_image`) 메타 태그를 모두 방출한다 (per spec §7.4, §10 "OpenGraph rendering").
- [ ] `curl -A "Slackbot 1.0 (+...)" $URL | grep og:` 인테그레이션 체크로 메타 태그 존재가 검증된다 (per spec §12).
- [ ] 페이지 메타에는 viewCount / likeCount / 작성자명·아바타가 표시된다 (per spec §7.3).

#### Story 3 — 좋아요는 로그인 유도
> As an anonymous reader, I want to see the like button on a public doc but be told I need to sign in to use it, so that the affordance is discoverable without being broken.

- [ ] 익명 상태로 `/docs/{id}` 좋아요 버튼은 비활성 상태("sign in to like" 툴팁) 또는 클릭 시 로그인 흐름으로 라우팅 — 기본값은 비활성 노출 (per spec §11 Q12 working default).
- [ ] 익명 상태에서 `POST /api/docs/{id}/like` 직접 호출은 401을 반환한다 (per spec §6.1 — `like` is `Auth: required`).

#### Story 4 — 비공개 문서로의 공유 링크는 누설 금지
> As an anonymous reader following a stale link to a doc whose author has since made private, I should get a 404 with no hint that the doc ever existed.

- [ ] `GET /api/docs/{id}` 또는 `/docs/{id}` 페이지로 접근 시 `visibility='private'` && 호출자 비-소유자 → 404 (per spec §6.1 authorization rule).
- [ ] 같은 응답에서 OpenGraph 메타 태그는 방출되지 않는다 — unfurler가 비공개 문서 제목을 인덱싱하지 못하도록 (per spec §7.4).

#### Story 5 — 공개 검색
> As an anonymous reader, I want to search the public corpus from `/docs/search?scope=public` so that I can find a topic across authors.

- [ ] 익명 상태에서 `/docs/search?scope=public&q=...` 페이지가 OpenSearch 결과를 highlight snippet과 함께 렌더한다 (per spec §6.1 `Auth: optional`).
- [ ] 익명 검색은 게이트웨이에서 anti-scrape per-IP soft cap이 걸려있다 — 정확한 임계값은 per-milestone ADR (per spec §6.1, §11 Q5).
- [ ] 결과는 `visibility='public'` 문서만 포함하며 비공개 본문은 한 토큰도 누출되지 않는다.

#### Story 6 — 조회 카운트는 익명에서도 동작
> As an anonymous reader, my repeated visits within 24h should count as one view, so that view counts reflect distinct readers.

- [ ] `POST /api/docs/{id}/view` 호출은 `visibility='public'`일 때만 `view_count`를 증가시킨다; 비공개 문서면 204 no-op (per spec §6.1).
- [ ] 같은 `PLAYGROUND_ANON` 쿠키로 24h 내 같은 문서를 N번 호출해도 카운트는 정확히 +1이다 (per spec §10 "View dedup correctness").
- [ ] 쿠키가 없으면 `view:{id}:ip:{X-Forwarded-For}`로 fallback 한다.

### Authenticated author

#### Story 7 — 최초 가입 → 첫 문서 생성
> As an authenticated user who just signed in via Google (M1), I want to create my first document immediately, so that the onboarding gap is zero.

- [ ] 로그인 직후 사이드바 `Docs` 행이 잠금 해제되어 `/docs/mine`으로 라우팅한다 (per spec §7.1 "Logged in" behavior).
- [ ] `/docs/mine` 첫 방문 시 빈 상태에서도 `+ New document` CTA가 우측 패널에 명확히 보인다.
- [ ] 클릭 → `/docs/new` 에디터로 진입 → 첫 저장 시 `POST /api/docs` (`CreateDocRequest`)가 호출되어 201 + `DocDetail` 응답 → `/docs/{id}`로 redirect (per spec §6.1, §7.2).
- [ ] 첫 저장된 문서는 `visibility='private'`, `path='/'`로 시작한다 (per spec §6.1).

#### Story 8 — 블록 에디터 작성
> As an authenticated author, I want a Notion-style block editor with slash commands and drag-handles, so that authoring feels modern without me writing raw MD.

- [ ] `/docs/{id}` (owner) 진입 시 **BlockNote** 블록 에디터가 로드된다 (`/` 메뉴 + 블록 드래그 핸들) — per spec §11 Q3 "DECIDED: BlockNote".
- [ ] 로드 시 본문 MD가 `tryParseMarkdownToBlocks`로 블록 트리로 파싱된다.
- [ ] 저장 시 블록 트리가 `blockToMarkdownLossy`로 raw MD로 직렬화되어 `body`로 PATCH 된다 (per spec §12 "Editor + rendering").
- [ ] M2 P0에서 저장은 **수동 저장** — 에디터 상단 우측 툴바의 명시적 저장 액션 사용. Auto-save는 M2.1 (per spec §2 deferred).

#### Story 9 — `.md` 파일 업로드
> As an authenticated author with an existing `.md` file from my blog, I want to drop it into `/docs/mine` and have a draft created, so that migration is one gesture.

- [ ] `/docs/mine`의 `+ New document` 버튼 옆 chevron 드롭다운에 `↑ Import .md…` 행이 있고 클릭 시 네이티브 파일 피커가 열린다 (per spec §12 "Authorship + tenant isolation").
- [ ] `/docs/mine` 뷰포트에 `.md` 파일을 드래그하면 오버레이가 표시되고 드롭 시 `multipart/form-data`로 `POST /api/docs` 호출 → 201 + draft 생성.
- [ ] `.md`가 아닌 파일을 드롭하면 `danger` 톤 토스트로 거부된다.
- [ ] 업로드된 문서는 `visibility='private'`로 시작하며 `title`은 (파일명 또는 form 필드)에서 도출한다.

#### Story 10 — 폴더에 문서 분류
> As an authenticated author, I want to file a new doc into a folder at create time (e.g., `/agents/build-log/`), so that my docs aren't all in one bucket.

- [ ] `/docs/new` 에디터에는 첫 저장 전에 `path`를 지정할 수 있는 picker가 있다 (per spec §11 Q15 — 구체적 UX는 Stage-2 designer).
- [ ] picker가 비어있으면 현재 `/docs/mine`에서 선택한 폴더의 path를 default로 적용, 직접 진입한 경우 `/`.
- [ ] path는 정규식 `^(/|(/[a-z0-9][a-z0-9-]*)+/)$`를 만족해야 하며 위반 시 API가 400 반환 (per spec §4.1, §6.5).
- [ ] **M2 P0에서 기존 문서의 path 이동은 불가** — 재배치하려면 DELETE 후 새 path로 재생성 (per spec §4.1 "path rules"). Move 액션은 M2.1.

#### Story 11 — 내 폴더 트리 탐색
> As an authenticated author, I want to browse my docs by directory on `/docs/mine`, so that organization scales past a flat list.

- [ ] `/docs/mine`은 좌측 트리 페인(상태 필터 + 폴더 트리)과 우측 리스트 페인으로 구성된다 (per spec §7.2).
- [ ] 트리는 `GET /api/docs/folders`에서 가져온 `[{ path, count }]` 리스트로 구성된다.
- [ ] 폴더 카운트는 `SELECT path, COUNT(*) FROM docs.documents WHERE user_id=? GROUP BY path`와 정확히 일치한다 (per spec §10 "Folder listing scoping").
- [ ] 폴더 선택 시 우측 리스트가 `GET /api/docs?scope=mine&path=...`로 갱신된다.
- [ ] 다른 사용자의 폴더/문서는 절대 노출되지 않는다 — 통합 테스트로 검증.

#### Story 12 — 발행은 마찰 없이 즉시
> As an authenticated author, I want clicking Publish to be one click with no modal, so that publishing feels light.

- [ ] 에디터 우상단 `Publish` 클릭 → 즉시 `POST /api/docs/{id}/publish`(빈 body) 호출 (per spec §6.1, §2 "No Publish modal").
- [ ] 응답이 200이면 `✓ Published as /docs/{id}` + `[Copy link]` + `[View public]` 액션이 있는 토스트가 표시된다.
- [ ] 같은 클릭으로 문서는 `visibility='public'`이 되고 `published_at`은 NULL이었으면 now()로, 아니면 기존 값 유지 (per spec §4.4, §12 "Publish lifecycle").
- [ ] 발행 후 즉시 `/docs/{id}`가 익명 독자에게도 200, 커뮤니티 피드와 (소유자 본인이라면) 홈에도 즉시 노출된다.

#### Story 13 — Unpublish는 모달로 확인
> As an authenticated author, I want Unpublish and Delete to ask "are you sure", so that I don't lose visibility or data by accident.

- [ ] `Unpublish` 클릭 → 확인 모달 (per spec §2 "Friction is reserved for Unpublish (modal) and Delete (modal)").
- [ ] 확인 시 `POST /api/docs/{id}/unpublish` → `visibility='private'`, `published_at` 유지.
- [ ] 즉시 커뮤니티 피드와 홈에서 사라지고 `/docs/{id}`는 익명 독자에게 404, 소유자에게는 200으로 응답한다 (per spec §12 "Publish lifecycle").

#### Story 14 — 재발행은 같은 URL
> As an authenticated author, I want re-publishing an unpublished doc to keep the same URL, so that old shared links don't break.

- [ ] publish → unpublish → publish 사이클 후 문서의 UUID `id`는 변하지 않는다 → URL `/docs/{id}` 안정 (per spec §10 "URL stability", §4.4).
- [ ] 통합 테스트가 위 사이클을 실행하고 URL 안정성을 명시적으로 단언한다.

#### Story 15 — 삭제는 하드 삭제 + 캐스케이드
> As an authenticated author, I want Delete to permanently remove the document and its likes, so that I don't worry about ghost data.

- [ ] `Delete` 클릭 → 확인 모달 → `DELETE /api/docs/{id}` (per spec §6.1, §2).
- [ ] 204 응답 후 `docs.documents` row 제거, `docs.document_likes`가 ON DELETE CASCADE로 함께 제거, OpenSearch 인덱스에서도 제거 (per spec §4.1, §12 "Publish lifecycle").
- [ ] `docs.document.deleted` 이벤트가 발행된다.
- [ ] M2 P0에서 토스트의 `Undo` 링크는 표시되되 동작하지 않는다 (M2.1로 연기, per spec §11 Q13).

#### Story 16 — 글로벌 ⌘K 검색 팔레트
> As an authenticated author, I want to hit ⌘K from any page and search my docs live, so that navigation is keyboard-first.

- [ ] 인증된 페이지 어디서든 `⌘K`로 검색 오버레이가 뜬다 (per spec §2 "Global ⌘K search palette").
- [ ] 기본 스코프는 `mine`, `Tab` 키로 `public`(커뮤니티 전체) 스코프로 토글한다.
- [ ] 입력 시 `GET /api/docs/search?q=...&scope=...`를 live로 호출한다.
- [ ] Enter는 선택된 결과 문서로 이동, `⌘+Enter`는 같은 쿼리로 `/docs/search` 풀 페이지를 연다.

#### Story 17 — 전용 검색 페이지 (deep-linkable)
> As an authenticated author, I want a full-page search experience at `/docs/search` so I can share search-result links and filter by folder.

- [ ] `/docs/search` 페이지는 스코프 토글(`mine` / `public`)과, `scope=mine`일 때 폴더 필터를 제공한다 (per spec §7.2).
- [ ] URL 쿼리(`q`, `scope`, `path`)는 deep-linkable — 같은 URL로 접근하면 같은 결과가 재현된다.
- [ ] cursor 기반 페이지네이션으로 다음 페이지를 받는다.

### Authenticated reader

#### Story 18 — 다른 작성자의 글 읽기 + 좋아요
> As an authenticated reader who didn't author the doc, I want to like a public document so the author gets a signal.

- [ ] 다른 작성자의 공개 문서(`/docs/{id}`)에서 좋아요 버튼이 활성 상태로 렌더된다.
- [ ] 클릭 → `POST /api/docs/{id}/like` → 204 → 버튼이 filled 상태로 토글되고 `like_count` 표시가 +1.
- [ ] 같은 사용자가 다시 클릭 → `DELETE /api/docs/{id}/like` → 204 → outline 상태로 토글, 카운트 -1 (per spec §6.1, §10 "Like idempotency").
- [ ] 반복 호출에 대해 idempotent — `like_count`는 항상 `COUNT(*) FROM document_likes WHERE document_id=?`와 일치한다.

#### Story 19 — 사이드바 `Docs` 행 라우팅
> As an authenticated reader, clicking the sidebar `Docs` row should take me to my own docs, so the locked-vs-shipped UX from M1 carries over correctly.

- [ ] 로그아웃 상태에서 사이드바 `Docs` → `/docs` (커뮤니티 피드) (per spec §7.1).
- [ ] 로그인 상태에서 사이드바 `Docs` → `/docs/mine`.
- [ ] active state는 `/docs`, `/docs/mine`, `/docs/{id}`, `/docs/new`, `/docs/search` 모두에서 `accent.soft` 백그라운드로 점등된다.

### Owner (특수)

#### Story 20 — 소유자 큐레이트 홈
> As the site owner, I want the home page's "Latest documents" section to keep showing only my docs, so the home stays a curated portfolio surface even after other authors publish.

- [ ] 홈(`/`)의 `Latest documents` 섹션은 `GET /api/docs?author={ownerUserId}` 결과만 노출한다 (per spec §7.3).
- [ ] `ownerUserId`는 `PLAYGROUND_OWNER_GOOGLE_SUB` 환경변수에서 부팅 시 resolve되어 캐시된다 (per spec §6.3).
- [ ] 환경변수 미설정 또는 lookup 실패 시 홈 섹션은 빈 리스트(fail-closed) + 부팅 시 WARN 로그.
- [ ] 다른 작성자의 공개 문서는 홈에 절대 노출되지 않는다 — 통합 테스트로 검증 (per spec §12 "Owner-curated home").
- [ ] **M2 P0에서 owner는 다른 사용자의 문서에 대해 어떤 특권도 갖지 않는다** — 모더레이션은 M2.1+ (per spec §2 deferred, §11 Q16).

## UX surfaces

Per spec §7.2 — 모든 시각 처리는 Stage-2 design doc에서 결정.

| Route | Auth | Purpose |
|---|---|---|
| `/` | optional | Home; `Latest documents` = owner-only 공개 문서 (per spec §7.3) |
| `/docs` | optional | 커뮤니티 피드 — 모든 작성자의 공개 문서 |
| `/docs/mine` | required | 내 문서 — 좌측 디렉터리 트리 + 우측 리스트 |
| `/docs/{id}` | optional | 단일 문서; 소유자면 BlockNote 에디터, 그 외(공개일 때)는 렌더된 read-only 뷰 |
| `/docs/new` | required | 새 문서 에디터; 첫 저장 시 `/docs/{id}`로 redirect |
| `/docs/search` | required(public is optional) | 풀 페이지 검색; 스코프 토글 + 폴더 필터 + cursor 페이지네이션. `scope=public`만이면 인증 불필요 |

사이드바 처리(per spec §7.1)는 M1에서 잠금/잠금해제 메커니즘이 이미 자리잡혀 있으며, M2 ship 시점에 `Docs` 행이 잠금 해제된다. 잠금된 다른 행(`Chat M4`, `System status M5`)은 그대로 유지.

## Non-functional requirements

Per spec §10에서 PRD 음성으로 재진술 — 빠짐없이 ship 직전 통과해야 함.

- **Tenant isolation:** 모든 인증 mutation(`PATCH`, `POST /publish`, `POST /unpublish`, `DELETE`)은 write 전에 `X-User-Id == doc.user_id`를 검증한다. 불일치 → 404(403 아님 — 존재 누설 금지). repository-level guard(예: `@WithCurrentUser` 인터셉터)가 query-author마다 반복하는 것보다 선호.
- **Outbox 정합성:** 이벤트와 DB write는 원자적으로 성공한다. Spring Modulith Events JPA가 working direction (per spec §11 Q1).
- **검색 projection lag tolerance:** DB write → OpenSearch visibility lag ≤ 2s (P95). 초과 시 WARN 로그.
- **검색 projection failure isolation:** OpenSearch unavailable이 API write를 막지 않는다 — 실패한 projection은 Kafka redelivery로 재시도, 정확한 백오프는 per-milestone ADR.
- **Authorization 정합성 (ADR-09 invariant):** `GET /api/docs/{id}`는 호출자가 소유자가 아닐 때 `visibility='private'` row를 절대 반환하지 않는다. 통합 테스트 필수.
- **커뮤니티 피드 정합성:** `GET /api/docs`는 `visibility != 'public'`인 row를 절대 반환하지 않는다. 통합 테스트 필수.
- **폴더 listing scoping:** `GET /api/docs/folders`는 `user_id != X-User-Id`인 row를 절대 반환하지 않는다. 통합 테스트 필수.
- **Observability:** 모든 상태 전이(create, publish, unpublish, delete, body-edit)는 `documentId`, `userId`, `event`, `bodyChecksum`(해당 시)을 포함한 INFO 구조화 로그를 발생시킨다. Search projector는 별도 INFO/WARN.
- **Body size cap:** API와 DB column 제약(혹은 trigger) 양쪽에서 강제. 기본값 ~1MB (per spec §11 Q4).
- **URL 안정성:** publish → unpublish → republish 후 같은 UUID, 같은 URL. 통합 테스트 필수 (per spec §10).
- **View dedup 정합성:** 같은 `PLAYGROUND_ANON` 쿠키가 24h 내 같은 문서를 N회 호출해도 view_count는 정확히 +1. 작성자가 본인 문서를 보는 것도 카운트에서 제외하지 않는다(M2 P0의 의도적 대칭 처리).
- **Like idempotency:** `POST /like`와 `DELETE /like`는 둘 다 반복 호출에 idempotent. 동일 사용자의 동시 like/unlike는 `(document_id, user_id)` PK 경합으로 직렬화. 최종 `like_count == COUNT(*) FROM document_likes`.
- **카운터 드리프트 tolerance:** 부분 실패로 `view_count` / `like_count`가 source of truth와 어긋나면 nightly re-sync job이 복구.
- **OpenGraph 렌더링:** `/docs/{id}` 공개 문서의 모든 SSR 응답은 `og:title`, `og:description`, `og:url`, `og:type=article`, `og:publishedTime`, `og:authors[]`, Twitter Card 메타를 방출한다. 비공개 → 404, 메타 미방출.
- **Excerpt derivation determinism:** spec §4.3 알고리즘이 유일한 acceptable derivation — 동일 본문 입력에 대해 byte-stable excerpt 출력. 통합 테스트 필수.

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 전체 체크리스트, per spec §12 미러링.

### Authorship + tenant isolation
- [ ] 모든 인증 사용자가 in-app 에디터와 `.md` 업로드 양쪽으로 문서를 만들 수 있으며 stable document id를 받는다.
- [ ] `.md` 업로드 경로는 `/docs/mine`의 두 affordance에서 도달 가능: (a) `+ New document` 버튼의 chevron 드롭다운 `↑ Import .md…`, (b) viewport에 `.md` 파일 드래그-드롭. `.md`가 아닌 파일은 `danger` 토스트로 거부.
- [ ] `GET /api/docs?scope=mine`은 호출자 문서 전체(visibility 무관)를 반환; `GET /api/docs/{id}`는 단일 문서를 반환.
- [ ] Tenant isolation 테스트: user A는 user B의 비공개 문서를 `GET /api/docs/{id}`로 읽지 못함(404). PATCH / publish / unpublish / delete도 동일하게 404.

### Public surface (community-wide)
- [ ] `GET /api/docs`(no params)는 auth header 없이 작동, 모든 작성자의 `visibility='public'` 문서 반환. cursor 페이지네이션 동작.
- [ ] `GET /api/docs?author={userId}`는 auth header 없이 작동, 해당 사용자의 공개 문서만 반환. 두 사용자 문서를 seed해서 필터 준수 검증.
- [ ] `GET /api/docs/{id}` (public)은 auth header 없이 작동. 같은 호출이 비공개 문서면 비-소유자 → 404, 소유자 → 200.
- [ ] `GET /api/docs`(list)는 `visibility != 'public'` row를 절대 반환하지 않음.
- [ ] 커뮤니티 피드 통합 테스트: 세 사용자 공개 + 각자 비공개를 seed, `GET /api/docs`가 공개만 반환하는지 단언.

### Owner-curated home
- [ ] 홈은 `Latest documents`를 `GET /api/docs?author={ownerUserId}`(=`PLAYGROUND_OWNER_GOOGLE_SUB` resolve)로 렌더. 비-소유자의 공개 문서는 홈에 나타나지 않는다.

### Publish lifecycle
- [ ] `POST /api/docs/{id}/publish`(빈 body)는 `visibility='public'`, `published_at`을 NULL이면 now()로 설정(아니면 유지), 즉시 익명 독자에게 `/docs/{id}` 도달 가능.
- [ ] `POST /api/docs/{id}/unpublish`는 `visibility='private'`로 토글, `published_at` 유지, `/docs/{id}`는 익명에 404, 소유자에 200.
- [ ] Re-publish는 같은 URL(UUID `id`) 유지. publish→unpublish→publish 사이클 후 URL stable 단언.
- [ ] Hard delete는 문서 제거 + `document_likes` 캐스케이드 + OpenSearch entry 제거 + `docs.document.deleted` 발행.

### Events
- [ ] `docs.document.uploaded`는 create와 body 변경 시 발행, shared-kernel envelope 사용 (per spec §5).
- [ ] `docs.document.visibility-changed`는 publish/unpublish 시 발행, `oldVisibility` / `newVisibility` / (해당 시) `publishedAt` 정확.
- [ ] `docs.document.deleted`는 hard delete 시 발행.
- [ ] 모든 이벤트는 outbox 패턴으로 DB write와 atomic.

### Editor + rendering
- [ ] `/docs/{id}` 소유자 뷰는 **BlockNote** 블록 에디터를 렌더(`/` 메뉴 + 드래그 핸들). 로드 시 `tryParseMarkdownToBlocks`, 저장 시 `blockToMarkdownLossy`.
- [ ] `/docs/{id}` 비-소유자 뷰(공개)는 `unified` + `remark` + `rehype` + `shiki`로 렌더. 에디터는 authoring UX만 바꾸고 reading pipeline은 동일.
- [ ] GFM 지원(tables, code fences with language, task lists, strikethrough, autolinks), 외부 URL 이미지만(`data:` URL과 unscoped relative 경로 거부), HTML in MD는 sanitize (per spec §9).

### Search
- [ ] `GET /api/docs/search?q=...&scope=mine`은 호출자 docs 스코프의 OpenSearch hit을 반환.
- [ ] `GET /api/docs/search?q=...&scope=public`은 커뮤니티 전체 공개 docs 스코프의 hit을 반환.
- [ ] OpenSearch projection eventually-consistent: `docs.document.*` 이벤트 후 ≤ 2s P95에 인덱스 반영.
- [ ] OpenSearch unavailable → 검색 라우트 503 반환, write/read/그 외 M2 라우트는 영향 없음.
- [ ] 글로벌 `⌘K` 팔레트가 인증된 모든 페이지에서 작동, 기본 `scope=mine`, `Tab`으로 `public` 전환, `Enter`는 문서로, `⌘+Enter`는 `/docs/search`로.

### Engagement
- [ ] `POST /api/docs/{id}/view`는 공개 문서에서만 `view_count` +1; 같은 anon cookie의 24h 내 반복은 +1만. 비공개 문서는 204 no-op.
- [ ] `POST /api/docs/{id}/like`는 idempotent; 같은 사용자 2회 호출해도 `like_count` +1, `DELETE`로 0 복귀.
- [ ] `GET /api/docs/{id}`는 `viewCount` + `likeCount` 반환; 인증된 호출자에 대해 `likedByMe`도 반환.

### UX
- [ ] 사이드바 `Docs` 행: 로그아웃 → `/docs`(커뮤니티), 로그인 → `/docs/mine`.
- [ ] `/docs/mine`은 좌측 트리 페인(상태 필터 + 폴더 트리 from `GET /api/docs/folders`) + 우측 리스트(`GET /api/docs?scope=mine&path=...`). 폴더 전환 시 우측 갱신.
- [ ] 폴더 카운트가 `COUNT(*) FROM docs.documents WHERE user_id=? GROUP BY path`와 정확히 일치.
- [ ] `+ New document` 드롭다운은 현재 폴더 선택을 `path`로 pre-apply한 채 `/docs/new`를 연다.
- [ ] Publish 클릭 → 즉시 발행 + 토스트(`Copy link`, `View public`); Unpublish/Delete는 확인 모달.

### Excerpt + share preview
- [ ] 모든 문서 메타데이터 DTO가 spec §4.3 알고리즘으로 derive된 `excerpt`를 반환.
- [ ] 공개 `/docs/{id}` SSR HTML에 OpenGraph + Twitter Card 메타가 방출됨 — `curl -A "Slackbot 1.0 (+...)" $URL | grep og:`로 검증.
- [ ] 비공개 `/docs/{id}`를 비-소유자가 접근 시 404 + 메타 태그 미방출.

### Cross-milestone (traceability — non-blocking for M2 close)
- [ ] Manual E2E: 임의 인증 사용자가 비공개 문서 업로드 → M3 임베딩 완료(후행 마일스톤) → M4 private chat에서 그 문서 인용된 답변. M2 close blocker가 아니다.

## Out of scope

### M2.1 (same milestone bucket, ship if cycle has slack)
- 이미지/첨부 업로드(presigned to local volume or Postgres `bytea`).
- `cover_image_url` 컬럼 on `docs.documents` (`og:image` 구동).
- 에디터 auto-save.
- Move-to-folder 액션 (`POST /api/docs/{id}/move`) + `docs.document.moved` 이벤트. M2 P0는 read-only 트리; create-time path picker만.
- 풍부한 `⌘K` 커맨드 팔레트(검색 너머).
- **공개 문서의 댓글** — 로그인 필수, owner가 sole moderation authority.
- **Owner 모더레이션** — owner의 다른 사용자 공개 문서 하드 삭제(takedown). M2 P0에서 owner는 다른 사용자 문서에 특권 없음.
- **Undo-after-delete** — 30s tombstone 컬럼 + soft delete 사이클.

### P2 (별도 후속 마일스톤)
- Tags / categories — 폴더 계층이 유일한 조직 primitive.
- RSS / Atom 피드.
- 버전 이력 / diff 뷰.
- Engagement 기반 랭킹(view/like를 피드/RAG 정렬에 반영).
- 작성자별 공개 랜딩 페이지 `/by/{author}`.
- Math / Mermaid markdown 기능.

## Dependencies

- **요구:** M1 (Identity) 출시 완료 — 다중 작성자 식별, `X-User-Id` 헤더 인젝션, `PLAYGROUND_ANON` 쿠키.
- **요구:** M0 compose 업데이트 — **OpenSearch** 추가(`docs-v1` 인덱스 호스팅), Redis 이미 존재(M0). per-milestone ADR이 OpenSearch 버전/이미지 태그 핀(per spec §11 Q6).
- **요구:** per-milestone ADR-11(또는 다음 번호) — 라이브러리/버전 핀(BlockNote 버전, OpenSearch 클라이언트, Korean analyzer 선택, outbox 라이브러리, body size cap, anti-scrape rate limit), ADR-05(데이터 store) amendment(OpenSearch 2nd tier search store 추가), ADR-08(BC-to-BC) amendment(M3의 docs body fetch 메커니즘 — gateway-internal HTTP vs read-only DB role), ADR-09 amendment(`/api/docs/public/**` allowlist row를 새 `/api/docs/**` anonymous-OK 라우트로 갱신).
- **소비자(M2 close blocker 아님):** M3(RAG-Ingestion)이 `docs.document.*` 이벤트를 consume — M3의 이벤트 컨트랙트 의존성. 이 PRD는 이벤트 envelope/topic naming만 freeze하고 M3 실제 작업은 별도 마일스톤.
- **소비자(M2 close blocker 아님):** M4(RAG-Chat)이 작성자별 corpus를 검색 — M2의 `user_id` 스코프 보장에만 의존.

## Open questions for the implementer

Per spec §11 — per-milestone ADR(architect) + Stage-3 implementer가 해소할 사항. 여기 그대로 옮겨두어 PRD 리뷰어가 한 곳에서 본다.

1. **Outbox library / pattern** — Spring Modulith Events vs Debezium vs hand-rolled outbox table. Working direction: Spring Modulith Events JPA.
2. **M3 body fetch mechanism** — gateway-internal HTTP route vs. read-only DB role on the `docs` schema. ADR-08은 현재 BC-to-BC를 Kafka-only로 규정 — 이게 첫 정당화된 예외이며 ADR amendment 필요.
3. **Editor library — DECIDED: BlockNote** (https://www.blocknotejs.org). per-milestone ADR이 `@blocknote/core` + `@blocknote/react` 정확한 버전, MD-roundtrip adapter 설정, Next.js App Router SSR 전략을 핀. 번들 사이즈 + 접근성 audit도 ADR.
4. **Body size cap** — 구체적 수치(기본 ~1MB)와 강제 위치.
5. **공개 비-인증 read의 rate limit** — `/api/docs`(커뮤니티 피드)는 저렴(LLM 없음); `/api/docs/search?scope=public`은 anti-scrape per-IP soft cap이 필요. 정확한 수치는 ADR.
6. **OpenSearch 버전 + 클라이언트** — OpenSearch 2.x vs 1.x; Spring Data OpenSearch vs native low-level client. docker-compose 태그 + dependency tree 영향. dev는 single-node, prod도 단일 host.
7. **Korean analyzer** — `nori`(built-in) vs `seunjeon`; 한국어 문서 검색 품질에 영향.
8. **Metadata-only event topic** — title/visibility/path-only 변경에 대해 `docs.document.metadata-changed`를 추가할지(검색 projector가 `uploaded` 재발사 없이 동기화), 아니면 OpenSearch 내 title/path drift를 수용할지.
9. **Owner resolution path** — `docs` → `identity.users` cross-schema SELECT(단순, 스키마 격리 깨짐) vs identity HTTP call at boot(부팅 시 의존). ADR-08 amendment 범위에 영향.
10. **View dedup TTL** — 24h가 working default. 인증된 view는 anon cookie 대신 `X-User-Id`로 dedup해서 더 정확하게 할지? 현재: auth 상태 무관 anon-cookie 단일 경로.
11. **Counter sync 전략** — denormalized 컬럼 + nightly re-sync(현재 default) vs trigger-based 유지 vs `docs.document.engagement-*` 토픽 기반 event-sourced rebuild.
12. **익명 viewer 좋아요 ergonomics** — 익명에서 좋아요 버튼을 아예 숨길지, 비활성 상태로 노출할지. Working default: 비활성 노출 + "sign in to like" 툴팁.
13. **Undo-after-delete UX** — M2 P0: 토스트 표시되나 `Undo` 링크 비기능. M2.1: tombstone 컬럼 + 30s soft delete + tombstone flip으로 undo.
14. **OpenSearch authorName drift** — spec §4.2가 M2 P0에서 drift 수용(`authorName`이 index time denormalized, identity `display_name` 변경 시 자동 업데이트 없음). ADR이 `user.display-name-changed` 이벤트 구독 여부 결정.
15. **Folder picker UX at create time** — M2 P0는 path를 create 시에만 설정. picker가 에디터 header의 breadcrumb-style인지, first save modal인지, sidebar field인지는 Stage-2 designer 결정. 데이터 컨트랙트는 `CreateDocRequest.path?`.
16. **Owner moderation 표면 (M2.1+)** — M2.1에 owner takedown 라우트 도입 시 라우트 형태와 audit trail은 그 spec amendment에서. M2 P0는 라우트와 UI affordance 모두 생략.

---

> **PRD vs spec:** 이 문서는 사용자/리뷰어가 읽는 표면이다. DTO 필드 이름, DB 컬럼 정확한 타입, OpenSearch mapping JSON, 이벤트 envelope 정확한 필드 순서 같은 기술 컨트랙트는 spec(`docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md`)이 우선한다. PRD가 spec과 어긋나 보이면 spec을 따른다.
