"""derive node tests (ADR-19 Phase 3a-2) — footprint-driven MassingInputs."""

from __future__ import annotations

from math import ceil

import pytest

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.nodes.derive import derive_inputs
from architecture.domain.models import ClassifiedBrief, Zone
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
