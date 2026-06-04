"""persist node (ADR-19 §A19.9) — write arch.outputs row + build response.

Single step, no branching. Writes through infra ArchOutput +
shared_kernel.database; constructs the GenerateMassingResponse with the
relative /api/arch/outputs/{id} URL.
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
    extracted = state["extracted"]
    boxes = state["boxes"]
    total_area = sum(r.area_m2 for r in extracted.rooms)
    floor_count = max(b.floor for b in boxes)
    program_json = ProgramJsonWire(
        rooms=[RoomWire(name=r.name, areaM2=r.area_m2) for r in extracted.rooms],
        totalAreaM2=total_area,
        floorCount=floor_count,
    )
    summary = format_summary(
        room_count=len(extracted.rooms),
        floor_count=floor_count,
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
            floor_count=floor_count,
        )
        session.add(row)
        session.flush()
        output_id = row.id

    return {
        "response": GenerateMassingResponse(
            fileUrl=f"/api/arch/outputs/{output_id}",
            programJson=program_json,
            totalAreaM2=total_area,
            floorCount=floor_count,
            summary=summary,
        )
    }
