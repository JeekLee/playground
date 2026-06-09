"""load_recipe node (refine pipeline) — fetch the prior massing's recipe.

Downloads the sibling .glb of the request's base .3dm key, parses the embedded
refineRecipe (spec D1), and seeds the channels classify → derive consume. A
missing/legacy .glb (no recipe) maps to RECIPE_NOT_FOUND so chat's LLM can tell
the user to regenerate instead.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace
from typing import Callable

from architecture.app.refine_recipe import RECIPE_KEY, RefineRecipe
from architecture.app.state import MassingState
from architecture.infra.blob_storage import download_from_key
from architecture.infra.glb_serializer import read_glb_extras
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)


def _glb_key(storage_key: str) -> str:
    if storage_key.endswith(".3dm"):
        return storage_key[: -len(".3dm")] + ".glb"
    return storage_key


def make_load_recipe_node(settings: Settings) -> Callable[[MassingState], dict]:
    def load_recipe(state: MassingState) -> dict:
        base_key = state["base_storage_key"]
        try:
            glb_bytes = download_from_key(_glb_key(base_key), settings)
            extras = read_glb_extras(glb_bytes)
            recipe = RefineRecipe.model_validate(extras[RECIPE_KEY])
        except MassingError:
            raise
        except Exception as exc:  # noqa: BLE001 — any read/parse failure = no recipe
            logger.info("refine recipe load failed for %s: %s", base_key, exc)
            raise MassingError(
                MassingErrorCode.RECIPE_NOT_FOUND,
                "이 모델은 수정 정보를 담고 있지 않아 새로 생성해야 합니다",
                cause=exc,
            ) from exc
        return {
            "normalized": recipe.normalized,
            "target_floors_above": recipe.target_floors_above,
            "floor_height_m": recipe.floor_height_m,
            "detail": SimpleNamespace(title=recipe.brief_title),
        }

    return load_recipe
