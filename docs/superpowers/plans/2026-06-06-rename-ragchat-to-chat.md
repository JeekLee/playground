# rag-chat → chat 개명 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rag-chat을 chat으로 완전 개명 (모듈·패키지·클래스·인프라·공개 URL·버킷·Redis 키) 후 전 스택 데이터 리셋·클린 기동.

**Architecture:** 단일 원자 커밋의 기계적 변환 (Task 1) + 데이터 리셋·검증 (Task 2). 변환은 디렉토리 git mv → 경로 한정 일괄 sed → 의미 변경 지점(gateway StripPrefix 3→2 등) 수동 확인 순. ADR/과거 spec·plan 문서는 역사 기록으로 불변.

**Tech Stack:** git mv + find/sed, Gradle, docker compose down -v.

**Spec:** `docs/superpowers/specs/2026-06-06-rename-ragchat-to-chat-design.md`

**Worktree:** `git worktree add .claude/worktrees/rename-chat -b worktree-rename-chat main` 후 진입. `cp infra/.env .claude/worktrees/rename-chat/infra/.env` (실 env — sed 대상). Java는 `backend/springboot/`에서 gradlew, Python은 `uv sync --extra test` 1회, FE는 `pnpm install --frozen-lockfile` 1회.

---

## 사전 인벤토리 (2026-06-06 grep 확정 — 변환 대상의 전부)

- **모듈**: `backend/springboot/rag-chat/{rag-chat-api,rag-chat-app,rag-chat-domain,rag-chat-infra}`; `settings.gradle.kts:48-54`
- **패키지**: `com.playground.ragchat` (rag-chat 모듈 내부 전체 + metrics 테스트엔 없음)
- **클래스**: `RagChatApiGroupConfig, RagChatApplication, RagChatErrorCode, RagChatProperties, RagChatPropertiesBinding, RagChatPropertiesConfig, RagChatReactiveExceptionHandler, RagChatSecurityConfig` (8개 — `RagChat*` 패턴 전부)
- **application.yml** (rag-chat-api): `spring.application.name: rag-chat-api`, `pool-name: rag-chat-pool`, `playground.rag-chat.*` 키 블록(≈line 82), loki `application: rag-chat` 라벨, `com.playground.ragchat` 로거, 주석들
- **Redis 키**: `rag-chat:lock:user:%s` (RedissonConcurrentStreamLockAdapter), `rag-chat:bucket:user:%s:hourly|daily` (RedissonTokenBucketAdapter) → `chat:...` (데이터 리셋이라 안전)
- **gateway** `application.yml`: route id `rag-chat-api`, `uri: http://rag-chat-api:18084`, `Path=/api/rag/chat/**`, **`StripPrefix=3` → `2`**
- **compose**: 서비스 `rag-chat-api`, `container_name: playground-backend-rag-chat-api`, `hostname: rag-chat-api`, build context `../backend/springboot` + dockerfile `rag-chat/rag-chat-api/Dockerfile`, env `PLAYGROUND_RAGCHAT_*`, agent-tools 블록의 rag-chat 언급 주석, 버킷 기본값 `rag-chat-attachments`
- **Dockerfile**: `backend/springboot/rag-chat/rag-chat-api/Dockerfile` (모듈 경로 참조 내부 확인)
- **env**: `infra/.env` + `infra/.env.example` — `PLAYGROUND_RAGCHAT_*` 변수 + rag-chat 언급 주석들
- **FE** (4파일): `shared/api/chat.ts`, `chat.sse.ts`, `chat.server.ts`, `features/chat-stream/useChatStream.ts` — `/api/rag/chat` 경로 상수
- **BE 경로 상수**: `ChatTurnService.ATTACHMENT_DOWNLOAD_PREFIX`, `SessionResponses`의 동일 상수 — `"/api/rag/chat/attachments/"` → `"/api/chat/attachments/"`
- **metrics 모듈**: `BuildDashboardUseCase`, `BuildServicesUseCase`, `MetricsHttpProperties`, `RedissonIpRateLimiterAdapter`, Loki/Prometheus/BuildTimeseries/BuildServices 테스트 — `rag-chat`/`rag-chat-api` 서비스 식별자
- **agent-tools**: `shared_kernel/config.py`·`blob_storage.py`·`store_glb.py` 등의 rag-chat 언급 주석/독스트링, `PLAYGROUND_ARCHITECTURE_MINIO_BUCKET` 기본값 `rag-chat-attachments`, `rag_chat` 파이썬 변수 없음
- **버킷**: `rag-chat-attachments` → `chat-attachments` (양쪽 기본값 + env)
- **제외 (불변)**: `docs/adr/**`, `docs/design/**` 본문(노트 추가만), `docs/superpowers/**` 과거 문서, `**/build/**`·`.next`·lock 파일, git 히스토리, DB 스키마 `chat.*`, 에러코드 `CHAT-*`, shared-kernel `com.playground.shared.chat`

---

### Task 1: 전수 개명 (원자 커밋)

**Files:** 위 인벤토리 전부. 작업 순서가 중요 — 디렉토리 이동 후 내용 치환.

