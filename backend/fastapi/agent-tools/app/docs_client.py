"""Cross-BC HTTP client to docs-api per ADR-08 Exception 5 + ADR-18 §A18.6.

One call to `GET /{id}` returns the metadata + body + visibility +
extraction status in a single DocumentDetailResponse — M8 doesn't need
the `/internal/docs/{id}/body` two-call sequence the Java implementation
considered, because the public detail endpoint accepts the X-User-Id
header for visibility filtering.

`X-User-Id` + `X-User-Sub` headers are forwarded from M8's request
(set by rag-chat per M7's ToolDispatcher contract — ADR-17 §X).
Authorization / cookies are NOT forwarded.
"""

from __future__ import annotations

import logging
from uuid import UUID

import httpx

from .config import Settings
from .errors import MassingError, MassingErrorCode
from .models import DocsDetailSubset

logger = logging.getLogger(__name__)


class DocsClient:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self._settings = settings
        # Client injection is for testing (respx fixtures). Production uses
        # a long-lived client per process — created lazily.
        self._owned_client = client is None
        self._client = client or httpx.Client(
            base_url=settings.docs_api_base_url,
            timeout=settings.docs_api_timeout_seconds,
        )

    def close(self) -> None:
        if self._owned_client:
            self._client.close()

    def get_document(
        self,
        doc_id: UUID,
        *,
        user_id: UUID | None,
        user_sub: str | None,
    ) -> DocsDetailSubset:
        """Fetch the document detail with X-User-Id forwarded for visibility."""
        headers: dict[str, str] = {}
        if user_id is not None:
            headers["X-User-Id"] = str(user_id)
        if user_sub:
            headers["X-User-Sub"] = user_sub

        try:
            resp = self._client.get(f"/{doc_id}", headers=headers)
        except httpx.TimeoutException as exc:
            raise MassingError(
                MassingErrorCode.BRIEF_FETCH_FAILED,
                f"docs-api timeout for doc {doc_id}",
                cause=exc,
            ) from exc
        except httpx.HTTPError as exc:
            raise MassingError(
                MassingErrorCode.BRIEF_FETCH_FAILED,
                f"docs-api transport error: {exc}",
                cause=exc,
            ) from exc

        if resp.status_code == 404:
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_FOUND,
                f"brief document {doc_id} not found or not visible",
            )
        if resp.status_code == 403:
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_ACCESSIBLE,
                f"brief document {doc_id} not accessible to caller",
            )
        if resp.status_code >= 500:
            raise MassingError(
                MassingErrorCode.BRIEF_FETCH_FAILED,
                f"docs-api {resp.status_code}: {resp.text[:200]}",
            )
        if resp.status_code >= 400:
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_FOUND,
                f"docs-api {resp.status_code}: {resp.text[:200]}",
            )

        return DocsDetailSubset.model_validate(resp.json())
