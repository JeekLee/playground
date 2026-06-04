"""Massing subgraph (ADR-19 §A19.8 / §A19.9) — resolve_site → compute.

A compiled StateGraph composed as a single node in the orchestrator
workflow. Linear today; Phase-3's 건폐율 fix + floor-converge loop lands
here. Shares the parent state keys (`req`, `extracted` in; `site`,
`floor_height`, `boxes` out).
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from architecture.app.state import MassingState
from architecture.domain.algorithm import compute_massing
from architecture.domain.models import ExtractedProgram, Room, RoomBox, SiteFootprint
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.api.dtos import GenerateMassingRequest


def _resolve_site(
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


def build_massing_subgraph(settings: Settings):
    """Compile the resolve_site → compute subgraph."""

    def resolve_site(state: MassingState) -> dict:
        req = state["req"]
        extracted = state["extracted"]
        site = _resolve_site(req, extracted)
        floor_height = req.floor_height or settings.default_floor_height_m
        return {"site": site, "floor_height": floor_height}

    def compute(state: MassingState) -> dict:
        extracted = state["extracted"]
        domain_rooms = [Room(name=r.name, area_m2=r.area_m2) for r in extracted.rooms]
        boxes: list[RoomBox] = compute_massing(
            domain_rooms,
            site=state["site"],
            floor_height=state["floor_height"],
            max_floors=settings.default_max_floors,
        )
        return {"boxes": boxes}

    g = StateGraph(MassingState)
    g.add_node("resolve_site", resolve_site)
    g.add_node("compute", compute)
    g.add_edge(START, "resolve_site")
    g.add_edge("resolve_site", "compute")
    g.add_edge("compute", END)
    return g.compile()
