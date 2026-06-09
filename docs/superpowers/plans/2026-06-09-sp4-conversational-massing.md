# 대화 기반 매싱 생성 (SP4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `generate_massing` build a .3dm from inline conversation text (`requirements`) as an alternative to an uploaded brief PDF (`briefDocId`), so users can sketch a massing by describing the program in chat.

**Architecture:** Add a second "space-program source" at the single docs-api seam (`fetch_brief`). When `requirements` is present, `fetch_brief` synthesizes a `DocsDetailSubset` (body = the conversation text) instead of reading docs-api; the rest of the pipeline (locate→extract→reconcile→classify→derive→compute→serialize→store) runs unchanged. The site-area re-prompt loop fails fast on the inline path (re-extracting the same short text is pointless). chat stays corpus/domain-agnostic — it only relays the tool schema and passes args through.

**Tech Stack:** Python 3.12 / FastAPI / LangGraph / Pydantic v2 (agent-tools `architecture` BC); Java 21 / Spring Boot (chat-domain tool descriptor). Tests: pytest (agent-tools, `asyncio_mode=auto`), JUnit5 + AssertJ (chat).

**Spec:** `docs/superpowers/specs/2026-06-09-sp4-conversational-massing-design.md`

---

## File Structure

**agent-tools (`backend/fastapi/agent-tools/`)**
- Modify: `architecture/api/dtos.py` — `GenerateMassingRequest`: `briefDocId` optional, add `requirements`, exactly-one `model_validator`.
- Modify: `architecture/app/nodes/fetch_brief.py` — inline branch synthesizing `DocsDetailSubset`.
- Modify: `architecture/api/routers/tools.py` — start-log mode branch (doc/inline) so it stops logging `"None"`.
- Modify: `architecture/app/graphs/program_resolution.py` — `_route` fail-fast on the inline path.
- Create: `tests/test_generate_massing_request.py` — exactly-one validation.
- Create: `tests/test_fetch_brief.py` — inline vs doc branch.
- Modify: `tests/test_workflow.py` — inline fail-fast + inline end-to-end.

**chat (`backend/springboot/chat/`)**
- Modify: `chat-domain/src/main/java/com/playground/chat/domain/tool/MassingTool.java` — `INPUT_SCHEMA` (`briefDocId` non-required, add `requirements`) + `DESCRIPTION` (exactly-one source + ask-for-site-area).
- Create: `chat-domain/src/test/java/com/playground/chat/domain/tool/MassingToolTest.java` — schema/description assertions.

No `frontend/`, `infra/`, chat-app, or chat-infra changes — FE is source-agnostic (spec D6) and chat passes tool args through unchanged (spec D3).

---

## Pre-flight (run once, before Task 1)

- [ ] **Create the worktree** (project convention — all code edits go through a worktree):

```
EnterWorktree({ name: "sp4-conversational-massing" })
```

- [ ] **Seed real compose env into the worktree** (needed only if you run docker in Task 6):

Run: `cp infra/.env <worktree>/infra/.env`
(Never use `infra/.env.example` for a real run — it lacks real creds.)

- [ ] **Install agent-tools test deps inside the worktree:**

Run: `cd backend/fastapi/agent-tools && pip install -e ".[test]"`
Expected: resolves and installs; `pytest` available.

- [ ] **Baseline the test suites are green before any change:**

Run: `cd backend/fastapi/agent-tools && pytest -q`
Expected: ~106 tests pass (0 failures).

Run: `cd backend/springboot && ./gradlew :chat:chat-domain:test`
Expected: BUILD SUCCESSFUL.

---

