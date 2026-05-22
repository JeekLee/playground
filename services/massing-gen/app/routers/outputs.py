"""GET /api/arch/outputs/{id} — owner-only .3dm download per ADR-18 §21 +
PRD Story 5.

Gateway routes /api/arch/** here with StripPrefix=2, so the upstream
sees /outputs/{id}. Session cookie is gateway-issued (M1); the gateway
adds X-User-Id to the request, which our deps.get_user_context reads.

Tenant isolation: a non-owner request returns 404 (not 403) — same
pattern as docs-api per M2 §6.5 tenant-isolation spec.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Response
from sqlalchemy import select

from ..deps import UserContextDep
from ..database import session_scope
from ..errors import MassingError, MassingErrorCode
from ..models import ArchOutput

router = APIRouter(prefix="/outputs", tags=["outputs"])


@router.get("/{output_id}")
def download_output(
    output_id: UUID,
    user: UserContextDep,
) -> Response:
    with session_scope() as session:
        stmt = select(ArchOutput).where(ArchOutput.id == output_id)
        row = session.execute(stmt).scalar_one_or_none()

        # Tenant isolation: non-owner == not-found (don't leak existence).
        if row is None or row.user_id != user.user_id:
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_FOUND,
                f"output {output_id} not found",
            )

        file_bytes = bytes(row.file_bytes)
        slug = row.brief_slug
        # Use the row's created_at for filename stability (idempotent
        # filename if the user re-downloads).
        timestamp = (
            row.created_at.astimezone(timezone.utc).strftime("%Y%m%d-%H%M%S")
            if isinstance(row.created_at, datetime)
            else datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        )

    filename = f"massing-{slug}-{timestamp}.3dm"
    return Response(
        content=file_bytes,
        media_type="application/octet-stream",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Content-Length": str(len(file_bytes)),
        },
    )
