"""SQLAlchemy ORM mapping for the `arch.outputs` row (ADR-19 §A19.7 — infra).

The schema is bootstrapped from `schema.sql` (kept at the agent-tools root);
this module only declares the ORM mapping the persist node writes through.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import DateTime, Integer, LargeBinary, REAL, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID as PGUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


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
