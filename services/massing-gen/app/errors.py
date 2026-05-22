"""M8 error model per ADR-18 §7.

Each `MassingErrorCode` carries:
- a stable wire `code` (FE consumes via the `<CODE>: <message>` prefix
  grammar in M7's `tool_error.message` per ADR-18 §A18.4 unchanged from
  pre-flip — Python flip kept the error shape identical to the Java
  intent).
- an HTTP status the tool endpoint should emit so M7's `ToolDispatcher`
  can map UPSTREAM_4XX / UPSTREAM_5XX correctly.
"""

from __future__ import annotations

from enum import Enum
from http import HTTPStatus


class MassingErrorCode(str, Enum):
    BRIEF_NOT_FOUND = "BRIEF_NOT_FOUND"
    BRIEF_NOT_ACCESSIBLE = "BRIEF_NOT_ACCESSIBLE"
    BRIEF_NOT_READY = "BRIEF_NOT_READY"
    # BRIEF_FETCH_FAILED — docs-api transport/5xx/timeout per ADR-18 §A18.5
    # §7 supersession row (made explicit by the Python flip; was implicit in
    # the Java original which leaked it via SIDECAR_*).
    BRIEF_FETCH_FAILED = "BRIEF_FETCH_FAILED"
    BRIEF_EXTRACTION_FAILED = "BRIEF_EXTRACTION_FAILED"
    MASSING_ALGORITHM_FAILED = "MASSING_ALGORITHM_FAILED"
    # SIDECAR_* — broadened semantics post-flip: rhino3dm.py is in-process
    # (no real sidecar), but the wire-shape codes are preserved so FE's
    # parseM8ErrorPrefix doesn't need updating. SIDECAR_TIMEOUT now covers
    # any LLM-gateway timeout; SIDECAR_FAILED covers LLM-gateway 5xx or
    # rhino3dm runtime failure.
    SIDECAR_TIMEOUT = "SIDECAR_TIMEOUT"
    SIDECAR_FAILED = "SIDECAR_FAILED"
    INTERNAL = "INTERNAL"

    @property
    def http_status(self) -> HTTPStatus:
        return _HTTP_STATUS_BY_CODE[self]


_HTTP_STATUS_BY_CODE: dict[MassingErrorCode, HTTPStatus] = {
    MassingErrorCode.BRIEF_NOT_FOUND: HTTPStatus.NOT_FOUND,
    MassingErrorCode.BRIEF_NOT_ACCESSIBLE: HTTPStatus.FORBIDDEN,
    MassingErrorCode.BRIEF_NOT_READY: HTTPStatus.UNPROCESSABLE_ENTITY,
    MassingErrorCode.BRIEF_FETCH_FAILED: HTTPStatus.BAD_GATEWAY,
    MassingErrorCode.BRIEF_EXTRACTION_FAILED: HTTPStatus.UNPROCESSABLE_ENTITY,
    MassingErrorCode.MASSING_ALGORITHM_FAILED: HTTPStatus.UNPROCESSABLE_ENTITY,
    MassingErrorCode.SIDECAR_TIMEOUT: HTTPStatus.GATEWAY_TIMEOUT,
    MassingErrorCode.SIDECAR_FAILED: HTTPStatus.BAD_GATEWAY,
    MassingErrorCode.INTERNAL: HTTPStatus.INTERNAL_SERVER_ERROR,
}


class MassingError(Exception):
    """Domain exception that maps cleanly to the tool's HTTP response."""

    def __init__(self, code: MassingErrorCode, message: str, *, cause: Exception | None = None):
        super().__init__(f"{code.value}: {message}")
        self.code = code
        self.message = message
        self.cause = cause
