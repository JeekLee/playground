"""Brief extractor — Pydantic validation gating LLM output."""

from __future__ import annotations

from typing import Any

import pytest

from app.brief_extractor import extract_program
from app.errors import MassingError, MassingErrorCode


class _FakeLlm:
    """Stub LlmClient that returns a pre-set raw content string."""

    def __init__(self, raw: str) -> None:
        self._raw = raw

    def complete_json(self, system_prompt: str, user_prompt: str) -> str:  # noqa: ARG002
        return self._raw


def test_happy_path_with_site() -> None:
    raw = """{"site": {"width": 20, "depth": 10},
              "rooms": [{"name": "lobby", "areaM2": 40}]}"""
    program = extract_program("brief body", _FakeLlm(raw))
    assert program.site is not None
    assert program.site.width == 20.0
    assert program.rooms[0].name == "lobby"


def test_happy_path_without_site() -> None:
    raw = '{"site": null, "rooms": [{"name": "lobby", "areaM2": 40}]}'
    program = extract_program("brief body", _FakeLlm(raw))
    assert program.site is None


def test_empty_body_fails() -> None:
    with pytest.raises(MassingError) as ei:
        extract_program("", _FakeLlm("ignored"))
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_non_json_response_fails() -> None:
    with pytest.raises(MassingError) as ei:
        extract_program("body", _FakeLlm("not json at all"))
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_schema_violation_fails() -> None:
    # rooms must be non-empty.
    raw = '{"site": null, "rooms": []}'
    with pytest.raises(MassingError) as ei:
        extract_program("body", _FakeLlm(raw))
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED


def test_negative_area_fails() -> None:
    raw = '{"site": null, "rooms": [{"name": "x", "areaM2": -1}]}'
    with pytest.raises(MassingError) as ei:
        extract_program("body", _FakeLlm(raw))
    assert ei.value.code == MassingErrorCode.BRIEF_EXTRACTION_FAILED
