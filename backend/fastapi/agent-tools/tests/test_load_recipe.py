"""load_recipe + apply_edits nodes (refine pipeline front)."""
from __future__ import annotations

import pytest

from architecture.app.edits import EditOp
from architecture.app.nodes.apply_edits import apply_edits as apply_edits_node
from architecture.app.nodes.load_recipe import make_load_recipe_node
from architecture.app.refine_recipe import RefineRecipe
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem, RoomBox
from architecture.infra.glb_serializer import serialize_glb
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

from pydantic import TypeAdapter

_OPS = TypeAdapter(list[EditOp])


def _recipe_glb() -> bytes:
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9000.0,
    )
    recipe = RefineRecipe(normalized=nb, floor_height_m=3.5,
                          target_floors_above=4, brief_title="원본 브리프")
    boxes = [RoomBox(name="지상", zone="지상", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    return serialize_glb(boxes, program_json={"rooms": []},
                         refine_recipe=recipe.model_dump(by_alias=True, mode="json"))


def test_load_recipe_populates_state(monkeypatch):
    glb = _recipe_glb()
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: glb)
    node = make_load_recipe_node(Settings())
    out = node({"base_storage_key": "architecture/massing/x/y.3dm"})
    assert out["target_floors_above"] == 4
    assert out["floor_height_m"] == 3.5
    assert out["detail"].title == "원본 브리프"
    assert isinstance(out["normalized"], NormalizedBrief)


def test_load_recipe_missing_recipe_raises(monkeypatch):
    # A .glb with no refineRecipe (legacy model) → RECIPE_NOT_FOUND.
    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=1.0, depth=1.0, height=3.5)]
    legacy = serialize_glb(boxes, program_json={"rooms": []})
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: legacy)
    node = make_load_recipe_node(Settings())
    with pytest.raises(MassingError) as ei:
        node({"base_storage_key": "architecture/massing/x/y.3dm"})
    assert ei.value.code == MassingErrorCode.RECIPE_NOT_FOUND


def test_apply_edits_node_sets_req_and_normalized():
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9000.0,
    )
    state = {"normalized": nb, "target_floors_above": 4, "floor_height_m": 3.5,
             "edits": _OPS.validate_python([{"op": "SetFloors", "targetFloorsAbove": 3}])}
    out = apply_edits_node(state)
    assert out["target_floors_above"] == 3
    assert out["req"].target_floors == 3 and out["req"].floor_height == 3.5
