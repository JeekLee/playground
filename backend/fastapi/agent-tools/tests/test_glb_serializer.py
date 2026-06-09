"""serialize_glb behavior — valid binary glTF, Y-up conversion, per-box geometry,
zone colors, ground plane, and floor-gap readability (preview-only styling).
"""

from __future__ import annotations

import io
import json
import struct

import pytest
import trimesh

from architecture.domain.models import RoomBox
from architecture.infra.glb_serializer import FLOOR_GAP_M, serialize_glb
from shared_kernel.errors import MassingError


def _box(name="lab", floor=1, x=0.0, y=0.0, z=0.0, w=2.0, d=3.0, h=10.0, zone=None):
    return RoomBox(
        name=name, zone=zone or name, floor=floor,
        x=x, y=y, z=z, width=w, depth=d, height=h,
    )


def _load(data: bytes) -> trimesh.Scene:
    return trimesh.load(io.BytesIO(data), file_type="glb")


def _color(geom) -> tuple[float, ...]:
    """Normalize a roundtripped baseColorFactor to 0..1 floats."""
    factor = geom.visual.material.baseColorFactor
    values = [float(c) for c in factor]
    if max(values) > 1.0:  # uint8 roundtrip
        values = [v / 255.0 for v in values]
    return tuple(round(v, 2) for v in values)


def test_glb_magic_and_roundtrip():
    data = serialize_glb([_box(), _box(name="hall", floor=2, z=10.0)])
    # Binary glTF container magic per Khronos spec.
    assert data[:4] == b"glTF"
    scene = _load(data)
    # 2 room boxes + 1 ground plane.
    assert len(scene.geometry) == 3
    assert any(name.startswith("ground") for name in scene.geometry)


def test_z_up_converted_to_y_up():
    # Rhino height rides Z; after export the model must stand on glTF +Y.
    data = serialize_glb([_box(w=2.0, d=3.0, h=10.0)])
    scene = _load(data)
    geom = next(g for name, g in scene.geometry.items() if name.startswith("lab"))
    extent = geom.bounding_box.extents  # (x, y, z) in glTF space
    assert extent[1] == pytest.approx(10.0 - FLOOR_GAP_M)  # height → Y, minus gap
    assert extent[0] == pytest.approx(2.0)  # width stays X
    assert extent[2] == pytest.approx(3.0)  # depth → Z


def test_floor_gap_leaves_slit_between_floors():
    # Two stacked floors: f1 top must sit FLOOR_GAP_M below f2 bottom.
    data = serialize_glb([
        _box(floor=1, z=0.0, h=3.5),
        _box(floor=2, z=3.5, h=3.5),
    ])
    scene = _load(data)
    f1 = next(g for name, g in scene.geometry.items() if "-f1-" in name)
    f2 = next(g for name, g in scene.geometry.items() if "-f2-" in name)
    f1_top = f1.bounds[1][1]  # max Y
    f2_bottom = f2.bounds[0][1]  # min Y
    assert f2_bottom - f1_top == pytest.approx(FLOOR_GAP_M)


def test_zone_colors_distinct_and_consistent():
    data = serialize_glb([
        _box(name="시험영역", floor=1, z=0.0, h=3.5),
        _box(name="업무영역", floor=1, x=5.0, z=0.0, h=3.5),
        _box(name="시험영역", floor=2, z=3.5, h=3.5),
    ])
    scene = _load(data)
    colors = {
        name: _color(g) for name, g in scene.geometry.items() if not name.startswith("ground")
    }
    c_exam_f1 = next(c for n, c in colors.items() if n.startswith("시험영역-f1"))
    c_exam_f2 = next(c for n, c in colors.items() if n.startswith("시험영역-f2"))
    c_work = next(c for n, c in colors.items() if n.startswith("업무영역"))
    # Same zone keeps its color across floors; different zones differ.
    assert c_exam_f1 == c_exam_f2
    assert c_exam_f1[:3] != c_work[:3]


def test_below_grade_zone_is_darker():
    data = serialize_glb([
        _box(name="공용", floor=1, z=0.0, h=3.5),
        _box(name="공용", floor=-1, z=-3.5, h=3.5),
    ])
    scene = _load(data)
    above = _color(next(g for n, g in scene.geometry.items() if "-f1-" in n))
    below = _color(next(g for n, g in scene.geometry.items() if "-f-1-" in n))
    # Same palette slot, dimmed below grade — every RGB channel strictly darker.
    assert all(b < a for a, b in zip(above[:3], below[:3]))


