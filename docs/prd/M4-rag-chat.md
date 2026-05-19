# PRD: M4 — RAG-Chat

> **Source of truth:** `docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md` (v1, 2026-05-18). 이 PRD는 사용자/리뷰어가 읽는 표면이고, 정확한 DTO 필드, SQL 컬럼, SSE 이벤트 grammar, retry 곡선의 ms 단위 값 같은 기술 컨트랙트는 spec이 우선한다. Section references like "spec §6.1"은 그 spec 안의 위치를 가리킨다.
>
> **Parallel work:** 이 PRD와 함께 묶이는 per-milestone ADR(provisionally **ADR-14** at `docs/adr/14-m4-rag-chat.md`)이 architect의 별도 세션에서 작성된다 — 라이브러리/버전/포트 핀, circuit breaker 라이브러리 선택, 모듈 quadruplet wiring, retry 곡선의 정확한 ms 값, 6개의 cross-doc amendment(spec §13)는 모두 ADR-14가 책임진다. Stage-2 design refresh(`docs/design/M4-rag-chat.md`)도 별도 in flight.
>
> **Important supersession note:** roadmap.md §M4의 현재 문구("any visitor — anonymous or signed-in")는 spec §2 + §13에 의해 **다시 한 번 재amend되어 auth-only 모델로 되돌아간다**. 이 PRD는 spec을 canonical로 취급한다. roadmap.md의 §M4 anon 관련 문구는 ADR-14 PR이 일괄 재수정한다.

## 한 줄 설명

인증된 사용자가 자신의 비공개 문서와 모든 작성자의 공개 문서를 대상으로 Perplexity 스타일 인용 기반 chat을 SSE 스트리밍 + inline citation accordion + top-tab 세션 관리 UX로 사용할 수 있는, **auth-only 단일 엔드포인트** RAG chat BC를 출시한다.

## Summary

M4는 playground의 첫 번째 **사용자 대면 LLM surface**다. M3가 pgvector(`rag.document_chunks`)를 `(user_id, visibility)` invariant 하에 source-of-truth로 유지하고 있고, `spark-inference-gateway`가 BGE-M3 임베딩과 Qwen3-32B 생성을 노출하고 있으며, M1이 `X-User-Id` 헤더 인젝션을 안정화한 직후 시점이라 이 PRD가 가능하다.

표면은 단일 streaming 엔드포인트(`POST /api/rag/chat`)와 세션 CRUD 라우트(`/api/rag/chat/sessions/**`), 그리고 `/chat` 페이지 하나로 구성된다. 인증된 사용자는 사이드바의 `Chat` 행으로 진입해 top-tab으로 parallel 대화 세션을 관리하고, 각 user turn에 대해 백엔드는 fresh per-turn retrieval(top-K from pgvector, `WHERE visibility='public' OR (user_id=? AND visibility='private')`)을 수행한 뒤 Spring AI의 `Flux<ChatResponse>`를 SSE의 `retrieval` → `token`* → `done`/`error` 이벤트 시퀀스로 forward한다. Assistant message의 `[N]` marker는 inline expandable accordion에 매핑되어 클릭 한 번에 원문 chunk excerpt와 source document로 이어진다.

비용 보호는 두 layer로 구성된다: (1) Redis token-bucket 기반 per-user rate limit(시간당 60 completion, 일일 200 completion, `max_tokens=4000`) + (2) `spark-inference-gateway` 5xx에 대한 Resilience4j circuit breaker. 동시 스트림은 user당 1로 cap이 걸리고, 두 번째 stream은 latest-wins로 첫 번째를 abort한다.

지금 출시하는 이유: M3가 chunk corpus를 준비해두었고, 사용자에게 "내 문서로 직접 질문" 시나리오를 제공하는 것이 playground의 핵심 차별화 surface다. P0는 desktop only, semantic-only retrieval, 한국어/영어 mix 본문 지원이며, mobile / hybrid retrieval / 대화 export 등은 M4.1로 미룬다.

**Out of scope re-revision:** 직전 사이클의 roadmap §M4 amend("anonymous or signed-in")는 본 사이클에서 **다시 뒤집힌다** — anon chat은 P2로 영구 추방되었고, `POST /api/rag/chat/public` 라우트는 ADR-09 allowlist에서 제거된다. 이는 비용 ceiling(unauth caller가 GPU 예산을 drain할 수 없도록)을 위한 의도적 narrowing이며, 자세한 이유는 spec §2 + §13.

## User personas

M4는 **인증 사용자 전용**이다. Anon 페르소나는 spec §2에 의해 명시적으로 제거되었다 — `/chat`에 unauth로 접근하면 server-side에서 `/login?return=/chat`로 redirect되고, `POST /api/rag/chat`은 `X-User-Id` 부재 시 401을 반환한다.

| 페르소나 | 핵심 동기 | M4에서 가능한 것 |
|---|---|---|
| **Authenticated end-user (primary)** | 자신의 비공개 문서 + 커뮤니티 공개 문서를 대상으로 자연어 질문을 던지고, 인용된 근거를 한 번에 따라가고 싶음 | `/chat` 진입 → top-tab으로 parallel 세션 관리 → user turn 입력 → streaming 답변 + inline citation accordion → `[N]` 클릭 한 번으로 원문 chunk excerpt와 source doc 페이지로 이동 |
| **Operator (= 프로젝트 오너 / dev)** | LLM 비용이 통제되고, 게이트웨이 장애 시 우아하게 fallback하며, 사용량/latency/오류율을 한눈에 볼 수 있기를 원함 | Redis token-bucket 기반 per-user rate limit, Resilience4j circuit breaker, Micrometer 메트릭(`playground.rag_chat.tokens`), 상태 전이 구조화 로그, M5에서 폴링 가능한 표면 |

오너(JeekLee)는 M4에서 일반 authenticated user와 구분되지 않는다 — public + 자신의 private corpus라는 동일한 retrieval 범위를 가진다. (M2의 "owner-curated home"과 같은 owner special-case는 chat 표면에는 적용되지 않는다.)

