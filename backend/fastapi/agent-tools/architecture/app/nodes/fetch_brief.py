"""fetch_brief node (ADR-19 §A19.9) — resolve the space-program source.

Two sources (SP4 D1):
- doc path: one docs-api read + readiness check (the original behavior), or
- inline path: synthesize a DocsDetailSubset from the conversation `requirements`
  text — no docs-api call. Downstream nodes never re-read briefDocId, so the
  synthesized `detail.body` lets the rest of the pipeline run unchanged.
"""

from __future__ import annotations

from typing import Callable
from uuid import uuid4

from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode
from shared_kernel.models import DocsDetailSubset

from architecture.app.state import MassingState

# Inline-path (SP4) synthetic brief title — generic, human-readable, non-null (D4).
_INLINE_BRIEF_TITLE = "매싱 요청"


def make_fetch_brief_node(docs_client: DocsClient) -> Callable[[MassingState], dict]:
    def fetch_brief(state: MassingState) -> dict:
        req = state["req"]
        if req.requirements is not None:
            # Inline path (SP4 D1): the conversation text IS the brief. Skip
            # docs-api and synthesize a DocsDetailSubset. All required no-default
            # fields (id/author_id/title/visibility) must be set or Pydantic
            # raises; id/author_id/visibility are dead downstream but required.
            detail = DocsDetailSubset(
                id=uuid4(),
                author_id=state["user_id"],
                title=_INLINE_BRIEF_TITLE,
                body=req.requirements,
                visibility="private",
                extraction_status="extracted",
            )
            return {"detail": detail}

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
