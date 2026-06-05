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
   Within a split zone, FFD assigns rooms to levels; fragmentation → unsplit
   degrade (deviation 2). Each split zone's floor rectangle is subdivided into
   room boxes + 공용 잔여 (슬롯의 1% 초과 시). Non-split zones remain as a
   single zone-level box.

Floor / level indices:
- above-grade: floor 1..target_floors_above, z >= 0
- below-grade: floor -1..-basement_levels (B1 = -1), z < 0
"""

from __future__ import annotations

from math import sqrt

from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.domain.models import COMMON_AREA_NAME, MassingInputs, Room, RoomBox, Zone


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
        # Zone별 실→층 FFD 사전 할당 (None = rooms 없음/단편화 강등).
        assignments = {
            z.name: _assign_rooms_ffd(
                z.rooms,
                inputs.target_floors_above,
                z.area_m2 / inputs.target_floors_above,
            )
            for z in above
        }
        slot_by_zone = {
            z.name: z.area_m2 / inputs.target_floors_above for z in above
        }
        for floor in range(1, inputs.target_floors_above + 1):
            boxes.extend(
                _pack_level(
                    per_floor_zones,
                    side=side,
                    floor=floor,
                    z=(floor - 1) * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                    assignments=assignments,
                    slot_by_zone=slot_by_zone,
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
        # 지하 zone별 실→레벨 FFD 사전 할당.
        assignments_below = {
            z.name: _assign_rooms_ffd(z.rooms, levels, z.area_m2 / levels)
            for z in below
        }
        slot_below = {z.name: z.area_m2 / levels for z in below}
        # B1 = level 1 = floor -1 = z -h; B2 = floor -2 = z -2h; ...
        for level in range(1, levels + 1):
            boxes.extend(
                _pack_level(
                    per_level_zones,
                    side=side,
                    floor=-level,
                    z=-level * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                    assignments=assignments_below,
                    slot_by_zone=slot_below,
                    level_key=level,
                )
            )

    if not boxes:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "no zones produced any boxes",
        )
    return boxes


def _assign_rooms_ffd(
    rooms: list[Room], n_levels: int, slot_area: float
) -> dict[int, list[Room]] | None:
    """First-fit-decreasing room→level 할당 (1-indexed level).

    derive가 "최대 실 ≤ 슬롯"은 보장하지만 단편화로 전체 패킹이 실패할 수
    있다 — 그때 None을 반환하고 호출부가 zone을 통짜로 강등한다 (design
    spec deviation 2).

    rooms가 비어있으면 None 반환 (분할 없음).

    정렬은 내림차순 stable — 면적이 같은 실은 brief 입력 순서를 유지한다.
    FFD가 실을 배정하지 못한 층은 _subdivide_zone_rect에서 공용 스트립만
    생성된다 (비실 면적 전부를 공용으로 표시)."""
    if not rooms:
        return None
    remaining = [slot_area] * n_levels
    out: dict[int, list[Room]] = {}
    for room in sorted(rooms, key=lambda r: r.area_m2, reverse=True):
        for i in range(n_levels):
            if room.area_m2 <= remaining[i] + 1e-6:
                out.setdefault(i + 1, []).append(room)
                remaining[i] -= room.area_m2
                break
        else:
            return None
    return out


def _subdivide_zone_rect(
    *,
    zone_name: str,
    x0: float,
    y0: float,
    rect_w: float,
    rect_d: float,
    rooms: list[Room],
    slot_area: float,
    floor: int,
    z: float,
    height: float,
) -> list[RoomBox]:
    """zone 사각형 내부를 [실들 + 공용 잔여]의 비례 스트립으로 분할 (D1·D2).

    각 엔트리는 rect 깊이 전체를 쓰는 세로 스트립 — 폭은 면적 비례
    (width_i = rect_w × area_i / total). 스트립은 절대 rect를 벗어나지도,
    서로 겹치지도 않으며 Σ(스트립 면적) == rect 면적이라 이웃 zone과의
    교차가 원천 차단된다 (square-aspect shelf는 잔여 박스가 풋프린트
    밖으로 돌출하는 문제가 있었다 — 2026-06-05 리뷰).

    FFD가 실을 배정하지 않은 층은 엔트리가 공용 하나뿐이라 슬롯 전체가
    공용·기타 스트립이 된다 (분할 zone의 비실(非室) 면적은 전부 공용).
    """
    entries: list[tuple[str, float]] = [
        (r.name, r.area_m2)
        for r in sorted(rooms, key=lambda r: r.area_m2, reverse=True)
    ]
    remainder = slot_area - sum(r.area_m2 for r in rooms)
    if remainder > slot_area * 0.01:
        entries.append((COMMON_AREA_NAME, remainder))

    total = sum(area for _, area in entries)
    boxes: list[RoomBox] = []
    cursor_x = 0.0
    for name, area in entries:
        width = rect_w * (area / total)
        boxes.append(
            RoomBox(
                name=name,
                zone=zone_name,
                floor=floor,
                x=x0 + cursor_x,
                y=y0,
                z=z,
                width=width,
                depth=rect_d,
                height=height,
            )
        )
        cursor_x += width
    return boxes


def _pack_level(
    zones: list[Zone],
    *,
    side: float,
    floor: int,
    z: float,
    height: float,
    assignments: dict[str, dict[int, list[Room]] | None] | None = None,
    slot_by_zone: dict[str, float] | None = None,
    level_key: int | None = None,
) -> list[RoomBox]:
    """Shelf-first-fit pack of square-aspect rectangles within a `side`×`side`
    footprint. Carried over from the M8 packer, generalized to a square site.

    When `assignments` is provided, split zones subdivide their rect into
    room boxes + 공용 잔여; unsplit zones (assignment is None) remain as a
    single zone-level box.
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

        assignment = (assignments or {}).get(zone.name)
        if assignment is None:
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
        else:
            key = level_key if level_key is not None else floor
            boxes.extend(
                _subdivide_zone_rect(
                    zone_name=zone.name,
                    x0=shelf_x,
                    y0=shelf_y,
                    rect_w=width,
                    rect_d=depth,
                    rooms=assignment.get(key, []),
                    slot_area=(slot_by_zone or {})[zone.name],
                    floor=floor,
                    z=z,
                    height=height,
                )
            )

        shelf_x += width
        if depth > shelf_height:
            shelf_height = depth

    return boxes
