"""Resolve step tests (ADR-19 Phase 3a) — BriefAnalysis -> MassingInputs."""

from __future__ import annotations

import pytest

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.nodes.resolve import resolve_inputs
from architecture.domain.models import BriefAnalysis, ProgramItem
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode


def _req(**over) -> GenerateMassingRequest:
    body = {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    body.update(over)
    return GenerateMassingRequest.model_validate(body)


def _analysis(**over) -> BriefAnalysis:
    base = dict(
        program=[
            ProgramItem(name="업무", area_m2=20000.0, grade="above"),
            ProgramItem(name="시험", area_m2=6500.0, grade="above"),
            ProgramItem(name="지하주차", area_m2=4500.0, grade="below"),
        ],
        site_area_m2=12000.0,
    )
    base.update(over)
    return BriefAnalysis(**base)


SETTINGS = Settings()


def test_grade_inference_from_name_when_unknown() -> None:
    analysis = _analysis(
        program=[
            ProgramItem(name="사무실", area_m2=1000.0, grade="unknown"),
            ProgramItem(name="지하 주차장", area_m2=500.0, grade="unknown"),
        ],
        site_area_m2=5000.0,
    )
    inputs = resolve_inputs(analysis, _req(), SETTINGS)
    by_name = {z.name: z.grade for z in inputs.zones}
    assert by_name["사무실"] == "above"
    assert by_name["지하 주차장"] == "below"
    assert inputs.basement_levels == 1


def test_explicit_grade_wins_over_inference() -> None:
    analysis = _analysis(
        program=[ProgramItem(name="주차", area_m2=300.0, grade="above")],
        site_area_m2=5000.0,
    )
    inputs = resolve_inputs(analysis, _req(), SETTINGS)
    assert inputs.zones[0].grade == "above"  # explicit, despite "주차"
    assert inputs.basement_levels == 0


def test_coverage_cap_default_applied() -> None:
    inputs = resolve_inputs(_analysis(coverage_ratio_max=None), _req(), SETTINGS)
    assert inputs.coverage_cap == SETTINGS.default_coverage_cap == 0.6


def test_coverage_cap_from_brief() -> None:
    inputs = resolve_inputs(_analysis(coverage_ratio_max=0.8), _req(), SETTINGS)
    assert inputs.coverage_cap == 0.8


def test_target_floors_default() -> None:
    inputs = resolve_inputs(_analysis(floor_limit=None), _req(), SETTINGS)
    assert inputs.target_floors_above == SETTINGS.default_target_floors_above == 4


def test_target_floors_from_floor_limit() -> None:
    inputs = resolve_inputs(_analysis(floor_limit=6), _req(), SETTINGS)
    assert inputs.target_floors_above == 6


def test_request_target_floors_wins() -> None:
    inputs = resolve_inputs(
        _analysis(floor_limit=6), _req(targetFloors=8), SETTINGS
    )
    assert inputs.target_floors_above == 8


def test_floor_height_from_request() -> None:
    inputs = resolve_inputs(_analysis(), _req(floorHeight=4.2), SETTINGS)
    assert inputs.floor_height_m == 4.2


def test_floor_height_default() -> None:
    inputs = resolve_inputs(_analysis(), _req(), SETTINGS)
    assert inputs.floor_height_m == SETTINGS.default_floor_height_m


def test_missing_site_area_raises_brief_not_ready() -> None:
    with pytest.raises(MassingError) as ei:
        resolve_inputs(_analysis(site_area_m2=None), _req(), SETTINGS)
    assert ei.value.code == MassingErrorCode.BRIEF_NOT_READY


def test_kfi_like_defaults_to_4_above_1_below() -> None:
    # 26,500 above + 4,500 below, no floor_limit, default coverage 0.6,
    # site 12,000 → footprint 6,625 ≤ 7,200 allowed → feasible.
    inputs = resolve_inputs(
        _analysis(site_area_m2=12000.0, floor_limit=None), _req(), SETTINGS
    )
    assert inputs.target_floors_above == 4
    assert inputs.basement_levels == 1
