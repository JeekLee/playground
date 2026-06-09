"""Refine recipe — the re-derivable program embedded in a massing .glb's extras.

Generation embeds this (NormalizedBrief + resolved params) so a later
`refine_massing` can re-run the deterministic algorithm (classify → derive →
compute) on the prior program with typed edits applied — skipping the slow LLM
extraction. See spec D1.
"""

from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel, Field

from architecture.domain.models import NormalizedBrief

# glTF scenes[0].extras key holding the recipe (sibling of "programJson").
RECIPE_KEY = "refineRecipe"


class RefineRecipe(BaseModel):
    """The payload embedded in .glb extras under RECIPE_KEY (internal contract).

    Dumped with ``by_alias=True`` so the embedded keys are camelCase
    (``floorHeightM`` etc.), matching the wire convention; ``populate_by_name``
    lets ``model_validate`` accept them back on the refine read path.
    """

    normalized: NormalizedBrief
    floor_height_m: float = Field(alias="floorHeightM")
    target_floors_above: int = Field(alias="targetFloorsAbove")
    brief_title: str = Field(alias="briefTitle")

    model_config = {"populate_by_name": True}


@dataclass(frozen=True, slots=True)
class RefineDeriveReq:
    """Duck-typed stand-in for GenerateMassingRequest.

    ``derive_inputs`` reads ONLY ``target_floors`` and ``floor_height`` from its
    ``req`` arg. We must NOT reconstruct a GenerateMassingRequest (its SP4
    exactly-one validator would reject a refine call), so the refine graph puts
    this object in ``state["req"]`` instead.
    """

    target_floors: int | None
    floor_height: float | None
