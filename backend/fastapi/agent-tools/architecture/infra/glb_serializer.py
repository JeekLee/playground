"""trimesh .glb serialization for the in-chat 3D preview.

The .glb is a *preview-only* derived artifact (design spec
2026-06-05-massing-glb-preview): the deliverable stays the .3dm. RoomBox
geometry is already plain axis-aligned boxes, so the mesh maps 1:1 — no
.3dm round-trip.

glTF is +Y-up (Khronos spec); RoomBox coordinates are Rhino Z-up
(ADR-18 §11). Each box mesh is rotated -90° about X after placement so
the model stands upright in model-viewer: (x, y, z) → (x, z, -y).
"""

from __future__ import annotations

import numpy as np
import trimesh

from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.domain.models import RoomBox

# Z-up (Rhino) → Y-up (glTF): rotate -90° about +X.
_Z_UP_TO_Y_UP = trimesh.transformations.rotation_matrix(-np.pi / 2.0, (1.0, 0.0, 0.0))


def serialize_glb(boxes: list[RoomBox]) -> bytes:
    """Build a .glb scene from the box list. Returns binary glTF bytes."""
    if not boxes:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "no boxes to serialize",
        )
    scene = trimesh.Scene()
    for i, box in enumerate(boxes):
        mesh = trimesh.creation.box(extents=(box.width, box.depth, box.height))
        # creation.box centers on the origin; RoomBox anchors at the
        # lower-left corner — shift by half-extents before the up-axis swap.
        mesh.apply_translation((
            box.x + box.width / 2.0,
            box.y + box.depth / 2.0,
            box.z + box.height / 2.0,
        ))
        mesh.apply_transform(_Z_UP_TO_Y_UP)
        # Index suffix keeps geometry names unique when room names repeat.
        scene.add_geometry(mesh, geom_name=f"{box.name}-f{box.floor}-{i}")
    return bytes(scene.export(file_type="glb"))