- [ ] **Step 1: 디렉토리·모듈 이동**

```bash
cd backend/springboot
git mv rag-chat chat
git mv chat/rag-chat-api chat/chat-api
git mv chat/rag-chat-app chat/chat-app
git mv chat/rag-chat-domain chat/chat-domain
git mv chat/rag-chat-infra chat/chat-infra
# 패키지 디렉토리 (각 모듈의 main+test)
for m in chat-api chat-app chat-domain chat-infra; do
  for s in main test; do
    d="chat/$m/src/$s/java/com/playground"
    [ -d "$d/ragchat" ] && git mv "$d/ragchat" "$d/chat"
  done
done
```

- [ ] **Step 2: settings.gradle.kts** — 4개 include를 `":chat:chat-api"` 등으로, 주석 "M4 — rag-chat quadruplet" → "M4 — chat quadruplet (rag-chat에서 개명, 2026-06-06 spec)".

- [ ] **Step 3: Java 일괄 치환** (chat 모듈 + metrics 모듈 + gateway 리소스 한정 — docs/ 절대 제외)

```bash
cd backend/springboot
# 패키지/임포트
grep -rl "com\.playground\.ragchat" chat metrics --include="*.java" \
  | xargs sed -i 's/com\.playground\.ragchat/com.playground.chat/g'
# 클래스명 (선언+참조) — RagChat 접두 8종
grep -rl "RagChat" chat metrics --include="*.java" \
  | xargs sed -i 's/RagChat/Chat/g'
# 파일명 rename
cd chat && for f in $(find . -name "RagChat*.java"); do
  git mv "$f" "$(dirname $f)/$(basename $f | sed 's/^RagChat/Chat/')"; done && cd ..
# 문자열/주석의 rag-chat (경로상수·Redis 키·로그 문구 포함)
grep -rl "rag-chat" chat metrics --include="*.java" --include="*.yml" --include="*.sql" \
  | xargs sed -i 's|/api/rag/chat|/api/chat|g; s/rag-chat/chat/g'
```

주의: `s/RagChat/Chat/g`가 `ChatChat`을 만들 수 있는 기존 `Chat*` 클래스와의 충돌은 없음 (RagChat 접두만 매치). 치환 후 `grep -rn "ChatChat\|chatchat" chat metrics`로 오염 0 확인.

- [ ] **Step 4: 의미 변경 지점 수동 확인** (sed가 못 하는 것)

1. `gateway/src/main/resources/application.yml`: route를 직접 편집 —

```yaml
        - id: chat-api
          uri: http://chat-api:18084
          predicates:
            - Path=/api/chat/**
          filters:
            - StripPrefix=2   # /api/chat 2세그먼트 — 개명 전 /api/rag/chat은 3이었다
```

2. chat 모듈 `application.yml`: Step 3 sed가 키들을 바꿨는지 육안 확인 — `spring.application.name: chat-api`, `pool-name: chat-pool`, `playground.chat.*`, loki `application: chat`, logger `com.playground.chat`. `PLAYGROUND_RAGCHAT_*` placeholder 참조는 `PLAYGROUND_CHAT_*`로.
3. `chat/chat-api/Dockerfile`: 내부의 모듈 경로(`rag-chat/rag-chat-api`) → `chat/chat-api`; gradle 좌표 `:rag-chat:rag-chat-api` → `:chat:chat-api`.
4. Redis 키 상수: `chat:lock:user:%s` / `chat:bucket:user:%s:*`로 바뀌었는지 + 그 javadoc.
5. `RedissonIpRateLimiterAdapter`(metrics)의 rag-chat 언급 — 맥락 읽고 갱신.

- [ ] **Step 5: compose + env + FE + agent-tools**

```bash
cd <worktree-root>
sed -i 's/playground-backend-rag-chat-api/playground-backend-chat-api/g; s|rag-chat/rag-chat-api|chat/chat-api|g; s/rag-chat-api/chat-api/g; s/PLAYGROUND_RAGCHAT_/PLAYGROUND_CHAT_/g; s/rag-chat-attachments/chat-attachments/g; s/rag-chat/chat/g' infra/docker-compose.yml
sed -i 's/PLAYGROUND_RAGCHAT_/PLAYGROUND_CHAT_/g; s/rag-chat-attachments/chat-attachments/g; s/rag-chat-api/chat-api/g; s/rag-chat/chat/g' infra/.env infra/.env.example
grep -rl "/api/rag/chat" frontend/src --include="*.ts" --include="*.tsx" \
  | xargs sed -i 's|/api/rag/chat|/api/chat|g'
grep -rl "rag-chat" frontend/src --include="*.ts" --include="*.tsx" \
  | xargs sed -i 's/rag-chat/chat/g'   # 주석 잔존분
grep -rl "rag-chat\|RAGCHAT" backend/fastapi/agent-tools --include="*.py" | grep -v .venv \
  | xargs sed -i 's/PLAYGROUND_RAGCHAT_/PLAYGROUND_CHAT_/g; s/rag-chat-attachments/chat-attachments/g; s/rag-chat/chat/g'
```

