"""compute node (ADR-19 Phase 3a-2) — deterministic massing.

Single job: `state["inputs"]` (MassingInputs) -> `state["boxes"]`
(list[RoomBox]) via the framework-free `domain.algorithm.compute_massing`.

No branching, no LLM, no I/O. The coverage / FAR gates are enforced upstream
in derive, so this stage may assume a feasible massing. Floors arrive already
footprint-derived (Phase 3a-2): the algorithm itself is unchanged.
"""

from __future__ import annotations

from architecture.app.state import MassingState
from architecture.domain.algorithm import compute_massing


def compute(state: MassingState) -> dict:
    return {"boxes": compute_massing(state["inputs"])}
