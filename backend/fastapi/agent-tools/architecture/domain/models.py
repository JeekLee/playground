"""Domain models for the architecture BC (framework-free per ADR-19 §A19.7).

ADR-19 Phase 3a introduces the two-schema extract→resolve→compute pipeline:

- `BriefAnalysis` (+ `ProgramItem`, `Parking`, `Constraint`) — the OPEN,
  fact-only representation the LLM extracts. Carries only what the brief
  STATES; never invented dimensions or floor counts.
- `MassingInputs` (+ `Zone`) — the TIGHT, validated contract the deterministic
  algorithm consumes. Built from `BriefAnalysis` by the rule-first resolve
  step. Pydantic validators enforce the coverage gate so the algorithm may
  assume a feasible massing.
- `RoomBox` — pure-Python dataclass for the algorithm's box output.

Wire DTOs (request/response) live in `api/dtos.py`; the SQLAlchemy ORM row
(`ArchOutput`) lives in `infra/persistence.py`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, Field, model_validator


# --- LLM extraction target: BriefAnalysis (OPEN, fact-only) ---


class ProgramItem(BaseModel):
    """A single area-program line the brief states."""

    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0)
    grade: Literal["above", "below", "unknown"] = "unknown"
    kind: str | None = None


class Parking(BaseModel):
    below: int | None = None
    above: int | None = None
    total: int | None = None


class Constraint(BaseModel):
    """Open-tail requirement bucket (용도지역/일조/조경/공개공지/분동/증축/단지맥락 등)."""

    category: str = Field(min_length=1)
    text: str = Field(min_length=1)
    value: float | None = None
    unit: str | None = None


class BriefAnalysis(BaseModel):
    """Open, fact-only extraction the LLM produces from the brief.

    Every field except `program` is optional — the model fills only what the
    brief states. `None` is meaningful (e.g. floor_limit=None means "층수
    제한없음"); the resolve step decides defaults, not the extractor.
    """

    program: list[ProgramItem] = Field(min_length=1)
    site_area_m2: float | None = None
    coverage_ratio_max: float | None = None  # 건폐율, stored 0..1 (80% -> 0.8)
    floor_area_ratio_max: float | None = None  # 용적률 (e.g. 3.5)
    total_gfa_m2: float | None = None  # 연면적
    floor_limit: int | None = None  # null when "층수 제한없음"
    height_limit_m: float | None = None
    parking: Parking | None = None
    constraints: list[Constraint] = Field(default_factory=list)
    notes: str | None = None

    @model_validator(mode="after")
    def _at_least_one_program(self) -> "BriefAnalysis":
        if not self.program:
            raise ValueError("program must have at least one item")
        return self


# --- Algorithm contract: MassingInputs (TIGHT, validated) ---


class Zone(BaseModel):
    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0)
    grade: Literal["above", "below"]


class MassingInputs(BaseModel):
    """Validated contract the deterministic algorithm consumes.

    Validators enforce feasibility so `compute_massing` may assume it holds:
    - zones non-empty;
    - any below-grade zone requires basement_levels >= 1;
    - the coverage gate: above-grade footprint
      (sum above areas / target_floors_above) <= site_area * coverage_cap.
    """

    zones: list[Zone] = Field(min_length=1)
    site_area_m2: float = Field(gt=0)
    coverage_cap: float = Field(gt=0, le=1)
    target_floors_above: int = Field(ge=1)
    basement_levels: int = Field(ge=0)
    floor_height_m: float = Field(gt=0)

    @model_validator(mode="after")
    def _validate(self) -> "MassingInputs":
        if not self.zones:
            raise ValueError("zones must be non-empty")
        has_below = any(z.grade == "below" for z in self.zones)
        if has_below and self.basement_levels < 1:
            raise ValueError(
                "below-grade zones present but basement_levels is 0"
            )
        above_area = sum(z.area_m2 for z in self.zones if z.grade == "above")
        if above_area > 0:
            footprint = above_area / self.target_floors_above
            allowed = self.site_area_m2 * self.coverage_cap
            if footprint > allowed + 1e-6:
                raise ValueError(
                    "coverage gate violated: above-grade footprint "
                    f"{footprint:.1f} m² (= {above_area:.1f} / "
                    f"{self.target_floors_above} floors) exceeds allowed "
                    f"{allowed:.1f} m² (= site {self.site_area_m2:.1f} × "
                    f"coverage {self.coverage_cap:.2f}). Increase "
                    "target_floors_above, the site area, or coverage cap."
                )
        return self

    @property
    def above_footprint_area(self) -> float:
        above_area = sum(z.area_m2 for z in self.zones if z.grade == "above")
        return above_area / self.target_floors_above if above_area > 0 else 0.0


# --- Algorithm output (no Pydantic — keep zero-dep) ---


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
