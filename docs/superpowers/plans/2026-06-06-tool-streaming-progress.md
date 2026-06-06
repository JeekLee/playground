# Tool Streaming + Generic Progress Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 도구 호출을 NDJSON 스트리밍으로 전환 (총량 타임아웃 → idle 타임아웃) + 노드 단위 진행 이벤트를 제네릭 `ToolRunCard`로 표시.

**Architecture:** agent-tools가 `graph.stream(stream_mode=["debug","values"], subgraphs=True)`의 task(노드 시작) 이벤트를 NDJSON으로 흘리고(heartbeat 10s), rag-chat 디스패처가 `bodyToFlux(JsonNode)` + `Flux.timeout(idle)`로 소비하며 progress를 채팅 SSE `tool_progress`로 릴레이, FE는 wire 데이터만으로 구동되는 제네릭 in-flight 카드를 렌더한다. **검증 완료**: LangGraph 0.2.60에서 멀티모드+subgraphs 스트림이 `(ns, mode, payload)` 튜플로 노드-시작 이벤트와 최종 state를 모두 제공함 (2026-06-06 컨테이너 실측).

**Tech Stack:** LangGraph debug stream, FastAPI StreamingResponse + asyncio queue bridge, Spring WebFlux `bodyToFlux` + Reactor per-signal timeout + Resilience4j, React 18.

**Spec:** `docs/superpowers/specs/2026-06-06-tool-streaming-progress-design.md`

**Spec deviation locked during planning** (Task 6에서 spec에 기록): W1 `error` 이벤트에 `status`(HTTP class) 필드 추가 — agent-tools는 `exc.code.http_status`를 알고 있고, rag-chat이 이를 기존 UPSTREAM_4XX/UPSTREAM_5XX 분류로 매핑해 오늘의 FE 동작과 바이트 호환을 유지한다.

**Worktree:** `git worktree add .claude/worktrees/tool-streaming -b worktree-tool-streaming main` 후 진입. Python: `backend/fastapi/agent-tools/`에서 `uv sync --extra test` 1회. Java: `backend/springboot/`에서 gradlew. FE: `frontend/`에서 `pnpm install --frozen-lockfile` 1회.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `backend/fastapi/agent-tools/architecture/app/stages.py` | Create | 스테이지 맵 + `progress_event()` |
| `backend/fastapi/agent-tools/architecture/app/workflow.py` | Modify | `stream()` 제너레이터 추가 |
| `backend/fastapi/agent-tools/architecture/api/routers/tools.py` | Modify | NDJSON StreamingResponse + heartbeat 브리지 |
| `backend/fastapi/agent-tools/shared_kernel/config.py` | Modify | `stream_heartbeat_seconds`, llm max_retries |
| `backend/fastapi/agent-tools/shared_kernel/llm.py` | Modify | `max_retries=1` |
| `backend/fastapi/agent-tools/tests/test_workflow.py` | Modify | stream() 시퀀스/attempt/error 테스트 |
| `backend/fastapi/agent-tools/tests/test_stream_endpoint.py` | Create | NDJSON/heartbeat 엔드포인트 테스트 |
| `backend/springboot/rag-chat/rag-chat-domain/.../tool/ToolDescriptor.java` | Modify | +displayName, +totalTimeout, timeout=idle 의미 |
| `backend/springboot/rag-chat/rag-chat-domain/.../tool/MassingTool.java` | Modify | displayName "매싱 모델", 60s/600s |
| `backend/springboot/rag-chat/rag-chat-app/.../port/ToolDispatcherPort.java` | Modify | +`ToolProgress` record + listener 파라미터 |
| `backend/springboot/rag-chat/rag-chat-infra/.../tool/WebClientToolDispatcher.java` | Modify | NDJSON Flux 소비 |
| `backend/springboot/rag-chat/rag-chat-app/.../ChatStreamEvent.java`(또는 동등 위치) | Modify | +ToolProgress, ToolCall+displayName |
| `backend/springboot/rag-chat/rag-chat-app/.../service/ChatTurnService.java` | Modify | progress 릴레이 + displayName |
| `backend/springboot/rag-chat/rag-chat-api/.../controller/ChatStreamController.java` | Modify | toSse: tool_progress + displayName |
| `frontend/src/shared/api/chat.ts` / `chat.sse.ts` | Modify | ToolProgressEventPayload + 파싱 |
| `frontend/src/entities/chat/types.ts` | Modify | in_flight에 displayName/progress |
| `frontend/src/features/chat-stream/useChatStream.ts` | Modify | tool_progress 머지 |
| `frontend/src/features/chat-tool-card/ToolRunCard.tsx` | Create | 제네릭 in-flight 카드 |
| `frontend/src/features/chat-tool-card/ToolCardList.tsx` | Modify | in_flight → ToolRunCard |
| `frontend/src/features/chat-tool-card/MassingResultCard.tsx` | Modify | in-flight 분기 삭제 |

---

### Task 1: agent-tools — 스테이지 맵 + `MassingWorkflow.stream()` (TDD)

**Files:**
- Create: `backend/fastapi/agent-tools/architecture/app/stages.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/workflow.py`
- Test: `backend/fastapi/agent-tools/tests/test_workflow.py`

- [ ] **Step 1: 실패 테스트 추가** — `tests/test_workflow.py` 끝에 (기존 `_FIXED_ANALYSIS`/`_FakeDocs`/`_build_workflow` 재사용; `_NO_SITE` 픽스처는 `test_reprompt_loop_...`의 `no_site` dict와 동일 내용을 모듈 레벨 상수로 승격해 공유):

