"""Brief slug helper per ADR-18 §21."""

from __future__ import annotations

from architecture.slug import briefslug


def test_korean_title() -> None:
    assert briefslug("한국소방산업기술원 청사 확충") == "한국소방산업기술원-청사-확충"


def test_ascii_title() -> None:
    assert briefslug("Seoul Library Brief 2026") == "Seoul-Library-Brief-2026"


def test_mixed() -> None:
    assert briefslug("KFI 2026/Q1 brief") == "KFI-2026-Q1-brief"


def test_punctuation_only() -> None:
    assert briefslug("!!!---???") == "brief"


def test_empty() -> None:
    assert briefslug("") == "brief"
    assert briefslug("   ") == "brief"


def test_caps_length() -> None:
    long_title = "한국소방산업기술원 청사 확충 이전사업 설계공모 지침서 KFI 2026"
    slug = briefslug(long_title)
    assert len(slug) <= 40


def test_no_trailing_dash() -> None:
    assert not briefslug("trailing - ").endswith("-")
    assert not briefslug(" - leading").startswith("-")
