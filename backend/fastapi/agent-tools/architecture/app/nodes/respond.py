"""respond node — build the `{result, artifact}` envelope (ADR-20 §D3 revised).

The `store` node has already uploaded the .3dm to MinIO and stashed the
object key in `state["storage_key"]`. This node assembles the response:
- `result`: LLM-visible massing summary (floors, area, program JSON, Korean summary)
- `artifact`: metadata only — filename, contentType, sizeBytes, storageKey

No bytes travel in the HTTP response body; rag-chat records the storageKey
in `chat.message_attachments` and serves downloads via its own MinIO GET.
"""

from __future__ import annotations

from architecture.api.dtos import (
    GenerateMassingResponse,
    MassingArtifact,
    MassingResult,
    ProgramJsonWire,
    RoomWire,
)
from architecture.app.state import MassingState
from architecture.domain.summary import format_summary


def respond(state: MassingState) -> dict:
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

    storage_key: str = state["storage_key"]
    file_bytes: bytes = state["file_bytes"]
    # Reconstruct filename from storageKey (last path segment).
    filename = storage_key.rsplit("/", 1)[-1]

    return {
        "response": GenerateMassingResponse(
            result=MassingResult(
                programJson=program_json,
                totalAreaM2=total_area,
                floorCount=floors_above,
                basementLevels=basement_levels,
                summary=summary,
            ),
            artifact=MassingArtifact(
                filename=filename,
                contentType="application/octet-stream",
                sizeBytes=len(file_bytes),
                storageKey=storage_key,
            ),
        )
    }
