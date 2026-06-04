"""serialize node (ADR-19 §A19.9) — rhino3dm `.3dm` bytes.

Single step, no branching. Delegates to infra/serializer.
"""

from __future__ import annotations

from architecture.app.state import MassingState
from architecture.infra.serializer import serialize_massing


def serialize(state: MassingState) -> dict:
    return {"file_bytes": serialize_massing(state["boxes"])}
