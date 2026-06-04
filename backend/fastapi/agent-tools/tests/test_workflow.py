"""MassingWorkflow LangGraph behavior test (ADR-19 §A19.8 / Phase 2c).

Proves the orchestrator sequences fetch_brief → extract → massing(subgraph) →
serialize → persist and produces the expected GenerateMassingResponse, using a
stubbed docs client, an injected fake extraction chain (no network / no real
LLM), and a fake DB session (no real Postgres). This is the behavior-identical
guard at the result level; the real LLM/docs/DB path + extraction parity is
covered by the real-gateway E2E.
"""

from __future__ import annotations

import contextlib
import uuid
from types import SimpleNamespace

from langchain_core.runnables import RunnableLambda

from architecture.app import workflow as wf
from architecture.app.nodes import persist as persist_node
from architecture.app.workflow import MassingWorkflow
from architecture.api.dtos import GenerateMassingRequest
from architecture.domain.models import ExtractedProgram
from shared_kernel.config import Settings


class _FakeDocs:
    def get_document(self, doc_id, *, user_id, user_sub):
        return SimpleNamespace(
            extraction_status="extracted",
            body="대지면적 20m x 10m. 로비 40m². 사무실 30m².",
            title="테스트 브리프",
        )


# Fixed extracted program — site 20x10, two rooms totalling 70 m².
_FIXED_PROGRAM = ExtractedProgram.model_validate(
    {
        "site": {"width": 20.0, "depth": 10.0},
        "rooms": [
            {"name": "로비", "areaM2": 40.0},
            {"name": "사무실", "areaM2": 30.0},
        ],
    }
)


def _fake_extraction_chain() -> RunnableLambda:
    """Stands in for the LCEL extraction chain: yields a fixed ExtractedProgram
    regardless of the {"brief_body": ...} input."""
    return RunnableLambda(lambda _inputs: _FIXED_PROGRAM)


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

    # site 20x10 = 200 m² ≥ total 70 m² → 1 floor; 2 rooms.
    assert resp.total_area_m2 == 70.0
    assert resp.floor_count == 1
    assert len(resp.program_json.rooms) == 2
    assert resp.file_url == f"/api/arch/outputs/{_FIXED_ID}"
    assert resp.summary == "2실 · 1층 · 총 70 m²"


def test_graph_has_expected_nodes_and_massing_subgraph():
    flow = _build_workflow()
    nodes = set(flow._graph.get_graph().nodes)
    # orchestrator nodes (massing is the subgraph-as-node)
    assert {"fetch_brief", "extract", "massing", "serialize", "persist"} <= nodes

    # the massing subgraph is itself a compiled graph composed as a node
    subgraph = wf.build_massing_subgraph(Settings())
    sub_nodes = set(subgraph.get_graph().nodes)
    assert {"resolve_site", "compute"} <= sub_nodes
