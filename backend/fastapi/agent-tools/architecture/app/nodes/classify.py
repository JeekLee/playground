"""classify node (ADR-19 Phase 3a-2) — grade zones + find the footprint driver.

Single job: `state["normalized"]` (NormalizedBrief) -> `state["classified"]`
(ClassifiedBrief). Pure/rule, no LLM.

Responsibilities:
(a) Grade every zone above/below: explicit grade wins; else infer from keywords
    (지하/주차/parking/basement/B1 -> below; otherwise above).
(b) Identify the **largest single space** among the above-grade NAMED
    sub-spaces and compute its **gross-adjusted area** — this is the minimum
    footprint the building must provide (`footprint_driver_m2`):

      gross_adjusted = net_area / 전용비율

    where 전용비율 (net_ratio) comes from the sub-space's parent zone when the
    brief stated the 전용/공용 split, else `DEFAULT_NET_RATIO` (0.75). A
    sub-space already marked `is_net=False` (gross) is used as-is. The driver
    is the max over above-grade sub-spaces.

`footprint_driver_m2` is None when there are no above-grade named sub-spaces
(derive then falls back to the GFA/coverage sizing).
"""

from __future__ import annotations

from architecture.app.state import MassingState
from architecture.domain.models import (
    ClassifiedBrief,
    NormalizedBrief,
    NormalizedZone,
    ProgramItem,
    Zone,
)

_BELOW_SIGNALS = ("지하", "주차", "parking", "basement", "b1")

# Fallback 전용비율 when a brief does not state the zone's 전용/공용 split.
DEFAULT_NET_RATIO = 0.75


def _infer_grade(name: str, kind: str | None = None) -> str:
    blob = f"{name} {kind or ''}".lower()
    if any(sig in blob for sig in _BELOW_SIGNALS):
        return "below"
    return "above"


def _grade_zone(z: NormalizedZone) -> str:
    if z.grade in ("above", "below"):
        return z.grade
    return _infer_grade(z.name)


def _grade_item(it: ProgramItem) -> str:
    if it.grade in ("above", "below"):
        return it.grade
    return _infer_grade(it.name, it.kind)


def classify_brief(normalized: NormalizedBrief) -> ClassifiedBrief:
    # (a) Grade zones.
    graded_zones = [
        Zone(name=z.name, area_m2=z.area_m2, grade=_grade_zone(z))  # type: ignore[arg-type]
        for z in normalized.zones
    ]

    # net_ratio per zone name, for gross-adjusting sub-spaces.
    net_ratio_by_zone = {
        z.name: z.net_ratio for z in normalized.zones if z.net_ratio
    }

    # (b) Largest above-grade named sub-space -> gross-adjusted footprint driver.
    driver: float | None = None
    for it in normalized.sub_spaces:
        if _grade_item(it) != "above":
            continue
        if it.is_net is False:
            # already a gross area
            gross = it.area_m2
        else:
            ratio = net_ratio_by_zone.get(it.parent_zone or "")
            if not ratio:
                ratio = DEFAULT_NET_RATIO
            gross = it.area_m2 / ratio
        if driver is None or gross > driver:
            driver = gross

    return ClassifiedBrief(
        zones=graded_zones,
        footprint_driver_m2=driver,
        site_area_m2=normalized.site_area_m2,
        coverage_ratio_max=normalized.coverage_ratio_max,
        floor_area_ratio_max=normalized.floor_area_ratio_max,
        total_gfa_m2=normalized.total_gfa_m2,
        floor_limit=normalized.floor_limit,
        consistency_note=normalized.consistency_note,
    )


def classify(state: MassingState) -> dict:
    return {"classified": classify_brief(state["normalized"])}
