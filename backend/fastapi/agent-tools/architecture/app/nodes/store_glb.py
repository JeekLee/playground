"""store_glb node — derive + upload the preview .glb next to the .3dm.

Same MinIO prefix as the .3dm, extension swapped — strip the trailing
``.3dm`` and append ``.glb``; non-.3dm keys have no preview (design spec
2026-06-05-massing-glb-preview). chat's /attachments/{id}/preview
endpoint re-derives the key with the same suffix swap, so nothing new
lands in Postgres.

Preview is best-effort: any failure logs a warning and the workflow
continues — the .3dm is already stored, only the preview goes missing
(the FE endpoint answers 404 for an absent .glb).
"""

from __future__ import annotations

import logging

from architecture.app.program_wire import build_program_json
from architecture.app.refine_recipe import RefineRecipe
from architecture.app.state import MassingState
from architecture.infra.blob_storage import upload_to_key
from architecture.infra.glb_serializer import serialize_glb
from shared_kernel.config import get_settings

logger = logging.getLogger(__name__)


def store_glb(state: MassingState) -> dict:
    try:
        storage_key = state["storage_key"]
        if not storage_key.endswith(".3dm"):
            logger.warning(
                "store_glb: unexpected storage_key %s — skipping preview", storage_key
            )
            return {}
        glb_key = storage_key[: -len(".3dm")] + ".glb"
        program_json = build_program_json(state["boxes"], state["inputs"]).model_dump(
            by_alias=True, mode="json"
        )
        refine_recipe = RefineRecipe(
            normalized=state["normalized"],
            floor_height_m=state["inputs"].floor_height_m,
            target_floors_above=state["inputs"].target_floors_above,
            brief_title=state["detail"].title,
        ).model_dump(by_alias=True, mode="json")
        glb_bytes = serialize_glb(
            state["boxes"], program_json=program_json, refine_recipe=refine_recipe
        )
        upload_to_key(
            file_bytes=glb_bytes,
            key=glb_key,
            content_type="model/gltf-binary",
            settings=get_settings(),
        )
        logger.info(
            "store_glb node: uploaded preview %s (%d bytes)", glb_key, len(glb_bytes)
        )
    except Exception:  # noqa: BLE001 — preview must never sink the turn
        logger.warning("store_glb failed — preview unavailable, continuing", exc_info=True)
    return {}
