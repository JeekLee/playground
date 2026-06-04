"""Shared graph state for the architecture massing workflow (ADR-19 §A19.8).

Channels passed between orchestrator nodes and the massing subgraph. Each
node writes a disjoint subset, so the default last-write merge is correct
(no reducers needed).
"""

from __future__ import annotations

from typing import TypedDict
from uuid import UUID

from shared_kernel.models import DocsDetailSubset

from architecture.api.dtos import GenerateMassingRequest, GenerateMassingResponse
from architecture.domain.models import ExtractedProgram, RoomBox, SiteFootprint


class MassingState(TypedDict, total=False):
    # inputs
    req: GenerateMassingRequest
    user_id: UUID
    user_sub: str | None
    # intermediates
    detail: DocsDetailSubset
    extracted: ExtractedProgram
    site: SiteFootprint
    floor_height: float
    boxes: list[RoomBox]
    file_bytes: bytes
    # output
    response: GenerateMassingResponse
