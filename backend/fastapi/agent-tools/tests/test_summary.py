"""Summary formatter — Korean fixed format per ADR-18 §5."""

from __future__ import annotations

from architecture.summary import format_summary


def test_canonical_example() -> None:
    # From ADR-18 §5 example.
    assert format_summary(room_count=12, floor_count=3, total_area_m2=480.0) == (
        "12실 · 3층 · 총 480 m²"
    )


def test_rounds_total_area() -> None:
    # %.0f truncates / rounds to nearest int per Python formatting rules.
    assert format_summary(room_count=5, floor_count=2, total_area_m2=123.7) == (
        "5실 · 2층 · 총 124 m²"
    )


def test_single_floor() -> None:
    assert format_summary(room_count=3, floor_count=1, total_area_m2=80.0) == (
        "3실 · 1층 · 총 80 m²"
    )
