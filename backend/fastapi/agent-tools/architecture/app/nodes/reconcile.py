"""reconcile node (ADR-19 Phase 3a-2) — normalize the open extraction.

Single job: `state["analysis"]` (BriefAnalysis) -> `state["normalized"]`
(NormalizedBrief). Pure/rule, no LLM.

Responsibilities:
1. Prefer GROSS per-zone totals. A brief often gives both 전용 sub-spaces AND a
   zone 합계 (which already includes 공용). When `zones_gross` is present we
   mass with those gross totals and keep the named `program` items as
   sub-spaces. When `zones_gross` is empty we fall back to treating `program`
   items as the zones (legacy / brief gave no breakdown), and they double as
   the sub-spaces so classify can still find a footprint driver.
2. Normalize ratios to 0..1: a coverage of 80 (rather than 0.8) is divided by
   100; everything is clamped to [0, 1].
3. Consistency check: Σ(zone gross) vs `total_gfa_m2`. If they deviate by more
   than ±5% record a non-fatal `consistency_note` (never fail).

`net_ratio` (전용비율) is carried forward per zone so classify can gross-adjust
a named net sub-space.
"""

from __future__ import annotations

from architecture.app.state import MassingState
from architecture.domain.models import (
    BriefAnalysis,
    NormalizedBrief,
    NormalizedZone,
)

_CONSISTENCY_TOLERANCE = 0.05  # ±5%


def _to_ratio(value: float | None) -> float | None:
    """Coerce a ratio to 0..1. 80 -> 0.8; 0.8 -> 0.8; clamp into [0, 1]."""
    if value is None:
        return None
    r = value / 100.0 if value > 1.0 else value
    return max(0.0, min(1.0, r))


def reconcile_analysis(analysis: BriefAnalysis) -> NormalizedBrief:
    coverage = _to_ratio(analysis.coverage_ratio_max)

    if analysis.zones_gross:
        zones = [
            NormalizedZone(
                name=zg.name,
                area_m2=zg.area_m2,
                grade=zg.grade,
                net_ratio=_to_ratio(zg.net_ratio),
            )
            for zg in analysis.zones_gross
        ]
        # Named program items are 전용 sub-spaces within those gross zones.
        sub_spaces = list(analysis.program)
    else:
        # No gross breakdown — program items ARE the zones; reuse them as
        # sub-spaces too so classify can still locate a footprint driver.
        zones = [
            NormalizedZone(name=it.name, area_m2=it.area_m2, grade=it.grade)
            for it in analysis.program
        ]
        sub_spaces = list(analysis.program)

    # Consistency check (non-fatal).
    note: str | None = None
    gross_sum = sum(z.area_m2 for z in zones)
    if analysis.total_gfa_m2 and gross_sum > 0:
        deviation = abs(gross_sum - analysis.total_gfa_m2) / analysis.total_gfa_m2
        if deviation > _CONSISTENCY_TOLERANCE:
            note = (
                f"zone gross sum {gross_sum:.0f}㎡ deviates {deviation * 100:.1f}% "
                f"from stated 연면적 {analysis.total_gfa_m2:.0f}㎡ "
                f"(>±{_CONSISTENCY_TOLERANCE * 100:.0f}%)"
            )

    return NormalizedBrief(
        zones=zones,
        sub_spaces=sub_spaces,
        site_area_m2=analysis.site_area_m2,
        coverage_ratio_max=coverage,
        floor_area_ratio_max=analysis.floor_area_ratio_max,
        total_gfa_m2=analysis.total_gfa_m2,
        floor_limit=analysis.floor_limit,
        consistency_note=note,
    )


def reconcile(state: MassingState) -> dict:
    return {"normalized": reconcile_analysis(state["analysis"])}
