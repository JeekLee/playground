"""Massing algorithm tests (ADR-19 Phase 3a) — compute_massing(MassingInputs)."""

from __future__ import annotations

import math

import pytest
from pydantic import ValidationError

from architecture.domain.algorithm import compute_massing
from architecture.domain.models import COMMON_AREA_NAME, MassingInputs, Room, Zone


def _inputs(**over) -> MassingInputs:
    base = dict(
        zones=[
            Zone(name="업무", area_m2=20000.0, grade="above"),
            Zone(name="시험", area_m2=6500.0, grade="above"),
            Zone(name="지하주차", area_m2=4500.0, grade="below"),
        ],
        site_area_m2=8000.0,
        coverage_cap=0.9,
        target_floors_above=4,
        basement_levels=1,
        floor_height_m=3.5,
    )
    base.update(over)
    return MassingInputs(**base)


def test_above_and_below_split() -> None:
    inputs = _inputs()
    boxes = compute_massing(inputs)

    above_floors = sorted({b.floor for b in boxes if b.floor > 0})
    below_floors = sorted({b.floor for b in boxes if b.floor < 0})

    # 4 above-grade floors (1..4) + 1 basement (B1 = -1).
    assert above_floors == [1, 2, 3, 4]
    assert below_floors == [-1]

    # above-grade z stacks from 0; basement z is negative.
    for b in boxes:
        if b.floor > 0:
            assert pytest.approx(b.z) == (b.floor - 1) * 3.5
        else:
            assert pytest.approx(b.z) == b.floor * 3.5  # -1 * 3.5


def test_footprint_area_matches_coverage_math() -> None:
    # above total = 26,500 / 4 floors = 6,625 m² footprint; side = sqrt(6625).
    inputs = _inputs()
    assert inputs.above_footprint_area == pytest.approx(6625.0)
    side = math.sqrt(6625.0)
    above_boxes = [b for b in compute_massing(inputs) if b.floor == 1]
    for b in above_boxes:
        assert b.x >= 0 and b.y >= 0
        assert b.x + b.width <= side + 1e-3


def test_every_above_floor_carries_full_program() -> None:
    inputs = _inputs()
    boxes = compute_massing(inputs)
    names_floor1 = {b.name for b in boxes if b.floor == 1}
    names_floor4 = {b.name for b in boxes if b.floor == 4}
    assert names_floor1 == {"업무", "시험"}
    assert names_floor4 == {"업무", "시험"}


def test_basement_carries_below_zones() -> None:
    boxes = compute_massing(_inputs())
    b1 = {b.name for b in boxes if b.floor == -1}
    assert b1 == {"지하주차"}


def test_coverage_gate_violation_raises() -> None:
    # above 26,500 / 4 = 6,625 footprint; site 5,000 × 0.6 = 3,000 allowed → fail.
    with pytest.raises(ValidationError):
        _inputs(site_area_m2=5000.0, coverage_cap=0.6)


def test_below_grade_requires_basement_level() -> None:
    with pytest.raises(ValidationError):
        MassingInputs(
            zones=[Zone(name="지하", area_m2=100.0, grade="below")],
            site_area_m2=1000.0,
            coverage_cap=0.6,
            target_floors_above=1,
            basement_levels=0,
        )


def test_determinism() -> None:
    inputs = _inputs()
    r1 = compute_massing(inputs)
    r2 = compute_massing(inputs)
    assert [(b.name, b.floor, b.x, b.y) for b in r1] == [
        (b.name, b.floor, b.x, b.y) for b in r2
    ]


def test_above_only_no_basement() -> None:
    inputs = MassingInputs(
        zones=[Zone(name="로비", area_m2=400.0, grade="above")],
        site_area_m2=1000.0,
        coverage_cap=0.6,
        target_floors_above=2,
        basement_levels=0,
        floor_height_m=3.5,
    )
    boxes = compute_massing(inputs)
    assert sorted({b.floor for b in boxes}) == [1, 2]
    assert all(b.floor > 0 for b in boxes)


# --- 실별 분할 (design spec 2026-06-05-room-split-massing D1·D2) ---


