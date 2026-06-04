"""resolve step (ADR-19 Phase 3a) — BriefAnalysis -> MassingInputs.

Rule-first, deterministic. No LLM. Maps the open extraction to the tight
algorithm contract, applying config defaults only where the brief is silent.

Mapping rules:
- zones      — one Zone per ProgramItem. grade = item.grade when above/below,
               else inferred "below" if name/kind signals 지하/주차/basement/B1,
               else "above".
- site_area  — BriefAnalysis.site_area_m2; if None -> MassingError(BRIEF_NOT_READY)
               so the resolution subgraph can re-prompt the extractor.
- coverage   — coverage_ratio_max if present else DEFAULT_COVERAGE_CAP.
- floors     — request.targetFloors > floor_limit (treated as target) >
               DEFAULT_TARGET_FLOORS_ABOVE.
- basements  — 1 if any below-grade zone else 0.
- floor_ht   — request.floorHeight or settings.default_floor_height_m.

The coverage gate lives in MassingInputs validation; a violation surfaces as
MASSING_ALGORITHM_FAILED here (the brief's program cannot fit the site under
the resolved floor count / coverage cap).
"""

from __future__ import annotations

from typing import Callable

from pydantic import ValidationError

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.state import MassingState
from architecture.domain.models import BriefAnalysis, MassingInputs, Zone
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

_BELOW_SIGNALS = ("지하", "주차", "parking", "basement", "b1")


def _infer_grade(name: str, kind: str | None) -> str:
    blob = f"{name} {kind or ''}".lower()
    if any(sig in blob for sig in _BELOW_SIGNALS):
        return "below"
    return "above"


def resolve_inputs(
    analysis: BriefAnalysis,
    req: GenerateMassingRequest,
    settings: Settings,
) -> MassingInputs:
    """Build the validated MassingInputs from the open BriefAnalysis."""

    zones = [
        Zone(
            name=item.name,
            area_m2=item.area_m2,
            grade=(
                item.grade
                if item.grade in ("above", "below")
                else _infer_grade(item.name, item.kind)
            ),
        )
        for item in analysis.program
    ]

    if analysis.site_area_m2 is None:
        # Hard-required input missing → trigger the re-prompt loop.
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_READY,
            "site area (대지면적) not found in brief — cannot size the footprint",
        )
    site_area_m2 = analysis.site_area_m2

    coverage_cap = (
        analysis.coverage_ratio_max
        if analysis.coverage_ratio_max is not None
        else settings.default_coverage_cap
    )

    target_floors_above = (
        req.target_floors
        or analysis.floor_limit
        or settings.default_target_floors_above
    )

    basement_levels = 1 if any(z.grade == "below" for z in zones) else 0
    floor_height_m = req.floor_height or settings.default_floor_height_m

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


def make_resolve_node(settings: Settings) -> Callable[[MassingState], dict]:
    """Build the resolve node: state['analysis'] + state['req'] -> state['inputs']."""

    def resolve(state: MassingState) -> dict:
        inputs = resolve_inputs(state["analysis"], state["req"], settings)
        return {"inputs": inputs}

    return resolve
