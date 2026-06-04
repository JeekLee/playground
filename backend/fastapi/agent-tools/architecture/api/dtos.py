"""HTTP wire DTOs for the architecture BC (ADR-19 §A19.7 — api layer).

Pydantic models for the tool entry-point request/response shapes
(PRD §wire-shape). Domain models live in `domain/models.py`; the
SQLAlchemy ORM row lives in `infra/persistence.py`.
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, Field


class GenerateMassingRequest(BaseModel):
    """POST /internal/tools/generate-massing — body validated by ADR-18 §9 schema."""

    brief_doc_id: UUID = Field(alias="briefDocId")
    site_width: float | None = Field(default=None, alias="siteWidth", gt=0)
    site_depth: float | None = Field(default=None, alias="siteDepth", gt=0)
    floor_height: float | None = Field(default=None, alias="floorHeight", gt=0)

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
    floor_count: int = Field(alias="floorCount", ge=1)

    model_config = {"populate_by_name": True}


class GenerateMassingResponse(BaseModel):
    """Tool endpoint response shape per ADR-18 §10 + PRD §wire-shape."""

    file_url: str = Field(alias="fileUrl")
    program_json: ProgramJsonWire = Field(alias="programJson")
    total_area_m2: float = Field(alias="totalAreaM2")
    floor_count: int = Field(alias="floorCount")
    summary: str  # Korean fixed format per ADR-18 §5 + §A18.5

    model_config = {"populate_by_name": True}