## Task 1: agent-tools — `GenerateMassingRequest` exactly-one source

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/dtos.py:15-30`
- Test: `backend/fastapi/agent-tools/tests/test_generate_massing_request.py` (create)

- [ ] **Step 1: Write the failing test**

Create `backend/fastapi/agent-tools/tests/test_generate_massing_request.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_generate_massing_request.py -v`
Expected: FAIL — `test_neither_source_rejected` / `test_both_sources_rejected` fail because `briefDocId` is still required and no exactly-one validator exists yet (and `requirements` is an unknown field → `test_requirements_only_is_valid` errors).

- [ ] **Step 3: Modify `GenerateMassingRequest`**

In `backend/fastapi/agent-tools/architecture/api/dtos.py`, change the import line 12 and replace the class body (lines 15-30):

Replace:
```python
from pydantic import BaseModel, Field
```
with:
```python
from pydantic import BaseModel, Field, model_validator
```

Replace the whole `GenerateMassingRequest` class (lines 15-30) with:
```python
class GenerateMassingRequest(BaseModel):
    """POST /internal/tools/generate-massing — body validated by ADR-18 §9 schema.

    The space program comes from EXACTLY ONE source (SP4 D1):
    - `briefDocId` — an uploaded brief PDF read via docs-api, or
    - `requirements` — free-text program synthesized from the conversation.
    Providing both, or neither, is a 422 validation error.

    `siteWidth`/`siteDepth` are retained for wire compatibility but are no longer
    consumed by the Phase-3a pipeline (the algorithm sizes a square footprint
    from area). `targetFloors` (ADR-19 Phase 3a) overrides the resolved
    above-grade floor count.
    """

    brief_doc_id: UUID | None = Field(default=None, alias="briefDocId")
    requirements: str | None = Field(default=None)
    site_width: float | None = Field(default=None, alias="siteWidth", gt=0)
    site_depth: float | None = Field(default=None, alias="siteDepth", gt=0)
    floor_height: float | None = Field(default=None, alias="floorHeight", gt=0)
    target_floors: int | None = Field(default=None, alias="targetFloors", ge=1)

    model_config = {"populate_by_name": True}

    @model_validator(mode="after")
    def _exactly_one_source(self) -> "GenerateMassingRequest":
        # Blank requirements ("" or whitespace) is treated as "not provided" so
        # it cannot masquerade as the inline path downstream — fetch_brief and
        # the resolve_program router both branch on `requirements is not None`.
        if self.requirements is not None and not self.requirements.strip():
            self.requirements = None
        has_doc = self.brief_doc_id is not None
        has_req = self.requirements is not None
        if has_doc == has_req:
            raise ValueError("provide exactly one of briefDocId or requirements")
        return self
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_generate_massing_request.py -v`
Expected: PASS (6 passed).

- [ ] **Step 5: Confirm no regression in DTO consumers**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_derive.py tests/test_workflow.py -q`
Expected: PASS — existing tests build `GenerateMassingRequest.model_validate({"briefDocId": ...})` (requirements absent → None → valid doc path).

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/dtos.py \
        backend/fastapi/agent-tools/tests/test_generate_massing_request.py
git commit -m "feat(agent-tools): GenerateMassingRequest exactly-one source (briefDocId|requirements)"
```

---

## Task 2: agent-tools — `fetch_brief` inline branch + router log

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/fetch_brief.py:1-34`
- Modify: `backend/fastapi/agent-tools/architecture/api/routers/tools.py:36-39`
- Test: `backend/fastapi/agent-tools/tests/test_fetch_brief.py` (create)

- [ ] **Step 1: Write the failing test**

Create `backend/fastapi/agent-tools/tests/test_fetch_brief.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_fetch_brief.py -v`
Expected: FAIL — `test_inline_requirements_skips_docs_and_synthesizes_detail` raises `AssertionError("docs-api must not be called...")` because the current node always calls `docs_client.get_document`.

- [ ] **Step 3: Add the inline branch to `fetch_brief`**

