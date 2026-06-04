"""Massing algorithm tests per ADR-18 §A18.9."""

from __future__ import annotations

import pytest

from architecture.algorithm import compute_massing
from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.models import Room, SiteFootprint


def _site(width: float = 20.0, depth: float = 10.0) -> SiteFootprint:
    return SiteFootprint(width=width, depth=depth)


def test_single_floor_happy_path() -> None:
    rooms = [Room("로비", 40.0), Room("카페테리아", 30.0), Room("화장실", 6.0)]
    boxes = compute_massing(rooms, _site(), floor_height=3.5, max_floors=10)
    assert len(boxes) == 3
    assert all(b.floor == 1 for b in boxes)
    assert all(b.z == 0.0 for b in boxes)
    assert all(b.height == 3.5 for b in boxes)


def test_multi_floor_when_total_exceeds_site() -> None:
    # site = 20 × 10 = 200 m²; total = 220 m² → 2 floors.
    rooms = [Room("R1", 100.0), Room("R2", 100.0), Room("R3", 20.0)]
    boxes = compute_massing(rooms, _site(), floor_height=3.5, max_floors=10)
    floors = {b.floor for b in boxes}
    assert floors == {1, 2}
    # z stacks: floor 2 → z = 3.5.
    for b in boxes:
        assert pytest.approx(b.z) == (b.floor - 1) * 3.5


def test_over_area_raises() -> None:
    # site = 20 × 10 = 200; total = 2200 → would need 11 floors > max_floors=10
    rooms = [Room(f"R{i}", 200.0) for i in range(11)]
    with pytest.raises(MassingError) as ei:
        compute_massing(rooms, _site(), floor_height=3.5, max_floors=10)
    assert ei.value.code == MassingErrorCode.MASSING_ALGORITHM_FAILED


def test_empty_rooms_raises() -> None:
    with pytest.raises(MassingError) as ei:
        compute_massing([], _site(), floor_height=3.5, max_floors=10)
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_zero_site_raises() -> None:
    with pytest.raises(MassingError) as ei:
        compute_massing(
            [Room("R", 10.0)],
            SiteFootprint(width=0.0, depth=0.0),
            floor_height=3.5,
            max_floors=10,
        )
    # site.area_m2 == 0 triggers MASSING_ALGORITHM_FAILED branch.
    assert ei.value.code == MassingErrorCode.MASSING_ALGORITHM_FAILED


def test_determinism() -> None:
    rooms = [Room("A", 50.0), Room("B", 30.0), Room("C", 70.0)]
    site = _site()
    r1 = compute_massing(rooms, site, floor_height=3.5, max_floors=10)
    r2 = compute_massing(rooms, site, floor_height=3.5, max_floors=10)
    assert [(b.name, b.x, b.y, b.floor) for b in r1] == [
        (b.name, b.x, b.y, b.floor) for b in r2
    ]


def test_room_box_within_site_bounds() -> None:
    rooms = [Room("R1", 60.0), Room("R2", 60.0), Room("R3", 60.0)]
    site = _site(20, 10)
    boxes = compute_massing(rooms, site, floor_height=3.5, max_floors=10)
    for b in boxes:
        assert b.x >= 0 and b.y >= 0
        assert b.width > 0 and b.depth > 0
        assert b.x + b.width <= site.width + 1e-3