```python
# --- stream() (tool-streaming spec D1) ---

_NO_SITE = BriefAnalysis.model_validate(
    {
        "program": [{"name": "업무", "area_m2": 1000.0, "grade": "above"}],
        "site_area_m2": None,
    }
)

_EXPECTED_STAGES = [
    "fetch_brief", "locate", "extract", "reconcile", "classify",
    "derive", "compute", "serialize", "store_3dm", "store_glb",
]


def _patch_stores(monkeypatch):
    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260606/test-uuid/{filename}",
    )
    monkeypatch.setattr(
        "architecture.app.nodes.store_glb.upload_to_key",
        lambda file_bytes, key, content_type, settings: None,
    )


def test_stream_yields_progress_sequence_then_result(monkeypatch):
    _patch_stores(monkeypatch)
    flow = _build_workflow()
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))

    progress = [e for e in events if e["event"] == "progress"]
    assert [e["stage"] for e in progress] == _EXPECTED_STAGES
    assert [e["stageIndex"] for e in progress] == list(range(1, 11))
    assert all(e["stageCount"] == 10 for e in progress)
    assert all("attempt" not in e for e in progress)  # 단일 시도 — 필드 없음
    # 한국어 레이블 (FE verbatim 렌더 — spec W1)
    assert progress[2]["label"] == "공간 프로그램 추출 중"

    terminal = events[-1]
    assert terminal["event"] == "result"
    assert terminal["result"]["briefTitle"] == "KFI 테스트 브리프"
    assert terminal["result"]["programJson"]["rooms"]
    assert terminal["artifact"]["storageKey"].endswith(".3dm")


def test_stream_attempt_counts_extract_retries(monkeypatch):
    _patch_stores(monkeypatch)
    calls = {"n": 0}

    def flaky(_inputs):
        calls["n"] += 1
        return _NO_SITE if calls["n"] == 1 else _FIXED_ANALYSIS

    flow = MassingWorkflow(Settings(), _FakeDocs(), extraction_chain=RunnableLambda(flaky))
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))

    extract_events = [e for e in events if e.get("stage") == "extract"]
    assert len(extract_events) == 2
    assert "attempt" not in extract_events[0]
    assert extract_events[1]["attempt"] == 2
    assert events[-1]["event"] == "result"


def test_stream_terminal_error_event_on_massing_error():
    flow = MassingWorkflow(
        Settings(), _FakeDocs(), extraction_chain=RunnableLambda(lambda _i: _NO_SITE)
    )
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))
    terminal = events[-1]
    assert terminal["event"] == "error"
    assert terminal["code"] == "BRIEF_NOT_READY"
    assert terminal["status"] == 422
    assert isinstance(terminal["message"], str) and terminal["message"]
```

기존 `test_reprompt_loop_fails_when_site_area_never_found`의 인라인 `no_site`를 `_NO_SITE` 상수 참조로 교체.

- [ ] **Step 2: 실패 확인** — `uv run pytest tests/test_workflow.py -v 2>&1 | tail -8` → 신규 3건 FAIL (`stream` 미존재).

- [ ] **Step 3: stages.py 생성**

```python
"""매싱 파이프라인 진행 스테이지 맵 (tool-streaming spec D1).

stage = LangGraph 노드명(서브그래프 내부 포함), label = FE가 verbatim
렌더하는 한국어 텍스트 (ADR-18 §5 summary 전례). 맵에 없는 노드
(respond, resolve_program 래퍼 등)는 progress를 발신하지 않는다.
"""

from __future__ import annotations

STAGES: tuple[tuple[str, str], ...] = (
    ("fetch_brief", "브리프 조회"),
    ("locate", "면적 정보 탐색"),
    ("extract", "공간 프로그램 추출 중"),
    ("reconcile", "프로그램 정합"),
    ("classify", "공간 분류"),
    ("derive", "층수·풋프린트 산정"),
    ("compute", "매싱 계산"),
    ("serialize", "3D 모델 생성"),
    ("store_3dm", "파일 저장"),
    ("store_glb", "미리보기 생성"),
)
STAGE_COUNT = len(STAGES)
_INDEX = {name: i + 1 for i, (name, _) in enumerate(STAGES)}
_LABEL = dict(STAGES)


def progress_event(node: str, attempt: int | None = None) -> dict | None:
    """노드-시작 → progress 이벤트 dict. 맵 밖 노드는 None.

    `attempt`는 2 이상일 때만 필드로 포함 (spec W1)."""
    if node not in _INDEX:
        return None
    ev: dict = {
        "event": "progress",
        "stage": node,
        "label": _LABEL[node],
        "stageIndex": _INDEX[node],
        "stageCount": STAGE_COUNT,
    }
    if attempt is not None and attempt >= 2:
        ev["attempt"] = attempt
    return ev
```

- [ ] **Step 4: workflow.stream() 구현** — `workflow.py`에 추가 (imports: `from typing import Iterator`, `from architecture.app.stages import progress_event`, `from shared_kernel.errors import MassingError, MassingErrorCode`):

```python
    def stream(
        self,
        req: GenerateMassingRequest,
        *,
        user_id: UUID,
        user_sub: str | None,
    ) -> Iterator[dict]:
        """진행 + 터미널 이벤트 제너레이터 (tool-streaming spec D1/W1).

        graph.stream(stream_mode=["debug","values"], subgraphs=True):
        debug `task` 페이로드가 노드-시작 신호 (updates 모드는 완료-후라
        "진행 중" 의미 불일치), 외부 그래프의 마지막 `values`가 최종
        state. 이벤트 튜플은 (namespace, mode, payload) — 2026-06-06
        LangGraph 0.2.60 컨테이너 실측으로 검증.

        모든 종료는 정확히 1개의 터미널 이벤트(result | error)로 끝난다.
        heartbeat는 여기 없음 — async 브리지(router)가 주입.
        """
        extract_attempts = 0
        last_values: MassingState | None = None
        try:
            for ns, mode, payload in self._graph.stream(
                {"req": req, "user_id": user_id, "user_sub": user_sub},
                stream_mode=["debug", "values"],
                subgraphs=True,
            ):
                if mode == "values":
                    if not ns:  # 외부 그래프 state만 (서브그래프 values 제외)
                        last_values = payload
                    continue
                if payload.get("type") != "task":
                    continue
                node = payload.get("payload", {}).get("name", "")
                attempt = None
                if node == "extract":
                    extract_attempts += 1
                    attempt = extract_attempts
                ev = progress_event(node, attempt)
                if ev is not None:
                    yield ev

            if last_values is None or "response" not in last_values:
                raise MassingError(
                    MassingErrorCode.INTERNAL, "graph finished without a response"
                )
            response: GenerateMassingResponse = last_values["response"]
            yield {"event": "result", **response.model_dump(by_alias=True, mode="json")}
        except MassingError as exc:
            yield {
                "event": "error",
                "code": exc.code.value,
                "message": exc.message,
                "status": int(exc.code.http_status),
            }
        except Exception as exc:  # noqa: BLE001 — 스트림은 항상 터미널 이벤트로 끝난다
            logger.exception("massing stream failed")
            yield {
                "event": "error",
                "code": MassingErrorCode.INTERNAL.value,
                "message": str(exc),
                "status": 500,
            }
```

