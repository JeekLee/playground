"""apply_edits node (refine pipeline) — apply typed edits to the loaded recipe.

Mutates the NormalizedBrief + floor count, then writes a RefineDeriveReq into
state["req"] so the reused derive node sizes floors/height correctly. classify
runs next and recomputes the footprint driver + grade from the edited program.
"""

from __future__ import annotations

from architecture.app.edits import apply_edits as _apply_edits
from architecture.app.refine_recipe import RefineDeriveReq
from architecture.app.state import MassingState


def apply_edits(state: MassingState) -> dict:
    edited, tfa = _apply_edits(
        state["normalized"], state["target_floors_above"], state["edits"]
    )
    return {
        "normalized": edited,
        "target_floors_above": tfa,
        "req": RefineDeriveReq(target_floors=tfa, floor_height=state["floor_height_m"]),
    }
