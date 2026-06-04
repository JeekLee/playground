"""Domain models for the architecture BC (framework-free per ADR-19 §A19.7).

- `Room` / `SiteFootprint` / `RoomBox` — pure-Python dataclasses for the
  massing algorithm (zero-dep).
- `ExtractedProgram` (+ `SiteExtracted`, `ExtractedRoom`) — the strict shape
  the LLM must return, validated post-call via Pydantic (replaces the ADR-18
  §9 JSON Schema validator per §A18.3).

Wire DTOs (request/response) live in `api/dtos.py`; the SQLAlchemy ORM row
(`ArchOutput`) lives in `infra/persistence.py`.
"""

from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel, Field, field_validator


# --- LLM extractor output (validated by Pydantic) ---


class SiteExtracted(BaseModel):
    width: float = Field(gt=0)
    depth: float = Field(gt=0)


class ExtractedRoom(BaseModel):
    """Single room entry the LLM extractor emits."""

    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0, alias="areaM2")

    model_config = {"populate_by_name": True}


class ExtractedProgram(BaseModel):
    """Strict shape the LLM must return — validated post-call."""

    site: SiteExtracted | None = None
    rooms: list[ExtractedRoom] = Field(min_length=1)

    @field_validator("rooms")
    @classmethod
    def _at_least_one(cls, v: list[ExtractedRoom]) -> list[ExtractedRoom]:
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