`run()`은 그대로 유지 (동기 경로 + 기존 테스트). 모듈 docstring에 stream 한 줄 추가.

- [ ] **Step 5: 통과 + 전체** — `uv run pytest tests/ -q` → **104 passed** (101 + 3).

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/stages.py \
        backend/fastapi/agent-tools/architecture/app/workflow.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): MassingWorkflow.stream — node-start progress + terminal events"
```

---

### Task 2: agent-tools — NDJSON 엔드포인트 + heartbeat 브리지 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/routers/tools.py`
- Modify: `backend/fastapi/agent-tools/shared_kernel/config.py`
- Modify: `backend/fastapi/agent-tools/shared_kernel/llm.py`
- Test: `backend/fastapi/agent-tools/tests/test_stream_endpoint.py` (Create)

- [ ] **Step 1: config + llm 준비**

`shared_kernel/config.py` — `# --- Server ---` 직전에:

```python
    # --- Tool streaming (tool-streaming spec D1/D4) ---
    # NDJSON heartbeat 발신 간격. rag-chat idle(60s)의 1/6 — 일시 지연 여유.
    stream_heartbeat_seconds: float = Field(default=10.0)
```

`shared_kernel/llm.py` — `kwargs`에 `max_retries=1,` 추가 + 주석:

```python
        # 호출당 상한 = timeout × (1 + max_retries) = 120s × 2 — rag-chat의
        # total cap(600s) 안에서 예측 가능 (tool-streaming spec D4).
        max_retries=1,
```

- [ ] **Step 2: 실패 테스트** — `tests/test_stream_endpoint.py` 생성:

```python
"""NDJSON 스트림 엔드포인트 (tool-streaming spec W1) — 시퀀스/heartbeat/한글."""

from __future__ import annotations

import json
import time
import uuid

from fastapi.testclient import TestClient

from shared_kernel.config import Settings, get_settings


class _StubWorkflow:
    """스크립트된 이벤트를 흘리는 워크플로 대역 — 브리지/직렬화만 검증."""

    def __init__(self, events: list[dict], delay_s: float = 0.0):
        self._events = events
        self._delay_s = delay_s

    def stream(self, req, *, user_id, user_sub):
        for ev in self._events:
            if self._delay_s:
                time.sleep(self._delay_s)
            yield ev


def _lines(client: TestClient, app) -> list[dict]:
    with client.stream(
        "POST",
        "/internal/tools/generate-massing",
        json={"briefDocId": str(uuid.uuid4())},
        headers={"X-User-Id": str(uuid.uuid4())},
    ) as r:
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("application/x-ndjson")
        return [json.loads(line) for line in r.iter_lines() if line]


def _make_client(stub: _StubWorkflow, heartbeat_s: float = 10.0):
    from main import app

    client = TestClient(app)
    client.__enter__()  # lifespan 실행 (app.state 초기화)
    app.state.workflow = stub
    app.dependency_overrides[get_settings] = lambda: Settings(
        stream_heartbeat_seconds=heartbeat_s
    )
    return client, app


def test_streams_events_as_ndjson_lines():
    events = [
        {"event": "progress", "stage": "fetch_brief", "label": "브리프 조회",
         "stageIndex": 1, "stageCount": 10},
        {"event": "result", "result": {"summary": "2실"}, "artifact": {"storageKey": "k.3dm"}},
    ]
    client, app = _make_client(_StubWorkflow(events))
    try:
        lines = _lines(client, app)
        assert lines == events  # 한글 라벨 포함 그대로 (ensure_ascii=False)
    finally:
        app.dependency_overrides.clear()
        client.__exit__(None, None, None)


def test_heartbeat_injected_when_idle():
    events = [
        {"event": "progress", "stage": "extract", "label": "공간 프로그램 추출 중",
         "stageIndex": 3, "stageCount": 10},
        {"event": "result", "result": {}, "artifact": {}},
    ]
    # 이벤트 간 0.25s 지연, heartbeat 0.05s → 이벤트 사이에 heartbeat ≥ 1개.
    client, app = _make_client(_StubWorkflow(events, delay_s=0.25), heartbeat_s=0.05)
    try:
        lines = _lines(client, app)
        assert {"event": "heartbeat"} in lines
        non_hb = [l for l in lines if l.get("event") != "heartbeat"]
        assert non_hb == events
    finally:
        app.dependency_overrides.clear()
        client.__exit__(None, None, None)
```

- [ ] **Step 3: 실패 확인** — `uv run pytest tests/test_stream_endpoint.py -v` → FAIL (엔드포인트가 단일 JSON 반환 / content-type 불일치).

주의: `SettingsDep`의 실제 의존성 함수가 `get_settings`가 아니면 (shared_kernel/context.py 확인) override 키를 실제 함수로 맞추고 보고할 것.

- [ ] **Step 4: tools.py 재작성**

