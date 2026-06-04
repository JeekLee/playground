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

from architecture.api.content_disposition import content_disposition_attachment
from architecture.api.deps import UserContextDep
from shared_kernel.database import session_scope
from shared_kernel.errors import MassingError, MassingErrorCode
from architecture.infra.persistence import ArchOutput

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
            # RFC 6266 — slug preserves Hangul, which is not latin-1 and
            # would crash the ASGI response if placed in a bare filename=.
            "Content-Disposition": content_disposition_attachment(filename),
            "Content-Length": str(len(file_bytes)),
        },
    )
