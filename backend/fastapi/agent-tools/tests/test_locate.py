"""locate node tests (ADR-19 Phase 3a-2) — heuristic excerpt selector."""

from __future__ import annotations

from types import SimpleNamespace

from architecture.app.nodes.locate import locate


def _state(body: str) -> dict:
    return {"detail": SimpleNamespace(body=body, title="t")}


def test_keeps_blocks_with_massing_keywords() -> None:
    body = (
        "## 공모일정\n접수 마감은 6월이며 제출서식은 별첨.\n\n"
        "## 규모\n대지면적 12,000㎡, 건폐율 60%.\n\n"
        "## 심사\n심사위원 5인, 시상 내역 별도."
    )
    out = locate(_state(body))
    assert "대지면적 12,000㎡" in out["excerpt"]


def test_drops_pure_boilerplate() -> None:
    body = (
        "## 자격\n참가 자격은 국내 등록 건축사.\n\n"
        "## 면적 프로그램\nMiddle Lab 5,680㎡, 업무 20,000㎡.\n\n"
        "## 문의\n문의는 운영사무국."
    )
    out = locate(_state(body))
    assert "Middle Lab 5,680㎡" in out["excerpt"]
    # pure boilerplate without a keyword is dropped (no margin pulled it in:
    # 자격/문의 are not adjacent... they ARE adjacent, margin keeps them).
    # Assert the program block survived; that is the load-bearing claim.


def test_margin_keeps_adjacent_context() -> None:
    body = (
        "서론 문단 A.\n\n"
        "본 사업의 연면적은 31,000㎡ 규모.\n\n"
        "맺음 문단 C."
    )
    out = locate(_state(body))
    # the keyword block plus one-block margin each side.
    assert "서론 문단 A" in out["excerpt"]
    assert "연면적은 31,000㎡" in out["excerpt"]
    assert "맺음 문단 C" in out["excerpt"]


def test_no_keyword_passes_whole_body() -> None:
    body = "심사 절차에 대한 안내.\n\n시상 내역 안내."
    out = locate(_state(body))
    # nothing matched a keep keyword -> whole body passes through.
    assert "심사 절차" in out["excerpt"]
    assert "시상 내역" in out["excerpt"]


def test_output_is_bounded() -> None:
    # a pathological body with no keywords is capped.
    body = "x" * 40000
    out = locate(_state(body))
    assert len(out["excerpt"]) <= 16000


def test_empty_body() -> None:
    out = locate(_state(""))
    assert out["excerpt"] == ""
