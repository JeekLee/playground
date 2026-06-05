"""HTTP wire DTOs for the architecture BC (ADR-19 §A19.7 — api layer).

Pydantic models for the tool entry-point request/response shapes
(PRD §wire-shape). Domain models live in `domain/models.py`; the
SQLAlchemy ORM row lives in `infra/persistence.py`.
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, Field


class GenerateMassingRequest(BaseModel):
    """POST /internal/tools/generate-massing — body validated by ADR-18 §9 schema.

    `siteWidth`/`siteDepth` are retained for wire compatibility but are no longer
    consumed by the Phase-3a pipeline (the algorithm sizes a square footprint
    from area). `targetFloors` (ADR-19 Phase 3a) overrides the resolved
    above-grade floor count.
    """

    brief_doc_id: UUID = Field(alias="briefDocId")
    site_width: float | None = Field(default=None, alias="siteWidth", gt=0)
    site_depth: float | None = Field(default=None, alias="siteDepth", gt=0)
    floor_height: float | None = Field(default=None, alias="floorHeight", gt=0)
    target_floors: int | None = Field(default=None, alias="targetFloors", ge=1)

    model_config = {"populate_by_name": True}


class RoomWire(BaseModel):
    """Single room entry in the response programJson."""

    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0, alias="areaM2")

    model_config = {"populate_by_name": True}


class ProgramJsonWire(BaseModel):
    """programJson shape returned to rag-chat in the tool result.

    Per ADR-18 §9 the schema validation pin (rooms required,
    additionalProperties: false). FE consumes this via the EXPANDED card.
    """

    rooms: list[RoomWire] = Field(min_length=1)
    total_area_m2: float = Field(alias="totalAreaM2")
    floor_count: int = Field(alias="floorCount", ge=1)  # above-grade floors
    basement_levels: int = Field(default=0, alias="basementLevels", ge=0)

    model_config = {"populate_by_name": True}


class MassingResult(BaseModel):
    """The LLM-visible tool result per ADR-20 §D2.

    This is the `result` half of the `{result, artifact}` envelope — the only
    payload fed back to the LLM and into the `tool_result` SSE event. It carries
    NO `fileUrl`: ADR-20 retires agent-tools storage, so the bytes travel
    out-of-band in `artifact` (NON-LLM) and rag-chat owns persistence + download.

    `floorCount` is the above-grade floor count (ADR-19 Phase 3a); below-grade
    levels are carried in `basementLevels` (and rendered in `summary`).
    """

    program_json: ProgramJsonWire = Field(alias="programJson")
    total_area_m2: float = Field(alias="totalAreaM2")
    floor_count: int = Field(alias="floorCount")  # above-grade
    basement_levels: int = Field(default=0, alias="basementLevels")
    summary: str  # Korean fixed format per ADR-18 §5 + §A18.5
    brief_title: str = Field(alias="briefTitle")  # document title from docs-api

    model_config = {"populate_by_name": True}


class MassingArtifact(BaseModel):
    """The NON-LLM file artifact per ADR-20 §D3 revised.

    agent-tools owns the MinIO write path: the `store` node uploaded the .3dm
    before this DTO is built, so only metadata travels in the HTTP response.
    No bytes / no base64 here — rag-chat records `storageKey` in
    `chat.message_attachments` and serves downloads via its own MinIO GET.
    """

    filename: str  # slug-based .3dm name, e.g. massing-<slug>-<ts>.3dm
    content_type: str = Field(default="application/octet-stream", alias="contentType")
    size_bytes: int = Field(alias="sizeBytes")
    storage_key: str = Field(alias="storageKey")

    model_config = {"populate_by_name": True}


class GenerateMassingResponse(BaseModel):
    """Tool endpoint envelope per ADR-20 §D2 + §D3 revised — `{result, artifact}`.

    `result` is the LLM-visible payload (the massing summary); `artifact`
    carries metadata only (filename, contentType, sizeBytes, storageKey) — the
    bytes are already in MinIO. The dispatcher detects the envelope by the
    presence of BOTH keys and records the storageKey as a chat.message_attachments
    row without touching MinIO.
    """

    result: MassingResult
    artifact: MassingArtifact

    model_config = {"populate_by_name": True}