Replace the entire contents of `backend/fastapi/agent-tools/architecture/app/nodes/fetch_brief.py` with:
```python
"""fetch_brief node (ADR-19 §A19.9) — resolve the space-program source.

Two sources (SP4 D1):
- doc path: one docs-api read + readiness check (the original behavior), or
- inline path: synthesize a DocsDetailSubset from the conversation `requirements`
  text — no docs-api call. Downstream nodes never re-read briefDocId, so the
  synthesized `detail.body` lets the rest of the pipeline run unchanged.
"""

from __future__ import annotations

from typing import Callable
from uuid import uuid4

from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode
from shared_kernel.models import DocsDetailSubset

from architecture.app.state import MassingState

# Inline-path (SP4) synthetic brief title — generic, human-readable, non-null (D4).
_INLINE_BRIEF_TITLE = "매싱 요청"


def make_fetch_brief_node(docs_client: DocsClient) -> Callable[[MassingState], dict]:
    def fetch_brief(state: MassingState) -> dict:
        req = state["req"]
        if req.requirements is not None:
            # Inline path (SP4 D1): the conversation text IS the brief. Skip
            # docs-api and synthesize a DocsDetailSubset. All required no-default
            # fields (id/author_id/title/visibility) must be set or Pydantic
            # raises; id/author_id/visibility are dead downstream but required.
            detail = DocsDetailSubset(
                id=uuid4(),
                author_id=state["user_id"],
                title=_INLINE_BRIEF_TITLE,
                body=req.requirements,
                visibility="private",
                extraction_status="extracted",
            )
            return {"detail": detail}

        detail = docs_client.get_document(
            req.brief_doc_id, user_id=state["user_id"], user_sub=state["user_sub"]
        )
        if detail.extraction_status and detail.extraction_status != "extracted":
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_READY,
                f"brief extraction_status={detail.extraction_status}, expected 'extracted'",
            )
        if not detail.body or not detail.body.strip():
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_READY,
                "brief body is empty (extraction may have failed upstream)",
            )
        return {"detail": detail}

    return fetch_brief
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_fetch_brief.py -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Fix the router start-log (minor — D1)**

In `backend/fastapi/agent-tools/architecture/api/routers/tools.py`, replace the `logger.info(...)` block (lines 36-39):

Replace:
```python
    logger.info(
        "generate_massing requested",
        extra={"brief_doc_id": str(req.brief_doc_id), "user_id": str(user.user_id)},
    )
```
with:
```python
    logger.info(
        "generate_massing requested",
        extra={
            "mode": "inline" if req.requirements is not None else "doc",
            "brief_doc_id": str(req.brief_doc_id) if req.brief_doc_id else None,
            "user_id": str(user.user_id),
        },
    )
```

- [ ] **Step 6: Run the stream-endpoint test to confirm the router still works**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_stream_endpoint.py -q`
Expected: PASS (2 passed) — the doc-path request (`{"briefDocId": ...}`) logs `mode="doc"`.

- [ ] **Step 7: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/nodes/fetch_brief.py \
        backend/fastapi/agent-tools/architecture/api/routers/tools.py \
        backend/fastapi/agent-tools/tests/test_fetch_brief.py
git commit -m "feat(agent-tools): fetch_brief inline-requirements branch + mode-aware start log"
```

---

## Task 3: agent-tools — `_route` fail-fast on the inline path

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/graphs/program_resolution.py:83-93`
- Test: `backend/fastapi/agent-tools/tests/test_workflow.py` (append)

- [ ] **Step 1: Write the failing test**

Append to `backend/fastapi/agent-tools/tests/test_workflow.py` (the module already imports `uuid`, `pytest`, `SimpleNamespace`, `pr`, `GenerateMassingRequest`, `Settings`, `RunnableLambda`, and defines `_NO_SITE`):

