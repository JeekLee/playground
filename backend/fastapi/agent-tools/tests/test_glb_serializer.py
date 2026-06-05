"""serialize_glb behavior — valid binary glTF, Y-up conversion, per-box geometry."""

from __future__ import annotations

import io

import pytest
import trimesh

from architecture.domain.models import RoomBox
from architecture.infra.glb_serializer import serialize_glb
from shared_kernel.errors import MassingError


def _box(name="lab", floor=1, x=0.0, y=0.0, z=0.0, w=2.0, d=3.0, h=10.0):
    return RoomBox(name=name, floor=floor, x=x, y=y, z=z, width=w, depth=d, height=h)


def test_glb_magic_and_roundtrip():
    data = serialize_glb([_box(), _box(name="hall", floor=2, z=10.0)])
    # Binary glTF container magic per Khronos spec.
    assert data[:4] == b"glTF"
    scene = trimesh.load(io.BytesIO(data), file_type="glb")
    assert len(scene.geometry) == 2


def test_z_up_converted_to_y_up():
    # Rhino height rides Z; after export the model must stand on glTF +Y.
    data = serialize_glb([_box(w=2.0, d=3.0, h=10.0)])
    scene = trimesh.load(io.BytesIO(data), file_type="glb")
    extent = scene.bounding_box.extents  # (x, y, z) in glTF space
    assert extent[1] == pytest.approx(10.0)  # height → Y
    assert extent[0] == pytest.approx(2.0)   # width stays X
    assert extent[2] == pytest.approx(3.0)   # depth → Z


def test_empty_boxes_raises():
    with pytest.raises(MassingError):
        serialize_glb([])
