"""Brief-to-massing orchestrator — LangGraph `StateGraph` (ADR-19 §D4 / Phase 2b).

Phase 2b migrates the previously-linear `MassingWorkflow.run` into a LangGraph
graph as the reference/observability pattern. It is **behavior-identical** to
the pre-graph version: the same five logical steps, the same MassingError
raising, the same response. The graph is a straight line — no branching/looping
is added here (that is Phase 3: site re-prompt + floor-count iteration + the
건폐율 algorithm fix). Node logic is unchanged from the original 8-step flow:

  fetch_brief → extract → massing → serialize → persist

1. fetch_brief — one docs-api `GET /{id}` (X-User-Id-filtered visibility),
   verify extraction_status == 'extracted' + non-empty body, else BRIEF_NOT_READY.
2. extract    — LLM → ExtractedProgram (rooms + optional site).
3. massing    — site fallback (request override > LLM-extracted > error) +
   MassingAlgorithm → List[RoomBox].
4. serialize  — rhino3dm → .3dm bytes.
5. persist    — write arch.outputs row; build GenerateMassingResponse with the
   relative `/api/arch/outputs/{id}` URL.

MassingError raised in any node propagates out of `graph.invoke()` unchanged
(LangGraph does not swallow node exceptions), so the FastAPI handler maps it
exactly as before.
"""

from __future__ import annotations

import logging
from typing import TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from shared_kernel.config import Settings
from shared_kernel.database import session_scope
from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode
from shared_kernel.llm_client import LlmClient
from shared_kernel.models import DocsDetailSubset

from .algorithm import compute_massing
from .brief_extractor import extract_program
from .models import (
    ArchOutput,
    ExtractedProgram,
    GenerateMassingRequest,
    GenerateMassingResponse,
    ProgramJsonWire,
    Room,
    RoomBox,
    RoomWire,
    SiteFootprint,
)
from .serializer import serialize_massing
from .slug import briefslug
from .summary import format_summary

logger = logging.getLogger(__name__)


class MassingState(TypedDict, total=False):
    """Channels passed between graph nodes. Each node writes a disjoint subset,
    so the default last-write merge is correct (no reducers needed)."""

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


class MassingWorkflow:
    def __init__(
        self,
        settings: Settings,
        docs_client: DocsClient,
        llm_client: LlmClient,
    ):
        self._settings = settings
        self._docs = docs_client
        self._llm = llm_client
        self._graph = self._build_graph()
        try:  # one-time visualization hook so Phase-3's richer graph is observable
            logger.info("architecture massing graph:\n%s", self._graph.get_graph().draw_ascii())
        except Exception:  # noqa: BLE001 — visualization is best-effort, never fatal
            logger.debug("graph ascii render unavailable")

    def _build_graph(self):
        g = StateGraph(MassingState)
        g.add_node("fetch_brief", self._fetch_brief)
        g.add_node("extract", self._extract)
        g.add_node("massing", self._massing)
        g.add_node("serialize", self._serialize)
        g.add_node("persist", self._persist)
        g.add_edge(START, "fetch_brief")
        g.add_edge("fetch_brief", "extract")
        g.add_edge("extract", "massing")
        g.add_edge("massing", "serialize")
        g.add_edge("serialize", "persist")
        g.add_edge("persist", END)
        return g.compile()

    def run(
        self,
        req: GenerateMassingRequest,
        *,
        user_id: UUID,
        user_sub: str | None,
    ) -> GenerateMassingResponse:
        final: MassingState = self._graph.invoke(
            {"req": req, "user_id": user_id, "user_sub": user_sub}
        )
        return final["response"]

    # --- nodes (logic identical to the pre-Phase-2b 8-step flow) ---

    def _fetch_brief(self, state: MassingState) -> dict:
        # 1+2+3: brief detail (with X-User-Id-filtered visibility).
        req = state["req"]
        detail = self._docs.get_document(
            req.brief_doc_id, user_id=state["user_id"], user_sub=state["user_sub"]
        )
        if detail.extraction_status and detail.extraction_status != "extracted":
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_READY,
                f"brief extraction_status={detail.extraction_status}, expected 'extracted'",
            )
        if not detail.body or not detail.body.strip():
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_READY,
                "brief body is empty (extraction may have failed upstream)",
            )
        return {"detail": detail}

    def _extract(self, state: MassingState) -> dict:
        # 4: LLM extract.
        return {"extracted": extract_program(state["detail"].body, self._llm)}

    def _massing(self, state: MassingState) -> dict:
        # 5: site fallback chain. 6: algorithm.
        req = state["req"]
        extracted = state["extracted"]
        site = self._resolve_site(req, extracted)
        floor_height = req.floor_height or self._settings.default_floor_height_m
        domain_rooms = [Room(name=r.name, area_m2=r.area_m2) for r in extracted.rooms]
        boxes: list[RoomBox] = compute_massing(
            domain_rooms,
            site=site,
            floor_height=floor_height,
            max_floors=self._settings.default_max_floors,
        )
        return {"site": site, "floor_height": floor_height, "boxes": boxes}

    def _serialize(self, state: MassingState) -> dict:
        # 7: serialize.
        return {"file_bytes": serialize_massing(state["boxes"])}

    def _persist(self, state: MassingState) -> dict:
        # 8: persist + respond.
        req = state["req"]
        extracted = state["extracted"]
        boxes = state["boxes"]
        total_area = sum(r.area_m2 for r in extracted.rooms)
        floor_count = max(b.floor for b in boxes)
        program_json = ProgramJsonWire(
            rooms=[RoomWire(name=r.name, areaM2=r.area_m2) for r in extracted.rooms],
            totalAreaM2=total_area,
            floorCount=floor_count,
        )
        summary = format_summary(
            room_count=len(extracted.rooms),
            floor_count=floor_count,
            total_area_m2=total_area,
        )
        slug = briefslug(state["detail"].title)

        with session_scope() as session:
            row = ArchOutput(
                brief_doc_id=req.brief_doc_id,
                brief_slug=slug,
                user_id=state["user_id"],
                file_bytes=state["file_bytes"],
                program_json=program_json.model_dump(by_alias=True),
                total_area_m2=total_area,
                floor_count=floor_count,
            )
            session.add(row)
            session.flush()
            output_id = row.id

        return {
            "response": GenerateMassingResponse(
                fileUrl=f"/api/arch/outputs/{output_id}",
                programJson=program_json,
                totalAreaM2=total_area,
                floorCount=floor_count,
                summary=summary,
            )
        }

    def _resolve_site(
        self,
        req: GenerateMassingRequest,
        extracted: ExtractedProgram,
    ) -> SiteFootprint:
        # Priority: request override → LLM-extracted → no default (error).
        width = req.site_width or (extracted.site.width if extracted.site else None)
        depth = req.site_depth or (extracted.site.depth if extracted.site else None)
        if width is None or depth is None:
            raise MassingError(
                MassingErrorCode.MASSING_ALGORITHM_FAILED,
                "site dimensions missing: provide siteWidth+siteDepth in request "
                "or include site info in the brief",
            )
        return SiteFootprint(width=width, depth=depth)
