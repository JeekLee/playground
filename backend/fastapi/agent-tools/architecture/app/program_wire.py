"""programJson wire 빌더 — respond(SSE)와 store_glb(.glb extras)의 단일 소스.

extras 임베드 설계(2026-06-05-glb-extras-program-json D1): 두 소비자가 같은
빌더를 호출하므로 SSE 페이로드와 .glb extras가 항상 동일 데이터임이 구조적으로
보장된다. labelAnchor는 glb_serializer의 Z-up→Y-up 변환 + 층 슬릿을 반영한
실 박스 상면 중심 (room-split spec D6).
"""

from __future__ import annotations

from architecture.api.dtos import LabelAnchorWire, ProgramJsonWire, RoomWire
from architecture.domain.models import COMMON_AREA_NAME, MassingInputs, RoomBox, Zone
from architecture.infra.glb_serializer import FLOOR_GAP_M  # FLOOR_GAP_M: glb_serializer의 층 슬릿과 동일해야 라벨이 박스 상면에 정확히 앉는다 — 공유 상수로 동기화.


def build_program_json(boxes: list[RoomBox], inputs: MassingInputs) -> ProgramJsonWire:
    zones = inputs.zones
    total_area = sum(z.area_m2 for z in zones)
    return ProgramJsonWire(
        rooms=_build_rooms_wire(boxes, zones),
        totalAreaM2=total_area,
        floorCount=inputs.target_floors_above,
        basementLevels=inputs.basement_levels,
    )


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
