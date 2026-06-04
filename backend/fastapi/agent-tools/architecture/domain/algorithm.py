"""M8 massing algorithm per ADR-18 §8 — rectangular first-fit + area balance.

Pure-Python, no I/O, deterministic.

Inputs: room program (List[Room]) + site footprint + floor height +
optional max_floors cap.

Output: List[RoomBox] with floor / x / y / z / width / depth / height set.

Algorithm:
1. Total floor area = sum of room areas.
2. floor_count = ceil(total_area / site_area).
3. If floor_count > max_floors → raise MASSING_ALGORITHM_FAILED.
4. Sort rooms by area desc; balance allocation across floors greedy
   (smallest-floor-first).
5. Per floor, derive per-room rectangle (width × depth) from room area
   using the site aspect ratio; pack via shelf-first-fit.
"""

from __future__ import annotations

from math import ceil, sqrt
from typing import Iterable

from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.domain.models import Room, RoomBox, SiteFootprint


def compute_massing(
    rooms: Iterable[Room],
    site: SiteFootprint,
    floor_height: float,
    max_floors: int,
) -> list[RoomBox]:
    """Allocate rooms across floors and return per-room boxes."""

    room_list = list(rooms)
    if not room_list:
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "no rooms in extracted program",
        )

    total_area = sum(r.area_m2 for r in room_list)
    if total_area <= 0:
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "total room area is non-positive",
        )
    if site.area_m2 <= 0:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "site area is non-positive",
        )

    floor_count = max(1, ceil(total_area / site.area_m2))
    if floor_count > max_floors:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            (
                f"required {floor_count} floors exceeds max_floors={max_floors}"
                f" (total area {total_area:.1f} m², site {site.area_m2:.1f} m²)"
            ),
        )

    # Greedy balanced allocation: largest-first into smallest-current-floor.
    sorted_rooms = sorted(room_list, key=lambda r: r.area_m2, reverse=True)
    floors: list[list[Room]] = [[] for _ in range(floor_count)]
    floor_load: list[float] = [0.0] * floor_count
    for room in sorted_rooms:
        idx = min(range(floor_count), key=lambda i: floor_load[i])
        floors[idx].append(room)
        floor_load[idx] += room.area_m2

    boxes: list[RoomBox] = []
    site_aspect = site.width / site.depth
    for floor_index, floor_rooms in enumerate(floors, start=1):
        boxes.extend(
            _pack_floor(
                floor_rooms,
                site=site,
                site_aspect=site_aspect,
                floor=floor_index,
                z=(floor_index - 1) * floor_height,
                height=floor_height,
            )
        )
    return boxes


def _pack_floor(
    floor_rooms: list[Room],
    *,
    site: SiteFootprint,
    site_aspect: float,
    floor: int,
    z: float,
    height: float,
) -> list[RoomBox]:
    """Shelf-first-fit pack of axis-aligned rectangles within the site footprint.

    Each room gets a rectangle whose aspect ratio matches the site (so
    pack rate is roughly proportional to area). When a shelf runs out of
    width, start a new shelf at the next y. If a room overflows the site
    depth, the algorithm clamps the rectangle within the site bounds —
    the room is still recorded; over-area handling is the caller's job.
    """
    if not floor_rooms:
        return []

    boxes: list[RoomBox] = []
    shelf_y = 0.0
    shelf_x = 0.0
    shelf_height = 0.0  # tallest room depth on the current shelf

    for room in sorted(floor_rooms, key=lambda r: r.area_m2, reverse=True):
        # Derive room rectangle from area + site aspect ratio.
        depth = sqrt(room.area_m2 / site_aspect)
        width = room.area_m2 / depth
        # Clamp to site dimensions to avoid silently exiting the footprint.
        width = min(width, site.width)
        depth = min(depth, site.depth - shelf_y if shelf_y < site.depth else site.depth)

        if shelf_x + width > site.width + 1e-6:
            shelf_y += shelf_height
            shelf_x = 0.0
            shelf_height = 0.0

        boxes.append(
            RoomBox(
                name=room.name,
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
