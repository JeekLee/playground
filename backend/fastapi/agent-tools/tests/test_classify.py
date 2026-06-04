"""classify node tests (ADR-19 Phase 3a-2) — grade zones + footprint driver."""

from __future__ import annotations

import pytest

from architecture.app.nodes.classify import DEFAULT_NET_RATIO, classify_brief
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem


def test_grades_zones_explicit_and_inferred() -> None:
    norm = NormalizedBrief(
        zones=[
            NormalizedZone(name="업무영역", area_m2=20000.0, grade="above"),
            NormalizedZone(name="지하주차", area_m2=4500.0, grade="unknown"),
        ],
        sub_spaces=[],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    grades = {z.name: z.grade for z in classified.zones}
    assert grades["업무영역"] == "above"
    assert grades["지하주차"] == "below"  # inferred from "지하"/"주차"


def test_picks_largest_above_subspace_and_gross_adjusts_with_zone_ratio() -> None:
    # KFI-like: Middle Lab 5,680 net within a zone with 전용비율 0.75.
    norm = NormalizedBrief(
        zones=[
            NormalizedZone(name="연구영역", area_m2=26500.0, grade="above",
                           net_ratio=0.75),
        ],
        sub_spaces=[
            ProgramItem(name="Small Lab", area_m2=2000.0, grade="above",
                        parent_zone="연구영역", is_net=True),
            ProgramItem(name="Middle Lab", area_m2=5680.0, grade="above",
                        parent_zone="연구영역", is_net=True),
        ],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    # gross-adjusted = 5680 / 0.75 ≈ 7573.3 — Middle Lab wins.
    assert classified.footprint_driver_m2 == pytest.approx(5680.0 / 0.75, rel=1e-6)


def test_gross_adjust_uses_default_ratio_when_zone_split_unknown() -> None:
    norm = NormalizedBrief(
        zones=[NormalizedZone(name="업무영역", area_m2=20000.0, grade="above")],
        sub_spaces=[
            ProgramItem(name="대공간", area_m2=3000.0, grade="above"),
        ],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    assert classified.footprint_driver_m2 == pytest.approx(3000.0 / DEFAULT_NET_RATIO)


def test_gross_subspace_used_as_is() -> None:
    norm = NormalizedBrief(
        zones=[NormalizedZone(name="업무영역", area_m2=20000.0, grade="above")],
        sub_spaces=[
            ProgramItem(name="강당", area_m2=4000.0, grade="above", is_net=False),
        ],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    assert classified.footprint_driver_m2 == pytest.approx(4000.0)


def test_below_grade_subspaces_excluded_from_driver() -> None:
    norm = NormalizedBrief(
        zones=[
            NormalizedZone(name="업무영역", area_m2=20000.0, grade="above"),
            NormalizedZone(name="지하영역", area_m2=8000.0, grade="below"),
        ],
        sub_spaces=[
            ProgramItem(name="사무실", area_m2=1000.0, grade="above"),
            ProgramItem(name="지하기계실", area_m2=6000.0, grade="below"),
        ],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    # the below-grade 6,000 must not drive the above-grade footprint.
    assert classified.footprint_driver_m2 == pytest.approx(1000.0 / DEFAULT_NET_RATIO)


def test_no_above_subspaces_yields_none_driver() -> None:
    norm = NormalizedBrief(
        zones=[NormalizedZone(name="지하영역", area_m2=8000.0, grade="below")],
        sub_spaces=[ProgramItem(name="지하기계실", area_m2=6000.0, grade="below")],
        site_area_m2=12000.0,
    )
    classified = classify_brief(norm)
    assert classified.footprint_driver_m2 is None
