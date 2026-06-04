"""rhino3dm .3dm serialization per ADR-18 §A18.2.

rhino3dm.py is imported directly (no sidecar — ADR-18 §A18.1 flip). The
library wraps the OpenNURBS C++ via prebuilt wheels — aarch64 + x86_64
manylinux wheels are on PyPI as of 8.x.

Each RoomBox becomes a Mesh primitive (8 vertices + 6 quads) placed at
its (x, y, z) anchor with extent (width, depth, height). The room name
goes to a per-object Rhino layer; the floor index goes to a per-object
user-text attribute. The .3dm bytes are returned.
"""

from __future__ import annotations

import base64
import binascii

import rhino3dm  # type: ignore[import-not-found]

from .errors import MassingError, MassingErrorCode
from .models import RoomBox


def serialize_massing(boxes: list[RoomBox]) -> bytes:
    """Build a .3dm from the box list. Returns the binary file bytes."""
    if not boxes:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "no boxes to serialize",
        )

    try:
        file = rhino3dm.File3dm()

        # One layer per unique room name (Rhino convention for labelling).
        layer_index_by_name: dict[str, int] = {}
        for box in boxes:
            if box.name in layer_index_by_name:
                continue
            layer = rhino3dm.Layer()
            layer.Name = box.name
            layer_index_by_name[box.name] = file.Layers.Add(layer)

        for box in boxes:
            mesh = _make_box_mesh(box)
            attrs = rhino3dm.ObjectAttributes()
            attrs.LayerIndex = layer_index_by_name[box.name]
            attrs.SetUserString("floor", str(box.floor))
            attrs.SetUserString("roomName", box.name)
            file.Objects.AddMesh(mesh, attrs)

        # rhino3dm.py 8.x exposes ToByteArray() returning raw archive bytes;
        # other builds (e.g. 8.17) only expose Encode(), which returns a
        # *base64 string* of the binary archive, not the archive itself.
        if hasattr(file, "ToByteArray"):
            data = bytes(file.ToByteArray())  # type: ignore[attr-defined]
        else:
            encoded = file.Encode()
            data = encoded if isinstance(encoded, bytes) else encoded.encode("latin-1")
        # A real .3dm begins with the OpenNURBS magic string. If we don't see
        # it, `data` is still base64 — decode it so the download is an openable
        # binary .3dm rather than base64 text Rhino can't parse.
        if not data.startswith(b"3D Geometry File Format"):
            try:
                data = base64.b64decode(data, validate=True)
            except (binascii.Error, ValueError):
                pass
        return data
    except MassingError:
        raise
    except Exception as exc:  # noqa: BLE001 — wrap any rhino3dm runtime issue
        raise MassingError(
            MassingErrorCode.SIDECAR_FAILED,
            f".3dm serialization failed: {exc}",
            cause=exc,
        ) from exc


def _make_box_mesh(box: RoomBox) -> "rhino3dm.Mesh":
    """8-vertex / 6-quad axis-aligned box mesh anchored at (x, y, z).

    Vertex layout (Rhino's right-handed Z-up):
        0: (x,           y,            z)            bottom
        1: (x + width,   y,            z)
        2: (x + width,   y + depth,    z)
        3: (x,           y + depth,    z)
        4: (x,           y,            z + height)   top
        5: (x + width,   y,            z + height)
        6: (x + width,   y + depth,    z + height)
        7: (x,           y + depth,    z + height)
    Quads: bottom, top, +x, +y, -x, -y.
    """
    mesh = rhino3dm.Mesh()
    x, y, z = box.x, box.y, box.z
    w, d, h = box.width, box.depth, box.height

    mesh.Vertices.Add(x,     y,     z)
    mesh.Vertices.Add(x + w, y,     z)
    mesh.Vertices.Add(x + w, y + d, z)
    mesh.Vertices.Add(x,     y + d, z)
    mesh.Vertices.Add(x,     y,     z + h)
    mesh.Vertices.Add(x + w, y,     z + h)
    mesh.Vertices.Add(x + w, y + d, z + h)
    mesh.Vertices.Add(x,     y + d, z + h)

    mesh.Faces.AddFace(0, 3, 2, 1)  # bottom (-z normal — CCW when viewed from below)
    mesh.Faces.AddFace(4, 5, 6, 7)  # top    (+z normal)
    mesh.Faces.AddFace(1, 2, 6, 5)  # +x face
    mesh.Faces.AddFace(2, 3, 7, 6)  # +y face
    mesh.Faces.AddFace(3, 0, 4, 7)  # -x face
    mesh.Faces.AddFace(0, 1, 5, 4)  # -y face

    mesh.Normals.ComputeNormals()
    mesh.Compact()
    return mesh