def _split_inputs(**over) -> MassingInputs:
    base = dict(
        zones=[
            Zone(
                name="연구",
                area_m2=1200.0,
                grade="above",
                rooms=[
                    Room(name="대실험실", area_m2=500.0),
                    Room(name="실험실A", area_m2=300.0),
                    Room(name="실험실B", area_m2=250.0),
                ],
            ),
        ],
        site_area_m2=2000.0,
        coverage_cap=0.6,
        target_floors_above=2,   # slot = 600
        basement_levels=0,
        floor_height_m=3.5,
    )
    base.update(over)
    return MassingInputs(**base)


def test_ffd_places_each_room_whole_on_one_floor() -> None:
    boxes = compute_massing(_split_inputs())
    rooms = [b for b in boxes if b.name not in ("연구", COMMON_AREA_NAME)]
    # FFD: 대실험실(500)→f1; 실험실A(300)→f1? 잔여 100 < 300 → f2;
    # 실험실B(250)→f2 (잔여 300). 각 실은 정확히 1개 박스.
    by_name = {b.name: b for b in rooms}
    assert len(rooms) == 3
    assert by_name["대실험실"].floor == 1
    assert by_name["실험실A"].floor == 2
    assert by_name["실험실B"].floor == 2
    # 실 박스는 zone 키를 가진다.
    assert all(b.zone == "연구" for b in rooms)


def test_remainder_common_boxes_fill_each_floor() -> None:
    boxes = compute_massing(_split_inputs())
    common = [b for b in boxes if b.name == COMMON_AREA_NAME]
    by_floor = {b.floor: b for b in common}
    # f1: 600 − 500 = 100; f2: 600 − 550 = 50. 면적 = width × depth 근사.
    assert by_floor[1].width * by_floor[1].depth == pytest.approx(100.0, rel=0.05)
    assert by_floor[2].width * by_floor[2].depth == pytest.approx(50.0, rel=0.05)
    assert all(b.zone == "연구" for b in common)


def test_split_boxes_stay_inside_zone_rect() -> None:
    inputs = _split_inputs()
    boxes = compute_massing(inputs)
    side = math.sqrt(1200.0 / 2)  # 단일 zone → zone rect = 풋프린트 전체
    for b in boxes:
        assert b.x >= -1e-6 and b.y >= -1e-6
        assert b.x + b.width <= side + 1e-3
        assert b.y + b.depth <= side * 1.5  # shelf 클램프 관용치 (기존 packer 의미론)


def test_zone_without_rooms_unsplit_alongside_split_zone() -> None:
    inputs = _split_inputs(
        zones=[
            Zone(name="연구", area_m2=1200.0, grade="above",
                 rooms=[Room(name="대실험실", area_m2=500.0)]),
            Zone(name="업무", area_m2=800.0, grade="above"),
        ],
    )
    boxes = compute_massing(inputs)
    work = [b for b in boxes if b.zone == "업무"]
    # 미분할 zone: 층당 1박스, name == zone.
    assert len(work) == 2
    assert all(b.name == "업무" for b in work)


def test_ffd_fragmentation_degrades_zone_unsplit() -> None:
    # 총량은 맞지만(6×350=2,100 ≤ 2,400) 슬롯 600엔 350이 1개씩만 →
    # 4슬롯에 6실 패킹 불가 → 통짜 강등 (deviation 2).
    inputs = _split_inputs(
        zones=[
            Zone(name="연구", area_m2=2400.0, grade="above",
                 rooms=[Room(name=f"실{i}", area_m2=350.0) for i in range(6)]),
        ],
        target_floors_above=4,
    )
    boxes = compute_massing(inputs)
    assert all(b.name == "연구" and b.zone == "연구" for b in boxes)
    assert len(boxes) == 4


def test_below_grade_rooms_split_in_basement() -> None:
    inputs = _split_inputs(
        zones=[
            Zone(name="지하", area_m2=600.0, grade="below",
                 rooms=[Room(name="주차장", area_m2=400.0)]),
        ],
        target_floors_above=1,
        basement_levels=1,
    )
    # above가 비므로 위 zones만으로는 above_footprint=0 → 지상 박스 없음.
    boxes = compute_massing(inputs)
    parking = next(b for b in boxes if b.name == "주차장")
    assert parking.floor == -1 and parking.zone == "지하"
    common = next(b for b in boxes if b.name == COMMON_AREA_NAME)
    assert common.floor == -1
