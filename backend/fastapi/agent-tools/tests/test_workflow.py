"""MassingWorkflow LangGraph behavior test (ADR-19 §A19.8 / Phase 3a-2).

Proves the orchestrator sequences fetch_brief → resolve_program(subgraph) →
compute → serialize → persist and produces the expected
GenerateMassingResponse, using a stubbed docs client, an injected fake
extraction chain (no network), and a fake DB session (no real Postgres).
The resolve_program subgraph now runs the 5-stage interpretation pipeline
(locate → extract → reconcile → classify → derive) with floors footprint-
driven by the largest single space. Real LLM/docs/DB + extraction parity is
covered by the real-gateway E2E.
"""

from __future__ import annotations

import contextlib
import uuid
from types import SimpleNamespace

from langchain_core.runnables import RunnableLambda

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
            body=(
                "## 규모\n대지면적 14,000㎡. 연면적 31,000㎡.\n\n"
                "## 면적 프로그램\n연구영역 합계 26,500㎡(전용 75%), "
                "Middle Lab 5,680㎡. 지하주차 4,500㎡."
            ),
            title="KFI 테스트 브리프",
        )


# KFI-like fixed extraction: gross zones (연구영역 26,500 above + 지하 4,500 below)
# plus a named above-grade sub-space (Middle Lab 5,680 net, 전용 75%). Footprint
# driver = 5,680 / 0.75 ≈ 7,573 → floors = ceil(26,500 / 7,573) = 4; site 14,000
# × coverage 0.6 = 8,400 buildable (driver fits). → 지상 4층 + 지하 1층.
_FIXED_ANALYSIS = BriefAnalysis.model_validate(
    {
        "program": [
            {
                "name": "Middle Lab",
                "area_m2": 5680.0,
                "grade": "above",
                "parent_zone": "연구영역",
                "is_net": True,
            },
        ],
        "zones_gross": [
            {"name": "연구영역", "area_m2": 26500.0, "grade": "above",
             "net_ratio": 0.75},
            {"name": "지하영역", "area_m2": 4500.0, "grade": "below"},
        ],
        "site_area_m2": 14000.0,
        "coverage_ratio_max": 0.6,
        "total_gfa_m2": 31000.0,
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

    # zone gross total = 31,000 m²; footprint-driven 4 above-grade floors + 1
    # basement (driver ≈ 7,573 from Middle Lab 5,680 / 0.75).
    assert resp.total_area_m2 == 31000.0
    assert resp.floor_count == 4
    assert resp.basement_levels == 1
    # two GROSS zones drive the program (연구영역 + 지하영역).
    assert len(resp.program_json.rooms) == 2
    assert resp.file_url == f"/api/arch/outputs/{_FIXED_ID}"
    assert resp.summary == "2실 · 지상 4층 + 지하 1층 · 총 31000 m²"


def test_graph_has_expected_nodes_and_subgraphs():
    flow = _build_workflow()
    nodes = set(flow._graph.get_graph().nodes)
    assert {
        "fetch_brief",
        "resolve_program",
        "compute",
        "serialize",
        "persist",
    } <= nodes

    # the program-resolution subgraph runs all 5 interpretation stages with a
    # re-prompt loop anchored at derive.
    res_sub = pr.build_program_resolution_subgraph(
        Settings(), chain=_fake_extraction_chain()
    )
    assert {
        "locate",
        "extract",
        "reconcile",
        "classify",
        "derive",
    } <= set(res_sub.get_graph().nodes)


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