```python
def test_inline_path_fails_fast_without_reextraction():
    # Inline requirements with no site area → derive returns {} → _route raises
    # immediately (no retry). extract must be called exactly once (re-extracting
    # the same short conversation text is pointless — SP4 D2).
    from shared_kernel.errors import MassingError, MassingErrorCode

    calls = {"n": 0}

    def counting(_inputs):
        calls["n"] += 1
        return _NO_SITE

    sub = pr.build_program_resolution_subgraph(
        Settings(), chain=RunnableLambda(counting)
    )
    req = GenerateMassingRequest.model_validate(
        {"requirements": "도서관 3층, 일반열람실 2400㎡"}  # no site area
    )
    with pytest.raises(MassingError) as ei:
        sub.invoke(
            {"req": req, "detail": SimpleNamespace(body="도서관 3층", title="t")}
        )
    assert ei.value.code == MassingErrorCode.BRIEF_NOT_READY
    assert calls["n"] == 1  # no re-extraction on the inline path
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_workflow.py::test_inline_path_fails_fast_without_reextraction -v`
Expected: FAIL — `calls["n"] == 3` (current `_route` retries `MAX_EXTRACT_RETRIES`=2 times before raising), so `assert calls["n"] == 1` fails.

- [ ] **Step 3: Add the inline fail-fast branch to `_route`**

In `backend/fastapi/agent-tools/architecture/app/graphs/program_resolution.py`, replace the `_route` function (lines 83-93):

Replace:
```python
    def _route(state: MassingState) -> str:
        if "inputs" in state:
            return "done"
        if state.get("extract_attempts", 0) <= MAX_EXTRACT_RETRIES:
            return "retry"
        # Budget exhausted — surface the missing-input error.
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_READY,
            "site area (대지면적) not found after "
            f"{state.get('extract_attempts', 0)} extraction attempts",
        )
```
with:
```python
    def _route(state: MassingState) -> str:
        if "inputs" in state:
            return "done"
        # Inline-requirements path (SP4 D2): re-extracting the same short
        # conversation text is pointless, so fail fast instead of looping. The
        # tool error reaches chat and the LLM asks the user for the site area.
        if state["req"].requirements is not None:
            raise MassingError(
                MassingErrorCode.BRIEF_NOT_READY,
                "site area (대지면적) not found in the conversation requirements "
                "— ask the user for the site size (대지면적) before generating",
            )
        if state.get("extract_attempts", 0) <= MAX_EXTRACT_RETRIES:
            return "retry"
        # Budget exhausted — surface the missing-input error.
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_READY,
            "site area (대지면적) not found after "
            f"{state.get('extract_attempts', 0)} extraction attempts",
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_workflow.py::test_inline_path_fails_fast_without_reextraction -v`
Expected: PASS.

- [ ] **Step 5: Confirm the doc-path retry loop still works (regression)**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_workflow.py::test_reprompt_loop_fails_when_site_area_never_found tests/test_workflow.py::test_stream_attempt_counts_extract_retries -v`
Expected: PASS — these build `req` with `briefDocId` only (requirements None), so the existing retry path is unchanged.

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/graphs/program_resolution.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): inline massing path fails fast on missing site area"
```

---

## Task 4: agent-tools — inline end-to-end (no docs-api)

**Files:**
- Test: `backend/fastapi/agent-tools/tests/test_workflow.py` (append)

This proves fetch_brief (Task 2) + resolve_program + derive (Task 3) compose into a full `{result, artifact}` envelope on the inline path, and that docs-api is never touched.

- [ ] **Step 1: Write the test**

Append to `backend/fastapi/agent-tools/tests/test_workflow.py`:

