"""persist node (ADR-19 §A19.9 / Phase 3a) — write arch.outputs row + response.

Single step, no branching. Writes through infra ArchOutput +
shared_kernel.database; constructs the GenerateMassingResponse with the
relative /api/arch/outputs/{id} URL.

Phase 3a: the program/floor figures come from the validated `MassingInputs`
(zones, target_floors_above, basement_levels), not the raw extraction —
`floorCount` is above-grade floors, basements ride in `basementLevels` +
`summary`. `arch.outputs.floor_count` stores the above-grade count (schema
unchanged — Phase 3b owns any persistence change).
"""

from __future__ import annotations

from architecture.api.dtos import (
    GenerateMassingResponse,
    ProgramJsonWire,
    RoomWire,
)
from architecture.app.state import MassingState
from architecture.domain.slug import briefslug
from architecture.domain.summary import format_summary
from architecture.infra.persistence import ArchOutput
from shared_kernel.database import session_scope


def persist(state: MassingState) -> dict:
    req = state["req"]
    inputs = state["inputs"]

    zones = inputs.zones
    total_area = sum(z.area_m2 for z in zones)
    floors_above = inputs.target_floors_above
    basement_levels = inputs.basement_levels

    program_json = ProgramJsonWire(
        rooms=[RoomWire(name=z.name, areaM2=z.area_m2) for z in zones],
        totalAreaM2=total_area,
        floorCount=floors_above,
        basementLevels=basement_levels,
    )
    summary = format_summary(
        room_count=len(zones),
        floors_above=floors_above,
        basement_levels=basement_levels,
        total_area_m2=total_area,
    )
    slug = briefslug(state["detail"].title)

    with session_scope() as session:
        row = ArchOutput(
            brief_doc_id=req.brief_doc_id,
            brief_slug=slug,
            user_id=state["user_id"],
            file_bytes=state["file_bytes"],
            program_json=program_json.model_dump(by_alias=True),
            total_area_m2=total_area,
            floor_count=floors_above,
        )
        session.add(row)
        session.flush()
        output_id = row.id

    return {
        "response": GenerateMassingResponse(
            fileUrl=f"/api/arch/outputs/{output_id}",
            programJson=program_json,
            totalAreaM2=total_area,
            floorCount=floors_above,
            basementLevels=basement_levels,
            summary=summary,
        )
    }
