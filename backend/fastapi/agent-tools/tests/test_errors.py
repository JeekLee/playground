"""Error-code enum stability + HTTP status mapping tests."""

from __future__ import annotations

from http import HTTPStatus

import pytest

from shared_kernel.errors import MassingError, MassingErrorCode


def test_enum_has_expected_codes() -> None:
    expected = {
        "BRIEF_NOT_FOUND",
        "BRIEF_NOT_ACCESSIBLE",
        "BRIEF_NOT_READY",
        "BRIEF_FETCH_FAILED",
        "BRIEF_EXTRACTION_FAILED",
        "MASSING_ALGORITHM_FAILED",
        "SIDECAR_TIMEOUT",
        "SIDECAR_FAILED",
        "INTERNAL",
    }
    assert {c.value for c in MassingErrorCode} == expected


@pytest.mark.parametrize(
    "code, status",
    [
        (MassingErrorCode.BRIEF_NOT_FOUND, HTTPStatus.NOT_FOUND),
        (MassingErrorCode.BRIEF_NOT_ACCESSIBLE, HTTPStatus.FORBIDDEN),
        (MassingErrorCode.BRIEF_NOT_READY, HTTPStatus.UNPROCESSABLE_ENTITY),
        (MassingErrorCode.BRIEF_FETCH_FAILED, HTTPStatus.BAD_GATEWAY),
        (MassingErrorCode.BRIEF_EXTRACTION_FAILED, HTTPStatus.UNPROCESSABLE_ENTITY),
        (MassingErrorCode.MASSING_ALGORITHM_FAILED, HTTPStatus.UNPROCESSABLE_ENTITY),
        (MassingErrorCode.SIDECAR_TIMEOUT, HTTPStatus.GATEWAY_TIMEOUT),
        (MassingErrorCode.SIDECAR_FAILED, HTTPStatus.BAD_GATEWAY),
        (MassingErrorCode.INTERNAL, HTTPStatus.INTERNAL_SERVER_ERROR),
    ],
)
def test_http_status_mapping(code: MassingErrorCode, status: HTTPStatus) -> None:
    assert code.http_status == status


def test_exception_carries_prefix_in_str() -> None:
    exc = MassingError(MassingErrorCode.BRIEF_NOT_READY, "brief still processing")
    assert "BRIEF_NOT_READY: brief still processing" in str(exc)
