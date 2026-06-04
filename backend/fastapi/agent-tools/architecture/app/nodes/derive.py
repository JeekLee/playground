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
4. target_floors_above = request.targetFloors override else
   ceil(above_gross / footprint) (>= 1). Floors now FOLLOW the footprint.
5. basement_levels = 1 if any below-grade zone else 0.
6. floor_height from request/settings.
7. Validate via MassingInputs (coverage + 용적률 gate).

Missing site_area (can't size the footprint) -> MassingError(BRIEF_NOT_READY),
which triggers the program-resolution re-prompt loop.
"""

from __future__ import annotations

import logging
from math import ceil

from pydantic import ValidationError

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.state import MassingState
from architecture.domain.models import ClassifiedBrief, MassingInputs, Zone
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)


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

    # --- Floors: DERIVED from the footprint (request override wins) ---
    if req.target_floors:
        target_floors_above = req.target_floors
    elif above_gross > 0 and footprint > 0:
        target_floors_above = max(1, ceil(above_gross / footprint))
    else:
        target_floors_above = settings.default_target_floors_above

    basement_levels = 1 if has_below else 0
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

    try:
        return MassingInputs(
            zones=zones,
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