```python
"""Tool entry-point router (ADR-19 §D2) — NDJSON 스트리밍 (tool-streaming spec W1).

POST /internal/tools/generate-massing 은 application/x-ndjson 스트림을
반환한다: progress*(노드 시작) → heartbeat*(10s 무이벤트) → result|error
터미널 1개. 스트림 시작 후 HTTP status는 200 고정 — 모든 실패는 error
이벤트 (MassingError 핸들러는 스트림 전 단계 — 인증/검증 — 만 커버).
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import AsyncIterator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from architecture.api.deps import SettingsDep, UserContextDep, WorkflowDep
from architecture.api.dtos import GenerateMassingRequest
from architecture.app.workflow import MassingWorkflow
from shared_kernel.context import UserContext

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/tools", tags=["tools"])


@router.post("/generate-massing")
async def generate_massing(
    req: GenerateMassingRequest,
    user: UserContextDep,
    workflow: WorkflowDep,
    settings: SettingsDep,
) -> StreamingResponse:
    logger.info(
        "generate_massing requested",
        extra={"brief_doc_id": str(req.brief_doc_id), "user_id": str(user.user_id)},
    )
    return StreamingResponse(
        _ndjson_events(workflow, req, user, settings.stream_heartbeat_seconds),
        media_type="application/x-ndjson",
    )


async def _ndjson_events(
    workflow: MassingWorkflow,
    req: GenerateMassingRequest,
    user: UserContext,
    heartbeat_seconds: float,
) -> AsyncIterator[bytes]:
    """동기 `workflow.stream()`을 워커 스레드에서 돌리고 asyncio 큐로 브리지.

    `heartbeat_seconds` 동안 이벤트가 없으면 heartbeat 줄을 주입 — rag-chat
    idle 타이머 리셋 전용 (rag-chat이 필터). 한글 라벨은 ensure_ascii=False."""
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue[dict | None] = asyncio.Queue()

    def produce() -> None:
        try:
            for ev in workflow.stream(req, user_id=user.user_id, user_sub=user.user_sub):
                loop.call_soon_threadsafe(queue.put_nowait, ev)
        finally:
            loop.call_soon_threadsafe(queue.put_nowait, None)  # 종료 센티널

    producer = loop.run_in_executor(None, produce)
    try:
        while True:
            try:
                ev = await asyncio.wait_for(queue.get(), timeout=heartbeat_seconds)
            except TimeoutError:
                yield b'{"event":"heartbeat"}\n'
                continue
            if ev is None:
                break
            yield json.dumps(ev, ensure_ascii=False).encode("utf-8") + b"\n"
    finally:
        await producer
```

(기존 `response_model=GenerateMassingResponse` 제거 — import 정리. `SettingsDep`이 deps.py re-export에 없으면 추가.)

- [ ] **Step 5: 통과 + 전체** — `uv run pytest tests/ -q` → **106 passed** (104 + 2).

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/routers/tools.py \
        backend/fastapi/agent-tools/shared_kernel/config.py \
        backend/fastapi/agent-tools/shared_kernel/llm.py \
        backend/fastapi/agent-tools/tests/test_stream_endpoint.py
git commit -m "feat(agent-tools): NDJSON streaming endpoint with heartbeat bridge"
```

---

### Task 3: rag-chat — descriptor 확장 + 디스패처 스트림 소비 (TDD)

**Files:**
- Modify: `backend/springboot/rag-chat/rag-chat-domain/src/main/java/com/playground/ragchat/domain/tool/ToolDescriptor.java`
- Modify: `backend/springboot/rag-chat/rag-chat-domain/src/main/java/com/playground/ragchat/domain/tool/MassingTool.java`
- Modify: `backend/springboot/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/port/ToolDispatcherPort.java`
- Modify: `backend/springboot/rag-chat/rag-chat-infra/src/main/java/com/playground/ragchat/infrastructure/tool/WebClientToolDispatcher.java`
- Modify: `backend/springboot/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/service/ChatTurnService.java` (호출부 — no-op 리스너; 릴레이는 Task 4)
- Test: `backend/springboot/rag-chat/rag-chat-infra/src/test/java/com/playground/ragchat/infrastructure/tool/WebClientToolDispatcherTest.java` + `ToolCallingE2ETest.java`

먼저 세 파일(ToolDescriptor, WebClientToolDispatcher, WebClientToolDispatcherTest)을 전부 읽을 것 — 아래 코드는 목표 형태이고 기존 헬퍼/스타일에 맞춘다.

- [ ] **Step 1: ToolDescriptor + MassingTool**

`ToolDescriptor`:

```java
public record ToolDescriptor(
        String name,
        String displayName,       // FE in-flight 카드 표시명 (tool-streaming spec W2)
        String description,
        String parameterSchema,
        URI endpoint,
        Duration timeout,         // IDLE 타임아웃 — NDJSON 신호-간 무이벤트 한계 (spec D2)
        Duration totalTimeout)    // 절대 상한 — blockLast 캡 (spec D4)
```

javadoc에 idle/total 의미 명시. `MassingTool.MASSING`:

```java
    public static final ToolDescriptor MASSING = new ToolDescriptor(
            "generate_massing",
            "매싱 모델",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(60),    // idle — heartbeat(10s)의 6배 여유
            Duration.ofSeconds(600));  // total cap — 파이프라인 최악치 여유
```

(기존 120s javadoc 문단을 idle/total 설명으로 교체.)

- [ ] **Step 2: ToolDispatcherPort — ToolProgress + 리스너**

```java
    /** NDJSON progress 이벤트의 app-layer 사본 (tool-streaming spec W2). */
    record ToolProgress(
            String id,
            String name,
            String stage,
            String label,
            int stageIndex,
            int stageCount,
            Integer attempt) {}   // null = 첫 시도

    ToolInvocationResult invoke(
            String id,
            String name,
            JsonNode args,
            UserContext userCtx,
            java.util.function.Consumer<ToolProgress> onProgress);