**No anonymous persona by design.** 익명 chat은 spec §2의 P2로 추방되었고, 공개 corpus 탐색은 M2의 `/docs` 피드 + `/docs/search?scope=public`이 담당한다.

## User stories with acceptance criteria

User story는 spec §12의 5개 acceptance 서브섹션(Auth + tenant isolation / Streaming protocol / Conversation persistence / UX / Cost protection)을 페르소나 voice로 풀어낸 것이다.

### Auth + tenant isolation

#### Story 1 — Unauthenticated callers receive 401
> As the operator, I want unauthenticated requests to `/api/rag/chat` to receive 401 so that the cost ceiling isn't bypassed and the auth-only invariant holds at the gateway.

- [ ] `X-User-Id` 헤더 없이 `POST /api/rag/chat`을 호출하면 401이 반환된다 (per spec §5.1, §12 "Auth + tenant isolation").
- [ ] ADR-09 §"Route classification"의 `/api/rag/chat/public` 행이 제거되고, `/api/rag/chat`, `/api/rag/chat/sessions/**`, `GET /api/rag/chat/sessions/*/messages`가 모두 `authenticated` 분류로 등록된다 (per spec §8.1).
- [ ] 게이트웨이 통합 테스트가 unauth POST → 401을 명시적으로 단언한다.
- [ ] Anon 사용자가 `/chat` 경로로 직접 navigation 시 Next.js middleware가 server-side에서 `/login?return=/chat`로 302 한다 — chat UI flicker가 발생하지 않는다 (per spec §7.6).

#### Story 2 — Cross-tenant private docs are never retrievable
> As an authenticated user, I want my private documents to be retrievable only by me, never by another caller, so that tenant isolation is enforced at the SQL layer.

- [ ] pgvector retrieval SQL은 `WHERE visibility = 'public' OR (user_id = $X_USER_ID AND visibility = 'private')` 절을 사용한다 (per spec §6.1 step 6).
- [ ] 통합 테스트: 두 사용자 A, B가 각각 private chunk를 seed → A의 `X-User-Id`로 chat 요청 시 B의 private chunk는 retrieval 결과에 절대 포함되지 않는다 (per spec §12 "Auth + tenant isolation").
- [ ] `chat.sessions`와 `chat.messages`의 모든 read는 SQL `WHERE user_id = ?` 절을 거친다 — application-layer 필터링이 아닌 SQL-level guard.

#### Story 3 — Session 소유권 검증
> As an authenticated user, I want my chat sessions to be inaccessible to anyone else, so that another user can't read, rename, or delete my conversations even if they guess a UUID.

- [ ] `POST /api/rag/chat`의 body `sessionId`가 `chat.sessions.user_id != X-User-Id` 또는 row 부재일 경우 404를 반환한다 (per spec §5.1). 403이 아닌 404 — 존재 누설 금지.
- [ ] `GET /api/rag/chat/sessions`는 호출자의 세션만 반환한다 (`WHERE user_id = X-User-Id`).
- [ ] `PATCH` / `DELETE /api/rag/chat/sessions/{id}`도 동일하게 not-owned → 404.
- [ ] `GET /api/rag/chat/sessions/{id}/messages`도 동일하게 not-owned → 404.

### Streaming protocol

#### Story 4 — TTFT는 빠르게
> As an authenticated user, I want responses to start streaming within ~2 seconds so that I see progress immediately and the chat feels responsive rather than stuck.

- [ ] Time-to-first-token (TTFT) P95 ≤ 2.0 s — 정의: K=6 retrieval against ~50k-chunk corpus + 1024-dim 임베딩 + Qwen3-32B 첫 토큰까지의 wall clock (per spec §10).
- [ ] TTFT 초과 시 WARN 로그를 emit한다 — operator가 latency degradation을 사후 추적할 수 있도록.
- [ ] `retrieval` SSE 이벤트는 첫 번째로, 그리고 항상 emit된다 (citation이 비어있어도) — 프론트엔드는 이 이벤트의 도착으로 "Thinking…" indicator를 dismiss한다 (per spec §5.2).

#### Story 5 — SSE 이벤트 순서가 결정적
> As the operator, I want the SSE stream to emit events in a deterministic order so that the frontend can rely on the protocol without speculative state.

- [ ] 응답의 `Content-Type`은 `text/event-stream`이고, 정확히 한 개의 `retrieval` 이벤트가 어떤 `token` 이벤트보다도 먼저 emit된다 (per spec §12 "Streaming protocol").
- [ ] 정상 완료 시 정확히 한 개의 `done` 이벤트가 스트림을 종료한다.
- [ ] 어떤 실패에 대해서도 정확히 한 개의 `error` 이벤트가 스트림을 종료하며, 이 경우 `done`은 emit되지 않는다.
- [ ] `done` 이벤트는 persisted `messageId`와 `tokensIn` / `tokensOut`을 carry한다 (per spec §5.2).

#### Story 6 — End-to-end 스트림 P95 cap
> As an authenticated user, I want a full response (up to `max_tokens=4000`) to complete within ~20 seconds so that I don't have to context-switch waiting for the answer.

- [ ] End-to-end stream P95 ≤ 20 s for `max_tokens=4000` completion (per spec §10).
- [ ] 초과 시 WARN 로그 + Micrometer metric (정확한 metric 이름은 ADR-14에서 핀).

#### Story 7 — Client abort propagates server-side
> As an authenticated user, I want closing the browser tab mid-stream to immediately stop the server from spending more tokens on my behalf.

- [ ] 클라이언트가 SSE 연결을 close하면 server-side에서 Spring AI 구독이 cancel되어 LLM 호출이 중단된다 (per spec §6.1 step 14, §12 "Streaming protocol").
- [ ] 이 경우 assistant message는 persist되지 않는다 — page reload 시 user message만 남고 assistant message는 사라진다.
- [ ] `ABORTED` 이벤트는 클라이언트가 이미 떠난 상태이므로 emit되지 않고, 서버 로그에만 기록된다 (per spec §6.5).
- [ ] (Open question — ADR-14에서 핀) Spring AI cancellation이 게이트웨이-측 generation도 실제로 중단시키는지 vs 게이트웨이는 끝까지 토큰을 생성하고 dropped되는지는 spec §11 #14 참조.

