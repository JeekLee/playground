"""MassingWorkflow LangGraph behavior test (ADR-19 Phase 2b).

Proves the graph sequences fetch_brief → extract → massing → serialize →
persist and produces the expected GenerateMassingResponse, using stubbed
docs/LLM clients and a fake DB session (no real Postgres). This is the
behavior-identical guard for the linear→graph migration; the real LLM/docs/DB
path is covered by the container E2E.
"""

from __future__ import annotations

import contextlib
import uuid
from types import SimpleNamespace

from architecture import workflow as wf
from architecture.models import GenerateMassingRequest
from shared_kernel.config import Settings


class _FakeDocs:
    def get_document(self, doc_id, *, user_id, user_sub):
        return SimpleNamespace(
            extraction_status="extracted",
            body="대지면적 20m x 10m. 로비 40m². 사무실 30m².",
            title="테스트 브리프",
        )


class _FakeLlm:
    def complete_json(self, system_prompt: str, user_prompt: str) -> str:
        return (
            '{"site": {"width": 20.0, "depth": 10.0}, '
            '"rooms": [{"name": "로비", "areaM2": 40.0}, {"name": "사무실", "areaM2": 30.0}]}'
        )


_FIXED_ID = uuid.UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")


class _FakeSession:
    def add(self, row):
        self._row = row

    def flush(self):
        # Postgres would assign id via server_default; emulate it.
        self._row.id = _FIXED_ID


@contextlib.contextmanager
def _fake_session_scope():
    yield _FakeSession()


def test_graph_runs_linear_path_and_builds_response(monkeypatch):
    monkeypatch.setattr(wf, "session_scope", _fake_session_scope)

    flow = wf.MassingWorkflow(Settings(), _FakeDocs(), _FakeLlm())
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )

    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)

    # site 20x10 = 200 m² ≥ total 70 m² → 1 floor; 2 rooms.
    assert resp.total_area_m2 == 70.0
    assert resp.floor_count == 1
    assert len(resp.program_json.rooms) == 2
    assert resp.file_url == f"/api/arch/outputs/{_FIXED_ID}"
    assert resp.summary == "2실 · 1층 · 총 70 m²"


def test_graph_has_expected_linear_nodes():
    flow = wf.MassingWorkflow(Settings(), _FakeDocs(), _FakeLlm())
    nodes = set(flow._graph.get_graph().nodes)
    assert {"fetch_brief", "extract", "massing", "serialize", "persist"} <= nodes