```python
def test_inline_requirements_end_to_end_without_docs(monkeypatch):
    # Inline path with a site area present (via the fixed extraction chain)
    # produces a full envelope and never calls docs-api.
    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260609/test-uuid/{filename}",
    )
    monkeypatch.setattr(
        "architecture.app.nodes.store_glb.upload_to_key",
        lambda file_bytes, key, content_type, settings: None,
    )

    class _NoDocs:
        def get_document(self, *a, **k):
            raise AssertionError("inline path must not call docs-api")

    flow = MassingWorkflow(
        Settings(), _NoDocs(), extraction_chain=_fake_extraction_chain()
    )
    req = GenerateMassingRequest.model_validate(
        {"requirements": "연구소, 대지면적 14000㎡, 연면적 31000㎡, Middle Lab 5680㎡"}
    )

    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)

    # Same KFI-shaped envelope as the doc path (fixed chain drives both).
    assert resp.result.floor_count == 4
    assert resp.result.basement_levels == 1
    assert resp.result.brief_title == "매싱 요청"  # inline generic title (D4)
    assert resp.artifact.storage_key.endswith(".3dm")
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd backend/fastapi/agent-tools && pytest tests/test_workflow.py::test_inline_requirements_end_to_end_without_docs -v`
Expected: PASS. (The `_fake_extraction_chain` returns `_FIXED_ANALYSIS` with `site_area_m2=14000`, so the pipeline resolves; `brief_title` comes from the synthesized `detail.title` = `"매싱 요청"`.)

> If this FAILS on `brief_title`, confirm the constant in `fetch_brief.py` is exactly `"매싱 요청"` (Task 2, `_INLINE_BRIEF_TITLE`) — the two must match.

- [ ] **Step 3: Run the full agent-tools suite (regression gate)**

Run: `cd backend/fastapi/agent-tools && pytest -q`
Expected: all pass (baseline ~106 + 9 new tests).

- [ ] **Step 4: Commit**

```bash
git add backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "test(agent-tools): inline massing end-to-end produces envelope without docs-api"
```

---

## Task 5: chat — `MassingTool` schema + description

**Files:**
- Modify: `backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/MassingTool.java:36-87`
- Test: `backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/tool/MassingToolTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/tool/MassingToolTest.java`:

```java
package com.playground.chat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SP4 — generate_massing accepts EITHER an uploaded brief (briefDocId) OR a
 * conversation-synthesized program (requirements). The schema must declare both
 * as optional (exactly-one is enforced server-side by agent-tools, SP4 D1) and
 * the description must steer the model to ask for the site area when unknown.
 */
class MassingToolTest {

    private static final ToolDescriptor MASSING = MassingTool.MASSING;

    @Test
    void schema_declares_requirements_property() {
        assertThat(MASSING.parameterSchema()).contains("\"requirements\"");
    }

    @Test
    void schema_retains_briefDocId_property() {
        assertThat(MASSING.parameterSchema()).contains("\"briefDocId\"");
    }

    @Test
    void schema_makes_briefDocId_optional() {
        // No `required` array → both sources optional; exactly-one is enforced
        // by agent-tools (SP4 D1).
        assertThat(MASSING.parameterSchema()).doesNotContain("\"required\":");
    }

    @Test
    void description_mentions_exactly_one_source() {
        assertThat(MASSING.description()).contains("EXACTLY ONE");
    }

    @Test
    void description_instructs_to_ask_for_site_area() {
        assertThat(MASSING.description()).contains("대지면적");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/springboot && ./gradlew :chat:chat-domain:test --tests "com.playground.chat.domain.tool.MassingToolTest"`
Expected: FAIL — current `INPUT_SCHEMA` has no `"requirements"` and still has `"required":["briefDocId"]`; current `DESCRIPTION` has no `"EXACTLY ONE"` and no `대지면적`.

- [ ] **Step 3: Update `INPUT_SCHEMA`**