```

`ChatTurnService`의 호출부는 이 태스크에서 `progress -> { }` no-op 람다로 컴파일만 유지 (릴레이는 Task 4).

- [ ] **Step 3: 실패 테스트** — `WebClientToolDispatcherTest`에 NDJSON 케이스 추가 (기존 WireMock 헬퍼 스타일 준수; 기존 단일-JSON 케이스들은 NDJSON 터미널-only 바디로 변환):

```java
    private static String ndjson(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void streamsProgressThenResult() {
        stubFor(post("/internal/tools/generate-massing").willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(ndjson(
                        "{\"event\":\"progress\",\"stage\":\"extract\",\"label\":\"공간 프로그램 추출 중\",\"stageIndex\":3,\"stageCount\":10}",
                        "{\"event\":\"progress\",\"stage\":\"compute\",\"label\":\"매싱 계산\",\"stageIndex\":7,\"stageCount\":10,\"attempt\":2}",
                        "{\"event\":\"result\",\"result\":{\"summary\":\"2실\"},\"artifact\":{\"storageKey\":\"k.3dm\",\"filename\":\"k.3dm\",\"contentType\":\"application/octet-stream\",\"sizeBytes\":5}}"))));

        List<ToolDispatcherPort.ToolProgress> seen = new ArrayList<>();
        ToolInvocationResult r = dispatcher.invoke("t1", "generate_massing",
                args(), userCtx(), seen::add);

        assertThat(r).isInstanceOf(ToolInvocationResult.Success.class);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).stage()).isEqualTo("extract");
        assertThat(seen.get(0).attempt()).isNull();
        assertThat(seen.get(1).attempt()).isEqualTo(2);
    }

    @Test
    void errorEventMapsToFailureWithCodePrefix() {
        stubFor(post("/internal/tools/generate-massing").willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(ndjson(
                        "{\"event\":\"error\",\"code\":\"BRIEF_NOT_READY\",\"message\":\"site area missing\",\"status\":422}"))));

        ToolInvocationResult r = dispatcher.invoke("t1", "generate_massing",
                args(), userCtx(), p -> { });

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) r;
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_4XX);
        assertThat(f.message()).isEqualTo("BRIEF_NOT_READY: site area missing");
    }

    @Test
    void errorEventStatus5xxMapsToUpstream5xx() {
        stubFor(post("/internal/tools/generate-massing").willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(ndjson(
                        "{\"event\":\"error\",\"code\":\"SIDECAR_FAILED\",\"message\":\"llm down\",\"status\":502}"))));

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure)
                dispatcher.invoke("t1", "generate_massing", args(), userCtx(), p -> { });
        assertThat(f.code()).isEqualTo(ToolErrorCode.UPSTREAM_5XX);
    }

    @Test
    void idleTimeoutTripsWhenNoEvents() {
        // idle 짧은 디스크립터로 디스패처 호출 — 바디를 idle보다 길게 지연.
        stubFor(post("/internal/tools/generate-massing").willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(ndjson("{\"event\":\"result\",\"result\":{},\"artifact\":{\"storageKey\":\"k.3dm\",\"filename\":\"k\",\"contentType\":\"x\",\"sizeBytes\":1}}"))
                .withFixedDelay(800)));   // idle 300ms 초과

        ToolInvocationResult r = invokeWithTimeouts(Duration.ofMillis(300), Duration.ofSeconds(5));
        assertThat(((ToolInvocationResult.Failure) r).code()).isEqualTo(ToolErrorCode.TIMEOUT);
    }

    @Test
    void streamWithoutTerminalEventIsInternal() {
        stubFor(post("/internal/tools/generate-massing").willReturn(aResponse()
                .withHeader("Content-Type", "application/x-ndjson")
                .withBody(ndjson(
                        "{\"event\":\"progress\",\"stage\":\"extract\",\"label\":\"l\",\"stageIndex\":3,\"stageCount\":10}",
                        "{\"event\":\"heartbeat\"}"))));

        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure)
                dispatcher.invoke("t1", "generate_massing", args(), userCtx(), p -> { });
        assertThat(f.code()).isEqualTo(ToolErrorCode.INTERNAL);
    }
