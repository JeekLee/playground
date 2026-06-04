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
from architecture.domain.models import (
    BriefAnalysis,
    ClassifiedBrief,
    MassingInputs,
    NormalizedBrief,
    RoomBox,
)


class MassingState(TypedDict, total=False):
    """Channels threaded through the massing pipeline (ADR-19 Phase 3a-2).

    Each node writes a disjoint subset, so the default last-write merge is
    correct. The interpretation stages produce one channel each:
      locate -> excerpt ; extract -> analysis ; reconcile -> normalized ;
      classify -> classified ; derive -> inputs ; compute -> boxes.
    """

    # inputs
    req: GenerateMassingRequest
    user_id: UUID
    user_sub: str | None
    # intermediates (one per interpretation stage)
    detail: DocsDetailSubset
    excerpt: str  # locate: massing-governing slice of detail.body
    analysis: BriefAnalysis  # extract: open, LLM-extracted facts
    normalized: NormalizedBrief  # reconcile: gross zones + sub-spaces + facts
    classified: ClassifiedBrief  # classify: graded zones + footprint driver
    inputs: MassingInputs  # derive: tight, validated algorithm contract
    extract_attempts: int  # re-prompt loop counter (ADR-19 Phase 3a)
    boxes: list[RoomBox]
    file_bytes: bytes
    storage_key: str  # set by the store node after MinIO upload (ADR-20 §D3 revised)
    # output
    response: GenerateMassingResponse
