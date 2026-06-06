# rag-chat → chat 개명 (SP1 of agentic-RAG 재구성) — Design Spec

**Date**: 2026-06-06
**Milestone**: M9 준비 (agentic-RAG 재구성의 선행 서브프로젝트)
**Sequel**: SP2 = search_documents 도구화 + agentic 루프 (별도 spec)

---

## Problem

RAG가 곧 도구화되면(SP2) "rag-chat"이라는 이름은 거짓이 된다 — chat은
순수 대화 오케스트레이터(LLM + 도구 디스패치 + 메시지 영속)가 되고
검색은 등록된 도구 중 하나일 뿐이다. SP2의 새 코드가 처음부터 깨끗한
이름 위에 쌓이도록 개명을 선행한다 (사용자 결정: A — 개명 먼저).

## Decisions

1. **완전 개명** — 코드·인프라·공개 URL·버킷까지. 반쪽 개명 없음.
2. **데이터 전체 리셋** — 마이그레이션 대신 전 스택 볼륨 삭제 후 새로
   기동 (사용자 결정). 단독 사용 중이라 잃을 데이터는 업로드된 브리프
   문서 + 채팅 세션 + 생성 아티팩트 (재업로드/재생성으로 복구).
3. **ADR 본문 불변** — 의사결정 역사 기록이므로 다시 쓰지 않는다. 개명
   사실은 이 spec + design doc 노트가 기록.

## Naming Map

| 현재 | 변경 후 |
|------|---------|
| Gradle `:rag-chat:rag-chat-{api,app,domain,infra}` | `:chat:chat-{api,app,domain,infra}` |
| 디렉토리 `backend/springboot/rag-chat/` | `backend/springboot/chat/` |
| 패키지 `com.playground.ragchat` | `com.playground.chat` |
| 클래스 `RagChat*` (Properties/ErrorCode/SecurityConfig/ReactiveExceptionHandler 등 전수) | `Chat*` |
| compose 서비스 `rag-chat-api` / 컨테이너 `playground-backend-rag-chat-api` / 호스트명 `rag-chat-api` | `chat-api` / `playground-backend-chat-api` / `chat-api` |
| gateway route id `rag-chat-api`, `Path=/api/rag/chat/**`, `StripPrefix=3`, `uri http://rag-chat-api:18084` | `chat-api`, `Path=/api/chat/**`, **`StripPrefix=2`**, `uri http://chat-api:18084` |
| FE 경로 상수 `/api/rag/chat/...` (4파일: chat.ts/chat.sse.ts/chat.server.ts/useChatStream.ts) | `/api/chat/...` |
| BE 경로 상수 `ATTACHMENT_DOWNLOAD_PREFIX = "/api/rag/chat/attachments/"` (ChatTurnService + SessionResponses) | `"/api/chat/attachments/"` |
| env `PLAYGROUND_RAGCHAT_*` | `PLAYGROUND_CHAT_*` (infra/.env 실파일 + .env.example + compose) |
| MinIO 버킷 `rag-chat-attachments` (agent-tools 기본값 + chat 기본값 양쪽) | `chat-attachments` |
| `application.yml` `spring.application.name` + `playground.rag-chat.*` 설정 키(실제 prefix는 구현 시 확인) | `chat-api` / `playground.chat.*` |
| metrics 모듈의 서비스 레지스트리/대시보드 참조 (`BuildDashboardUseCase`, `BuildServicesUseCase`, `MetricsHttpProperties`, Loki/Prometheus 테스트) | `chat-api` 기준으로 갱신 |
| agent-tools 주석·env 별칭 중 rag-chat 언급 (`PLAYGROUND_RAGCHAT_MINIO_BUCKET` 별칭 등) | chat 기준 갱신 |

**불변**: DB 스키마 `chat.*` (이미 chat), 에러코드 `CHAT-*`, shared-kernel
`com.playground.shared.chat.ChatStreamEvent` (이미 중립), `docs/adr/*` 본문.

## Data Reset 절차 (개명 배포와 함께)

1. `docker compose down` (전 서비스) — **named volume 전부 삭제**:
   `postgres-playground-data`, `redis-playground-data`,
   `kafka-playground-data`, MinIO·OpenSearch 데이터 볼륨,
   observability(`prometheus/loki/alloy-playground-data`) 포함 — 진짜 클린.
2. 새 이름으로 `up -d --build` — Flyway가 전 스키마 재생성, MinIO 버킷은
   서비스 기동 시 자동 생성(`chat-attachments`), OpenSearch 인덱스는
   docs-api가 재생성.
3. 검증: gateway 헬스 → 로그인 → 브리프 1개 업로드 → 매싱 생성 1회
   (스트리밍 카드 + 다운로드 + 미리보기) — 사용자 수동.

## Verification Gates

- 전 Gradle 모듈 빌드+테스트 green (개명 후 `:chat:*` 좌표로)
- agent-tools pytest 106 (rag-chat 참조 주석/별칭 갱신 후)
- FE typecheck/lint/build
- `grep -rin "rag-chat\|ragchat" backend frontend infra --include 소스류` →
  잔존 0 (docs/·ADR·git 히스토리 제외; spec/design 문서의 역사 서술 제외)
- 클린 스택 E2E (위 Data Reset 3)

## Out of Scope

- SP2 (search_documents 도구화·agentic 루프·citation 재설계) — 후속 spec
- ADR 본문 개정
- git 히스토리/과거 PR 본문의 명칭
- docs/superpowers/{specs,plans}의 과거 문서 본문 (역사 기록)
