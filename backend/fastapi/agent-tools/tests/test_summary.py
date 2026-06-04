"""Summary formatter — Korean fixed format per ADR-18 §5 + ADR-19 Phase 3a."""

from __future__ import annotations

from architecture.domain.summary import format_summary


def test_above_grade_only() -> None:
    assert format_summary(
        room_count=12, floors_above=3, basement_levels=0, total_area_m2=480.0
    ) == ("12실 · 지상 3층 · 총 480 m²")


def test_with_basement() -> None:
    assert format_summary(
        room_count=3, floors_above=4, basement_levels=1, total_area_m2=31000.0
    ) == ("3실 · 지상 4층 + 지하 1층 · 총 31000 m²")


def test_rounds_total_area() -> None:
    assert format_summary(
        room_count=5, floors_above=2, basement_levels=0, total_area_m2=123.7
    ) == ("5실 · 지상 2층 · 총 124 m²")


def test_multi_basement() -> None:
    assert format_summary(
        room_count=8, floors_above=6, basement_levels=2, total_area_m2=12000.0
    ) == ("8실 · 지상 6층 + 지하 2층 · 총 12000 m²")
