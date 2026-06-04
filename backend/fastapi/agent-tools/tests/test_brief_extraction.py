"""Brief-extraction LCEL chain tests (ADR-19 §A19.8 / §A19.10).

The chain is `ChatPromptTemplate | model.with_structured_output(ExtractedProgram)`.
Hermetic: a fake model returns a fixed structured object; the empty-body guard
and the error mapping are exercised directly. The real-gateway extraction parity
is gated on the E2E (not here).
"""

from __future__ import annotations

import httpx
import pytest
from langchain_core.runnables import RunnableLambda

from architecture.app.chains.brief_extraction import (
    build_brief_extraction_chain,
    extract_program,
)
from architecture.domain.models import ExtractedProgram
from shared_kernel.errors import MassingError, MassingErrorCode


class _StructuredRunnable(RunnableLambda):
    """Stands in for `model.with_structured_output(...)`."""


class _FakeModel:
    """Minimal stand-in for a BaseChatModel: only with_structured_output is used."""

    def __init__(self, result):
        self._result = result

    def with_structured_output(self, schema, *, method=None):  # noqa: ARG002
        result = self._result

        def _invoke(_messages):
            if isinstance(result, Exception):
                raise result
            return result

        return RunnableLambda(_invoke)


def _program(site=None) -> ExtractedProgram:
    return ExtractedProgram.model_validate(
        {"site": site, "rooms": [{"name": "lobby", "areaM2": 40}]}
    )


def test_happy_path_with_site() -> None:
    program = _program(site={"width": 20, "depth": 10})
    chain = build_brief_extraction_chain(_FakeModel(program))
    out = extract_program(chain, "brief body")
    assert out.site is not None
    assert out.site.width == 20.0
    assert out.rooms[0].name == "lobby"


def test_happy_path_without_site() -> None:
    chain = build_brief_extraction_chain(_FakeModel(_program(site=None)))
    out = extract_program(chain, "brief body")
    assert out.site is None


def test_empty_body_fails() -> None:
    chain = build_brief_extraction_chain(_FakeModel(_program()))
    with pytest.raises(MassingError) as ei:
        extract_program(chain, "")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_llm_failure_maps_to_extraction_failed() -> None:
    chain = build_brief_extraction_chain(_FakeModel(RuntimeError("model blew up")))
    with pytest.raises(MassingError) as ei:
        extract_program(chain, "body")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_timeout_maps_to_sidecar_timeout() -> None:
    chain = build_brief_extraction_chain(_FakeModel(httpx.TimeoutException("gateway timeout")))
    with pytest.raises(MassingError) as ei:
        extract_program(chain, "body")
    assert ei.value.code == MassingErrorCode.SIDECAR_TIMEOUT


def test_non_extractedprogram_result_fails() -> None:
    chain = build_brief_extraction_chain(_FakeModel(None))
    with pytest.raises(MassingError) as ei:
        extract_program(chain, "body")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED
