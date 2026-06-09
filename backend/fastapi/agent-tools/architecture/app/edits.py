"""Typed massing-refine edit operations + deterministic applier (spec D3).

The LLM maps a natural-language refine request to a list of these ops (the
chat schema declares the `op` discriminator + the union of fields loosely);
agent-tools validates strictly via the Pydantic discriminated union and applies
them to a NormalizedBrief. SetFloors changes the floor-count param; the other
three edit the program (sub-spaces / zone areas), after which classify → derive
recompute footprint driver, grade, and the 건폐율/용적률/slot-fit gates.
"""

from __future__ import annotations

from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field

from architecture.domain.models import NormalizedBrief, ProgramItem
from shared_kernel.errors import MassingError, MassingErrorCode


class RenameRoom(BaseModel):
    op: Literal["RenameRoom"]
    # `from` is a Python keyword → field name name_from, wire alias "from".
    name_from: str = Field(alias="from", min_length=1)
    name_to: str = Field(alias="to", min_length=1)
    model_config = {"populate_by_name": True}


class AddRoom(BaseModel):
    op: Literal["AddRoom"]
    name: str = Field(min_length=1)
    area_m2: float = Field(alias="areaM2", gt=0)
    zone: str | None = None
    model_config = {"populate_by_name": True}


class SetFloors(BaseModel):
    op: Literal["SetFloors"]
    target_floors_above: int = Field(alias="targetFloorsAbove", ge=1)
    model_config = {"populate_by_name": True}


class SetArea(BaseModel):
    op: Literal["SetArea"]
    target: str = Field(min_length=1)
    area_m2: float = Field(alias="areaM2", gt=0)
    model_config = {"populate_by_name": True}


EditOp = Annotated[
    Union[RenameRoom, AddRoom, SetFloors, SetArea],
    Field(discriminator="op"),
]


def apply_edits(
    normalized: NormalizedBrief,
    target_floors_above: int,
    edits: list[EditOp],
) -> tuple[NormalizedBrief, int]:
    """Apply edit ops to a (NormalizedBrief, floor-count) pair.

    Pure: deep-copies the brief, never mutates the input. Raises
    MassingError(REFINE_TARGET_NOT_FOUND) when a rename/resize names a room or
    zone that is not present. Returns the edited brief + (possibly changed)
    above-grade floor count.
    """
    nb = normalized.model_copy(deep=True)
    tfa = target_floors_above
    for op in edits:
        if isinstance(op, RenameRoom):
            matched = [s for s in nb.sub_spaces if s.name == op.name_from]
            if not matched:
                raise MassingError(
                    MassingErrorCode.REFINE_TARGET_NOT_FOUND,
                    f"수정할 실 '{op.name_from}'을(를) 찾을 수 없습니다",
                )
            # Renames ALL sub_spaces matching name_from (duplicate names collapse
            # together) — the LLM names rooms from a manifest that may repeat.
            for s in matched:
                s.name = op.name_to
        elif isinstance(op, AddRoom):
            nb.sub_spaces.append(
                ProgramItem(
                    name=op.name,
                    area_m2=op.area_m2,
                    grade="above",
                    parent_zone=op.zone,
                    is_net=True,
                )
            )
        elif isinstance(op, SetFloors):
            tfa = op.target_floors_above
        elif isinstance(op, SetArea):
            zmatch = [z for z in nb.zones if z.name == op.target]
            smatch = [s for s in nb.sub_spaces if s.name == op.target]
            if not zmatch and not smatch:
                raise MassingError(
                    MassingErrorCode.REFINE_TARGET_NOT_FOUND,
                    f"면적을 바꿀 대상 '{op.target}'을(를) 존(zone)·실에서 찾을 수 없습니다",
                )
            # A name colliding across a zone AND a room updates both (by design).
            for z in zmatch:
                z.area_m2 = op.area_m2
            for s in smatch:
                s.area_m2 = op.area_m2
    return nb, tfa
