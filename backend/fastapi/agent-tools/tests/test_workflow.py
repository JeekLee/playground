"""MassingWorkflow LangGraph behavior test (ADR-19 §A19.8 / Phase 3a).

Proves the orchestrator sequences fetch_brief → resolve_program(subgraph) →
massing(subgraph) → serialize → persist and produces the expected
GenerateMassingResponse, using a stubbed docs client, an injected fake
extraction chain (no network), and a fake DB session (no real Postgres).
Real LLM/docs/DB + extraction parity is covered by the real-gateway E2E.
"""

from __future__ import annotations

import contextlib
import uuid
from types import SimpleNamespace

from langchain_core.runnables import RunnableLambda

from architecture.app import workflow as wf
from architecture.app.graphs import program_resolution as pr
from architecture.app.nodes import persist as persist_node
from architecture.app.workflow import MassingWorkflow
from architecture.api.dtos import GenerateMassingRequest
from architecture.domain.models import BriefAnalysis
from shared_kernel.config import Settings


class _FakeDocs:
    def get_document(self, doc_id, *, user_id, user_sub):
        return SimpleNamespace(
            extraction_status="extracted",
            body="대지면적 12,000㎡. 업무 20,000㎡, 시험 6,500㎡, 지하주차 4,500㎡.",
            title="KFI 테스트 브리프",
        )


# KFI-like fixed extraction: above 26,500 (2 zones) + below 4,500 (1 zone),
# site 12,000, no floor_limit → resolve defaults to 4 above + 1 basement.
_FIXED_ANALYSIS = BriefAnalysis.model_validate(
    {
        "program": [
            {"name": "업무시설", "area_m2": 20000.0, "grade": "above"},
            {"name": "시험시설", "area_m2": 6500.0, "grade": "above"},
            {"name": "지하주차장", "area_m2": 4500.0, "grade": "below"},
        ],
        "site_area_m2": 12000.0,
    }
)


def _fake_extraction_chain() -> RunnableLambda:
    return RunnableLambda(lambda _inputs: _FIXED_ANALYSIS)


_FIXED_ID = uuid.UUID("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")


class _FakeSession:
    def add(self, row):
        self._row = row

    def flush(self):
        self._row.id = _FIXED_ID


@contextlib.contextmanager
def _fake_session_scope():
    yield _FakeSession()


def _build_workflow() -> MassingWorkflow:
    return MassingWorkflow(
        Settings(),
        _FakeDocs(),
        extraction_chain=_fake_extraction_chain(),
    )


def test_graph_runs_path_and_builds_response(monkeypatch):
    monkeypatch.setattr(persist_node, "session_scope", _fake_session_scope)

    flow = _build_workflow()
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )

    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)

    # total program area = 31,000 m²; 4 above-grade floors + 1 basement.
    assert resp.total_area_m2 == 31000.0
    assert resp.floor_count == 4
    assert resp.basement_levels == 1
    assert len(resp.program_json.rooms) == 3
    assert resp.file_url == f"/api/arch/outputs/{_FIXED_ID}"
    assert resp.summary == "3실 · 지상 4층 + 지하 1층 · 총 31000 m²"


def test_graph_has_expected_nodes_and_subgraphs():
    flow = _build_workflow()
    nodes = set(flow._graph.get_graph().nodes)
    assert {
        "fetch_brief",
        "resolve_program",
        "massing",
        "serialize",
        "persist",
    } <= nodes

    # the massing subgraph (deterministic compute).
    massing_sub = wf.build_massing_subgraph(Settings())
    assert "compute" in set(massing_sub.get_graph().nodes)

    # the program-resolution subgraph has extract + resolve with a re-prompt loop.
    res_sub = pr.build_program_resolution_subgraph(
        Settings(), chain=_fake_extraction_chain()
    )
    assert {"extract", "resolve"} <= set(res_sub.get_graph().nodes)


def test_reprompt_loop_fails_when_site_area_never_found(monkeypatch):
    # Extraction never yields a site area → resolve loops MAX_EXTRACT_RETRIES
    # times then raises BRIEF_NOT_READY.
    import pytest

    from shared_kernel.errors import MassingError, MassingErrorCode

    no_site = BriefAnalysis.model_validate(
        {
            "program": [{"name": "업무", "area_m2": 1000.0, "grade": "above"}],
            "site_area_m2": None,
        }
    )
    sub = pr.build_program_resolution_subgraph(
        Settings(), chain=RunnableLambda(lambda _i: no_site)
    )
    with pytest.raises(MassingError) as ei:
        sub.invoke(
            {
                "req": GenerateMassingRequest.model_validate(
                    {"briefDocId": "11111111-1111-1111-1111-111111111111"}
                ),
                "detail": SimpleNamespace(body="brief", title="t"),
            }
        )
    assert ei.value.code == MassingErrorCode.BRIEF_NOT_READY
