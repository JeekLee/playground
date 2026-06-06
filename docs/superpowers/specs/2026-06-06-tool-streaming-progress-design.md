# Tool Streaming + 진행 표시 (Generic ToolRunCard) — Design Spec

**Date**: 2026-06-06
**Milestone**: M8 (post-fix iteration)
**Prerequisites**: room-split massing (PR #231), glb-extras (PR #232)

---

## Problem

도구 호출이 단일 블로킹 HTTP다. rag-chat은 `bodyToMono` + 120s 총량
타임아웃으로 기다리고, 그동안 FE는 정보 없는 스피너만 보여준다. LLM-bound
파이프라인(브리프 추출 ~90s, openai SDK 재시도 시 200s+)이 타임아웃을
구조적으로 초과한다 — 2026-06-06 실측: agent-tools는 3회 모두 생성을
완료했지만 rag-chat이 120s에 포기, 사용자는 TIMEOUT 카드를 보고 Retry를
연타해 중복 실행 3개가 단일 LLM gateway에 겹쳤다.

근본 원인은 타임아웃 값이 아니라 모델: **장시간 도구를 "총 소요시간"으로
판정**하고, **진행 상태가 어디에도 보이지 않는다**.

## Decision

1. 도구 wire를 **NDJSON 스트리밍**으로 전환 — 타임아웃 기준을 "총량"에서
   **"신호 부재(idle)"**로 바꾼다. heartbeat가 오는 한 살아있다.
2. 진행 이벤트를 채팅 SSE로 릴레이하고, FE는 **제네릭 `ToolRunCard`**가
   wire 데이터만으로 렌더 — 새 도구는 FE 코드 0줄로 진행 표시를 얻는다.
3. 진행 표시는 메시지 레벨이 아니라 **툴 호출 카드 단위** — 향후 한 턴에
   복수 도구가 병렬 실행돼도 자연 확장된다 (메시지엔 retrieval용 `phase`
   인디케이터가 별도로 존재).

---

## Wire Contracts

### W1. agent-tools → rag-chat (`application/x-ndjson`)

`POST /internal/tools/generate-massing` 응답 스트림, 줄당 JSON 1개:

```jsonl
{"event":"progress","stage":"extract","label":"공간 프로그램 추출 중","stageIndex":3,"stageCount":10,"attempt":2}
{"event":"heartbeat"}
{"event":"result","result":{...},"artifact":{...}}
{"event":"error","code":"BRIEF_NOT_READY","message":"...","status":422}
```

- `progress`: 노드 **시작** 시점 발신. `stage`=노드명(영문 식별자),
  `label`=한국어 표시 텍스트(FE는 verbatim 렌더 — ADR-18 §5 summary 전례),
  `stageIndex` 1-based, `stageCount` 고정 10, `attempt`는 extract 재시작
  횟수 — **2 이상일 때만 포함** (첫 시도엔 필드 자체가 없음).
- `heartbeat`: 10초 간격 — idle 타이머 리셋 전용, rag-chat이 필터.
- `result` / `error`: 터미널 이벤트 (정확히 1개). `error.code/message`는
  기존 `<CODE>: <message>` 문법과 동일한 의미 (MassingErrorCode).
- 스트림 시작 후 HTTP status는 200 고정 — 모든 실패는 `error` 이벤트.
  스트림이 터미널 이벤트 없이 끝나면 rag-chat이 INTERNAL로 분류.

**구현 중 확정된 deviation (2026-06-06 plan):** `error` 이벤트에 `status`
(HTTP status int) 추가 — rag-chat이 기존 UPSTREAM_4XX/5XX 분류를 유지하는
근거 (agent-tools는 `MassingErrorCode.http_status`를 이미 보유).

2. "progress 파싱 불가 줄 무시"는 미구현 — Jackson NDJSON 디코더가 줄 단위
   스킵을 지원하지 않아 malformed 줄은 Flux 전체를 에러시킨다 (→ INTERNAL).
   agent-tools가 typed 이벤트만 발신하는 통제된 생산자라 실서비스에서 도달
   불가 — dispatcher javadoc에 기록됨.

### W2. rag-chat → FE (채팅 SSE)

신규 이벤트 `tool_progress`:

```json
{"id":"<toolCallId>","name":"generate_massing","stage":"extract",
 "label":"공간 프로그램 추출 중","stageIndex":3,"stageCount":10,"attempt":2}
```

기존 `tool_call` 이벤트에 `displayName`("매싱 모델") 추가. tool_result /
tool_error / done 불변. resume: replay buffer에 progress가 포함돼 자동
재생 (이벤트 ~10-30개, 4096 캡 무관).

---

## Design

### D1. agent-tools — `MassingWorkflow.stream()`

- `graph.stream(input, stream_mode="debug", subgraphs=True)` — debug 모드의
  task(시작) 이벤트를 필터해 노드-시작 신호로 사용 (updates 모드는 완료
  후라 "진행 중" 의미 불일치). 서브그래프 포함이라 resolve_program 내부
  단계가 그대로 나온다.
- 스테이지 맵 (순서 고정, stageCount=10):

  | # | stage | label |
  |---|-------|-------|
  | 1 | fetch_brief | 브리프 조회 |
  | 2 | locate | 면적 정보 탐색 |
  | 3 | extract | 공간 프로그램 추출 중 |
  | 4 | reconcile | 프로그램 정합 |
  | 5 | classify | 공간 분류 |
  | 6 | derive | 층수·풋프린트 산정 |
  | 7 | compute | 매싱 계산 |
  | 8 | serialize | 3D 모델 생성 |
  | 9 | store_3dm | 파일 저장 |
  | 10 | store_glb | 미리보기 생성 |

  맵에 없는 노드(respond 등)는 progress 미발신. extract 재시작마다
  `attempt` 증가.
- 그래프는 워커 스레드에서 실행, async 제너레이터가 큐로 브리지하며
  10초 무이벤트 시 heartbeat 주입. FastAPI `StreamingResponse`.
- `MassingError` → `error` 이벤트로 변환 (제너레이터 내 catch). 기존
  `run()`은 유지 (단위 테스트·동기 경로).
- `shared_kernel/llm.py`: `ChatOpenAI(max_retries=1)` 명시 — 호출당
  상한 = 120s × 2로 예측 가능.

### D2. rag-chat — 스트림 소비 디스패처

- `ToolDescriptor` 확장: `displayName: String` 추가; `timeout`의 의미를
  **idle 타임아웃**으로 전환 (massing: 60s), `totalTimeout: Duration`
  추가 (massing: 600s — 안전 캡).
- `WebClientToolDispatcher`: `bodyToFlux(JsonNode.class)` (WebFlux의
  x-ndjson 네이티브 디코딩) + `.timeout(idle)` (Reactor의 Flux.timeout은
  신호-간 — heartbeat가 리셋) + 전체 캡. 이벤트 분기:
  - `progress` → 리스너 콜백
  - `heartbeat` → 무시 (타이머 리셋 효과만)
  - `result` → `ToolInvocationResult.Success` (기존 envelope 파싱 재사용)
  - `error` → `Failure` (code/message — 기존 패킹 문법)
  - 터미널 없이 완료 → `Failure(INTERNAL)`
- **브레이커 의미 변화 (명시)**: transport 실패(idle TimeoutException ·
  IOException · non-2xx 핸드셰이크)만 기록. 도메인 `error` 이벤트는 4xx와
  같이 비기록 — 결정론적 도구 오류는 health 신호가 아니다. (기존엔
  5xx-equivalent가 기록됐으나, 스트림 전환 후 도메인 오류는 모두 이벤트로
  오므로 이 단순화를 수용한다.)
- `ToolDispatcherPort.invoke(...)`에 progress 리스너 파라미터 추가
  (`Consumer<ToolProgress>` 상당 — app 레이어 DTO). ChatTurnService가
  `ChatStreamEvent.ToolProgress`로 sink에 릴레이. `ToolCall` 이벤트 빌드
  시 `descriptor.displayName()` 동봉.
- `ChatStreamController.toSse`: `tool_progress` 케이스 추가, `tool_call`에
  displayName 필드 추가.

### D3. FE — 제네릭 `ToolRunCard`

- 신규 `features/chat-tool-card/ToolRunCard.tsx`: in-flight 전용, 전부
  wire 구동 —
  - 이름: `displayName ?? name`
  - 본문: 스피너 + 현재 `label` + `attempt ≥ 2`면 `(시도 N)` 표기
  - 핍 바: `stageCount`개, `stageIndex` 미만 done / 해당 active
  - progress 이벤트가 아직 없으면(미전환 툴·이벤트 도착 전) "Running…"
    fallback — 기존과 동일한 모양
  - 아이콘: 제네릭 (Lucide `Cog`) — 결과 카드의 툴별 아이콘과 구분
- `MassingResultCard`: **in-flight 분기 삭제** — result 분기만 남음.
  `ToolCardList`가 in_flight → ToolRunCard / result → 툴별 카드 분기.
- types: `ToolCallEventPayload.displayName?`, 신규
  `ToolProgressEventPayload`, `ToolCardState`(in_flight)에 `progress?` +
  `displayName?`. `chat.sse.ts`에 `tool_progress` 파싱. ChatPage 스트림
  상태에서 같은 id의 in-flight 카드에 progress 머지.

### D4. 타임아웃 정합표

| 계층 | 기준 | 값 |
|------|------|-----|
| agent-tools LLM 호출 | timeout × (1 + max_retries) | 120s × 2 |
| agent-tools heartbeat | 발신 간격 | 10s |
| rag-chat idle | 신호 부재 시 TIMEOUT | 60s |
| rag-chat total cap | 절대 상한 | 600s |

idle(60s) > heartbeat(10s) × 5 — 일시 지연 여유. total cap(600s) >
파이프라인 최악치(LLM 240s + 재프롬프트 3회) 여유.

---

## Error Handling

| 상황 | 동작 |
|------|------|
| idle 60s 초과 / 연결 단절 | TimeoutException → TIMEOUT tool_error (기존 FE 경로) + 브레이커 기록 |
| total 600s 초과 | TIMEOUT 동일 |
| `error` 이벤트 | Failure(code) → tool_error — 브레이커 비기록 |
| 터미널 없는 스트림 종료 | Failure(INTERNAL) |
| progress 파싱 불가 줄 | 해당 줄 무시 (스트림 계속) |
| FE: progress 없는 in-flight | "Running…" fallback |

## Backward Compatibility

- 전환은 원자적 — 현존 도구가 generate_massing 하나라 폴백 모드 없음.
  agent-tools와 rag-chat을 같은 PR로 배포.
- FE: `displayName`/`tool_progress` 모두 optional 처리 — 구 백엔드와
  섞여도 fallback 렌더.
- 도구 wire 계약 변경의 ADR-17/19 정식 어멘드는 out of scope (spec이 기록).

## Testing

- agent-tools: stream 엔드포인트 테스트 (fake chain + TestClient stream) —
  이벤트 시퀀스(progress 순서·stageIndex), heartbeat 주입, error 이벤트
  변환, attempt 카운트(재프롬프트 픽스처), result 터미널 envelope이
  기존 run()과 동일함
- rag-chat: `WebClientToolDispatcherTest`에 NDJSON 케이스 (성공 시퀀스 /
  error 이벤트 / idle 타임아웃 / 터미널 없는 종료 / heartbeat 리셋),
  ChatTurnService progress 릴레이 + displayName
- FE: typecheck / lint / build
- 통합: 실생성 — in-flight 카드에 단계 진행 + 완료 카드 정상

## Out of Scope

- ADR-17/19 정식 어멘드먼트
- 메시지 레벨 진행 표시 (retrieval `phase`와의 통합)
- progress 영속화 (완료 후 무의미)
- 도구 병렬 실행 (M9+ — 단 카드-단위 설계가 이미 수용)
- stopgap 타임아웃 상향 (스트리밍이 대체)
