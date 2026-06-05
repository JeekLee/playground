"""respond node — build the `{result, artifact}` envelope (ADR-20 §D3 revised).

The `store_3dm` node has already uploaded the .3dm to MinIO and stashed the
object key in `state["storage_key"]`. This node assembles the response:
- `result`: LLM-visible massing summary (floors, area, program JSON, Korean summary)
- `artifact`: metadata only — filename, contentType, sizeBytes, storageKey

programJson.rooms (room-split spec 2026-06-05): 분할 zone은 실/공용 박스가
행으로, 미분할 zone은 zone 1행. 행 순서는 박스의 zone 첫 등장 순서 — FE의
zone 색 슬롯이 glb와 일치하는 근거. labelAnchor는 glb의 Z-up→Y-up 변환
(x, z, -y) + 층 슬릿(FLOOR_GAP_M)을 반영한 실 박스 상면 중심.
"""

from __future__ import annotations

from architecture.api.dtos import (
    GenerateMassingResponse,
    LabelAnchorWire,
    MassingArtifact,
    MassingResult,
    ProgramJsonWire,
    RoomWire,
)
from architecture.app.state import MassingState
from architecture.domain.models import COMMON_AREA_NAME, RoomBox, Zone
from architecture.domain.summary import format_summary
from architecture.infra.glb_serializer import FLOOR_GAP_M


def _label_anchor(box: RoomBox) -> LabelAnchorWire:
    render_h = max(box.height - FLOOR_GAP_M, box.height * 0.5)
    return LabelAnchorWire(
        x=box.x + box.width / 2.0,
        y=box.z + render_h,
        z=-(box.y + box.depth / 2.0),
    )


def _build_rooms_wire(boxes: list[RoomBox], zones: list[Zone]) -> list[RoomWire]:
    zone_by_name = {z.name: z for z in zones}
    zone_order: list[str] = []
    for b in boxes:
        if b.zone not in zone_order:
            zone_order.append(b.zone)

    rows: list[RoomWire] = []
    for zname in zone_order:
        zboxes = [b for b in boxes if b.zone == zname]
        split = any(b.name != b.zone for b in zboxes)
        if not split:
            z = zone_by_name[zname]
            rows.append(RoomWire(name=z.name, areaM2=z.area_m2, zone=z.name))
            continue
        room_area = {r.name: r.area_m2 for r in zone_by_name[zname].rooms}
        for b in zboxes:
            if b.name == COMMON_AREA_NAME:
                rows.append(RoomWire(
                    name=b.name,
                    areaM2=round(b.width * b.depth, 1),
                    zone=zname,
                    floor=b.floor,
                ))
            else:
                rows.append(RoomWire(
                    name=b.name,
                    areaM2=room_area.get(b.name, round(b.width * b.depth, 1)),
                    zone=zname,
                    floor=b.floor,
                    labelAnchor=_label_anchor(b),
                ))
    return rows


def respond(state: MassingState) -> dict:
    inputs = state["inputs"]
    boxes: list[RoomBox] = state["boxes"]

    zones = inputs.zones
    total_area = sum(z.area_m2 for z in zones)
    floors_above = inputs.target_floors_above
    basement_levels = inputs.basement_levels

    rooms_wire = _build_rooms_wire(boxes, zones)
    program_json = ProgramJsonWire(
        rooms=rooms_wire,
        totalAreaM2=total_area,
        floorCount=floors_above,
        basementLevels=basement_levels,
    )
    # "N실" = 명명 실 + 미분할 zone (공용·기타 제외). 분할이 없으면 zone 수
    # 그대로라 기존 summary와 동일하다 (room-split spec).
    room_count = sum(1 for r in rooms_wire if r.name != COMMON_AREA_NAME)
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
