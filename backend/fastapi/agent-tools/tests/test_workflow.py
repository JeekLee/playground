"""MassingWorkflow LangGraph behavior test (ADR-19 §A19.8 / Phase 3a-2).

Proves the orchestrator sequences fetch_brief → resolve_program(subgraph) →
compute → serialize → store_3dm → store_glb → respond and produces the expected
GenerateMassingResponse `{result, artifact}` envelope (ADR-20 §D2), using a
stubbed docs client + an injected fake extraction chain (no network). ADR-20
made the BC stateless — no DB session, no arch.outputs row; the .3dm bytes ride
out base64-encoded in the response `artifact`. The resolve_program subgraph
runs the 5-stage interpretation pipeline (locate → extract → reconcile →
classify → derive) with floors footprint-driven by the largest single space.
Real LLM/docs + extraction parity is covered by the real-gateway E2E.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest

import rhino3dm
from langchain_core.runnables import RunnableLambda

from architecture.app.graphs import program_resolution as pr
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


def _build_workflow() -> MassingWorkflow:
    return MassingWorkflow(
        Settings(),
        _FakeDocs(),
        extraction_chain=_fake_extraction_chain(),
    )


def test_graph_runs_path_and_builds_envelope(monkeypatch):
    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260605/test-uuid/{filename}",
    )
    glb_uploads: list[tuple[str, str, bytes]] = []
    monkeypatch.setattr(
        "architecture.app.nodes.store_glb.upload_to_key",
        lambda file_bytes, key, content_type, settings:
            glb_uploads.append((key, content_type, file_bytes)),
    )

    flow = _build_workflow()
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )

    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)

    # ADR-20 §D2 — the response is the {result, artifact} envelope.
    result = resp.result
    # zone gross total = 31,000 m²; footprint-driven 4 above-grade floors + 1
    # basement (driver ≈ 7,573 from Middle Lab 5,680 / 0.75).
    assert result.total_area_m2 == 31000.0
    assert result.floor_count == 4
    assert result.basement_levels == 1
    # 실별 분할 (design spec 2026-06-05-room-split-massing): 연구영역은
    # Middle Lab + 층별 공용·기타로 분할, 지하영역은 통짜.
    rooms = result.program_json.rooms
    by_name = {}
    for r in rooms:
        by_name.setdefault(r.name, []).append(r)

    lab = by_name["Middle Lab"][0]
    assert lab.zone == "연구영역"
    assert lab.floor == 1                  # FFD: 가장 낮은 층
    assert lab.area_m2 == 5680.0           # 브리프 전용면적 그대로 (D2)
    assert lab.label_anchor is not None    # hotspot 좌표
    # 상면 중심: glTF Y = z_top = (floor-1)*h + (h - 0.15)
    assert lab.label_anchor.y == pytest.approx(3.5 - 0.15)

    commons = by_name.get("공용·기타", [])
    assert len(commons) == 4               # 연구영역 4층 각각
    assert all(c.zone == "연구영역" and c.label_anchor is None for c in commons)

    basement = by_name["지하영역"][0]
    assert basement.zone == "지하영역"     # 미분할 zone도 zone 세팅 (deviation 3)
    assert basement.floor is None          # 미분할 신호

    # summary: 명명 실(Middle Lab) + 미분할 zone(지하영역) = 2실 — 기존 문자열 유지.
    assert result.summary == "2실 · 지상 4층 + 지하 1층 · 총 31000 m²"
    assert result.brief_title == "KFI 테스트 브리프"
    # result is LLM-visible: no fileUrl leaks (ADR-20 retires agent-tools store).
    assert not hasattr(result, "file_url")

    # artifact carries metadata only (ADR-20 §D3 revised — bytes are in MinIO).
    artifact = resp.artifact
    assert artifact.filename.startswith("massing-")
    assert artifact.filename.endswith(".3dm")
    assert artifact.content_type == "application/octet-stream"
    assert artifact.storage_key.startswith("architecture/massing/")
    assert artifact.storage_key.endswith(artifact.filename)
    assert artifact.size_bytes > 0

    # .glb preview rides next to the .3dm — same prefix, extension swapped
    # (design spec 2026-06-05-massing-glb-preview).
    assert len(glb_uploads) == 1
    glb_key, glb_content_type, glb_bytes = glb_uploads[0]
    assert glb_key == artifact.storage_key[: -len(".3dm")] + ".glb"
    assert glb_content_type == "model/gltf-binary"
    assert len(glb_bytes) > 0

    # .glb extras == SSE programJson (단일 빌더 보장 — glb-extras spec D1·D2).
    import json as _json
    import struct as _struct
    json_len = _struct.unpack("<I", glb_bytes[12:16])[0]
    doc = _json.loads(glb_bytes[20 : 20 + json_len].decode("utf-8"))
    extras_pj = doc["scenes"][0]["extras"]["programJson"]
    assert extras_pj == result.program_json.model_dump(by_alias=True, mode="json")


def test_graph_has_expected_nodes_and_subgraphs():
    flow = _build_workflow()
    nodes = set(flow._graph.get_graph().nodes)
    assert {
        "fetch_brief",
        "resolve_program",
        "compute",
        "serialize",
        "store_3dm",
        "store_glb",
        "respond",
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
