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
from architecture.domain.models import BriefAnalysis, MassingInputs, RoomBox


class MassingState(TypedDict, total=False):
    # inputs
    req: GenerateMassingRequest
    user_id: UUID
    user_sub: str | None
    # intermediates
    detail: DocsDetailSubset
    analysis: BriefAnalysis  # open, LLM-extracted facts
    inputs: MassingInputs  # tight, validated algorithm contract
    extract_attempts: int  # re-prompt loop counter (ADR-19 Phase 3a)
    boxes: list[RoomBox]
    file_bytes: bytes
    # output
    response: GenerateMassingResponse