### Conversation persistence

#### Story 8 — 세션이 브라우저 세션을 가로질러 보존됨
> As an authenticated user, I want my conversations to persist across browser sessions so that I can come back tomorrow and find my chat exactly where I left it.

- [ ] `chat.sessions` 행은 user의 sign-out 또는 브라우저 close에도 유지된다 — TTL 없음, hard delete만이 삭제 경로다 (per spec §4.1).
- [ ] 재진입 시 사이드바 `Chat` → `/chat`은 가장 최근 `updated_at`의 세션을 default로 연다.
- [ ] `GET /api/rag/chat/sessions/{id}/messages`로 세션의 메시지 + citation history 전체를 로드할 수 있고, 같은 user message → assistant message 순서가 `created_at` 클럭과 일치한다 (per spec §12 "Conversation persistence").

#### Story 9 — Citation은 [N] 마커와 함께 영속화됨
> As an authenticated user, I want the citations I see during streaming to still be there when I reload the page, so that the `[N]` markers don't become dangling references.

- [ ] `chat.message_citations`는 assistant message commit 시점에 함께 insert되며 `(message_id, position)`이 PK다 (per spec §4.1).
- [ ] Page reload 후 같은 assistant message의 `[N]` marker가 같은 citation 카드로 expand된다.
- [ ] Reverse index `chat_message_citations_by_document` on `document_id`가 존재한다 — 후속 "이 문서가 어떤 대화에서 인용되었나" 조회를 enable.
- [ ] Cite-persistence policy(모든 retrieved chunk를 저장 vs LLM이 실제로 `[N]`을 출력한 것만 저장)는 ADR-14에서 결정되며, working assumption은 "실제로 출력된 것만"이다 (per spec §11 #10).

#### Story 10 — Session DELETE는 cascading hard delete
> As an authenticated user, I want deleting a session to permanently remove all its messages and citations atomically, so that I don't worry about ghost data.

- [ ] `DELETE /api/rag/chat/sessions/{id}` → `DELETE FROM chat.sessions WHERE id = ? AND user_id = ?` 단일 statement로 messages → citations가 CASCADE 제거된다 (per spec §4.4).
- [ ] 부분 삭제 상태(messages는 사라졌는데 session row가 남음 또는 그 반대)는 발생하지 않는다.
- [ ] 호출 자체는 idempotent — 이미 삭제된 세션의 DELETE는 404를 반환한다 (소유자가 아니거나 row 부재).

#### Story 11 — 첫 user turn에서 auto-title이 fire-and-forget
> As an authenticated user, I want my session's title to be auto-derived from my first user message so that I don't have to manually rename every conversation.

- [ ] 첫 user message commit 직후 Qwen3-32B에 대한 fire-and-forget 호출이 2–6 word 요약을 생성해 `chat.sessions.title`을 update한다 (per spec §2 + §11 #6).
- [ ] Auto-title 실패 시 `title`은 기본값 `'New chat'`으로 남는다 — chat 자체의 응답에는 영향이 없다.
- [ ] 사용자는 tab 위의 `⋯` 메뉴에서 수동 rename 가능 (`PATCH /api/rag/chat/sessions/{id}` body `{title: "..."}`)하며 수동 rename은 auto-title을 영구히 override한다.

### UX

#### Story 12 — Top-tab으로 parallel 세션을 관리
> As an authenticated user, I want to organize parallel conversations as top tabs so that I can flip between "asking about ADR drafts" and "drafting a roadmap" without losing context.

- [ ] `/chat`의 상단에 active/inactive 구분이 있는 tab strip이 렌더되고, `+` 버튼으로 새 세션이 head에 insert된다 (per spec §7.2).
- [ ] 최대 7개 visible tab + `▾ N more` 드롭다운으로 8번째 이후 세션을 노출하며, 드롭다운에서 선택하면 strip의 head로 끌어올린다.
- [ ] Tab hover 시 `⋯` 메뉴가 reveal되어 Rename / Delete 액션 노출 — Rename은 inline editing, Delete는 확인 dialog.
- [ ] Tab strip은 사이드바를 사용하지 **않는다** — 세션 list는 강요하지 않는 top-tab 방식 단일 표면만 P0 (per spec §2 "No sidebar-based session list").

#### Story 13 — Inline citation accordion
> As an authenticated user, I want each cited claim to be expandable to the source excerpt inline so that I don't have to context-switch to a side panel to verify the answer.

- [ ] Assistant message 본문의 `[N]` marker는 superscript pill로 렌더되고, 메시지 직하단의 collapsible accordion `▾ Citations · N`에 매핑된다 (per spec §7.3).
- [ ] Accordion expand 시 카드 per citation: `[n] Document title`, 약 160자 chunk excerpt, `↗ open` link to `/docs/{documentId}` (chunk anchor `#chunk-{chunkIndex}`는 M4.1).
- [ ] Stale citation(문서가 사후 삭제됨, JOIN이 null title 반환)은 `[n] (deleted) — 이 문서는 더 이상 사용할 수 없습니다`로 graceful degrade하며 `↗ open` link는 사라진다 (per spec §4.3, §7.3).
- [ ] N = 0인 retrieval(`RETRIEVAL_EMPTY`)인 경우 accordion은 "Citations · none"으로 렌더되고, expand 시 "(no citations — answer was unsupported)" 안내 — 답변 자체는 계속 stream된다 (per spec §6.5, §7.5).
- **M3.1 amendment note (2026-05-19):** `rag.document_chunks`에 `heading_path text[]` 컬럼이 추가되었다 (ADR-13 §1 amendment). citation card가 향후 `[n] Document title · API > Auth` 같은 섹션 breadcrumb을 렌더할 수 있다. M4 frontend가 이 컬럼을 retrieval response payload에 포함시키고 카드 UI에 표시하는 작업은 M4 follow-up — 이 PRD의 P0 acceptance criteria에는 영향 없음.

#### Story 14 — Stop affordance during streaming
> As an authenticated user, I want to interrupt a long-running response so that I can cancel a bad turn and rephrase without waiting it out.

- [ ] Streaming 중 composer의 Send 버튼이 Stop으로 토글되고, 메시지 내부에도 Stop이 노출된다 (per spec §7.4).
- [ ] Stop 클릭 → SSE abort within P95 ≤ 200 ms of click (per spec §12 "UX").
- [ ] Partial assistant message는 persist되지 **않는다** — 부분 텍스트는 greyed out + "Generation stopped" footer로 표시되며 페이지 reload 시 사라진다 (per spec §6.1 step 12, §11 #13).
- [ ] Tab switch during stream도 같은 abort 경로를 trigger한다 — 부분 메시지는 persist되지 않는다 (per spec §7.2).

#### Story 15 — 명확한 오류 banner
> As an authenticated user, I want a clear banner when the AI service is unavailable rather than a silent hang, so that I know whether to retry or wait.

- [ ] `503 GATEWAY_DOWN` SSE `error` → composer 위에 빨간 banner "AI service is currently unavailable." + `Retry last message` 버튼(같은 user message 재제출) (per spec §7.5).
- [ ] `429 RATE_LIMIT` SSE `error` → 노란 banner "You've hit your hourly limit. Try again in M minutes." + `retryAfter` 카운트다운; 카운트다운이 0에 도달할 때까지 composer 비활성화.
- [ ] `RETRIEVAL_EMPTY`(citations: [])는 오류가 아니다 — stream은 정상 진행되고 system prompt가 "I couldn't find relevant chunks for that" 응답을 유도한다.
- [ ] Session 404(다른 브라우저 세션에서 삭제된 stale tab) → 토스트 "This conversation no longer exists." + tab 제거 + 가장 최근 세션 또는 빈 `/chat`으로 redirect.

#### Story 16 — Empty / loading state는 명시적
> As an authenticated user opening a fresh session, I want to see a clear empty state with starter suggestions so that I have a starting point instead of a blank canvas.

- [ ] 새로 만든 세션 또는 모든 메시지가 삭제된 세션 → centered card "What do you want to know about your corpus?" + 3개 static suggestion chip — chip 클릭은 composer를 pre-fill하고 auto-send는 하지 않는다 (per spec §7.5).
- [ ] 3개 static suggestion 문자열은 ADR-14에서 핀 (per spec §11 #12).
- [ ] User submit → 첫 `retrieval` event 사이는 "Thinking…" spinner가 assistant 메시지 자리에 표시된다.
- [ ] Streaming 중 `token` event가 흐르는 동안 pulsing block cursor `▍`가 assistant text 끝에 렌더된다.

#### Story 17 — Sidebar `Chat` 행이 M4 deploy 시 unlock
> As the operator, I want the sidebar `Apps` row "Chat" to unlock automatically on M4 deploy so that the lock-vs-shipped UX convention from M1/M2 carries over.

- [ ] 로그인 상태 + M4 deployed → 사이드바 `Chat` 행이 active text + 배지 없음, 클릭 시 `/chat` (per spec §7.7).
- [ ] 로그아웃 상태 → 사이드바 `Chat` 행은 muted + `🔒 Sign in` 배지, 클릭 시 `/login?return=/chat`.
- [ ] 미shipped 후속 마일스톤(예: M5 Metrics) → muted + `🔒 M5` 배지, 클릭은 no-op (기존 컨벤션과 동일).
- [ ] ADR-09 amendment에서 `🔒 Sign in`이 세 번째 배지 상태로 등록된다 (auth-lock vs milestone-lock 구분 — per spec §8 + §13).

#### Story 18 — 데스크톱 only (P0)
> As an authenticated user on a tablet/phone, I should see a clear "desktop only for now" message rather than a broken mobile layout, so that the experience degrades honestly.

- [ ] 뷰포트 ≥ 720 px에서만 chat UI가 활성화된다 (per spec §2, §7.1).
- [ ] ≤ 719 px → "Chat is desktop-only for now" 안내 카드 (정확한 copy는 Stage-2 designer 결정). 모바일 풀 레이아웃은 M4.1.

### Cost protection

#### Story 19 — Per-user rate limit으로 GPU 예산을 보호
> As the operator, I want a per-user rate limit so that a single user can't drain the GPU budget by hammering chat in a loop.

- [ ] Redis 기반 token-bucket이 user당 시간당 60 completion + 일일 200 completion을 cap한다 (per spec §8.2 working numbers; ADR-14가 정확한 값 핀).
- [ ] Bucket이 empty면 429 + `Retry-After` 헤더를 반환한다 (per spec §5.1, §12 "Cost protection").
- [ ] Per-completion token cap `max_tokens=4000` + K=6 retrieval (per spec §8.2).
- [ ] Bucket 식별자는 `userId`(또는 `userId + sub`)이며 정확한 키는 ADR-14에서 핀 (per spec §11 #17).

#### Story 20 — Circuit breaker로 gateway 장애를 격리
> As the operator, I want a circuit breaker on the inference gateway so that a sustained 5xx burst doesn't pin every user request indefinitely.

- [ ] Resilience4j 기반 circuit breaker가 `spark-inference-gateway`(BGE-M3 + Qwen3-32B 양쪽) 호출에 걸려 있다 (per spec §6.4 working numbers).
- [ ] 최근 60초 실패율 > 50% → OPEN, 30초간 모든 후속 요청에 대해 즉시 `503 GATEWAY_DOWN` 응답.
- [ ] OPEN 만료 후 HALF_OPEN으로 전환되고 단일 probe 요청의 결과에 따라 CLOSED 또는 다시 OPEN으로 진입.
- [ ] Resilience4j 메트릭(`circuit_breaker_state`, `circuit_breaker_calls`)이 Micrometer로 노출된다 (per spec §10).

#### Story 21 — 동시 stream cap으로 단일 user의 폭주를 막음
> As the operator, I want a per-user concurrent stream cap so that one user opening 10 tabs and submitting simultaneously can't 10× their cost.

- [ ] User당 동시 streaming `POST /api/rag/chat`은 1로 cap된다 — 두 번째 동시 요청은 latest-wins로 첫 번째를 abort한다 (per spec §10).
- [ ] Lock backing은 Redis (Redisson — M3의 `@GlobalLock` 패턴과 동일) (per spec §10).
- [ ] Abort된 첫 stream은 partial assistant message가 persist되지 않는다 — Story 14의 Stop 경로와 동일.

#### Story 22 — Token usage가 메트릭으로 노출됨
> As the operator, I want every completed turn to record its token usage so that I can correlate cost spikes with users / time-of-day in the M5 dashboard.

- [ ] 모든 `done` event는 `tokens_in + tokens_out`을 Micrometer counter `playground.rag_chat.tokens` (tag: `userId`)로 기록한다 (per spec §10).
- [ ] `chat.messages.tokens_in / tokens_out` 컬럼이 assistant row마다 채워진다 (user row는 NULL).
- [ ] M5 (Metrics) 대시보드가 이 metric을 폴링하는 것은 M5의 일이며 M4 close blocker가 아니다.

## UX surfaces

Per spec §7 — 모든 시각 처리는 Stage-2 design doc에서 결정.

| Route | Auth | Purpose |
|---|---|---|
| `/chat` | required | Chat 페이지. 진입 시 가장 최근 `updated_at` 세션 또는 빈 새 세션. Top-tab strip + message list + composer. |
| `/login?return=/chat` | n/a | Anon이 `/chat` 또는 `Chat` 사이드바 행으로 접근 시 redirect 대상 |

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/rag/chat` | required | 단일 streaming chat endpoint (SSE). Body `{sessionId, message}`. |
| `POST /api/rag/chat/sessions` | required | 빈 세션 생성. |
| `GET /api/rag/chat/sessions` | required | 호출자의 세션 list. |
| `PATCH /api/rag/chat/sessions/{id}` | required | 세션 rename. |
| `DELETE /api/rag/chat/sessions/{id}` | required | Hard delete + CASCADE. Idempotent. |
| `GET /api/rag/chat/sessions/{id}/messages` | required | 메시지 + citation history 로드. |

사이드바 `Chat` 행 처리(per spec §7.1, §7.7)는 M1에서 잠금/잠금해제 메커니즘이 이미 자리잡혀 있으며, M4 ship 시점에 `Chat` 행이 잠금 해제된다. Logged-out 상태에서는 `🔒 Sign in` 배지로 표시된다.

## Bounded Context: RAG-Chat

- **책임 (Responsibility):** 인증된 사용자의 chat 세션, 메시지, citation link를 소유한다. Per-turn으로 query embedding, pgvector retrieval, prompt assembly, Qwen3-32B streaming generation을 수행한다. **Documents (M2)나 chunks (M3)의 write는 소유하지 않는다 — read-only로 cross-schema SELECT만 한다.** Identity (M1)도 소유하지 않는다.
- **핵심 엔티티 (Core entities — `chat` schema, source of truth):**
  - `Session` (`chat.sessions`): `id`, `user_id`, `title`, `created_at`, `updated_at`. PK `id`. Index `(user_id, updated_at DESC)`. Per spec §4.1.
  - `Message` (`chat.messages`): `id`, `session_id`, `user_id` (denormalized for fast tenant filter), `role IN ('user', 'assistant')`, `content`, `tokens_in`, `tokens_out`, `retrieval_k`, `created_at`. PK `id`. Index `(session_id, created_at)`. Assistant `content`는 `[N]` marker를 포함할 수 있음.
  - `Citation` (`chat.message_citations`): `message_id`, `position` (1-indexed), `document_id`, `chunk_index`. PK `(message_id, position)`. Reverse index on `document_id`.
- **소비 이벤트 (Consumed events):** **없다.** M4는 어떤 Kafka 토픽도 consume하지 않는다.
- **발행 이벤트 (Published events):** **없다.** M4는 어떤 Kafka 토픽도 publish하지 않는다.
- **외부 인터페이스 (Public HTTP):** `POST /api/rag/chat` (streaming) + `/api/rag/chat/sessions/**` CRUD. 모두 게이트웨이를 통해 인증된 호출자에게만 노출. Per spec §5.
- **내부 인터페이스 (compose-internal):**
  - **Cross-schema SELECT (read-only):**
    - `rag.document_chunks` (M3-owned): retrieval SQL의 source. `WHERE visibility = 'public' OR (user_id = $X_USER_ID AND visibility = 'private')` + cosine ORDER BY + LIMIT K. Per ADR-05 amendment in ADR-14.
    - `docs.documents` (M2-owned): citation enrichment용 `title` + `visibility` resolve. M2 owner-lookup 패턴(ADR-08 §"Exception 3")이 working template이지만 정식 결정은 ADR-14.
    - `identity.users` (M1-owned): 호출자의 `display_name` + `avatar_url` lookup (chat header 렌더용). Cross-schema SELECT vs internal HTTP는 ADR-14에서 핀 (per spec §3, §11 #3).
  - **outbound HTTP:** `spark-inference-gateway` (BGE-M3 dense 1024-dim embedding + Qwen3-32B chat completions). Spring AI 1.0 GA의 OpenAI-compatible `ChatClient` + `EmbeddingModel`. Per ADR-04.
  - **Redis:** token-bucket counters per user + per-user concurrent stream lock (Redisson `RLock`).
  - **Postgres (own schema `chat`):** `chat.sessions`, `chat.messages`, `chat.message_citations`. 다른 BC schema에 write 금지 (ADR-05).
  - **Kafka:** 사용하지 않는다. M4는 request-response BC이며 conversation history가 유일한 persistent state다 — Kafka 컨슈머가 필요하지 않다.

**Forward note for the architect:** M4가 어떤 이벤트도 publish/consume하지 **않는다**는 사실은 의도적이다. ADR-14는 M4를 위한 새 토픽을 도입해서는 안 된다 — M5 metrics는 `/actuator/metrics`를 폴링하므로 별도 Kafka 컨트랙트 불필요.

## Non-functional requirements

Per spec §10에서 PRD 음성으로 재진술 — ship 직전 통과해야 함.

- **Tenant isolation invariant:** 모든 인증 read는 `WHERE user_id = ?` 절을 통과한다. Retrieval SQL의 `OR (user_id = $X_USER_ID AND visibility = 'private')`는 application-layer concat이 아닌 parameterized query여야 하며, 통합 테스트가 cross-tenant leak이 없음을 단언한다.
- **Schema-per-BC invariant (ADR-05):** M4는 자신의 `chat` schema에만 write한다. `rag.document_chunks`와 `docs.documents`는 SELECT-only로만 접근한다 — cross-schema write 금지.
- **Auth-only invariant (ADR-09 amendment):** `/api/rag/chat`과 모든 세션 CRUD 라우트는 `X-User-Id` 없이 호출 시 401. anon allowlist에 한 행도 추가하지 않는다 — spec §13의 supersession 의도다.
- **Time-to-first-token (TTFT) P95 ≤ 2.0 s** for the median query (K=6 retrieval against ~50k-chunk corpus + 1024-dim 임베딩 + Qwen3-32B 첫 토큰). 초과 시 WARN 로그.
- **End-to-end stream P95 ≤ 20 s** for `max_tokens=4000` completion.
- **Session list load P95 ≤ 200 ms** (GET `/api/rag/chat/sessions`).
- **Concurrent stream cap per user: 1.** 두 번째 동시 `POST /api/rag/chat`은 첫 번째를 abort (latest-wins). Backend는 Redis(Redisson) per-user lock으로 enforce — M3의 `@GlobalLock` 패턴과 동일.
- **Cost telemetry:** 모든 `done` event → Micrometer counter `playground.rag_chat.tokens` (tag: `userId`). M5에서 폴링.
- **Observability:** 모든 상태 전이(turn start, retrieval done, stream start, stream end, stream abort, stream error) → INFO 구조화 로그(`userId`, `sessionId`, `messageId`, `eventType`, `tokensIn`, `tokensOut`, `latencyMs`).
- **Resilience4j 메트릭:** `circuit_breaker_state{name=spark-gateway}`, `circuit_breaker_calls{kind=successful|failed|not_permitted}` 노출.
- **Stale citation graceful degrade:** M3가 문서 삭제 후 chunk를 purge해도 `chat.message_citations` 행은 남아 있으며, 읽기 시 join이 null title을 반환하면 `(deleted)` 상태로 렌더 — 답변 자체가 broken되지 않는다 (per spec §4.3, §7.3).
- **Per-turn fresh retrieval:** 매 user turn마다 현재 메시지를 다시 임베딩하고 fresh top-K를 retrieval한다 — 이전 turn의 retrieved chunk를 carry forward하지 않는다 (per spec §6.1 step 5).
- **Context window budget (working):** 32k Qwen3-32B context를 (system ~200) + (retrieved K=6 × ~400 = 2400) + (history ~24576) + (assistant max_tokens 4000) ≈ 31176 ≈ 32k로 분할 (per spec §6.3). 정확한 분할은 ADR-14.

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 전체 체크리스트, per spec §12 미러링.

### Auth + tenant isolation
- [ ] `POST /api/rag/chat`을 `X-User-Id` 없이 호출하면 401을 반환한다. ADR-09 allowlist가 업데이트되고 게이트웨이 통합 테스트가 이를 단언한다.
- [ ] `POST /api/rag/chat`을 호출자 소유가 아닌 `sessionId`로 호출하면 404를 반환한다 (existence 누설 금지를 위해 403이 아닌 404).
- [ ] `GET /api/rag/chat/sessions`는 `user_id = X-User-Id`인 세션만 반환한다.
- [ ] Retrieval SQL이 `WHERE (visibility='public') OR (user_id = $X_USER_ID AND visibility='private')` 절을 포함한다 — parameterized 통합 테스트가 다른 사용자의 private chunk가 절대 returned되지 않음을 단언한다.

### Streaming protocol
- [ ] 응답이 `Content-Type: text/event-stream`이고, 정확히 한 개의 `retrieval` 이벤트가 어떤 `token` 이벤트보다도 앞서 emit된다.
- [ ] 정상 완료 시 정확히 한 개의 `done` 이벤트가 stream을 종료한다.
- [ ] 어떤 실패에 대해서도 정확히 한 개의 `error` 이벤트가 stream을 종료하고 `done`은 emit되지 않는다.
- [ ] Client disconnect(SSE `close`) → server-side abort(Spring AI 구독 cancel); assistant message는 persist되지 않는다.

### Conversation persistence
- [ ] `chat.messages` insert 순서가 `created_at` 클럭과 일치하며, reload 시 identical history가 렌더된다.
- [ ] `chat.message_citations` 행은 PK `(message_id, position)` + reverse index on `document_id`로 persist된다.
- [ ] Session DELETE는 messages → citations로 atomically CASCADE한다.
- [ ] Auto-title이 첫 user turn commit 후 fire-and-forget으로 trigger되며, 실패 경로에서는 `title = 'New chat'`이 그대로 남는다.

### UX
- [ ] 사이드바 `Apps` "Chat" 행이 M4 deploy 시 unlock된다 (M1 design doc + frontend implementer 업데이트).
- [ ] `/chat`은 server-side auth-gated이며 anon GET은 `/login?return=/chat`로 302 redirect된다.
- [ ] Citation accordion이 assistant message 직하단에 inline expand되며 별도 side panel은 없다.
- [ ] Stream 중 Stop 버튼 클릭 → P95 ≤ 200 ms 내에 SSE abort.
- [ ] Stream 중 tab switch → 진행 중인 stream을 abort하고 target session으로 전환한다.

### Cost protection
- [ ] Rate-limit bucket empty → 429 + `Retry-After` 헤더.
- [ ] Circuit breaker OPEN → 503 with body `{"code":"GATEWAY_DOWN"}`; 설정된 duration 후 HALF_OPEN으로 전이; 단일 probe call의 결과로 CLOSED 또는 재OPEN.
- [ ] Per-user concurrent stream cap = 1; 두 번째 stream은 첫 번째를 latest-wins abort한다.
- [ ] 모든 `done` event → `playground.rag_chat.tokens` Micrometer counter (tag `userId`)가 `tokens_in + tokens_out`을 record한다.

### Cross-milestone (traceability — non-blocking for M4 close)
- [ ] ADR-09 amendments(spec §8 + §13)가 M4 ADR PR(ADR-14)에 적용된다 — `/api/rag/chat/public` 행 제거, rate-limit 섹션 rename + 새 auth-only 수치, `🔒 Sign in` 배지 컨벤션 추가.
- [ ] spec `2026-05-16-m2-docs-bc-design.md` §8 amendment 블록이 재amend되어 "anonymous caller: WHERE visibility='public'" bullet이 제거된다 (per spec §13).
- [ ] `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract"의 anon 행이 제거된다 (per spec §13). M3 chunk schema invariant `(user_id, visibility)` non-null는 변하지 않는다.
- [ ] `docs/roadmap.md` §M4가 재amend되어 "any visitor" → "signed-in user only"로 되돌아간다 (per spec §13).
- [ ] ADR-04에 ChatClient streaming(`Flux<ChatResponse>`)이 rag-chat에 의해 exercise됨을 명시하는 informational 노트가 추가된다 (per spec §13).
- [ ] ADR-05에 `chat` schema 정의 + cross-schema-SELECT-from-rag-chat-to-rag+docs 노트가 amendment로 추가된다 (per spec §13).
- [ ] Manual E2E: 인증 사용자가 자신의 private 문서를 업로드 → M3 임베딩 완료 → M4 chat에서 그 문서가 인용된 답변. M4 close blocker가 아니다.

## Out of scope

### M4.1 (same milestone bucket, ship if cycle has slack)
- **Mobile layout (≤719 px).** 세로 stack, citation accordion default collapsed, tab strip → 드롭다운 (per spec §2 deferred).
- **Dynamic empty-state suggestions** sourced from the caller's recent docs (P0는 3개 static suggestion).
- **Hybrid retrieval (BM25 from OpenSearch + cosine from pgvector, RRF fusion).** M4 P0는 semantic-only.
- **Conversation export (download as Markdown).** 단일 세션을 .md 파일로 export.
- **Multi-turn retrieval** — 현재 user message만이 아니라 마지막 N개 turn을 concatenate해서 embed하는 변형. M4 P0는 current-turn-only.
- **Chunk-anchor citation link** — `/docs/{documentId}#chunk-{chunkIndex}` fragment 해석. M4 P0는 `/docs/{documentId}`까지만.

### P2 (별도 후속 마일스톤 / 다음 사이클)
- **Anonymous chat (removed in this cycle).** spec §2 + §13에 의해 P2로 추방. 직전 roadmap §M4의 "any visitor — anonymous or signed-in" 문구를 본 사이클이 **다시 뒤집어** auth-only로 되돌린다. 이유: 비용 ceiling(unauth caller가 GPU 예산을 drain할 수 없도록). 공개 corpus 탐색은 M2의 `/docs` 피드 + `/docs/search?scope=public`이 담당한다. M4 ADR PR이 ADR-09의 `/api/rag/chat/public` 행과 anon rate-limit 블록을 제거한다.
- **Public RAG chat against owner's docs only** ("ask jeeklee about their notes" mode — 원래 ADR-09 framing). 명시적 non-goal.
- **Custom personas / system prompts.** P0의 system prompt는 고정.
- **Tool-using agents / function calling.** M6+ Agents 마일스톤의 영역.
- **File uploads inside the chat surface** ("paste a doc here"). 문서 업로드는 M2의 `/docs/new` 표면이 유일한 경로.
- **Multi-modal generation** (images, audio).
- **Regenerate / copy assistant message** post-stream 액션. `done` event의 `messageId`를 leveraging해서 가능하지만 P1 후보.
- **Conversation summarization (in-line)** — context window가 가득 차면 오래된 turn을 요약 paragraph로 replace. P0 default는 drop oldest turns (per spec §6.3, §11 #9).

## Dependencies

- **요구:** M0 (Bootstrap) — compose stack(Postgres + pgvector + Kafka + Redis), `host.docker.internal:10080`이 `spark-inference-gateway`로 라우팅되는 `extra_hosts` 설정, Spring AI 1.0 GA infrastructure (per ADR-04).
- **요구:** M1 (Identity) 출시 완료 — `X-User-Id` 헤더 인젝션, OAuth 흐름, 사이드바 `Apps` 행의 lock/unlock 메커니즘.
- **요구:** M2 (Docs) — `docs.documents`의 `title` + `visibility`가 citation enrichment를 위한 cross-schema SELECT 표적으로 안정화됨. OpenSearch는 P0에는 사용하지 않으며(hybrid retrieval은 M4.1) 미래의 BM25 path를 위한 optional 의존성으로만 인식.
- **요구:** M3 (RAG-Ingestion) — `rag.document_chunks`의 `(user_id, visibility)` invariant가 항상 carry. M4 retrieval은 이 invariant에 의존하며, M3의 chunk row가 partial-write 상태로 가시화되지 않음에 의존한다. M3의 ingestion-complete signal mechanism이 무엇으로 결정되든(이벤트 vs DB flag vs 신호 없음, per M3 PRD Story 10) M4가 P0에서 active하게 의존하지는 않는다 — chat은 chunk를 단순 SELECT하므로 "아직 안 indexed된 doc"은 단지 retrieval에서 missing되어 빈 `RETRIEVAL_EMPTY`로 graceful degrade한다.
- **요구:** ADR-14 (M4 per-milestone ADR) — 라이브러리/버전/포트 핀(`rag-chat-api` 18084 후보), circuit breaker 라이브러리(Resilience4j working), 모듈 quadruplet wiring(`rag-chat-{api,app,domain,infra}`), retry 곡선 ms 값, rate-limit 정확한 수치, K(working 6), token budget 분할, auto-title prompt 정확한 wording + 모델 선택, cite-persistence policy, stop button persistence policy, X-User-Sub audit logging 범위, contract test fixture 전략, rate-limit identifier 범위. **ADR-14는 동시에 6개의 cross-doc amendment를 운반한다** (per spec §13): ADR-09(2개 섹션 + 1개 신설), ADR-04(informational), ADR-05(`chat` schema + cross-schema SELECT 노트), ADR-00(index), `docs/roadmap.md` §M4, `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §8 amendment block, `docs/prd/M3-rag-ingestion.md` §"M4 retrieval contract". 모든 amendment는 M4 ADR PR 하나로 atomic하게 land한다.
- **외부 의존성:** `spark-inference-gateway` (BGE-M3 임베딩 + Qwen3-32B chat completion). Spring AI의 OpenAI-compatible `ChatClient` + `EmbeddingModel`로 호출 (per ADR-04).
- **소비자(M4 close blocker 아님):** M5 (Metrics) — M4의 메트릭 surface(`playground.rag_chat.tokens`, Resilience4j metrics, latency histograms)를 폴링하여 대시보드에 표시.

## Open questions for the implementer

ADR-14(architect) + Stage-3 implementer가 해소할 사항. PRD 리뷰어가 한 곳에서 보도록 spec §11에서 그대로 옮겨둔다. **The architect must close these in ADR-14, the per-milestone ADR.**

1. **Port assignment** — `rag-chat-api`의 포트(M0–M3가 18080 / 18081 / 18082 / 18083 사용; M4는 18084 후보). 확정 필요.
2. **모듈 quadruplet wiring** — `rag-chat-{api, app, domain, infra}`. `-api`가 SSE controller + 세션 CRUD controller를 host. `-app`이 per-turn use case(auth + rate-limit + history assembly + retrieval orchestration + LLM call)를 host. `-domain`이 `Session`, `Message`, `Citation` aggregate와 prompt-template DSL(Spring-free)을 host. `-infra`가 JPA, Spring AI `ChatClient` adapter, BGE-M3 `EmbeddingModel` adapter, Redis Redisson rate-limit adapter, pgvector retrieval SQL adapter를 host.
3. **Cross-schema SELECT 패턴 vs internal HTTP** for `display_name` lookup — M2의 owner-lookup 선례(ADR-08 §"Exception 3") vs 신규 SELECT exception. 하나 핀.
4. **Circuit breaker 라이브러리** — Resilience4j vs Spring Cloud Circuit Breaker vs 핸드롤드. Resilience4j가 working assumption.
5. **Per-user token bucket backing** — Redis-only vs Postgres rollup table fallback(`chat.usage_daily`).
6. **Auto-title prompt** — 정확한 wording + 사용 모델(Qwen3-32B가 obvious answer이지만 더 작고 저렴한 모델이 가능하다면 더 좋음).
7. **Retrieval K** — 6 확정 또는 수정. 범위 4–10.
8. **Token budget split** — 200(system) + 2400(retrieval K=6 × 400) + 24576(history) + 4000(response) = 31176 ≈ 32k 확정 또는 수정.
9. **Conversation truncation policy** — oldest turn drop vs summarize-and-replace. P0 default = drop.
10. **Cite-persistence policy** — `chat.message_citations`에 retrieved citation 전부를 persist할지 vs LLM이 실제로 `[N]`을 출력한 것만 persist할지. PRD/ADR 결정 사항.
11. **Stale-citation rendering on the frontend** — 정확한 copy + "deleted" 상태에서 chunk_index를 표시할지 여부.
12. **Empty-state suggestion 문자열** — P0의 3개 static suggestion 문자열을 핀.
13. **Stop 버튼 → assistant message persistence** — "not persisted" 확정 또는 "abort 시점까지의 partial persist". P0 default = not persisted.
14. **Streaming abort signaling to the LLM** — Spring AI의 `Flux<ChatResponse>` cancellation이 실제로 게이트웨이-측 generation을 중단시키는가 vs 게이트웨이는 끝까지 토큰을 생성하고 dropped되는가? 후자라면 사용하지 않는 토큰에도 비용을 지불하는 것이므로 investigation 필요.
15. **`X-User-Sub` audit logging** — chat audit log의 mandatory field 범위 (PII concern vs operator의 debug 가능성).
16. **Contract test fixture 전략** — `spark-inference-gateway`에 대한 WireMock (BGE-M3 + Qwen3-32B), pgvector + Redis에 대한 Testcontainers. M3 ADR-13 §13과 같은 패턴인지 확정.
17. **Rate limit identifier** — `userId` only vs `userId + sub`. 일부 사용자가 동일 userId를 여러 브라우저에 걸쳐 공유할 수 있는가? OAuth-Google에서는 unlikely하지만 확인 필요.

---

> **PRD vs ADR:** 이 문서는 사용자(authenticated end-user + operator)와 리뷰어가 읽는 표면이다. 정확한 라이브러리 좌표, 포트 번호, ms 단위 retry/timeout 수치, circuit breaker library 선택, 모듈 wiring, rate limit 정확한 수치, K의 정확한 값, prompt wording, fixture 전략 같은 기술 컨트랙트는 ADR-14(architect의 per-milestone ADR)가 우선한다. PRD가 ADR과 어긋나 보이면 ADR을 따른다.
>
> **PRD vs spec:** SSE 이벤트 grammar의 정확한 field 순서, SQL 쿼리의 정확한 WHERE 절 표현, DB 컬럼의 정확한 타입 / 제약, 모듈 quadruplet의 정확한 entry point 위치 같은 컨트랙트는 spec(`docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md`)이 우선한다. PRD가 spec과 어긋나 보이면 spec을 따른다.
>
> **PRD vs roadmap supersession:** roadmap.md §M4의 현 문구("any visitor — anonymous or signed-in")는 본 사이클의 ADR-14 PR이 일괄 재amend한다 — auth-only 모델로 되돌아간다. 본 PRD는 spec을 canonical로 따른다.
