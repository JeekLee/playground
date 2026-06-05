"""derive node (ADR-19 Phase 3a-2) — footprint-driven MassingInputs.

Single job: `state["classified"]` (ClassifiedBrief) + `state["req"]` ->
`state["inputs"]` (validated MassingInputs). Pure/rule, no LLM.

This is where the Phase-3a-2 behavior change lives: **floors are DERIVED from
the footprint set by the largest single space**, not a fixed default.

Steps:
1. above_gross = Σ above-grade zone gross areas.
2. coverage_cap = brief coverage else DEFAULT_COVERAGE_CAP (0.6).
3. footprint = clamp(footprint_driver, lower=footprint_driver,
   upper=site_area*coverage_cap)
   i.e. footprint = min(footprint_driver, site_area*coverage_cap), but the
   driver is also the lower bound — so if the driver exceeds the coverage cap
   the program's largest space cannot fit the buildable footprint ->
   MassingError(MASSING_ALGORITHM_FAILED).
   When there is no footprint driver (no named above-grade sub-spaces) we fall
   back to sizing the footprint from above_gross / DEFAULT_TARGET_FLOORS_ABOVE,
   still capped by coverage.
4. Room attribution (D7): sub_spaces → zones via parent_zone or unique grade.
   Guard 3 (D4): Σrooms > gross×0.98 → drop (extraction error).
5. target_floors_above = request.targetFloors override else
   ceil(above_gross / footprint) (>= 1). Floors now FOLLOW the footprint.
   Guard 2: override slot check — misfit zones degraded.
   Slot-fit floor cap (D3): floors reduced if largest room > slot, re-checked
   against coverage (Guard 1); misfit zones degraded on coverage conflict.
6. basement_levels = 1 if any below-grade zone else 0.
   D3 amendment: below-grade slot = zone_gross / basement_levels; misfit → drop.
7. floor_height from request/settings.
8. Validate via MassingInputs (coverage + 용적률 gate).

Missing site_area (can't size the footprint) -> MassingError(BRIEF_NOT_READY),
which triggers the program-resolution re-prompt loop.
"""

from __future__ import annotations

import logging
from math import ceil, floor

from pydantic import ValidationError

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.state import MassingState
from architecture.domain.models import ClassifiedBrief, MassingInputs, ProgramItem, Room, Zone
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)

# Σ(zone 실) 허용 상한 — net 합이 gross를 사실상 채우면 추출 오류로 본다 (D4-3).
_ROOM_SUM_TOLERANCE = 0.98


def _attribute_rooms(classified: ClassifiedBrief) -> dict[str, list[Room]]:
    """sub_spaces → zone 귀속 (D7): parent_zone 명시 우선, 없으면 같은 grade의
    zone이 유일할 때만 귀속, 그 외 미배정."""
    zones_by_name = {z.name: z for z in classified.zones}
    by_grade: dict[str, list[Zone]] = {"above": [], "below": []}
    for z in classified.zones:
        by_grade[z.grade].append(z)

    rooms: dict[str, list[Room]] = {z.name: [] for z in classified.zones}
    for it in classified.sub_spaces:
        target = None
        if it.parent_zone and it.parent_zone in zones_by_name:
            target = zones_by_name[it.parent_zone]
        elif it.grade in by_grade and len(by_grade[it.grade]) == 1:
            target = by_grade[it.grade][0]
        if target is not None:
            rooms[target.name].append(Room(name=it.name, area_m2=it.area_m2))
    return rooms


def _drop_rooms(rooms: dict[str, list[Room]], zone_name: str, reason: str) -> None:
    if rooms.get(zone_name):
        logger.warning("room split degraded zone=%s reason=%s", zone_name, reason)
        rooms[zone_name] = []