def test_ground_plane_translucent_and_covers_footprint():
    data = serialize_glb([
        _box(name="A", floor=1, x=0.0, y=0.0, w=10.0, d=10.0, h=3.5),
        _box(name="B", floor=1, x=10.0, y=0.0, w=10.0, d=10.0, h=3.5),
    ])
    scene = _load(data)
    ground = next(g for name, g in scene.geometry.items() if name.startswith("ground"))
    # Translucent.
    assert _color(ground)[3] < 1.0
    # Wider than the 20m combined footprint (margin on both sides) and the
    # slab top sits below grade (glTF Y < 0) so floor-1 bottoms never z-fight.
    extent = ground.bounding_box.extents
    assert extent[0] > 20.0
    assert ground.bounds[1][1] < 0.0


def test_empty_boxes_raises():
    with pytest.raises(MassingError):
        serialize_glb([])


def _hls(color: tuple[float, ...]) -> tuple[float, float, float]:
    import colorsys
    return colorsys.rgb_to_hls(*color[:3])


def test_room_boxes_share_zone_hue_with_distinct_lightness():
    data = serialize_glb([
        _box(name="대실험실", zone="연구", floor=1, h=3.5),
        _box(name="실험실A", zone="연구", floor=1, x=5.0, h=3.5),
    ])
    scene = _load(data)
    c1 = _color(next(g for n, g in scene.geometry.items() if n.startswith("대실험실")))
    c2 = _color(next(g for n, g in scene.geometry.items() if n.startswith("실험실A")))
    h1, l1, _ = _hls(c1)
    h2, l2, _ = _hls(c2)
    assert h1 == pytest.approx(h2, abs=0.02)   # 같은 zone → 같은 hue
    assert abs(l1 - l2) > 0.03                  # 실 구분 → 명도 차이


def test_common_box_is_pale_desaturated():
    data = serialize_glb([
        _box(name="대실험실", zone="연구", floor=1, h=3.5),
        _box(name="공용·기타", zone="연구", floor=1, x=5.0, h=3.5),
    ])
    scene = _load(data)
    room = _color(next(g for n, g in scene.geometry.items() if n.startswith("대실험실")))
    common = _color(next(g for n, g in scene.geometry.items() if n.startswith("공용·기타")))
    _, l_room, s_room = _hls(room)
    _, l_common, s_common = _hls(common)
    assert l_common > l_room      # 더 밝고
    assert s_common < s_room      # 더 낮은 채도


def test_unsplit_zone_box_keeps_base_palette_color():
    # zone == name → 기존 원색 그대로 (readability pass와 동일 출력).
    data = serialize_glb([_box(name="연구", zone="연구", floor=1, h=3.5)])
    scene = _load(data)
    c = _color(next(g for n, g in scene.geometry.items() if n.startswith("연구")))
    assert c[:3] == (round(142 / 255, 2), round(152 / 255, 2), round(90 / 255, 2))


def _glb_json_chunk(data: bytes) -> dict:
    # GLB 2.0 컨테이너: 12B 헤더(magic/version/length) + chunk0(JSON).
    assert data[:4] == b"glTF"
    json_len = struct.unpack("<I", data[12:16])[0]
    return json.loads(data[20 : 20 + json_len].decode("utf-8"))


def test_program_json_embedded_in_scene_extras():
    pj = {"rooms": [{"name": "시험실", "areaM2": 500.0, "floor": 1}], "floorCount": 2}
    data = serialize_glb([_box(h=3.5)], program_json=pj)
    doc = _glb_json_chunk(data)
    assert doc["scenes"][0]["extras"]["programJson"] == pj


def test_no_program_json_no_extras():
    data = serialize_glb([_box(h=3.5)])
    doc = _glb_json_chunk(data)
    assert "extras" not in doc["scenes"][0]


def test_serialize_glb_embeds_refine_recipe_and_reads_back():
    from architecture.infra.glb_serializer import read_glb_extras, serialize_glb
    from architecture.domain.models import RoomBox

    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    recipe = {"normalized": {"zones": [{"name": "z", "area_m2": 100.0, "grade": "above"}]},
              "floor_height_m": 3.5, "target_floors_above": 1, "brief_title": "t"}
    glb = serialize_glb(boxes, program_json={"rooms": []}, refine_recipe=recipe)
    extras = read_glb_extras(glb)
    assert extras["refineRecipe"] == recipe
    assert extras["programJson"] == {"rooms": []}