compose의 `depends_on: rag-chat-api` 류 참조와 rag-ingestion 관련 주석(docs-api 소유 — `rag-ingestion`은 개명 대상 아님!)이 sed에 오염되지 않았는지 diff 육안 확인 — **`rag-ingestion` 문자열은 불변이어야 한다** (`s/rag-chat/chat/g`는 매치 안 하지만 확인).

- [ ] **Step 6: 게이트 전부**

```bash
cd backend/springboot && ./gradlew build -x integrationTest 2>&1 | tail -3   # 전 모듈 (개명된 :chat:* 포함)
cd ../fastapi/agent-tools && uv run pytest tests/ -q | tail -1               # 106
cd ../../frontend && pnpm typecheck && pnpm lint && pnpm build | tail -2
cd <worktree-root>
grep -rin "rag-chat\|ragchat" backend frontend infra \
  --include="*.java" --include="*.kts" --include="*.yml" --include="*.yaml" \
  --include="*.py" --include="*.ts" --include="*.tsx" --include="*.sql" \
  --include="Dockerfile" --include=".env*" \
  | grep -v "rag-ingestion" | grep -v "/build/" || echo "RESIDUAL ZERO"
```

Expected: 모두 green + `RESIDUAL ZERO`. (gradle 빌드가 integrationTest 태스크 부재로 `-x` 거부하면 그냥 `./gradlew build`.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor!: rename rag-chat -> chat — modules, packages, routes (/api/chat), bucket, redis keys"
```

---

### Task 2: 데이터 리셋 + 클린 기동 + 검증 + design-doc

- [ ] **Step 1: 전 스택 정지 + 볼륨 전체 삭제** (worktree 루트에서; 사용자 승인된 파괴 작업 — spec Decision 2)

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env down --remove-orphans
docker volume rm postgres-playground-data redis-playground-data kafka-playground-data \
  opensearch-playground-data minio-playground-data prometheus-playground-data \
  loki-playground-data alloy-playground-data 2>&1 || true
docker volume ls | grep playground   # 잔존 확인 — compose 프로젝트 프리픽스 붙은 실명이면 그 이름으로 재시도
```

(named volume의 실제 docker 이름은 `infra_` 프리픽스가 붙을 수 있음 — `docker volume ls`로 실명 확인 후 삭제.)

- [ ] **Step 2: 클린 기동**

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
```

전 서비스 healthy 대기 (chat-api는 새 컨테이너명 `playground-backend-chat-api`). Flyway 재생성 확인: `docker exec playground-postgres psql -U playground -d playground -c "\dn"` → identity/docs/chat 스키마. MinIO: `docker exec agent-tools python -c "..."`로 `chat-attachments` 버킷 자동생성 확인 (첫 생성 시점일 수 있음 — 없으면 매싱 1회 후 재확인).

- [ ] **Step 3: 라우트 스모크** — gateway 경유 새 경로:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/api/chat/sessions   # 401/403 (인증 필요 = 라우팅 OK), 404면 라우트 실패
```

- [ ] **Step 4: design-doc 노트** — `docs/design/M4-rag-chat.md` 최상단(제목 직하)에:

```markdown
> **2026-06-06 — 서비스 개명**: rag-chat → **chat** (agentic-RAG 재구성 SP1).
> 모듈 `:chat:chat-*`, 패키지 `com.playground.chat`, 공개 경로 `/api/chat/**`,
> 버킷 `chat-attachments`, Redis 키 `chat:*`. 본문의 rag-chat 명칭은 작성
> 시점 기록으로 유지. Spec:
> `docs/superpowers/specs/2026-06-06-rename-ragchat-to-chat-design.md`.
```

`docs/design/M6-M8-brief-to-massing.md`의 도구 스트리밍 노트 뒤에도 한 줄: `> (2026-06-06 후속: rag-chat 서비스는 chat으로 개명 — 위 명칭은 작성 시점 기록.)`

- [ ] **Step 5: Commit + 사용자 E2E 안내**

```bash
git add docs/design/
git commit -m "docs(design): note rag-chat -> chat rename"
```

사용자 수동: 로그인 → 브리프 재업로드 → 매싱 생성 (스트리밍 진행 카드 + 다운로드 + 3D 미리보기 + 새로고침 후 복원).

---

## Self-Review

1. **Spec coverage:** Naming Map 전 행 → Task 1 (Step 1-5 매핑); Data Reset 절차 → Task 2 Step 1-2; 검증 게이트 → Task 1 Step 6 + Task 2 Step 2-3; ADR 불변 → sed 경로 한정으로 보장; design doc 노트 → Task 2 Step 4. 누락 없음.
2. **Placeholder scan:** 없음 — 모든 치환이 명령어로 명시.
3. **함정 명시:** StripPrefix 3→2 (Step 4-1), `rag-ingestion` 불변 (Step 5 주의), volume 실명 프리픽스 (Task 2 Step 1), ChatChat 오염 검사 (Step 3).
