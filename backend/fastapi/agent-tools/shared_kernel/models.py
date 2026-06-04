"""Shared-kernel wire models — DTOs for the cross-cutting HTTP clients.

DocsDetailSubset is the deserialization contract of shared_kernel's
DocsClient (the docs-api detail response subset). It lives here, with its
only consumer, so shared_kernel stays free of any architecture-BC import
(shared_kernel must not depend on a BC).
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, Field


# --- DocsApi DTO subset (only what M8 needs from the detail response) ---


class DocsDetailSubset(BaseModel):
    """Subset of docs-api's DocumentDetailResponse — only the fields M8
    actually consumes. Permissive on extra fields so M6/M6.1+ doc-side
    additions don't break us."""

    id: UUID
    author_id: UUID = Field(alias="authorId")
    title: str
    body: str | None = None
    visibility: str  # "public" | "private"
    extraction_status: str | None = Field(default=None, alias="extractionStatus")

    model_config = {"populate_by_name": True, "extra": "ignore"}