```

(`invokeWithTimeouts` — 테스트 로컬 헬퍼: WireMock URL + 커스텀 idle/total의 ToolDescriptor로 디스패처 직접 호출. 기존 테스트가 디스크립터를 어떻게 주입하는지 읽고 동일 패턴 사용. heartbeat-리셋 단독 케이스는 WireMock 고정지연 모델상 타이밍-플레이키라 생략 — idle 케이스가 의미론을 커버.)

- [ ] **Step 4: 실패 확인** — `./gradlew :rag-chat:rag-chat-infra:test --tests "*WebClientToolDispatcherTest"` → 컴파일 FAIL (시그니처).

- [ ] **Step 5: 디스패처 구현** — `WebClientToolDispatcher`의 transport 부분 교체:

```java
        AtomicReference<JsonNode> terminal = new AtomicReference<>();
        client.post()
                .uri(descriptor.endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("X-User-Id", userCtx.userId().value().toString())
                .headers(h -> { /* 기존 X-User-Sub 로직 그대로 */ })
                .bodyValue(argsToSend)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                // Reactor의 Flux.timeout = 신호-간 idle 타임아웃 — heartbeat가
                // 타이머를 리셋한다 (tool-streaming spec D2).
                .timeout(descriptor.timeout(), Flux.error(
                        new TimeoutException("Tool '" + descriptor.name()
                                + "' sent no event within " + descriptor.timeout())))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnNext(node -> {
                    String event = node.path("event").asText("");
                    switch (event) {
                        case "progress" -> emitProgress(onProgress, id, descriptor.name(), node);
                        case "result", "error" -> terminal.compareAndSet(null, node);
                        case "heartbeat" -> { /* idle 리셋 효과만 — 무시 */ }
                        default -> log.debug("tool_stream_unknown_event tool={} event={}",
                                descriptor.name(), event);
                    }
                })
                .blockLast(descriptor.totalTimeout());

        JsonNode t = terminal.get();
        if (t == null) {
            return new ToolInvocationResult.Failure(id, descriptor.name(),
                    ToolErrorCode.INTERNAL, "tool stream ended without a terminal event");
        }
        if ("error".equals(t.path("event").asText())) {
            int status = t.path("status").asInt(400);
            ToolErrorCode code = status >= 500 ? ToolErrorCode.UPSTREAM_5XX : ToolErrorCode.UPSTREAM_4XX;
            String message = t.path("code").asText("INTERNAL") + ": " + t.path("message").asText("");
            return new ToolInvocationResult.Failure(id, descriptor.name(), code, message);
        }
        // result 이벤트 = 기존 {result, artifact} envelope (+event 필드) —
        // 기존 envelope 파싱 경로에 t를 그대로 넘긴다.
```

```java
    private void emitProgress(Consumer<ToolDispatcherPort.ToolProgress> onProgress,
            String id, String name, JsonNode node) {
        try {
            onProgress.accept(new ToolDispatcherPort.ToolProgress(
                    id, name,
                    node.path("stage").asText(""),
                    node.path("label").asText(""),
                    node.path("stageIndex").asInt(0),
                    node.path("stageCount").asInt(0),
                    node.hasNonNull("attempt") ? node.get("attempt").asInt() : null));
        } catch (RuntimeException e) {
            // 진행 표시는 best-effort — 리스너 예외가 스트림을 죽이면 안 된다.
            log.warn("tool_progress_listener_failed tool={} reason={}", name, e.toString());
        }
    }
```

`classify()`에 분기 추가: `blockLast` 총량 캡 초과는 `IllegalStateException`("Timeout on blocking read ...")로 옴 →

```java
        if (cause instanceof IllegalStateException ise
                && ise.getMessage() != null
                && ise.getMessage().contains("Timeout on blocking read")) {
            log.info("tool_total_cap tool={} cap={}", name, descriptor.totalTimeout());
            return new ToolInvocationResult.Failure(id, name, ToolErrorCode.TIMEOUT,
                    "Tool '" + name + "' did not finish within " + descriptor.totalTimeout());
        }
```

(classify 시그니처가 descriptor에 접근 못 하면 메시지에서 cap 값 생략 — 기존 구조에 맞춰 조정.) 기존 envelope 파싱(SCHEMA_INVALID 경로 포함)은 입력만 `byte[]`→`JsonNode`로 바뀌므로 재사용/적응. 클래스 javadoc을 NDJSON 계약으로 갱신. **브레이커**: transport 예외(idle TimeoutException·IOException·non-2xx)는 operator를 통해 기존대로 기록; 도메인 error 이벤트는 예외가 아니므로 비기록 (spec D2 의미 변화 — javadoc에 명시).

`ToolCallingE2ETest`의 도구 응답 스텁을 NDJSON 터미널-only로 변환.

- [ ] **Step 6: 통과 + 빌드** — `./gradlew :rag-chat:rag-chat-infra:test :rag-chat:rag-chat-app:build :rag-chat:rag-chat-domain:build` → green.

- [ ] **Step 7: Commit**

```bash
git add backend/springboot/rag-chat/
git commit -m "feat(rag-chat): NDJSON streaming tool dispatch — idle timeout + progress listener"
```

---

### Task 4: rag-chat — progress SSE 릴레이 + displayName (TDD)

**Files:**
- Modify: `ChatStreamEvent`(sealed interface — ChatTurnService와 같은 모듈에서 grep으로 위치 확인)
- Modify: `backend/springboot/rag-chat/rag-chat-app/.../service/ChatTurnService.java`
- Modify: `backend/springboot/rag-chat/rag-chat-api/.../controller/ChatStreamController.java`
- Test: `ChatStreamControllerTest.java` (toSse 매핑)

- [ ] **Step 1: 실패 테스트** — `ChatStreamControllerTest`에 (기존 테스트 스타일 읽고 맞출 것):

```java
    @Test
    void toSse_mapsToolProgress() {
        var evt = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "extract", "공간 프로그램 추출 중", 3, 10, 2);
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("tool_progress");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("stage", "extract")
                .containsEntry("label", "공간 프로그램 추출 중")
                .containsEntry("stageIndex", 3)
                .containsEntry("stageCount", 10)
                .containsEntry("attempt", 2);
    }

    @Test
    void toSse_toolProgressOmitsNullAttempt() {
        var evt = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "compute", "매싱 계산", 7, 10, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)
                ChatStreamController.toSse(evt).data();
        assertThat(data).doesNotContainKey("attempt");
    }

    @Test
    void toSse_toolCallCarriesDisplayName() {
        var evt = new ChatStreamEvent.ToolCall("t1", "generate_massing", "매싱 모델",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)
                ChatStreamController.toSse(evt).data();
        assertThat(data).containsEntry("displayName", "매싱 모델");
    }
```

- [ ] **Step 2: 구현**

`ChatStreamEvent`: `ToolCall(id, name, displayName, args)` 필드 추가 + 신규

```java
    /** 도구 진행 이벤트 — NDJSON progress의 SSE 릴레이 (tool-streaming spec W2). */
    record ToolProgress(String id, String name, String stage, String label,
            int stageIndex, int stageCount, Integer attempt) implements ChatStreamEvent {}
```

`ChatTurnService` 도구 호출부:

```java
        sink.tryEmitNext(new ChatStreamEvent.ToolCall(id, desc.name(), desc.displayName(), args));
        ToolInvocationResult result = toolDispatcherPort.invoke(
                id, desc.name(), args, userCtx,
                p -> sink.tryEmitNext(new ChatStreamEvent.ToolProgress(
                        p.id(), p.name(), p.stage(), p.label(),
                        p.stageIndex(), p.stageCount(), p.attempt())));
```

`ChatStreamController.toSse`: tool_call 매핑에 `data.put("displayName", tc.displayName())` (null이면 생략), 신규:

```java
        if (evt instanceof ChatStreamEvent.ToolProgress tp) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", tp.id());
            data.put("name", tp.name());
            data.put("stage", tp.stage());
            data.put("label", tp.label());
            data.put("stageIndex", tp.stageIndex());
            data.put("stageCount", tp.stageCount());
            if (tp.attempt() != null) {
                data.put("attempt", tp.attempt());
            }
            return ServerSentEvent.<Object>builder((Object) data).event("tool_progress").build();
        }
