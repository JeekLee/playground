"""fetch_brief node — doc path vs inline-requirements path (SP4 D1)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

from architecture.api.dtos import GenerateMassingRequest
from architecture.app.nodes.fetch_brief import make_fetch_brief_node


class _ForbiddenDocs:
    def get_document(self, doc_id, *, user_id, user_sub):
        raise AssertionError("docs-api must not be called on the inline path")


class _OkDocs:
    def __init__(self):
        self.calls = 0

    def get_document(self, doc_id, *, user_id, user_sub):
        self.calls += 1
        return SimpleNamespace(
            extraction_status="extracted", body="대지면적 4200㎡", title="t"
        )


def test_inline_requirements_skips_docs_and_synthesizes_detail():
    node = make_fetch_brief_node(_ForbiddenDocs())
    req = GenerateMassingRequest.model_validate(
        {"requirements": "도서관, 대지 4200㎡, 연면적 9800㎡, 3층"}
    )
    out = node({"req": req, "user_id": uuid.uuid4(), "user_sub": None})

    detail = out["detail"]
    assert detail.body == "도서관, 대지 4200㎡, 연면적 9800㎡, 3층"
    assert detail.extraction_status == "extracted"
    assert detail.title  # non-null generic fallback (D4)


def test_doc_path_reads_docs_api():
    docs = _OkDocs()
    node = make_fetch_brief_node(docs)
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )
    out = node({"req": req, "user_id": uuid.uuid4(), "user_sub": "sub"})

    assert docs.calls == 1
    assert out["detail"].body == "대지면적 4200㎡"
