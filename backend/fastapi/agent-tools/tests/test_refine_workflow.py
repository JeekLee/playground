"""RefineMassingWorkflow end-to-end (recipe glb → edits → classify→derive→…)."""
from __future__ import annotations

import uuid

import pytest

from architecture.api.dtos import RefineMassingRequest
from architecture.app.refine_recipe import RefineRecipe
from architecture.app.refine_workflow import RefineMassingWorkflow
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem, RoomBox
from architecture.infra.glb_serializer import serialize_glb
from shared_kernel.config import Settings
from shared_kernel.errors import MassingErrorCode


def _recipe_glb(target_floors=4) -> bytes:
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9800.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9800.0,
    )
    recipe = RefineRecipe(normalized=nb, floor_height_m=3.5,
                          target_floors_above=target_floors, brief_title="원본")
    boxes = [RoomBox(name="지상", zone="지상", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    return serialize_glb(boxes, program_json={"rooms": []},
                         refine_recipe=recipe.model_dump(by_alias=True, mode="json"))


def _patch_io(monkeypatch, glb: bytes):
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: glb)
    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260609/test-uuid/{filename}")
    monkeypatch.setattr("architecture.app.nodes.store_glb.upload_to_key",
                        lambda file_bytes, key, content_type, settings: None)


def test_refine_set_floors_runs_pipeline(monkeypatch):
    _patch_io(monkeypatch, _recipe_glb(target_floors=4))
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]})
    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)
    assert resp.result.floor_count == 2
    assert resp.result.brief_title == "원본"
    assert resp.artifact.storage_key.endswith(".3dm")


def test_refine_stream_terminal_result(monkeypatch):
    _patch_io(monkeypatch, _recipe_glb())
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 3}]})
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))
    progress = [e for e in events if e["event"] == "progress"]
    assert [e["stage"] for e in progress] == [
        "load_recipe", "apply_edits", "classify", "derive",
        "compute", "serialize", "store_3dm", "store_glb"]
    assert all(e["stageCount"] == 8 for e in progress)
    assert events[-1]["event"] == "result"


def test_refine_recipe_not_found_streams_error(monkeypatch):
    # legacy .glb with no recipe → error terminal
    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=1.0, depth=1.0, height=3.5)]
    _patch_io(monkeypatch, serialize_glb(boxes, program_json={"rooms": []}))
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]})
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))
    assert events[-1]["event"] == "error"
    assert events[-1]["code"] == MassingErrorCode.RECIPE_NOT_FOUND.value
    assert events[-1]["status"] == 422
