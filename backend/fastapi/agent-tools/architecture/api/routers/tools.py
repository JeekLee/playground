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
