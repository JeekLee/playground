"""fetch_brief node (ADR-19 §A19.9) — one docs-api read + readiness check.

Single step, no branching. Uses shared_kernel.docs_client.
"""

from __future__ import annotations

from typing import Callable

from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.app.state import MassingState


def make_fetch_brief_node(docs_client: DocsClient) -> Callable[[MassingState], dict]:
    def fetch_brief(state: MassingState) -> dict:
        req = state["req"]
        detail = docs_client.get_document(
            req.brief_doc_id, user_id=state["user_id"], user_sub=state["user_sub"]
        )
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
        return {"detail": detail}

    return fetch_brief