In `MassingTool.java`, replace the `INPUT_SCHEMA` constant (lines 36-53) with:
```java
    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                    + "\"type\":\"object\","
                    + "\"additionalProperties\":false,"
                    + "\"properties\":{"
                    + "\"briefDocId\":{\"type\":\"string\",\"format\":\"uuid\","
                    + "\"description\":\"docs.documents.id of an uploaded brief PDF to "
                    + "analyze. Use this ONLY when the user refers to an uploaded brief — "
                    + "copy the exact id from the [YOUR DOCUMENTS] list in context that "
                    + "matches the document the user refers to (by ordinal such as "
                    + "'두 번째'/'second', by title, or by type). Never invent a uuid. "
                    + "Mutually exclusive with requirements.\"},"
                    + "\"requirements\":{\"type\":\"string\","
                    + "\"description\":\"Free-text space program synthesized from the "
                    + "conversation — room types, areas (㎡), site area (대지면적), floor "
                    + "count, etc. Use this when the user describes the program in chat "
                    + "instead of pointing to an uploaded brief. Mutually exclusive with "
                    + "briefDocId.\"},"
                    + "\"siteWidth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site width in metres (overrides brief-extracted value)\"},"
                    + "\"siteDepth\":{\"type\":\"number\",\"exclusiveMinimum\":0,"
                    + "\"description\":\"site depth in metres (overrides brief-extracted value)\"},"
                    + "\"floorHeight\":{\"type\":\"number\",\"exclusiveMinimum\":0,\"default\":3.5,"
                    + "\"description\":\"per-floor height in metres (defaults to 3.5)\"}"
                    + "}}";
```
(The `"required":["briefDocId"],` line is removed — no `required` array means all properties are optional. `additionalProperties:false` is retained.)

- [ ] **Step 4: Update `DESCRIPTION`**

In `MassingTool.java`, replace the `DESCRIPTION` constant (lines 55-87) with:
```java
    private static final String DESCRIPTION =
            "Build a stacked rectangular massing model (.3dm Rhino file) from "
                    + "a building's room program.\n\n"
                    + "STRICT TRIGGER CRITERIA — invoke this tool ONLY when the "
                    + "user's most recent message explicitly requests a massing "
                    + "/ 매싱 / 매스 / mass / 매스모델 / .3dm be CREATED, "
                    + "GENERATED, or BUILT.\n\n"
                    + "PROGRAM SOURCE — provide EXACTLY ONE of:\n"
                    + "- briefDocId: the user refers to an uploaded brief PDF "
                    + "(match it in the [YOUR DOCUMENTS] list), or\n"
                    + "- requirements: the user describes the program in the "
                    + "conversation (room types, areas, site area, floor count) — "
                    + "synthesize that into a free-text string.\n"
                    + "Never send both. The site area (대지면적) is required to "
                    + "size the building: if it is unknown, ASK THE USER for it "
                    + "BEFORE invoking this tool — do not invent it.\n\n"
                    + "REGENERATION COUNTS: a request to generate AGAIN (\"다시 "
                    + "생성해줘\", \"재생성\", \"다시 만들어줘\", \"regenerate\", "
                    + "\"one more time\") MUST invoke this tool again in the "
                    + "current turn. A massing result already present in the "
                    + "conversation history NEVER satisfies a new generation "
                    + "request — do not answer from history, and do not claim "
                    + "the model was (re)generated unless this tool ran in "
                    + "this turn.\n\n"
                    + "DO NOT invoke this tool for any of the following, even if "
                    + "the conversation mentions a brief or document:\n"
                    + "- Questions about the brief content (\"what does it say\", "
                    + "  \"실별 크기 적혀있나\", \"요구사항이 뭐야\")\n"
                    + "- Summarization or extraction requests that do NOT mention "
                    + "  massing (\"실 프로그램 추출해줘\", \"요약해줘\")\n"
                    + "- Casual conversation, meta-questions, follow-ups, "
                    + "  acknowledgements (\"야\", \"응\", \"맞아\")\n"
                    + "- General questions about Korean architecture briefs that "
                    + "  don't request a massing artifact\n\n"
                    + "When the user does explicitly request a massing, the tool "
                    + "extracts the room program (rooms with areas in m²) from the "
                    + "chosen source and computes a stacked rectangular massing. "
                    + "The tool result contains a one-line Korean summary "
                    + "(e.g., \"12실 · 3층 · 총 480 m²\"). "
                    + "The .3dm file is delivered via a download button in the UI automatically — "
                    + "DO NOT write any URL, file link, or download path in your text response. "
                    + "Just relay the summary from the tool result and confirm completion.";
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend/springboot && ./gradlew :chat:chat-domain:test --tests "com.playground.chat.domain.tool.MassingToolTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Run the chat-domain tool suite (regression — ToolCatalog/ToolDescriptor still green)**

Run: `cd backend/springboot && ./gradlew :chat:chat-domain:test`
Expected: BUILD SUCCESSFUL — `ToolCatalogTest.registers_massingTool_postM8` and `ToolDescriptorTest` unaffected (descriptor `name`/`displayName`/timeouts unchanged).

- [ ] **Step 7: Commit**

```bash
git add backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/MassingTool.java \
        backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/tool/MassingToolTest.java
