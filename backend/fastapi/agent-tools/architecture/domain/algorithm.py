"""Massing algorithm (ADR-19 Phase 3a) — deterministic, brief-grounded.

Pure-Python, no I/O. Consumes the validated `MassingInputs` contract and
produces `list[RoomBox]`. The coverage gate is enforced upstream by
`MassingInputs` validation, so this function may assume a feasible massing.

Pipeline (replaces the M8 `ceil(total / site_area)` bug):

1. Split zones by grade (above / below).
2. Above-grade footprint area = sum(above areas) / target_floors_above.
   The site rectangle is a square of side sqrt(footprint_area) — the brief
   gives areas, not dimensions. Each above-grade zone's per-floor share is
   packed across floors 1..N (z = (floor-1) * h).
3. Below-grade zones are packed into `basement_levels` levels. Level k
   (1..basement_levels) is labelled floor = -k (B1 = -1) with z = -k * h.
4. Within a floor/level, zones are shelf-first-fit packed as rectangles
   derived from area (square aspect), matching the M8 shelf packer.

Floor / level indices:
- above-grade: floor 1..target_floors_above, z >= 0
- below-grade: floor -1..-basement_levels (B1 = -1), z < 0
"""

from __future__ import annotations

from math import sqrt

from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.domain.models import MassingInputs, RoomBox, Zone


def compute_massing(inputs: MassingInputs) -> list[RoomBox]:
    """Allocate zones across above-grade floors + basement levels."""

    above = [z for z in inputs.zones if z.grade == "above"]
    below = [z for z in inputs.zones if z.grade == "below"]

    boxes: list[RoomBox] = []

    # --- Above-grade: square footprint, split each zone per floor ---
    above_area = sum(z.area_m2 for z in above)
    if above_area > 0:
        footprint_area = above_area / inputs.target_floors_above
        side = sqrt(footprint_area)
        # Each above-grade floor carries an equal share of every zone, so the
        # program is distributed uniformly across the stacked footprint.
        per_floor_zones = [
            Zone(
                name=z.name,
                area_m2=z.area_m2 / inputs.target_floors_above,
                grade="above",
            )
            for z in above
        ]
        for floor in range(1, inputs.target_floors_above + 1):
            boxes.extend(
                _pack_level(
                    per_floor_zones,
                    side=side,
                    floor=floor,
                    z=(floor - 1) * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                )
            )

    # --- Below-grade: square footprint sized to the largest single level ---
    below_area = sum(z.area_m2 for z in below)
    if below_area > 0:
        levels = max(1, inputs.basement_levels)
        level_footprint_area = below_area / levels
        side = sqrt(level_footprint_area)
        per_level_zones = [
            Zone(
                name=z.name,
                area_m2=z.area_m2 / levels,
                grade="below",
            )
            for z in below
        ]
        # B1 = level 1 = floor -1 = z -h; B2 = floor -2 = z -2h; ...
        for level in range(1, levels + 1):
            boxes.extend(
                _pack_level(
                    per_level_zones,
                    side=side,
                    floor=-level,
                    z=-level * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                )
            )

    if not boxes:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "no zones produced any boxes",
        )
    return boxes


def _pack_level(
    zones: list[Zone],
    *,
    side: float,
    floor: int,
    z: float,
    height: float,
) -> list[RoomBox]:
    """Shelf-first-fit pack of square-aspect rectangles within a `side`×`side`
    footprint. Carried over from the M8 packer, generalized to a square site.
    """
    if not zones:
        return []

    boxes: list[RoomBox] = []
    shelf_y = 0.0
    shelf_x = 0.0
    shelf_height = 0.0  # tallest zone depth on the current shelf

    for zone in sorted(zones, key=lambda zz: zz.area_m2, reverse=True):
        # Square-aspect rectangle from area.
        w = sqrt(zone.area_m2)
        d = zone.area_m2 / w if w > 0 else 0.0
        width = min(w, side)
        remaining_depth = side - shelf_y if shelf_y < side else side
        depth = min(d, remaining_depth)

        if shelf_x + width > side + 1e-6:
            shelf_y += shelf_height
            shelf_x = 0.0
            shelf_height = 0.0

        boxes.append(
            RoomBox(
                name=zone.name,
                zone=zone.name,
                floor=floor,
                x=shelf_x,
                y=shelf_y,
                z=z,
                width=width,
                depth=depth,
                height=height,
            )
        )
        shelf_x += width
        if depth > shelf_height:
            shelf_height = depth

    return boxes
