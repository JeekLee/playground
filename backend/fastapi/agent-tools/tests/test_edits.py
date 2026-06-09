"""Edit-op applier (spec D3) — typed ops mutate a NormalizedBrief + floor count."""
from __future__ import annotations

import pytest

from architecture.app.edits import apply_edits, EditOp
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem
from shared_kernel.errors import MassingError, MassingErrorCode

from pydantic import TypeAdapter

_OPS = TypeAdapter(list[EditOp])


def _nb() -> NormalizedBrief:
    return NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0,
        coverage_ratio_max=0.6,
        total_gfa_m2=9000.0,
    )


def test_rename_room():
    nb, tfa = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "RenameRoom", "from": "일반열람실", "to": "멀티미디어실"}]))
    assert [s.name for s in nb.sub_spaces] == ["멀티미디어실"]
    assert tfa == 3


def test_add_room_appends_above_grade_net_subspace():
    nb, _ = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "AddRoom", "name": "노트북 열람실", "areaM2": 2450.0, "zone": "지상"}]))
    added = [s for s in nb.sub_spaces if s.name == "노트북 열람실"][0]
    assert added.area_m2 == 2450.0 and added.grade == "above" and added.is_net is True


def test_set_floors_changes_count_only():
    nb, tfa = apply_edits(_nb(), 4, _OPS.validate_python(
        [{"op": "SetFloors", "targetFloorsAbove": 2}]))
    assert tfa == 2
    assert nb.zones[0].area_m2 == 9000.0  # program unchanged


def test_set_area_on_zone_and_room():
    nb, _ = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "SetArea", "target": "지상", "areaM2": 12000.0},
         {"op": "SetArea", "target": "일반열람실", "areaM2": 3000.0}]))
    assert nb.zones[0].area_m2 == 12000.0
    assert [s for s in nb.sub_spaces if s.name == "일반열람실"][0].area_m2 == 3000.0


def test_rename_missing_target_raises():
    with pytest.raises(MassingError) as ei:
        apply_edits(_nb(), 3, _OPS.validate_python(
            [{"op": "RenameRoom", "from": "없는방", "to": "x"}]))
    assert ei.value.code == MassingErrorCode.REFINE_TARGET_NOT_FOUND


def test_set_area_missing_target_raises():
    with pytest.raises(MassingError) as ei:
        apply_edits(_nb(), 3, _OPS.validate_python(
            [{"op": "SetArea", "target": "없는것", "areaM2": 100.0}]))
    assert ei.value.code == MassingErrorCode.REFINE_TARGET_NOT_FOUND


def test_edits_do_not_mutate_input():
    original = _nb()
    apply_edits(original, 3, _OPS.validate_python(
        [{"op": "SetArea", "target": "지상", "areaM2": 1.0}]))
    assert original.zones[0].area_m2 == 9000.0  # deep-copied


def test_unknown_op_rejected():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        _OPS.validate_python([{"op": "Nope"}])


def test_ops_apply_sequentially():
    # A later op sees an earlier op's mutation (spec D3 — 순차 적용):
    # rename 일반열람실 → 멀티미디어실, then resize the RENAMED room.
    nb, _ = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "RenameRoom", "from": "일반열람실", "to": "멀티미디어실"},
         {"op": "SetArea", "target": "멀티미디어실", "areaM2": 3000.0}]))
    room = [s for s in nb.sub_spaces if s.name == "멀티미디어실"][0]
    assert room.area_m2 == 3000.0