git commit -m "feat(chat): generate_massing accepts inline requirements (briefDocId|requirements)"
```

---

## Task 6: Full verification + spec implementation note

**Files:**
- Modify: `docs/superpowers/specs/2026-06-09-sp4-conversational-massing-design.md` (append a one-line implementation note)

- [ ] **Step 1: Full agent-tools suite**

Run: `cd backend/fastapi/agent-tools && pytest -q`
Expected: all pass.

- [ ] **Step 2: Full chat build + test**

Run: `cd backend/springboot && ./gradlew :chat:chat-domain:test :chat:chat-app:test :chat:chat-infra:test`
Expected: BUILD SUCCESSFUL (chat-app/chat-infra unchanged but verifies the descriptor change doesn't break tool-calling tests).

- [ ] **Step 3: Rebuild the affected containers and smoke-check they come up**

Run (from the worktree, with `infra/.env` seeded):
```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --build agent-tools chat-api
```
Expected: both containers healthy. (`agent-tools` picks up the new DTO/node; `chat-api` picks up the new descriptor.)

Smoke-check the tool endpoint rejects an empty body with 422 (exactly-one):
```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T agent-tools \
  python -c "from architecture.api.dtos import GenerateMassingRequest; \
import pydantic; \
GenerateMassingRequest.model_validate({})"
```
Expected: raises `pydantic.ValidationError` (non-zero exit) — confirms exactly-one is live in the built image.

- [ ] **Step 4: Append implementation note to the spec**

Add to the end of `docs/superpowers/specs/2026-06-09-sp4-conversational-massing-design.md`:
```markdown

---

## Implementation note (2026-06-09)

Implemented per this spec. Source seam = `fetch_brief` (synthesizes
`DocsDetailSubset` from `requirements`); exactly-one enforced by
`GenerateMassingRequest._exactly_one_source` (blank requirements normalized to
None); inline path fails fast on missing site area in
`program_resolution._route`. chat `MassingTool` schema/description carry both
sources. FE unchanged (D6). ADR-19 not amended (this spec is the record).
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-06-09-sp4-conversational-massing-design.md
git commit -m "docs(sp4): implementation note on conversational massing"
```

---

## Done criteria

- `generate_massing` accepts `{requirements}` OR `{briefDocId}` (not both, not neither — 422 otherwise).
- Inline path produces the same `.3dm`/`.glb` + result card as the doc path (FE unchanged).
- Missing site area on the inline path fails fast (no re-extraction) with `BRIEF_NOT_READY`, prompting the LLM to ask the user.
- Doc path is byte-for-byte unchanged (no regression).
- Full agent-tools + chat-domain suites green.

## Manual E2E (user, after merge)

1. New chat → "도서관 매싱 만들어줘. 대지 4200㎡, 연면적 9800㎡, 일반열람실 2400㎡, 3층" → expect a massing result card + preview + download (inline path).
2. New chat → "도서관 매싱 만들어줘. 연면적 9800, 3층" (no site area) → expect the assistant to ask for 대지면적 rather than erroring out.
3. Regression: existing "이 브리프로 매싱 생성해줘" (uploaded PDF) still works.
