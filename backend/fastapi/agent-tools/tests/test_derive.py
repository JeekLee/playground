"""derive node tests (ADR-19 Phase 3a-2) — footprint-driven MassingInputs."""

from __future__ import annotations

from math import ceil

import pytest

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.nodes.derive import derive_inputs
from architecture.domain.models import ClassifiedBrief, ProgramItem, Zone
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

SETTINGS = Settings()


def _req(**over) -> GenerateMassingRequest:
    body = {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    body.update(over)
    return GenerateMassingRequest.model_validate(body)


def _classified(**over) -> ClassifiedBrief:
    base = dict(
        zones=[
            Zone(name="연구영역", area_m2=26500.0, grade="above"),
            Zone(name="지하영역", area_m2=4500.0, grade="below"),
        ],
        footprint_driver_m2=5680.0 / 0.75,  # ≈ 7573.3
        site_area_m2=14000.0,
        coverage_ratio_max=0.6,
    )
    base.update(over)
    return ClassifiedBrief(**base)


def test_kfi_footprint_driven_4_above_1_below() -> None:
    # above 26,500; driver ≈ 7,573; site 14,000 × 0.6 = 8,400 buildable.
    # footprint = driver 7,573 -> floors = ceil(26500/7573.3) = 4; basement = 1.
    inputs = derive_inputs(_classified(), _req(), SETTINGS)
    assert inputs.target_floors_above == 4
    assert inputs.basement_levels == 1
    # footprint that produced the floors is the driver, not a default.
    assert inputs.above_footprint_area == pytest.approx(26500.0 / 4)


def test_driver_exceeds_coverage_cap_raises() -> None:
    # driver 9,000 > site 12,000 × 0.6 = 7,200 -> infeasible largest space.
    classified = _classified(
        footprint_driver_m2=9000.0, site_area_m2=12000.0, coverage_ratio_max=0.6
    )
    with pytest.raises(MassingError) as ei:
        derive_inputs(classified, _req(), SETTINGS)
    assert ei.value.code == MassingErrorCode.MASSING_ALGORITHM_FAILED
    assert "largest space" in ei.value.message


def test_missing_site_area_raises_brief_not_ready() -> None:
    with pytest.raises(MassingError) as ei:
        derive_inputs(_classified(site_area_m2=None), _req(), SETTINGS)
    assert ei.value.code == MassingErrorCode.BRIEF_NOT_READY


def test_request_target_floors_override_wins() -> None:
    inputs = derive_inputs(_classified(), _req(targetFloors=6), SETTINGS)
    assert inputs.target_floors_above == 6


def test_coverage_cap_default_when_absent() -> None:
    inputs = derive_inputs(_classified(coverage_ratio_max=None), _req(), SETTINGS)
    assert inputs.coverage_cap == SETTINGS.default_coverage_cap == 0.6


def test_no_driver_falls_back_to_default_floor_sizing() -> None:
    # No footprint driver (no named above sub-spaces) -> size footprint from the
    # default floor count, then re-derive floors consistently.
    classified = _classified(footprint_driver_m2=None)
    inputs = derive_inputs(classified, _req(), SETTINGS)
    expected_footprint = 26500.0 / SETTINGS.default_target_floors_above
    assert inputs.target_floors_above == max(
        1, ceil(26500.0 / expected_footprint)
    )
    assert inputs.target_floors_above == SETTINGS.default_target_floors_above


def test_far_gate_violation_raises() -> None:
    # above 26,500 / site 5,000 = FAR 5.3 > cap 3.0.
    classified = _classified(
        site_area_m2=5000.0,
        coverage_ratio_max=0.6,
        floor_area_ratio_max=3.0,
        footprint_driver_m2=2000.0,  # under buildable 3,000 so coverage passes
    )
    with pytest.raises(MassingError) as ei:
        derive_inputs(classified, _req(), SETTINGS)
    assert ei.value.code == MassingErrorCode.MASSING_ALGORITHM_FAILED
    assert "용적률" in ei.value.message


def test_floor_height_from_request() -> None:
    inputs = derive_inputs(_classified(), _req(floorHeight=4.2), SETTINGS)
    assert inputs.floor_height_m == 4.2


# --- 실별 분할 (design spec 2026-06-05-room-split-massing D3·D4·D7) ---


def _sub(name, area, *, grade="above", parent=None, is_net=True):
    return ProgramItem(name=name, area_m2=area, grade=grade,
                       parent_zone=parent, is_net=is_net)


def test_rooms_attributed_by_parent_zone() -> None:
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["Middle Lab"]
    assert zone.rooms[0].area_m2 == 5680.0


def test_rooms_attributed_by_unique_grade_when_no_parent() -> None:
    # parent_zone 없음 + above zone이 유일 → 그 zone에 귀속.
    classified = _classified(
        sub_spaces=[_sub("로비", 800.0, parent=None)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["로비"]


def test_rooms_unattributed_when_grade_ambiguous() -> None:
    # above zone이 2개면 parent 없는 실은 미배정 (D7).
    classified = _classified(
        zones=[
            Zone(name="연구영역", area_m2=20000.0, grade="above"),
            Zone(name="업무영역", area_m2=6500.0, grade="above"),
        ],
        sub_spaces=[_sub("로비", 800.0, parent=None)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert all(not z.rooms for z in inputs.zones)


def test_floors_capped_so_largest_room_fits_slot() -> None:
    # zone 24,000 / room 9,000(gross): driver 9,000 → floors=ceil(24000/9000)=3,
    # slot 8,000 < 9,000 → cap floor(24000/9000)=2 → footprint 12,000 (재검증:
    # site 25,000 × 0.6 = 15,000 OK). 최종 floors=2, slot 12,000 ≥ 9,000.
    classified = _classified(
        zones=[Zone(name="연구영역", area_m2=24000.0, grade="above")],
        footprint_driver_m2=9000.0,
        site_area_m2=25000.0,
        coverage_ratio_max=0.6,
        sub_spaces=[_sub("대공간", 9000.0, parent="연구영역", is_net=False)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 2
    zone = inputs.zones[0]
    assert [r.name for r in zone.rooms] == ["대공간"]
    # 슬롯 보장: max room ≤ zone_gross / floors.
    assert 9000.0 <= zone.area_m2 / inputs.target_floors_above + 1e-6


def test_guard1_coverage_blocks_floor_reduction_degrades_zone() -> None:
    # cap이 floors=2를 요구하지만 footprint 12,000 > buildable 16,000×0.6=9,600
    # → 층수 축소 불가 → 해당 zone 통짜 강등 (rooms 비움), floors는 기존 산정값.
    classified = _classified(
        zones=[Zone(name="연구영역", area_m2=24000.0, grade="above")],
        footprint_driver_m2=8000.0,   # ≤ buildable 9,600 → 기존 경로는 통과
        site_area_m2=16000.0,
        coverage_ratio_max=0.6,
        sub_spaces=[_sub("대공간", 9000.0, parent="연구영역", is_net=False)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 3  # ceil(24000/8000) — 기존 산정 유지
    assert inputs.zones[0].rooms == []      # 강등


def test_guard2_target_floors_override_conflict_degrades_zone() -> None:
    # 오버라이드 6층 → slot 26500/6 ≈ 4,417 < Middle Lab 5,680 → 강등.
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(targetFloors=6), SETTINGS)
    assert inputs.target_floors_above == 6
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert zone.rooms == []


def test_guard3_room_sum_exceeds_zone_gross_degrades_zone() -> None:
    # Σ실 26,400 > 26,500×0.98 = 25,970 → 강등.
    classified = _classified(
        sub_spaces=[
            _sub("A", 13200.0, parent="연구영역"),
            _sub("B", 13200.0, parent="연구영역"),
        ],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert zone.rooms == []


def test_below_grade_room_fits_basement_slot_or_degrades() -> None:
    # 지하 zone 4,500 / basement 1층 슬롯 4,500 ≥ 실 4,000 → 배정.
    classified = _classified(
        sub_spaces=[_sub("지하주차장", 4000.0, grade="below", parent="지하영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    below = next(z for z in inputs.zones if z.grade == "below")
    assert [r.name for r in below.rooms] == ["지하주차장"]

    # 실 5,000 > 슬롯 4,500 → 강등 (지하는 층수 cap 대상 아님 — D3 amendment).
    classified2 = _classified(
        sub_spaces=[_sub("지하주차장", 5000.0, grade="below", parent="지하영역")],
    )
    inputs2 = derive_inputs(classified2, _req(), SETTINGS)
    below2 = next(z for z in inputs2.zones if z.grade == "below")
    assert below2.rooms == []


def test_duplicate_zone_names_skip_attribution() -> None:
    # 같은 이름의 zone이 둘이면 귀속이 이중 계상되므로 통째 강등 (리뷰 가드).
    classified = _classified(
        zones=[
            Zone(name="연구영역", area_m2=13000.0, grade="above"),
            Zone(name="연구영역", area_m2=13500.0, grade="above"),
        ],
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert all(z.rooms == [] for z in inputs.zones)


def test_kfi_fixture_splits_research_zone() -> None:
    # 기존 KFI 픽스처 + Middle Lab: floors 4 유지(cap floor(26500/5680)=4),
    # slot 6,625 ≥ 5,680 → 연구영역에 귀속.
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 4
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["Middle Lab"]
