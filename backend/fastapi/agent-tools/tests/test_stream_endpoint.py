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


def test_refine_streams_events_as_ndjson_lines():
    from main import app
    events = [
        {"event": "progress", "stage": "load_recipe", "label": "기존 매싱 불러오기",
         "stageIndex": 1, "stageCount": 8},
        {"event": "result", "result": {"summary": "2실"}, "artifact": {"storageKey": "k.3dm"}},
    ]
    client = TestClient(app)
    client.__enter__()
    app.state.refine_workflow = _StubWorkflow(events)
    app.dependency_overrides[get_settings] = lambda: Settings(stream_heartbeat_seconds=10.0)
    try:
        with client.stream(
            "POST", "/internal/tools/refine-massing",
            json={"baseStorageKey": "architecture/massing/x/y.3dm",
                  "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]},
            headers={"X-User-Id": str(uuid.uuid4())},
        ) as r:
            assert r.status_code == 200
            assert r.headers["content-type"].startswith("application/x-ndjson")
            lines = [json.loads(line) for line in r.iter_lines() if line]
        assert lines == events
    finally:
        app.dependency_overrides.clear()
        client.__exit__(None, None, None)