def derive_inputs(
    classified: ClassifiedBrief,
    req: GenerateMassingRequest,
    settings: Settings,
) -> MassingInputs:
    zones = list(classified.zones)
    above_gross = sum(z.area_m2 for z in zones if z.grade == "above")
    has_below = any(z.grade == "below" for z in zones)

    # site_area is required to size the footprint.
    if classified.site_area_m2 is None:
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_READY,
            "site area (대지면적) not found in brief — cannot size the footprint",
        )
    site_area_m2 = classified.site_area_m2

    coverage_cap = (
        classified.coverage_ratio_max
        if classified.coverage_ratio_max is not None
        else settings.default_coverage_cap
    )
    buildable_footprint = site_area_m2 * coverage_cap

    # --- Footprint: driven by the largest single space ---
    driver = classified.footprint_driver_m2
    if driver is not None and driver > 0:
        if driver > buildable_footprint + 1e-6:
            raise MassingError(
                MassingErrorCode.MASSING_ALGORITHM_FAILED,
                "program's largest space exceeds buildable footprint "
                f"(driver {driver:.0f}㎡ > site {site_area_m2:.0f}㎡ × "
                f"coverage {coverage_cap:.2f} = {buildable_footprint:.0f}㎡)",
            )
        # footprint = min(max(driver, ...), site_area*coverage_cap); driver is
        # both the value and its own lower bound, so this is just the driver
        # (it's already <= buildable_footprint here).
        footprint = driver
    elif above_gross > 0:
        # No named above-grade sub-space — last-resort fallback: size from the
        # default floor count, then let floors re-derive consistently.
        footprint = above_gross / settings.default_target_floors_above
        footprint = min(footprint, buildable_footprint)
    else:
        # No above-grade program at all (e.g. all-basement) — nominal footprint.
        footprint = buildable_footprint

    rooms_by_zone = _attribute_rooms(classified)

    # 가드 3 (D4): Σ실 > zone_gross × 0.98 → 추출 오류로 보고 강등.
    for z in zones:
        total = sum(r.area_m2 for r in rooms_by_zone.get(z.name, []))
        if total > z.area_m2 * _ROOM_SUM_TOLERANCE:
            _drop_rooms(rooms_by_zone, z.name,
                        f"room sum {total:.0f} > gross {z.area_m2:.0f} × {_ROOM_SUM_TOLERANCE}")

    # --- Floors: DERIVED from the footprint (request override wins) ---
    if req.target_floors:
        target_floors_above = req.target_floors
        # 가드 2 (D4): 오버라이드 층수에서 슬롯에 안 들어가는 zone은 강등.
        for z in zones:
            if z.grade != "above" or not rooms_by_zone.get(z.name):
                continue
            slot = z.area_m2 / target_floors_above
            biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
            if biggest > slot + 1e-6:
                _drop_rooms(rooms_by_zone, z.name,
                            f"override floors={target_floors_above}: room {biggest:.0f} > slot {slot:.0f}")
    elif above_gross > 0 and footprint > 0:
        target_floors_above = max(1, ceil(above_gross / footprint))

        # D3: 지상 zone마다 슬롯 ≥ 최대 실이 되도록 층수 상한을 적용.
        caps = []
        for z in zones:
            if z.grade != "above" or not rooms_by_zone.get(z.name):
                continue
            biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
            caps.append(max(1, floor(z.area_m2 / biggest)))
        if caps:
            capped = min(target_floors_above, min(caps))
            if capped < target_floors_above:
                # 층수가 줄면 풋프린트가 커진다 — 건폐율 재검증 (가드 1).
                new_footprint = above_gross / capped
                if new_footprint <= buildable_footprint + 1e-6:
                    target_floors_above = capped
                else:
                    for z in zones:
                        if z.grade != "above" or not rooms_by_zone.get(z.name):
                            continue
                        slot = z.area_m2 / target_floors_above
                        biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
                        if biggest > slot + 1e-6:
                            _drop_rooms(rooms_by_zone, z.name,
                                        "coverage cap blocks floor reduction "
                                        f"(need footprint {new_footprint:.0f} > buildable {buildable_footprint:.0f})")
    else:
        target_floors_above = settings.default_target_floors_above

    basement_levels = 1 if has_below else 0

    # D3 amendment: 지하 슬롯은 basement_levels가 결정 — 안 들어가면 강등.
    for z in zones:
        if z.grade != "below" or not rooms_by_zone.get(z.name):
            continue
        slot = z.area_m2 / max(1, basement_levels)
        biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
        if biggest > slot + 1e-6:
            _drop_rooms(rooms_by_zone, z.name,
                        f"basement room {biggest:.0f} > slot {slot:.0f}")
    floor_height_m = req.floor_height or settings.default_floor_height_m

    # --- 용적률 (FAR) gate: above_gross / site_area <= far_cap (if stated) ---
    far_cap = classified.floor_area_ratio_max
    if far_cap is not None and above_gross > 0:
        far = above_gross / site_area_m2
        if far > far_cap + 1e-6:
            raise MassingError(
                MassingErrorCode.MASSING_ALGORITHM_FAILED,
                f"용적률 gate violated: above-grade FAR {far:.2f} "
                f"(= {above_gross:.0f}㎡ / site {site_area_m2:.0f}㎡) "
                f"exceeds cap {far_cap:.2f}",
            )

    zones_with_rooms = [
        z.model_copy(update={"rooms": rooms_by_zone.get(z.name, [])})
        for z in zones
    ]

    try:
        return MassingInputs(
            zones=zones_with_rooms,
            site_area_m2=site_area_m2,
            coverage_cap=coverage_cap,
            target_floors_above=target_floors_above,
            basement_levels=basement_levels,
            floor_height_m=floor_height_m,
        )
    except ValidationError as exc:
        first = exc.errors()[0] if exc.errors() else {"msg": "invalid inputs"}
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            f"cannot resolve a feasible massing: {first.get('msg', 'invalid')}",
            cause=exc,
        ) from exc


def make_derive_node(settings: Settings):
    """Build the derive node: classified + req -> inputs."""

    def derive(state: MassingState) -> dict:
        classified = state["classified"]
        inputs = derive_inputs(classified, state["req"], settings)
        above_gross = sum(z.area_m2 for z in inputs.zones if z.grade == "above")
        footprint = above_gross / inputs.target_floors_above if inputs.target_floors_above else 0
        logger.info(
            "derived footprint_driver=%s above_gross=%.0f → footprint≈%.0f "
            "floors_above=%d basements=%d (site=%.0f coverage=%.2f)",
            classified.footprint_driver_m2,
            above_gross,
            footprint,
            inputs.target_floors_above,
            inputs.basement_levels,
            inputs.site_area_m2,
            inputs.coverage_cap,
        )
        return {"inputs": inputs}

    return derive