```

ToolCall 시그니처 변경에 따른 기존 사용처/테스트 전부 수정.

- [ ] **Step 3: 통과 + 전 모듈** — `./gradlew :rag-chat:rag-chat-api:test :rag-chat:rag-chat-app:test :rag-chat:rag-chat-infra:test` → green.

- [ ] **Step 4: Commit**

```bash
git add backend/springboot/rag-chat/
git commit -m "feat(rag-chat): tool_progress SSE relay + tool_call displayName"
```

---

### Task 5: FE — 제네릭 ToolRunCard + 스트림 머지

**Files:**
- Modify: `frontend/src/shared/api/chat.ts`, `frontend/src/shared/api/chat.sse.ts`
- Modify: `frontend/src/entities/chat/types.ts`
- Modify: `frontend/src/features/chat-stream/useChatStream.ts`
- Create: `frontend/src/features/chat-tool-card/ToolRunCard.tsx`
- Modify: `frontend/src/features/chat-tool-card/ToolCardList.tsx`, `MassingResultCard.tsx`, `index.ts`

먼저 다섯 파일을 전부 읽을 것 (특히 useChatStream의 tool_call/tool_result 리듀서 라인 251-330, types.ts의 ToolCardState 유니온).

- [ ] **Step 1: 타입 + 파서**

`chat.ts`: `ToolCallEventPayload`(또는 동등 — tool_call payload 타입)에 `displayName?: string;` 추가, 신규:

```typescript
/** `tool_progress` SSE — 도구 노드 진행 (tool-streaming spec W2). FE는 label verbatim 렌더. */
export interface ToolProgressEventPayload {
  id: string;
  name: string;
  stage: string;
  label: string;
  stageIndex: number;
  stageCount: number;
  attempt?: number;
}
```

`chat.sse.ts`: tool_call 파싱에 `displayName: typeof body.displayName === 'string' ? body.displayName : undefined` (tool_call의 body 접근 방식은 파일의 기존 패턴 — raw 최상위 — 을 따른다), 신규 케이스:

```typescript
    case 'tool_progress': {
      const raw = parsed as Record<string, unknown>;
      if (
        typeof raw.id !== 'string' || typeof raw.stage !== 'string' ||
        typeof raw.label !== 'string' || typeof raw.stageIndex !== 'number' ||
        typeof raw.stageCount !== 'number'
      ) {
        return null; // malformed — 스트림 계속 (기존 unknown-event 처리 방식 준수)
      }
      const payload: ToolProgressEventPayload = {
        id: raw.id,
        name: typeof raw.name === 'string' ? raw.name : '',
        stage: raw.stage,
        label: raw.label,
        stageIndex: raw.stageIndex,
        stageCount: raw.stageCount,
        attempt: typeof raw.attempt === 'number' ? raw.attempt : undefined,
      };
      return { type: 'tool_progress', payload };
    }
```

(SSE 이벤트 유니온 타입에 `tool_progress` 멤버 추가 — 파일의 기존 유니온 정의를 찾아 확장. malformed 처리도 파일의 기존 컨벤션에 맞출 것 — `return null`이 안 맞으면 동등한 skip 패턴.)

- [ ] **Step 2: 카드 상태**

`entities/chat/types.ts`의 in_flight 변형:

```typescript
  | {
      kind: 'in_flight';
      toolCall: ToolCallPayload;
      calledAt: number;
      /** 서버 발신 표시명 (tool_call.displayName) — 제네릭 ToolRunCard가 렌더. */
      displayName?: string;
      /** 최신 tool_progress — 없으면 "Running…" fallback. */
      progress?: ToolProgressEventPayload;
    }
```

`useChatStream.ts`: tool_call 분기에서 `displayName: ev.payload.displayName` 포함; 신규 분기 (tool_result 분기 앞):

```typescript
      } else if (ev.type === 'tool_progress') {
        // 같은 id의 in-flight 카드에 최신 진행 상태 머지.
        next.toolCards = next.toolCards.map((c) =>
          c.kind === 'in_flight' && c.toolCall.id === ev.payload.id
            ? { ...c, progress: ev.payload }
            : c,
        );
      }
```

(리듀서의 실제 상태 갱신 방식 — 변수명 `next` 등 — 은 파일의 기존 tool_call/tool_result 분기 패턴에 맞출 것.)

- [ ] **Step 3: ToolRunCard 생성**

```tsx
'use client';

import { Cog } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * 제네릭 in-flight 도구 카드 — 전부 wire 데이터로 구동 (tool-streaming
 * spec D3): 이름은 `tool_call.displayName ?? name`, 본문은 최신
 * `tool_progress`의 한국어 label + (시도 N), 핍 바는 stageCount/stageIndex.
 * progress가 아직 없으면(미전환 도구·이벤트 도착 전) "Running…" fallback.
 * 새 도구는 FE 코드 0줄로 진행 표시를 얻는다 — 결과 카드만 툴별 등록.
 */
