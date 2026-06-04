"""M8 wire-shape + domain models.

- Pydantic models for HTTP request/response shapes (per PRD §wire-shape).
- Pure-Python dataclasses for the massing domain (algorithm-side).
- SQLAlchemy ORM mapping for the `arch.outputs` row.

Kept in one module because the BC is small; if M8.1+ grows, split per concern.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, field_validator
from sqlalchemy import JSON, REAL, LargeBinary, String, Integer, DateTime, func
from sqlalchemy.dialects.postgresql import JSONB, UUID as PGUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


# --- Pydantic: tool entry-point request/response (PRD wire-shape) ---


class GenerateMassingRequest(BaseModel):
    """POST /internal/tools/generate-massing — body validated by ADR-18 §9 schema."""

    brief_doc_id: UUID = Field(alias="briefDocId")
    site_width: float | None = Field(default=None, alias="siteWidth", gt=0)
    site_depth: float | None = Field(default=None, alias="siteDepth", gt=0)
    floor_height: float | None = Field(default=None, alias="floorHeight", gt=0)

    model_config = {"populate_by_name": True}


class RoomWire(BaseModel):
    """Single room entry in the response programJson + LLM extractor output."""

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


# --- LLM extractor output (validated by Pydantic, replaces ADR-18 §9
# JSON Schema validator per §A18.3) ---


class SiteExtracted(BaseModel):
    width: float = Field(gt=0)
    depth: float = Field(gt=0)


class ExtractedProgram(BaseModel):
    """Strict shape the LLM must return — validated post-call."""

    site: SiteExtracted | None = None
    rooms: list[RoomWire] = Field(min_length=1)

    @field_validator("rooms")
    @classmethod
    def _at_least_one(cls, v: list[RoomWire]) -> list[RoomWire]:
        if not v:
            raise ValueError("must have at least one room")
        return v


# --- Domain models (algorithm-side, no Pydantic — keep zero-dep) ---


@dataclass(frozen=True, slots=True)
class Room:
    name: str
    area_m2: float


@dataclass(frozen=True, slots=True)
class SiteFootprint:
    width: float
    depth: float

    @property
    def area_m2(self) -> float:
        return self.width * self.depth


@dataclass(frozen=True, slots=True)
class RoomBox:
    """Algorithm output — one box per room. Coordinates per ADR-18 §11
    (lower-left origin, z = (floor-1) * floor_height)."""

    name: str
    floor: int
    x: float
    y: float
    z: float
    width: float
    depth: float
    height: float


# --- SQLAlchemy ORM ---


class Base(DeclarativeBase):
    pass


class ArchOutput(Base):
    __tablename__ = "outputs"
    __table_args__ = {"schema": "arch"}

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True, server_default=func.gen_random_uuid())
    brief_doc_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False)
    brief_slug: Mapped[str] = mapped_column(String, nullable=False)
    user_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False)
    file_bytes: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    program_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    total_area_m2: Mapped[float] = mapped_column(REAL, nullable=False)
    floor_count: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
