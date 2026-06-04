"""Brief-extraction LCEL chain tests (ADR-19 §A19.8 / §A19.10 / Phase 3a).

The chain is `ChatPromptTemplate | model.with_structured_output(BriefAnalysis)`.
Hermetic: a fake model returns a fixed structured object; the empty-body guard
and the error mapping are exercised directly. Real-gateway extraction parity is
gated on the E2E (not here).
"""

from __future__ import annotations

import httpx
import pytest
from langchain_core.runnables import RunnableLambda

from architecture.app.chains.brief_extraction import (
    build_brief_extraction_chain,
    extract_brief_analysis,
)
from architecture.domain.models import BriefAnalysis
from shared_kernel.errors import MassingError, MassingErrorCode


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


def _analysis(**over) -> BriefAnalysis:
    base = {
        "program": [{"name": "업무", "area_m2": 1000.0, "grade": "above"}],
        "site_area_m2": 5000.0,
    }
    base.update(over)
    return BriefAnalysis.model_validate(base)


def test_happy_path() -> None:
    chain = build_brief_extraction_chain(_FakeModel(_analysis()))
    out = extract_brief_analysis(chain, "brief body")
    assert out.site_area_m2 == 5000.0
    assert out.program[0].name == "업무"
    assert out.program[0].grade == "above"


def test_open_tail_constraints_preserved() -> None:
    program = _analysis(
        constraints=[{"category": "용도지역", "text": "일반상업지역"}],
        coverage_ratio_max=0.8,
        floor_limit=None,
    )
    chain = build_brief_extraction_chain(_FakeModel(program))
    out = extract_brief_analysis(chain, "body")
    assert out.coverage_ratio_max == 0.8
    assert out.floor_limit is None
    assert out.constraints[0].category == "용도지역"


def test_empty_body_fails() -> None:
    chain = build_brief_extraction_chain(_FakeModel(_analysis()))
    with pytest.raises(MassingError) as ei:
        extract_brief_analysis(chain, "")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_llm_failure_maps_to_extraction_failed() -> None:
    chain = build_brief_extraction_chain(_FakeModel(RuntimeError("model blew up")))
    with pytest.raises(MassingError) as ei:
        extract_brief_analysis(chain, "body")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_timeout_maps_to_sidecar_timeout() -> None:
    chain = build_brief_extraction_chain(
        _FakeModel(httpx.TimeoutException("gateway timeout"))
    )
    with pytest.raises(MassingError) as ei:
        extract_brief_analysis(chain, "body")
    assert ei.value.code == MassingErrorCode.SIDECAR_TIMEOUT


def test_non_briefanalysis_result_fails() -> None:
    chain = build_brief_extraction_chain(_FakeModel(None))
    with pytest.raises(MassingError) as ei:
        extract_brief_analysis(chain, "body")
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_extra_instruction_appended_to_prompt() -> None:
    # The re-prompt path appends a hint; build with it and confirm it composes.
    chain = build_brief_extraction_chain(
        _FakeModel(_analysis()), extra_instruction="\n\n[재확인 요청] ..."
    )
    out = extract_brief_analysis(chain, "body")
    assert isinstance(out, BriefAnalysis)
