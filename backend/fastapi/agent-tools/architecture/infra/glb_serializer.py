"""trimesh .glb serialization for the in-chat 3D preview.

The .glb is a *preview-only* derived artifact (design spec
2026-06-05-massing-glb-preview): the deliverable stays the .3dm. RoomBox
geometry is already plain axis-aligned boxes, so the mesh maps 1:1 — no
.3dm round-trip.

Readability styling (preview-only — none of this touches the .3dm):
- Each zone gets a deterministic muted color from ``_PALETTE`` keyed by
  first appearance in the box list (algorithm output order is stable).
- Below-grade boxes reuse their zone color dimmed by ``_BELOW_GRADE_DIM``.
- Every floor box is shortened by ``FLOOR_GAP_M`` at the top so stacked
  floors read as distinct slabs instead of one fused mass.
- A translucent ground slab marks grade level; its top sits
  ``_GROUND_CLEARANCE_M`` below z=0 so floor-1 bottoms never z-fight.

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

# Muted architectural tones — olive leads to match the product accent.
_PALETTE: tuple[tuple[int, int, int], ...] = (
    (142, 152, 90),   # olive
    (188, 132, 100),  # clay
    (110, 130, 155),  # slate
    (200, 178, 128),  # sand
    (150, 120, 140),  # mauve
)
# Below-grade boxes keep their zone hue, dimmed.
_BELOW_GRADE_DIM = 0.72

# Vertical slit carved off the TOP of every floor box (meters) so stacked
# floors read as separate slabs. The z anchor is untouched.
FLOOR_GAP_M = 0.15

# Ground slab: bounding footprint + margin, thin, translucent, top face
# kept clear of z=0 to avoid z-fighting with floor-1 bottoms.
_GROUND_MARGIN_RATIO = 0.15
_GROUND_THICKNESS_M = 0.3
_GROUND_CLEARANCE_M = 0.05
_GROUND_RGBA = (0.86, 0.85, 0.80, 0.45)


def serialize_glb(boxes: list[RoomBox]) -> bytes:
    """Build a .glb scene from the box list. Returns binary glTF bytes."""
    if not boxes:
        raise MassingError(
            MassingErrorCode.MASSING_ALGORITHM_FAILED,
            "no boxes to serialize",
        )

    # Palette slot per zone, keyed by first appearance (deterministic —
    # compute_massing emits floors in order with area-sorted zones).
    zone_slot: dict[str, int] = {}
    for box in boxes:
        zone_slot.setdefault(box.zone, len(zone_slot))

    scene = trimesh.Scene()
    for i, box in enumerate(boxes):
        # Shorten at the top only; guard against degenerate heights.
        render_h = max(box.height - FLOOR_GAP_M, box.height * 0.5)
        mesh = trimesh.creation.box(extents=(box.width, box.depth, render_h))
        # creation.box centers on the origin; RoomBox anchors at the
        # lower-left corner — shift by half-extents before the up-axis swap.
        mesh.apply_translation((
            box.x + box.width / 2.0,
            box.y + box.depth / 2.0,
            box.z + render_h / 2.0,
        ))
        mesh.apply_transform(_Z_UP_TO_Y_UP)
        rgb = _PALETTE[zone_slot[box.zone] % len(_PALETTE)]
        dim = _BELOW_GRADE_DIM if box.floor < 0 else 1.0
        mesh.visual = trimesh.visual.TextureVisuals(material=_solid_material(rgb, dim=dim))
        # Index suffix keeps geometry names unique when room names repeat.
        scene.add_geometry(mesh, geom_name=f"{box.name}-f{box.floor}-{i}")

    scene.add_geometry(_ground_plane(boxes), geom_name="ground")
    return bytes(scene.export(file_type="glb"))


def _solid_material(
    rgb: tuple[int, int, int],
    *,
    dim: float = 1.0,
    alpha: float = 1.0,
) -> "trimesh.visual.material.PBRMaterial":
    """Flat PBR material — matte, optionally dimmed/translucent."""
    base = [c / 255.0 * dim for c in rgb] + [alpha]
    kwargs: dict = {
        "baseColorFactor": base,
        "metallicFactor": 0.0,
        "roughnessFactor": 0.9,
    }
    if alpha < 1.0:
        kwargs["alphaMode"] = "BLEND"
    return trimesh.visual.material.PBRMaterial(**kwargs)


def _ground_plane(boxes: list[RoomBox]) -> "trimesh.Trimesh":
    """Translucent grade slab under the whole footprint (Rhino coords in,
    glTF orientation out)."""
    min_x = min(b.x for b in boxes)
    max_x = max(b.x + b.width for b in boxes)
    min_y = min(b.y for b in boxes)
    max_y = max(b.y + b.depth for b in boxes)
    margin = _GROUND_MARGIN_RATIO * max(max_x - min_x, max_y - min_y, 1.0)

    width = (max_x - min_x) + 2.0 * margin
    depth = (max_y - min_y) + 2.0 * margin
    slab = trimesh.creation.box(extents=(width, depth, _GROUND_THICKNESS_M))
    slab.apply_translation((
        min_x - margin + width / 2.0,
        min_y - margin + depth / 2.0,
        -_GROUND_CLEARANCE_M - _GROUND_THICKNESS_M / 2.0,
    ))
    slab.apply_transform(_Z_UP_TO_Y_UP)
    rgba = _GROUND_RGBA
    slab.visual = trimesh.visual.TextureVisuals(
        material=_solid_material(
            (int(rgba[0] * 255), int(rgba[1] * 255), int(rgba[2] * 255)),
            alpha=rgba[3],
        )
    )
    return slab
