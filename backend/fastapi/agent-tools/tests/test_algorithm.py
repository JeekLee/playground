"""Massing algorithm tests (ADR-19 Phase 3a) — compute_massing(MassingInputs)."""

from __future__ import annotations

import math

import pytest
from pydantic import ValidationError

from architecture.domain.algorithm import compute_massing
from architecture.domain.models import MassingInputs, Zone


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