export function ToolRunCard({
  state,
}: {
  state: Extract<ToolCardState, { kind: 'in_flight' }>;
}) {
  const name = state.displayName ?? state.toolCall.name;
  const p = state.progress;
  return (
    <ToolResultCard
      ariaLabel={`Tool call in flight: ${name}`}
      icon={<Cog size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={<span className="text-[14px] font-semibold text-text">{name}</span>}
      summary={
        <span className="inline-flex items-center gap-sm text-text-muted">
          <Spinner />
          <span>{p ? `${p.label}…` : 'Running…'}</span>
          {p?.attempt != null && p.attempt >= 2 && (
            <span className="text-[11px] text-text-subtle">(시도 {p.attempt})</span>
          )}
        </span>
      }
      primaryAction={null}
      footer={
        p ? (
          <div className="flex items-center gap-xs" aria-hidden="true">
            {Array.from({ length: p.stageCount }, (_, i) => (
              <span
                key={i}
                className={cn(
                  'h-[4px] w-[22px] rounded-full',
                  i + 1 < p.stageIndex
                    ? 'bg-accent/60'
                    : i + 1 === p.stageIndex
                      ? 'animate-pulse bg-accent'
                      : 'bg-border',
                )}
              />
            ))}
          </div>
        ) : null
      }
    />
  );
}

function Spinner() {
  // MassingResultCard에서 이동 — in-flight 분기 삭제로 유일 소비자가 됐다.
  return (
    <span
      aria-hidden="true"
      className="inline-block h-[12px] w-[12px] animate-tool-spinner rounded-full border-2 border-border border-t-accent"
    />
  );
}
```

- [ ] **Step 4: 분기 재배선**

- `ToolCardList.tsx`: `card.kind === 'in_flight'` → `<ToolRunCard state={card} />`; result → 기존 MassingResultCard. 파일 상단 라우팅 주석 갱신.
- `MassingResultCard.tsx`: in-flight 분기 + `Spinner` 삭제; props 타입을 `Extract<ToolCardState, { kind: 'result' }>`로 좁힘; JSDoc의 "In-flight state" 문단을 "in-flight은 제네릭 ToolRunCard가 담당 (tool-streaming spec D3)"로 교체.
- `index.ts`: ToolRunCard export 추가.

- [ ] **Step 5: 검증** — `pnpm typecheck && pnpm lint && pnpm build` → 전부 clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/
git commit -m "feat(frontend): generic ToolRunCard — wire-driven progress (stage label + pips)"
```

---

### Task 6: 통합 검증 + spec/design-doc 동기화

**Files:**
- Modify: `docs/superpowers/specs/2026-06-06-tool-streaming-progress-design.md`
- Modify: `docs/design/M6-M8-brief-to-massing.md`

- [ ] **Step 1: 재빌드** (worktree 루트에서; `--env-file infra/.env`; worktree에 infra/.env 없으면 메인 체크아웃에서 복사):

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build agent-tools rag-chat-api frontend
```

3종 healthy 대기 후 신코드 확인:
`docker exec agent-tools python -c "from architecture.app.stages import STAGE_COUNT; print(STAGE_COUNT)"` → `10`.

- [ ] **Step 2: 스트림 실관측** — 내부 엔드포인트를 스트리밍으로 호출해 progress 줄이 실시간으로 나오는지:

```bash
docker exec agent-tools python -c "
import httpx, time
t0 = time.time()
with httpx.stream('POST', 'http://localhost:18083/internal/tools/generate-massing',
    json={'briefDocId': '7e816ae7-b528-43d9-82ab-e0c6668412f4'},
    headers={'X-User-Id': '690df887-12c0-4165-944e-28426702634f'}, timeout=600.0) as r:
    assert r.status_code == 200, r.status_code
    for line in r.iter_lines():
        if line:
            print(f'{time.time()-t0:6.1f}s', line[:160])
"
```

Expected: progress 줄들이 단계별 시점에 출력 (extract 전후로 시간 간격 큼, 그 사이 heartbeat), 마지막 줄 `"event":"result"`. **이 출력은 보고에 포함** — idle/heartbeat 동작의 실증.

- [ ] **Step 3: spec amendment** — spec의 W1 섹션 error 예시를 `{"event":"error","code":"BRIEF_NOT_READY","message":"…","status":422}`로 갱신하고 끝에:

```markdown
**구현 중 확정된 deviation (2026-06-06 plan):** `error` 이벤트에 `status`
(HTTP status int) 추가 — rag-chat이 기존 UPSTREAM_4XX/5XX 분류를 유지하는
근거 (agent-tools는 `MassingErrorCode.http_status`를 이미 보유).
```

- [ ] **Step 4: design-doc 노트** — `docs/design/M6-M8-brief-to-massing.md`의 glb-extras 인용 블록 뒤에:

```markdown
> **2026-06-06 — 도구 스트리밍 + 진행 표시:** 도구 wire가 NDJSON 스트리밍
> (progress/heartbeat/result|error)으로 전환 — 타임아웃 기준이 총량(120s)에서
> idle(60s, total cap 600s)로 바뀌어 LLM-bound 장시간 실행이 안전하다.
> in-flight 카드는 제네릭 `ToolRunCard`: 서버 발신 displayName + 한국어 단계
> label + (시도 N) + stageCount 핍 바 — 전부 wire 구동이라 새 도구는 FE 코드
> 없이 진행 표시를 얻는다. Spec:
> `docs/superpowers/specs/2026-06-06-tool-streaming-progress-design.md`.
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-06-06-tool-streaming-progress-design.md \
        docs/design/M6-M8-brief-to-massing.md
git commit -m "docs: tool-streaming spec deviation (error.status) + design-doc note"
```

- [ ] **Step 6: 수동 E2E (사용자)** — 채팅에서 매싱 생성 → in-flight 카드에 단계 진행(레이블·핍) 표시 → 완료 카드 정상 → TIMEOUT 미발생.

---

## Self-Review

1. **Spec coverage:** W1(NDJSON·progress·heartbeat·터미널·한글)→T1·T2, W2(tool_progress·displayName)→T4·T5, D1(debug+values·스테이지 맵·attempt·error 변환·run 유지)→T1, D1 heartbeat 브리지→T2, D2(descriptor·idle Flux.timeout·total blockLast·이벤트 분기·브레이커 의미 변화·리스너)→T3, D3(ToolRunCard·fallback·in-flight 분기 삭제·머지)→T5, D4 정합표(60/600/10/120×2 — llm.py max_retries)→T2·T3, Error Handling 표 전 행→T1(error 이벤트)·T3(idle/total/터미널 없음)·T5(malformed 무시·fallback), Backward Compat(원자 배포·optional 필드)→T5·T6, Testing 절 전 항목→T1/T2/T3/T4/T5, Out of Scope 침범 없음. 누락 없음.
2. **Placeholder scan:** 없음. "기존 패턴에 맞출 것" 류 지시는 대상 파일 선독(先讀) 지시와 목표 코드를 함께 제공 — 의도된 적응 지점.
3. **Type consistency:** `progress_event(node, attempt)` T1 def ↔ stream() 호출 ✓; `stream() -> Iterator[dict]` T1 ↔ T2 브리지의 `workflow.stream(...)` ✓; `ToolProgress(id,name,stage,label,stageIndex,stageCount,attempt)` 순서 T3 record ↔ T4 릴레이 생성자 ↔ T5 `ToolProgressEventPayload` 필드 ✓; `ToolDescriptor(name, displayName, description, parameterSchema, endpoint, timeout, totalTimeout)` T3 ↔ MassingTool 생성자 ✓; error 이벤트 `status` T1 발신 ↔ T3 `path("status")` ↔ T6 spec 기록 ✓; SSE 와이어명 `tool_progress` T4 ↔ T5 파서 ✓.
