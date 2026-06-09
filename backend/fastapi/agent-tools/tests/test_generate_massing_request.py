"""GenerateMassingRequest exactly-one-source validation (SP4 D1)."""

from __future__ import annotations

import uuid

import pytest
from pydantic import ValidationError

from architecture.api.dtos import GenerateMassingRequest

_DOC_ID = "11111111-1111-1111-1111-111111111111"


def test_doc_id_only_is_valid():
    req = GenerateMassingRequest.model_validate({"briefDocId": _DOC_ID})
    assert req.brief_doc_id == uuid.UUID(_DOC_ID)
    assert req.requirements is None


def test_requirements_only_is_valid():
    req = GenerateMassingRequest.model_validate(
        {"requirements": "도서관, 대지 4200, 연면적 9800, 3층"}
    )
    assert req.brief_doc_id is None
    assert req.requirements == "도서관, 대지 4200, 연면적 9800, 3층"


def test_both_sources_rejected():
    with pytest.raises(ValidationError):
        GenerateMassingRequest.model_validate(
            {"briefDocId": _DOC_ID, "requirements": "도서관, 대지 4200"}
        )


def test_neither_source_rejected():
    with pytest.raises(ValidationError):
        GenerateMassingRequest.model_validate({})


def test_blank_requirements_treated_as_absent():
    # ""/whitespace requirements normalizes to None; with no briefDocId that is
    # "neither source" → rejected.
    with pytest.raises(ValidationError):
        GenerateMassingRequest.model_validate({"requirements": "   "})


def test_blank_requirements_with_doc_id_takes_doc_path():
    # Blank requirements normalized to None, briefDocId present → valid doc path.
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": _DOC_ID, "requirements": "  "}
    )
    assert req.brief_doc_id == uuid.UUID(_DOC_ID)
    assert req.requirements is None
