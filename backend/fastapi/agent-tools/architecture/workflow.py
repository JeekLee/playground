"""8-step orchestrator per PRD Story 2 + ADR-18 §8.

1. Fetch brief detail (one call to docs-api `GET /{id}` with X-User-Id —
   covers metadata + visibility + extractionStatus + body in one round trip).
2. Verify extractionStatus == 'extracted' → else BRIEF_NOT_READY.
3. (docs-api already filtered visibility via X-User-Id; 403 / 404 mapped
   in docs_client.)
4. LLM extract → ExtractedProgram (rooms + optional site).
5. Site fallback: request override > LLM-extracted > application.yml default
   (site.width / site.depth — config has no default site dimensions, so
   missing → MASSING_ALGORITHM_FAILED).
6. MassingAlgorithm → List[RoomBox].
7. rhino3dm serialize → .3dm bytes.
8. Persist arch.outputs row; return GenerateMassingResponse with relative
   `outputUrl: /api/arch/outputs/{id}`.
"""

from __future__ import annotations

import logging
from uuid import UUID

from .algorithm import compute_massing
from .brief_extractor import extract_program
from shared_kernel.config import Settings
from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode
from shared_kernel.llm_client import LlmClient
from .models import (
    ArchOutput,
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
from shared_kernel.database import session_scope

logger = logging.getLogger(__name__)


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

    def run(
        self,
        req: GenerateMassingRequest,
        *,
        user_id: UUID,
        user_sub: str | None,
    ) -> GenerateMassingResponse:
        # 1+2+3: brief detail (with X-User-Id-filtered visibility).
        detail = self._docs.get_document(req.brief_doc_id, user_id=user_id, user_sub=user_sub)
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

        # 4: LLM extract.
        extracted = extract_program(detail.body, self._llm)

        # 5: site fallback chain.
        site = self._resolve_site(req, extracted)
        floor_height = req.floor_height or self._settings.default_floor_height_m

        # 6: algorithm.
        domain_rooms = [Room(name=r.name, area_m2=r.area_m2) for r in extracted.rooms]
        boxes: list[RoomBox] = compute_massing(
            domain_rooms,
            site=site,
            floor_height=floor_height,
            max_floors=self._settings.default_max_floors,
        )

        # 7: serialize.
        file_bytes = serialize_massing(boxes)

        # 8: persist + respond.
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
        slug = briefslug(detail.title)

        with session_scope() as session:
            row = ArchOutput(
                brief_doc_id=req.brief_doc_id,
                brief_slug=slug,
                user_id=user_id,
                file_bytes=file_bytes,
                program_json=program_json.model_dump(by_alias=True),
                total_area_m2=total_area,
                floor_count=floor_count,
            )
            session.add(row)
            session.flush()
            output_id = row.id

        return GenerateMassingResponse(
            fileUrl=f"/api/arch/outputs/{output_id}",
            programJson=program_json,
            totalAreaM2=total_area,
            floorCount=floor_count,
            summary=summary,
        )

    def _resolve_site(
        self,
        req: GenerateMassingRequest,
        extracted,
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
