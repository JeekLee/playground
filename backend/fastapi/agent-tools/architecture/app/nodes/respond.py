"""respond node — build the `{result, artifact}` envelope (ADR-20 §D3 revised).

The `store_3dm` node has already uploaded the .3dm to MinIO and stashed the
object key in `state["storage_key"]`. This node assembles the response:
- `result`: LLM-visible massing summary (floors, area, program JSON, Korean summary)
- `artifact`: metadata only — filename, contentType, sizeBytes, storageKey

programJson.rooms row ordering and labelAnchor computation: see program_wire.
"""

from __future__ import annotations

from architecture.api.dtos import (
    GenerateMassingResponse,
    MassingArtifact,
    MassingResult,
)
from architecture.app.program_wire import build_program_json
from architecture.app.state import MassingState
from architecture.domain.models import COMMON_AREA_NAME, RoomBox
from architecture.domain.summary import format_summary


def respond(state: MassingState) -> dict:
    inputs = state["inputs"]
    boxes: list[RoomBox] = state["boxes"]

    floors_above = inputs.target_floors_above
    basement_levels = inputs.basement_levels
    total_area = sum(z.area_m2 for z in inputs.zones)

    program_json = build_program_json(boxes, inputs)
    # "N실" = 명명 실 + 미분할 zone (공용·기타 제외). 분할이 없으면 zone 수
    # 그대로라 기존 summary와 동일하다 (room-split spec).
    room_count = sum(1 for r in program_json.rooms if r.name != COMMON_AREA_NAME)
    summary = format_summary(
        room_count=room_count,
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
                briefTitle=state["detail"].title,
            ),
            artifact=MassingArtifact(
                filename=filename,
                contentType="application/octet-stream",
                sizeBytes=len(file_bytes),
                storageKey=storage_key,
            ),
        )
    }
