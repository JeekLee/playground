# 멀티-턴 매싱 리파인 (refine_massing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `refine_massing` tool that edits a previously generated massing (.3dm) — "2층을 노트북 열람실로", "3층으로 줄여", "열람실 3000으로" — by replaying the deterministic massing algorithm on the prior program with typed edits applied.

**Architecture:** At generation time, embed a re-derivable recipe (`NormalizedBrief` + floor height + floor count + brief title) into the preview `.glb` extras. `refine_massing` loads that recipe from MinIO, applies typed edit operations, and re-runs the pipeline from `classify` onward (skipping the slow LLM extraction) — so 건폐율/용적률/footprint/slot-fit gates all re-apply. chat injects a `[YOUR MODELS]` manifest of the session's model attachments, the LLM picks a `baseAttachmentId`, and chat resolves+validates it to a `baseStorageKey` before dispatch (the LLM never sees internal keys).

**Tech Stack:** Python 3.12 / FastAPI / LangGraph / Pydantic v2 / trimesh / minio (agent-tools `architecture` BC). Java 21 / Spring Boot / JDBC (chat). Next.js/TS (FE one line).

**Spec:** `docs/superpowers/specs/2026-06-09-massing-refine-design.md`

**Tooling note:** agent-tools is **uv-managed** — run Python tests with `uv run pytest ...` from `backend/fastapi/agent-tools/` (no bare pytest; `.venv` is materialized). chat tests: `cd backend/springboot && ./gradlew ...`.

---

## File Structure

**agent-tools (`backend/fastapi/agent-tools/`)**
- Modify `shared_kernel/errors.py` — add `RECIPE_NOT_FOUND`, `REFINE_TARGET_NOT_FOUND` (422).
- Modify `architecture/infra/blob_storage.py` — add `download_from_key(key, settings) -> bytes`.
- Modify `architecture/infra/glb_serializer.py` — `serialize_glb` gains `refine_recipe` kwarg; add `read_glb_extras(glb_bytes) -> dict`.
- Create `architecture/app/refine_recipe.py` — `RefineRecipe` Pydantic model, `RefineDeriveReq` dataclass, `RECIPE_KEY`.
- Create `architecture/app/edits.py` — `EditOp` discriminated union (RenameRoom/AddRoom/SetFloors/SetArea) + `apply_edits(...)` applier.
- Create `architecture/app/nodes/load_recipe.py` — `make_load_recipe_node`.
- Create `architecture/app/nodes/apply_edits.py` — `apply_edits` graph node.
- Modify `architecture/app/graphs/program_resolution.py` — surface `normalized`+`classified` from the `resolve_program` wrapper.
- Modify `architecture/app/nodes/store_glb.py` — build + embed `refineRecipe` (shared by generate & refine).
- Modify `architecture/app/stages.py` — factory + `REFINE_STAGES` + `refine_progress_event`.
- Modify `architecture/app/state.py` — add `base_storage_key`, `edits`, `target_floors_above`, `floor_height_m` channels.
- Create `architecture/app/refine_workflow.py` — `RefineMassingWorkflow`.
- Modify `architecture/api/dtos.py` — `RefineMassingRequest`.
- Modify `architecture/api/deps.py` — `get_refine_workflow` + `RefineWorkflowDep`.
- Modify `architecture/api/routers/tools.py` — `POST /refine-massing` route.
- Modify `main.py` — construct + cache `app.state.refine_workflow`.
- Tests: `tests/test_edits.py`, `tests/test_load_recipe.py`, `tests/test_refine_workflow.py`, extend `tests/test_workflow.py` (recipe embed).

**chat (`backend/springboot/chat/`)**
- Create `chat-domain/.../tool/RefineMassingTool.java` + modify `chat-domain/.../tool/ToolCatalog.java`.
- Create `chat-domain/.../model/UserModelRef.java`.
- Modify `chat-app/.../repository/AttachmentRepository.java` + `chat-infra/.../persistence/AttachmentRepositoryJdbcAdapter.java` — `findModelAttachments`.
- Modify `chat-domain/.../service/PromptTemplate.java` — 4th `assemble` param + `[YOUR MODELS]` block.
- Modify `chat-app/.../service/ChatTurnService.java` — model-manifest fetch/inject + `baseAttachmentId` resolve/transform in `handleToolInvocation`.
- Tests: `RefineMassingToolTest.java`, `PromptTemplateTest` (model block), `ChatTurnService` transform test, `AttachmentRepository` query test.

**FE**
- Modify `frontend/src/features/chat-tool-card/ToolCardList.tsx` — widen the tool-name gate (one line).

---

## Pre-flight (once, before Task 1)

- [ ] **Create the worktree:** `EnterWorktree({ name: "massing-refine" })`
- [ ] **Seed compose env:** `cp infra/.env <worktree>/infra/.env`
- [ ] **Baseline green:**
  - `cd backend/fastapi/agent-tools && uv sync --extra test && uv run pytest -q` → all pass (~116).
  - `cd backend/springboot && ./gradlew :chat:chat-domain:test` → BUILD SUCCESSFUL.

---

## Task 1: agent-tools — new error codes

**Files:**
- Modify: `backend/fastapi/agent-tools/shared_kernel/errors.py`
- Test: `backend/fastapi/agent-tools/tests/test_errors.py`

- [ ] **Step 1: Add the failing test** — append to `tests/test_errors.py`:

```python
def test_refine_error_codes_map_to_422():
    from http import HTTPStatus
    from shared_kernel.errors import MassingErrorCode
    assert MassingErrorCode.RECIPE_NOT_FOUND.http_status == HTTPStatus.UNPROCESSABLE_ENTITY
    assert MassingErrorCode.REFINE_TARGET_NOT_FOUND.http_status == HTTPStatus.UNPROCESSABLE_ENTITY
```

- [ ] **Step 2: Run, expect fail** — `cd backend/fastapi/agent-tools && uv run pytest tests/test_errors.py -q` → FAIL (`AttributeError: RECIPE_NOT_FOUND`).

- [ ] **Step 3: Implement** — in `shared_kernel/errors.py`, add to the `MassingErrorCode` enum (after `MASSING_ALGORITHM_FAILED`):

```python
    RECIPE_NOT_FOUND = "RECIPE_NOT_FOUND"
    REFINE_TARGET_NOT_FOUND = "REFINE_TARGET_NOT_FOUND"
```

and add to the `_HTTP_STATUS_BY_CODE` dict:

```python
    MassingErrorCode.RECIPE_NOT_FOUND: HTTPStatus.UNPROCESSABLE_ENTITY,
    MassingErrorCode.REFINE_TARGET_NOT_FOUND: HTTPStatus.UNPROCESSABLE_ENTITY,
```

- [ ] **Step 4: Run, expect pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/shared_kernel/errors.py backend/fastapi/agent-tools/tests/test_errors.py
git commit -m "feat(agent-tools): RECIPE_NOT_FOUND + REFINE_TARGET_NOT_FOUND error codes"
```

---

## Task 2: agent-tools — MinIO download-by-key

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/infra/blob_storage.py`
- Test: `backend/fastapi/agent-tools/tests/test_blob_storage.py` (create)

agent-tools is currently write-only to MinIO (`upload_artifact`, `upload_to_key`). Refine must read the `.glb` back; add a download helper next to the uploaders (same module owns the `_get_client` singleton).

- [ ] **Step 1: Write the failing test** — create `tests/test_blob_storage.py`:

```python
"""download_from_key — read an object back from MinIO (refine recipe path)."""
from __future__ import annotations

import io

from architecture.infra import blob_storage
from shared_kernel.config import Settings


class _FakeResp:
    def __init__(self, data: bytes):
        self._data = data
        self.closed = False
        self.released = False

    def read(self) -> bytes:
        return self._data

    def close(self) -> None:
        self.closed = True

    def release_conn(self) -> None:
        self.released = True


class _FakeClient:
    def __init__(self, data: bytes):
        self._resp = _FakeResp(data)
        self.calls: list[tuple[str, str]] = []

    def get_object(self, bucket_name, object_name):
        self.calls.append((bucket_name, object_name))
        return self._resp


def test_download_from_key_reads_and_closes(monkeypatch):
    fake = _FakeClient(b"GLBBYTES")
    monkeypatch.setattr(blob_storage, "_get_client", lambda settings: (fake, "playground"))
    out = blob_storage.download_from_key("architecture/massing/x/y.glb", Settings())
    assert out == b"GLBBYTES"
    assert fake.calls == [("playground", "architecture/massing/x/y.glb")]
    assert fake._resp.closed and fake._resp.released
```

- [ ] **Step 2: Run, expect fail** — `uv run pytest tests/test_blob_storage.py -q` → FAIL (`AttributeError: download_from_key`).

- [ ] **Step 3: Implement** — append to `architecture/infra/blob_storage.py` (after `upload_to_key`):

```python
def download_from_key(key: str, settings: Settings) -> bytes:
    """Download a MinIO object by its exact key and return the bytes.

    Inverse of ``upload_to_key``. Raises the minio client error (e.g. S3Error
    NoSuchKey) when the object is absent — callers map that to a domain error.
    """
    client, bucket = _get_client(settings)
    resp = client.get_object(bucket_name=bucket, object_name=key)
    try:
        return resp.read()
    finally:
        resp.close()
        resp.release_conn()
```

- [ ] **Step 4: Run, expect pass** → PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/infra/blob_storage.py backend/fastapi/agent-tools/tests/test_blob_storage.py
git commit -m "feat(agent-tools): download_from_key MinIO reader for refine"
```

---

## Task 3: agent-tools — RefineRecipe model + glb extras read/write

**Files:**
- Create: `backend/fastapi/agent-tools/architecture/app/refine_recipe.py`
- Modify: `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py`
- Test: `backend/fastapi/agent-tools/tests/test_glb_serializer.py` (append)

- [ ] **Step 1: Create the recipe model** — `architecture/app/refine_recipe.py`:

```python
"""Refine recipe — the re-derivable program embedded in a massing .glb's extras.

Generation embeds this (NormalizedBrief + resolved params) so a later
`refine_massing` can re-run the deterministic algorithm (classify → derive →
compute) on the prior program with typed edits applied — skipping the slow LLM
extraction. See spec D1.
"""

from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel, Field

from architecture.domain.models import NormalizedBrief

# glTF scenes[0].extras key holding the recipe (sibling of "programJson").
RECIPE_KEY = "refineRecipe"


class RefineRecipe(BaseModel):
    """The payload embedded in .glb extras under RECIPE_KEY (internal contract).

    Dumped with ``by_alias=True`` so the embedded keys are camelCase
    (``floorHeightM`` etc.), matching the wire convention; ``populate_by_name``
    lets ``model_validate`` accept them back on the refine read path.
    """

    normalized: NormalizedBrief
    floor_height_m: float = Field(alias="floorHeightM")
    target_floors_above: int = Field(alias="targetFloorsAbove")
    brief_title: str = Field(alias="briefTitle")

    model_config = {"populate_by_name": True}


@dataclass(frozen=True, slots=True)
class RefineDeriveReq:
    """Duck-typed stand-in for GenerateMassingRequest.

    ``derive_inputs`` reads ONLY ``target_floors`` and ``floor_height`` from its
    ``req`` arg. We must NOT reconstruct a GenerateMassingRequest (its SP4
    exactly-one validator would reject a refine call), so the refine graph puts
    this object in ``state["req"]`` instead.
    """

    target_floors: int | None
    floor_height: float | None
```

- [ ] **Step 2: Write the failing test** — append to `tests/test_glb_serializer.py`:

```python
def test_serialize_glb_embeds_refine_recipe_and_reads_back():
    from architecture.infra.glb_serializer import read_glb_extras, serialize_glb
    from architecture.domain.models import RoomBox

    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    recipe = {"normalized": {"zones": [{"name": "z", "area_m2": 100.0, "grade": "above"}]},
              "floor_height_m": 3.5, "target_floors_above": 1, "brief_title": "t"}
    glb = serialize_glb(boxes, program_json={"rooms": []}, refine_recipe=recipe)
    extras = read_glb_extras(glb)
    assert extras["refineRecipe"] == recipe
    assert extras["programJson"] == {"rooms": []}
```

> `RoomBox` is a frozen dataclass in `architecture/domain/models.py` (name, zone, floor:int, x,y,z, width, depth, height). Confirm field names against that file when writing the fixture.

- [ ] **Step 3: Run, expect fail** — `uv run pytest tests/test_glb_serializer.py::test_serialize_glb_embeds_refine_recipe_and_reads_back -q` → FAIL (unexpected `refine_recipe` kwarg / no `read_glb_extras`).

- [ ] **Step 4: Implement** — in `architecture/infra/glb_serializer.py`:

(a) widen the `serialize_glb` signature and embed the recipe. Change the signature line:
```python
def serialize_glb(
    boxes: list[RoomBox],
    *,
    program_json: dict | None = None,
    refine_recipe: dict | None = None,
) -> bytes:
```
and immediately after the existing `scene.metadata["programJson"] = program_json` block (right before `return bytes(scene.export(...))`), add:
```python
    if refine_recipe is not None:
        # glTF 2.0 extras sibling of programJson — the re-derivable recipe a
        # later refine_massing reads back (spec D1). Invisible to glb viewers.
        scene.metadata["refineRecipe"] = refine_recipe
```

(b) add a reader (inverse of the embed) at the end of the module:
```python
def read_glb_extras(glb_bytes: bytes) -> dict:
    """Parse scenes[0].extras from a .glb produced by serialize_glb.

    Minimal glTF-Binary reader: 12-byte header + length-prefixed chunks; chunk0
    is JSON. Returns the extras dict ({} when absent). Raises ValueError on a
    non-glTF / malformed container so callers can map it to a domain error.
    """
    import json
    import struct

    if len(glb_bytes) < 20:
        raise ValueError("glb too small")
    magic, version, _length = struct.unpack("<III", glb_bytes[:12])
    if magic != 0x46546C67:  # "glTF"
        raise ValueError("not a glTF container")
    if version != 2:
        raise ValueError(f"unsupported glTF version {version}")
    json_len = struct.unpack("<I", glb_bytes[12:16])[0]
    chunk_type = struct.unpack("<I", glb_bytes[16:20])[0]
    if chunk_type != 0x4E4F534A:  # "JSON"
        raise ValueError("chunk0 is not JSON")
    if 20 + json_len > len(glb_bytes):
        raise ValueError("declared JSON chunk exceeds buffer")
    doc = json.loads(glb_bytes[20 : 20 + json_len].decode("utf-8"))
    scenes = doc.get("scenes") or [{}]
    return scenes[0].get("extras") or {}
```

- [ ] **Step 5: Run, expect pass** → PASS. Also run the existing glb test file to confirm no regression: `uv run pytest tests/test_glb_serializer.py -q`.

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/refine_recipe.py \
        backend/fastapi/agent-tools/architecture/infra/glb_serializer.py \
        backend/fastapi/agent-tools/tests/test_glb_serializer.py
git commit -m "feat(agent-tools): RefineRecipe model + glb extras embed/read"
```

---

## Task 4: agent-tools — edit operations + applier

**Files:**
- Create: `backend/fastapi/agent-tools/architecture/app/edits.py`
- Test: `backend/fastapi/agent-tools/tests/test_edits.py` (create)

The four typed edits operate on a `NormalizedBrief` (gross zones + named sub-spaces). `SetFloors` is the only one that changes the floor-count param rather than the program.

- [ ] **Step 1: Write the failing tests** — create `tests/test_edits.py`:

```python
"""Edit-op applier (spec D3) — typed ops mutate a NormalizedBrief + floor count."""
from __future__ import annotations

import pytest

from architecture.app.edits import apply_edits, EditOp
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem
from shared_kernel.errors import MassingError, MassingErrorCode

from pydantic import TypeAdapter

_OPS = TypeAdapter(list[EditOp])


def _nb() -> NormalizedBrief:
    return NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0,
        coverage_ratio_max=0.6,
        total_gfa_m2=9000.0,
    )


def test_rename_room():
    nb, tfa = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "RenameRoom", "from": "일반열람실", "to": "멀티미디어실"}]))
    assert [s.name for s in nb.sub_spaces] == ["멀티미디어실"]
    assert tfa == 3


def test_add_room_appends_above_grade_net_subspace():
    nb, _ = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "AddRoom", "name": "노트북 열람실", "areaM2": 2450.0, "zone": "지상"}]))
    added = [s for s in nb.sub_spaces if s.name == "노트북 열람실"][0]
    assert added.area_m2 == 2450.0 and added.grade == "above" and added.is_net is True


def test_set_floors_changes_count_only():
    nb, tfa = apply_edits(_nb(), 4, _OPS.validate_python(
        [{"op": "SetFloors", "targetFloorsAbove": 2}]))
    assert tfa == 2
    assert nb.zones[0].area_m2 == 9000.0  # program unchanged


def test_set_area_on_zone_and_room():
    nb, _ = apply_edits(_nb(), 3, _OPS.validate_python(
        [{"op": "SetArea", "target": "지상", "areaM2": 12000.0},
         {"op": "SetArea", "target": "일반열람실", "areaM2": 3000.0}]))
    assert nb.zones[0].area_m2 == 12000.0
    assert [s for s in nb.sub_spaces if s.name == "일반열람실"][0].area_m2 == 3000.0


def test_rename_missing_target_raises():
    with pytest.raises(MassingError) as ei:
        apply_edits(_nb(), 3, _OPS.validate_python(
            [{"op": "RenameRoom", "from": "없는방", "to": "x"}]))
    assert ei.value.code == MassingErrorCode.REFINE_TARGET_NOT_FOUND


def test_set_area_missing_target_raises():
    with pytest.raises(MassingError) as ei:
        apply_edits(_nb(), 3, _OPS.validate_python(
            [{"op": "SetArea", "target": "없는것", "areaM2": 100.0}]))
    assert ei.value.code == MassingErrorCode.REFINE_TARGET_NOT_FOUND


def test_edits_do_not_mutate_input():
    original = _nb()
    apply_edits(original, 3, _OPS.validate_python(
        [{"op": "SetArea", "target": "지상", "areaM2": 1.0}]))
    assert original.zones[0].area_m2 == 9000.0  # deep-copied
```

- [ ] **Step 2: Run, expect fail** — `uv run pytest tests/test_edits.py -q` → FAIL (no `architecture.app.edits`).

- [ ] **Step 3: Implement** — create `architecture/app/edits.py`:

```python
"""Typed massing-refine edit operations + deterministic applier (spec D3).

The LLM maps a natural-language refine request to a list of these ops (the
chat schema declares the `op` discriminator + the union of fields loosely);
agent-tools validates strictly via the Pydantic discriminated union and applies
them to a NormalizedBrief. SetFloors changes the floor-count param; the other
three edit the program (sub-spaces / zone areas), after which classify → derive
recompute footprint driver, grade, and the 건폐율/용적률/slot-fit gates.
"""

from __future__ import annotations

from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field

from architecture.domain.models import NormalizedBrief, ProgramItem
from shared_kernel.errors import MassingError, MassingErrorCode


class RenameRoom(BaseModel):
    op: Literal["RenameRoom"]
    # `from` is a Python keyword → field name name_from, wire alias "from".
    name_from: str = Field(alias="from", min_length=1)
    name_to: str = Field(alias="to", min_length=1)
    model_config = {"populate_by_name": True}


class AddRoom(BaseModel):
    op: Literal["AddRoom"]
    name: str = Field(min_length=1)
    area_m2: float = Field(alias="areaM2", gt=0)
    zone: str | None = None
    model_config = {"populate_by_name": True}


class SetFloors(BaseModel):
    op: Literal["SetFloors"]
    target_floors_above: int = Field(alias="targetFloorsAbove", ge=1)
    model_config = {"populate_by_name": True}


class SetArea(BaseModel):
    op: Literal["SetArea"]
    target: str = Field(min_length=1)
    area_m2: float = Field(alias="areaM2", gt=0)
    model_config = {"populate_by_name": True}


EditOp = Annotated[
    Union[RenameRoom, AddRoom, SetFloors, SetArea],
    Field(discriminator="op"),
]


def apply_edits(
    normalized: NormalizedBrief,
    target_floors_above: int,
    edits: list[EditOp],
) -> tuple[NormalizedBrief, int]:
    """Apply edit ops to a (NormalizedBrief, floor-count) pair.

    Pure: deep-copies the brief, never mutates the input. Raises
    MassingError(REFINE_TARGET_NOT_FOUND) when a rename/resize names a room or
    zone that is not present. Returns the edited brief + (possibly changed)
    above-grade floor count.
    """
    nb = normalized.model_copy(deep=True)
    tfa = target_floors_above
    for op in edits:
        if isinstance(op, RenameRoom):
            matched = [s for s in nb.sub_spaces if s.name == op.name_from]
            if not matched:
                raise MassingError(
                    MassingErrorCode.REFINE_TARGET_NOT_FOUND,
                    f"수정할 실 '{op.name_from}'을(를) 찾을 수 없습니다",
                )
            for s in matched:
                s.name = op.name_to
        elif isinstance(op, AddRoom):
            nb.sub_spaces.append(
                ProgramItem(
                    name=op.name,
                    area_m2=op.area_m2,
                    grade="above",
                    parent_zone=op.zone,
                    is_net=True,
                )
            )
        elif isinstance(op, SetFloors):
            tfa = op.target_floors_above
        elif isinstance(op, SetArea):
            zmatch = [z for z in nb.zones if z.name == op.target]
            smatch = [s for s in nb.sub_spaces if s.name == op.target]
            if not zmatch and not smatch:
                raise MassingError(
                    MassingErrorCode.REFINE_TARGET_NOT_FOUND,
                    f"면적을 바꿀 대상 '{op.target}'을(를) 존(zone)·실에서 찾을 수 없습니다",
                )
            for z in zmatch:
                z.area_m2 = op.area_m2
            for s in smatch:
                s.area_m2 = op.area_m2
    return nb, tfa
```

- [ ] **Step 4: Run, expect pass** → all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/edits.py backend/fastapi/agent-tools/tests/test_edits.py
git commit -m "feat(agent-tools): typed refine edit ops + applier on NormalizedBrief"
```

---

## Task 5: agent-tools — embed recipe at generation (store_glb + surface normalized)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/graphs/program_resolution.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/store_glb.py`
- Test: `backend/fastapi/agent-tools/tests/test_workflow.py` (append)

The generate path's `resolve_program` wrapper currently surfaces only `analysis`+`inputs`; `store_glb` needs `normalized` to build the recipe.

- [ ] **Step 1: Write the failing test** — append to `tests/test_workflow.py`:

```python
def test_generate_embeds_refine_recipe_in_glb(monkeypatch):
    # The generate path embeds a refineRecipe (NormalizedBrief + params) in the
    # preview .glb extras so a later refine can replay the algorithm.
    from architecture.infra.glb_serializer import read_glb_extras

    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260609/test-uuid/{filename}",
    )
    captured: dict = {}
    monkeypatch.setattr(
        "architecture.app.nodes.store_glb.upload_to_key",
        lambda file_bytes, key, content_type, settings: captured.update(glb=file_bytes),
    )
    flow = _build_workflow()
    req = GenerateMassingRequest.model_validate(
        {"briefDocId": "11111111-1111-1111-1111-111111111111"}
    )
    flow.run(req, user_id=uuid.uuid4(), user_sub=None)

    extras = read_glb_extras(captured["glb"])
    recipe = extras["refineRecipe"]
    assert recipe["targetFloorsAbove"] == 4          # KFI fixture → 4 above-grade floors
    assert recipe["briefTitle"] == "KFI 테스트 브리프"
    assert recipe["normalized"]["zones"]              # NormalizedBrief survived
```

> `RefineRecipe.model_dump(by_alias=True)` yields camelCase keys (`targetFloorsAbove`, `floorHeightM`, `briefTitle`) — match those in the assertions. (We dump with `by_alias=True` in store_glb; see Step 3.) `NormalizedBrief` field names stay snake_case under `normalized`.

- [ ] **Step 2: Run, expect fail** — `uv run pytest tests/test_workflow.py::test_generate_embeds_refine_recipe_in_glb -q` → FAIL (`KeyError: 'refineRecipe'`).

- [ ] **Step 3: Implement.**

(a) In `architecture/app/graphs/program_resolution.py`, the wrapper `resolve_program` currently returns only `analysis`+`inputs`. Change its return to also surface `normalized` (and `classified`):

```python
    def resolve_program(state: MassingState) -> dict:
        out = sub.invoke(state)
        return {
            "analysis": out["analysis"],
            "normalized": out["normalized"],
            "classified": out["classified"],
            "inputs": out["inputs"],
        }
```

(b) In `architecture/app/nodes/store_glb.py`, build + embed the recipe. Replace the body of `store_glb` with:

```python
def store_glb(state: MassingState) -> dict:
    try:
        storage_key = state["storage_key"]
        if not storage_key.endswith(".3dm"):
            logger.warning(
                "store_glb: unexpected storage_key %s — skipping preview", storage_key
            )
            return {}
        glb_key = storage_key[: -len(".3dm")] + ".glb"
        program_json = build_program_json(state["boxes"], state["inputs"]).model_dump(
            by_alias=True, mode="json"
        )
        refine_recipe = RefineRecipe(
            normalized=state["normalized"],
            floor_height_m=state["inputs"].floor_height_m,
            target_floors_above=state["inputs"].target_floors_above,
            brief_title=state["detail"].title,
        ).model_dump(by_alias=True, mode="json")
        glb_bytes = serialize_glb(
            state["boxes"], program_json=program_json, refine_recipe=refine_recipe
        )
        upload_to_key(
            file_bytes=glb_bytes,
            key=glb_key,
            content_type="model/gltf-binary",
            settings=get_settings(),
        )
        logger.info(
            "store_glb node: uploaded preview %s (%d bytes)", glb_key, len(glb_bytes)
        )
    except Exception:  # noqa: BLE001 — preview must never sink the turn
        logger.warning("store_glb failed — preview unavailable, continuing", exc_info=True)
    return {}
```
and add the import at the top: `from architecture.app.refine_recipe import RefineRecipe`.

- [ ] **Step 4: Run, expect pass** → PASS. Run the whole workflow file for regression: `uv run pytest tests/test_workflow.py -q`.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/graphs/program_resolution.py \
        backend/fastapi/agent-tools/architecture/app/nodes/store_glb.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): embed refineRecipe (NormalizedBrief) in generated .glb"
```

---

## Task 6: agent-tools — load_recipe + apply_edits nodes + state channels

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/state.py`
- Create: `backend/fastapi/agent-tools/architecture/app/nodes/load_recipe.py`
- Create: `backend/fastapi/agent-tools/architecture/app/nodes/apply_edits.py`
- Test: `backend/fastapi/agent-tools/tests/test_load_recipe.py` (create)

- [ ] **Step 1: Add state channels** — in `architecture/app/state.py`, add to the `MassingState` TypedDict body (after `extract_attempts`):

```python
    # refine-only channels (refine_workflow): the recipe load + edits.
    base_storage_key: str  # refine: the prior massing's .3dm key (chat-resolved)
    edits: list  # refine: list[EditOp] to apply (see architecture.app.edits)
    target_floors_above: int  # refine: carried floor count (SetFloors mutates)
    floor_height_m: float  # refine: carried floor height
```
> `state["req"]` holds a `GenerateMassingRequest` on the generate path and a `RefineDeriveReq` duck-type on the refine path (both expose `target_floors`/`floor_height`, which is all `derive_inputs` reads). The annotation stays `GenerateMassingRequest`; the TypedDict is not runtime-enforced.

- [ ] **Step 2: Write the failing test** — create `tests/test_load_recipe.py`:

```python
"""load_recipe + apply_edits nodes (refine pipeline front)."""
from __future__ import annotations

import pytest

from architecture.app.edits import EditOp
from architecture.app.nodes.apply_edits import apply_edits as apply_edits_node
from architecture.app.nodes.load_recipe import make_load_recipe_node
from architecture.app.refine_recipe import RECIPE_KEY, RefineRecipe
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem
from architecture.infra import blob_storage
from architecture.infra.glb_serializer import read_glb_extras, serialize_glb
from architecture.domain.models import RoomBox
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

from pydantic import TypeAdapter

_OPS = TypeAdapter(list[EditOp])


def _recipe_glb() -> bytes:
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9000.0,
    )
    recipe = RefineRecipe(normalized=nb, floor_height_m=3.5,
                          target_floors_above=4, brief_title="원본 브리프")
    boxes = [RoomBox(name="지상", zone="지상", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    return serialize_glb(boxes, program_json={"rooms": []},
                         refine_recipe=recipe.model_dump(by_alias=True, mode="json"))


def test_load_recipe_populates_state(monkeypatch):
    glb = _recipe_glb()
    monkeypatch.setattr(blob_storage, "download_from_key", lambda key, settings: glb)
    # patch the symbol imported into the node module too:
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: glb)
    node = make_load_recipe_node(Settings())
    out = node({"base_storage_key": "architecture/massing/x/y.3dm"})
    assert out["target_floors_above"] == 4
    assert out["floor_height_m"] == 3.5
    assert out["detail"].title == "원본 브리프"
    assert isinstance(out["normalized"], NormalizedBrief)


def test_load_recipe_missing_recipe_raises(monkeypatch):
    # A .glb with no refineRecipe (legacy model) → RECIPE_NOT_FOUND.
    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=1.0, depth=1.0, height=3.5)]
    legacy = serialize_glb(boxes, program_json={"rooms": []})
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: legacy)
    node = make_load_recipe_node(Settings())
    with pytest.raises(MassingError) as ei:
        node({"base_storage_key": "architecture/massing/x/y.3dm"})
    assert ei.value.code == MassingErrorCode.RECIPE_NOT_FOUND


def test_apply_edits_node_sets_req_and_normalized():
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9000.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9000.0,
    )
    state = {"normalized": nb, "target_floors_above": 4, "floor_height_m": 3.5,
             "edits": _OPS.validate_python([{"op": "SetFloors", "targetFloorsAbove": 3}])}
    out = apply_edits_node(state)
    assert out["target_floors_above"] == 3
    assert out["req"].target_floors == 3 and out["req"].floor_height == 3.5
```

- [ ] **Step 3: Run, expect fail** — `uv run pytest tests/test_load_recipe.py -q` → FAIL (no node modules).

- [ ] **Step 4: Implement.**

Create `architecture/app/nodes/load_recipe.py`:
```python
"""load_recipe node (refine pipeline) — fetch the prior massing's recipe.

Downloads the sibling .glb of the request's base .3dm key, parses the embedded
refineRecipe (spec D1), and seeds the channels classify → derive consume. A
missing/legacy .glb (no recipe) maps to RECIPE_NOT_FOUND so chat's LLM can tell
the user to regenerate instead.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace
from typing import Callable

from architecture.app.refine_recipe import RECIPE_KEY, RefineRecipe
from architecture.app.state import MassingState
from architecture.infra.blob_storage import download_from_key
from architecture.infra.glb_serializer import read_glb_extras
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)


def _glb_key(storage_key: str) -> str:
    if storage_key.endswith(".3dm"):
        return storage_key[: -len(".3dm")] + ".glb"
    return storage_key


def make_load_recipe_node(settings: Settings) -> Callable[[MassingState], dict]:
    def load_recipe(state: MassingState) -> dict:
        base_key = state["base_storage_key"]
        try:
            glb_bytes = download_from_key(_glb_key(base_key), settings)
            extras = read_glb_extras(glb_bytes)
            recipe = RefineRecipe.model_validate(extras[RECIPE_KEY])
        except MassingError:
            raise
        except Exception as exc:  # noqa: BLE001 — any read/parse failure = no recipe
            logger.info("refine recipe load failed for %s: %s", base_key, exc)
            raise MassingError(
                MassingErrorCode.RECIPE_NOT_FOUND,
                "이 모델은 수정 정보를 담고 있지 않아 새로 생성해야 합니다",
                cause=exc,
            ) from exc
        return {
            "normalized": recipe.normalized,
            "target_floors_above": recipe.target_floors_above,
            "floor_height_m": recipe.floor_height_m,
            "detail": SimpleNamespace(title=recipe.brief_title),
        }

    return load_recipe
```

Create `architecture/app/nodes/apply_edits.py`:
```python
"""apply_edits node (refine pipeline) — apply typed edits to the loaded recipe.

Mutates the NormalizedBrief + floor count, then writes a RefineDeriveReq into
state["req"] so the reused derive node sizes floors/height correctly. classify
runs next and recomputes the footprint driver + grade from the edited program.
"""

from __future__ import annotations

from architecture.app.edits import apply_edits as _apply_edits
from architecture.app.refine_recipe import RefineDeriveReq
from architecture.app.state import MassingState


def apply_edits(state: MassingState) -> dict:
    edited, tfa = _apply_edits(
        state["normalized"], state["target_floors_above"], state["edits"]
    )
    return {
        "normalized": edited,
        "target_floors_above": tfa,
        "req": RefineDeriveReq(target_floors=tfa, floor_height=state["floor_height_m"]),
    }
```

- [ ] **Step 5: Run, expect pass** → all pass.

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/state.py \
        backend/fastapi/agent-tools/architecture/app/nodes/load_recipe.py \
        backend/fastapi/agent-tools/architecture/app/nodes/apply_edits.py \
        backend/fastapi/agent-tools/tests/test_load_recipe.py
git commit -m "feat(agent-tools): load_recipe + apply_edits refine nodes"
```

---

## Task 7: agent-tools — refine progress stages

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/stages.py`
- Test: `backend/fastapi/agent-tools/tests/test_stages.py` (create)

- [ ] **Step 1: Write the failing test** — create `tests/test_stages.py`:

```python
from architecture.app.stages import (
    REFINE_STAGE_COUNT,
    progress_event,
    refine_progress_event,
)


def test_generate_progress_unchanged():
    ev = progress_event("extract")
    assert ev["stageIndex"] == 3 and ev["stageCount"] == 10
    assert progress_event("respond") is None


def test_refine_progress_sequence():
    assert REFINE_STAGE_COUNT == 8
    assert refine_progress_event("load_recipe")["stageIndex"] == 1
    assert refine_progress_event("store_glb")["stageIndex"] == 8
    assert refine_progress_event("store_glb")["stageCount"] == 8
    assert refine_progress_event("extract") is None  # not a refine stage
    assert refine_progress_event("respond") is None
```

- [ ] **Step 2: Run, expect fail** — `uv run pytest tests/test_stages.py -q` → FAIL (no `refine_progress_event`).

- [ ] **Step 3: Implement** — in `architecture/app/stages.py`, refactor `progress_event` into a factory and add the refine map. Replace everything from the `_INDEX = ...` line through the end of `progress_event` with:

```python
STAGE_COUNT = len(STAGES)

# Refine pipeline (spec D4) — skips fetch_brief/locate/extract/reconcile; the
# LLM extraction is replaced by load_recipe + apply_edits, then classify/derive
# re-run deterministically.
REFINE_STAGES: tuple[tuple[str, str], ...] = (
    ("load_recipe", "기존 매싱 불러오기"),
    ("apply_edits", "수정 반영"),
    ("classify", "공간 분류"),
    ("derive", "층수·풋프린트 재산정"),
    ("compute", "매싱 재계산"),
    ("serialize", "3D 모델 생성"),
    ("store_3dm", "파일 저장"),
    ("store_glb", "미리보기 생성"),
)
REFINE_STAGE_COUNT = len(REFINE_STAGES)


def _make_progress_event(stages: tuple[tuple[str, str], ...]):
    index = {name: i + 1 for i, (name, _) in enumerate(stages)}
    label = dict(stages)
    count = len(stages)

    def progress_event(node: str, attempt: int | None = None) -> dict | None:
        """노드-시작 → progress 이벤트 dict. 맵 밖 노드는 None.

        `attempt`는 2 이상일 때만 필드로 포함 (spec W1)."""
        if node not in index:
            return None
        ev: dict = {
            "event": "progress",
            "stage": node,
            "label": label[node],
            "stageIndex": index[node],
            "stageCount": count,
        }
        if attempt is not None and attempt >= 2:
            ev["attempt"] = attempt
        return ev

    return progress_event


progress_event = _make_progress_event(STAGES)
refine_progress_event = _make_progress_event(REFINE_STAGES)
```

- [ ] **Step 4: Run, expect pass** → PASS. Regression: `uv run pytest tests/test_stream_endpoint.py tests/test_workflow.py -q` (the generate stream test pins `progress_event`).

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/stages.py backend/fastapi/agent-tools/tests/test_stages.py
git commit -m "feat(agent-tools): refine progress stage map + progress-event factory"
```

---

## Task 8: agent-tools — RefineMassingRequest DTO + RefineMassingWorkflow

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/dtos.py`
- Create: `backend/fastapi/agent-tools/architecture/app/refine_workflow.py`
- Test: `backend/fastapi/agent-tools/tests/test_refine_workflow.py` (create)

- [ ] **Step 1: Write the failing test** — create `tests/test_refine_workflow.py`:

```python
"""RefineMassingWorkflow end-to-end (recipe glb → edits → classify→derive→…)."""
from __future__ import annotations

import uuid

import pytest

from architecture.api.dtos import RefineMassingRequest
from architecture.app.refine_recipe import RefineRecipe
from architecture.app.refine_workflow import RefineMassingWorkflow
from architecture.domain.models import NormalizedBrief, NormalizedZone, ProgramItem, RoomBox
from architecture.infra.glb_serializer import serialize_glb
from shared_kernel.config import Settings
from shared_kernel.errors import MassingErrorCode


def _recipe_glb(target_floors=4) -> bytes:
    nb = NormalizedBrief(
        zones=[NormalizedZone(name="지상", area_m2=9800.0, grade="above")],
        sub_spaces=[ProgramItem(name="일반열람실", area_m2=2400.0, grade="above",
                                parent_zone="지상", is_net=True)],
        site_area_m2=9000.0, coverage_ratio_max=0.6, total_gfa_m2=9800.0,
    )
    recipe = RefineRecipe(normalized=nb, floor_height_m=3.5,
                          target_floors_above=target_floors, brief_title="원본")
    boxes = [RoomBox(name="지상", zone="지상", floor=1, x=0.0, y=0.0, z=0.0,
                     width=10.0, depth=10.0, height=3.5)]
    return serialize_glb(boxes, program_json={"rooms": []},
                         refine_recipe=recipe.model_dump(by_alias=True, mode="json"))


def _patch_io(monkeypatch, glb: bytes):
    monkeypatch.setattr("architecture.app.nodes.load_recipe.download_from_key",
                        lambda key, settings: glb)
    monkeypatch.setattr(
        "architecture.app.nodes.store_3dm.upload_artifact",
        lambda file_bytes, filename, content_type, settings:
            f"architecture/massing/20260609/test-uuid/{filename}")
    monkeypatch.setattr("architecture.app.nodes.store_glb.upload_to_key",
                        lambda file_bytes, key, content_type, settings: None)


def test_refine_set_floors_runs_pipeline(monkeypatch):
    _patch_io(monkeypatch, _recipe_glb(target_floors=4))
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]})
    resp = flow.run(req, user_id=uuid.uuid4(), user_sub=None)
    assert resp.result.floor_count == 2
    assert resp.result.brief_title == "원본"
    assert resp.artifact.storage_key.endswith(".3dm")


def test_refine_stream_terminal_result(monkeypatch):
    _patch_io(monkeypatch, _recipe_glb())
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 3}]})
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))
    progress = [e for e in events if e["event"] == "progress"]
    assert [e["stage"] for e in progress] == [
        "load_recipe", "apply_edits", "classify", "derive",
        "compute", "serialize", "store_3dm", "store_glb"]
    assert all(e["stageCount"] == 8 for e in progress)
    assert events[-1]["event"] == "result"


def test_refine_recipe_not_found_streams_error(monkeypatch):
    # legacy .glb with no recipe → error terminal
    boxes = [RoomBox(name="z", zone="z", floor=1, x=0.0, y=0.0, z=0.0,
                     width=1.0, depth=1.0, height=3.5)]
    _patch_io(monkeypatch, serialize_glb(boxes, program_json={"rooms": []}))
    flow = RefineMassingWorkflow(Settings())
    req = RefineMassingRequest.model_validate(
        {"baseStorageKey": "architecture/massing/x/y.3dm",
         "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]})
    events = list(flow.stream(req, user_id=uuid.uuid4(), user_sub=None))
    assert events[-1]["event"] == "error"
    assert events[-1]["code"] == MassingErrorCode.RECIPE_NOT_FOUND.value
    assert events[-1]["status"] == 422
```

- [ ] **Step 2: Run, expect fail** → FAIL (no `RefineMassingRequest` / `RefineMassingWorkflow`).

- [ ] **Step 3: Implement.**

(a) In `architecture/api/dtos.py`, add (after `GenerateMassingResponse`):
```python
class RefineMassingRequest(BaseModel):
    """POST /internal/tools/refine-massing — edit a prior massing.

    `baseStorageKey` is chat-resolved (the LLM passed an attachment id; chat
    mapped it to the prior .3dm's MinIO key). `edits` are the typed ops applied
    to the recipe loaded from that model's .glb.
    """

    base_storage_key: str = Field(alias="baseStorageKey", min_length=1)
    edits: list[EditOp] = Field(min_length=1)

    model_config = {"populate_by_name": True}
```
and add the import at the top of `dtos.py`: `from architecture.app.edits import EditOp`.

(b) Create `architecture/app/refine_workflow.py`:
```python
"""Brief-edit orchestrator — the refine_massing LangGraph (spec D4).

  START → load_recipe → apply_edits → classify → derive → compute
        → serialize → store_3dm → store_glb → respond → END

Reuses classify/derive/compute/serialize/store_3dm/store_glb/respond unchanged;
the only new nodes are load_recipe (fetch + parse the embedded recipe) and
apply_edits (apply the typed ops). The slow LLM extraction (fetch_brief →
classify path of generate) is skipped — refine re-enters at classify with the
edited NormalizedBrief, so the 건폐율/용적률/footprint/slot-fit gates all re-run.
"""

from __future__ import annotations

import logging
from typing import Iterator
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.api.dtos import GenerateMassingResponse, RefineMassingRequest
from architecture.app.nodes.apply_edits import apply_edits
from architecture.app.nodes.classify import classify
from architecture.app.nodes.compute import compute
from architecture.app.nodes.derive import make_derive_node
from architecture.app.nodes.load_recipe import make_load_recipe_node
from architecture.app.nodes.respond import respond
from architecture.app.nodes.serialize import serialize
from architecture.app.nodes.store_3dm import store_3dm
from architecture.app.nodes.store_glb import store_glb
from architecture.app.stages import refine_progress_event
from architecture.app.state import MassingState

logger = logging.getLogger(__name__)


class RefineMassingWorkflow:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._graph = self._build_graph()

    def _build_graph(self):
        g = StateGraph(MassingState)
        g.add_node("load_recipe", make_load_recipe_node(self._settings))
        g.add_node("apply_edits", apply_edits)
        g.add_node("classify", classify)
        g.add_node("derive", make_derive_node(self._settings))
        g.add_node("compute", compute)
        g.add_node("serialize", serialize)
        g.add_node("store_3dm", store_3dm)
        g.add_node("store_glb", store_glb)
        g.add_node("respond", respond)
        g.add_edge(START, "load_recipe")
        g.add_edge("load_recipe", "apply_edits")
        g.add_edge("apply_edits", "classify")
        g.add_edge("classify", "derive")
        g.add_edge("derive", "compute")
        g.add_edge("compute", "serialize")
        g.add_edge("serialize", "store_3dm")
        g.add_edge("store_3dm", "store_glb")
        g.add_edge("store_glb", "respond")
        g.add_edge("respond", END)
        return g.compile()

    def _seed(self, req: RefineMassingRequest, user_id: UUID, user_sub: str | None) -> dict:
        return {
            "base_storage_key": req.base_storage_key,
            "edits": req.edits,
            "user_id": user_id,
            "user_sub": user_sub,
        }

    def run(
        self, req: RefineMassingRequest, *, user_id: UUID, user_sub: str | None
    ) -> GenerateMassingResponse:
        final: MassingState = self._graph.invoke(self._seed(req, user_id, user_sub))
        return final["response"]

    def stream(
        self, req: RefineMassingRequest, *, user_id: UUID, user_sub: str | None
    ) -> Iterator[dict]:
        """Progress + terminal events, mirroring MassingWorkflow.stream but with
        the refine stage map and no extract-attempt special case."""
        last_values: MassingState | None = None
        try:
            for ns, mode, payload in self._graph.stream(
                self._seed(req, user_id, user_sub),
                stream_mode=["debug", "values"],
                subgraphs=True,
            ):
                if mode == "values":
                    if not ns:
                        last_values = payload
                    continue
                if payload.get("type") != "task":
                    continue
                node = payload.get("payload", {}).get("name", "")
                ev = refine_progress_event(node)
                if ev is not None:
                    yield ev
            if last_values is None or "response" not in last_values:
                raise MassingError(
                    MassingErrorCode.INTERNAL, "refine graph finished without a response"
                )
            response: GenerateMassingResponse = last_values["response"]
            yield {"event": "result", **response.model_dump(by_alias=True, mode="json")}
        except MassingError as exc:
            yield {
                "event": "error",
                "code": exc.code.value,
                "message": exc.message,
                "status": int(exc.code.http_status),
            }
        except Exception as exc:  # noqa: BLE001 — always terminate with one event
            logger.exception("refine stream failed")
            yield {
                "event": "error",
                "code": MassingErrorCode.INTERNAL.value,
                "message": str(exc),
                "status": 500,
            }
```

- [ ] **Step 4: Run, expect pass** → all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/dtos.py \
        backend/fastapi/agent-tools/architecture/app/refine_workflow.py \
        backend/fastapi/agent-tools/tests/test_refine_workflow.py
git commit -m "feat(agent-tools): RefineMassingWorkflow + RefineMassingRequest"
```

---

## Task 9: agent-tools — refine route + DI + app wiring

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/deps.py`
- Modify: `backend/fastapi/agent-tools/main.py`
- Modify: `backend/fastapi/agent-tools/architecture/api/routers/tools.py`
- Test: `backend/fastapi/agent-tools/tests/test_stream_endpoint.py` (append)

- [ ] **Step 1: Write the failing test** — append to `tests/test_stream_endpoint.py`:

```python
def test_refine_streams_events_as_ndjson_lines():
    from main import app
    events = [
        {"event": "progress", "stage": "load_recipe", "label": "기존 매싱 불러오기",
         "stageIndex": 1, "stageCount": 8},
        {"event": "result", "result": {"summary": "2실"}, "artifact": {"storageKey": "k.3dm"}},
    ]
    client = TestClient(app)
    client.__enter__()
    app.state.refine_workflow = _StubWorkflow(events)
    app.dependency_overrides[get_settings] = lambda: Settings(stream_heartbeat_seconds=10.0)
    try:
        with client.stream(
            "POST", "/internal/tools/refine-massing",
            json={"baseStorageKey": "architecture/massing/x/y.3dm",
                  "edits": [{"op": "SetFloors", "targetFloorsAbove": 2}]},
            headers={"X-User-Id": str(uuid.uuid4())},
        ) as r:
            assert r.status_code == 200
            assert r.headers["content-type"].startswith("application/x-ndjson")
            lines = [json.loads(line) for line in r.iter_lines() if line]
        assert lines == events
    finally:
        app.dependency_overrides.clear()
        client.__exit__(None, None, None)
```
> `_StubWorkflow` (already in this file) exposes `stream(req, *, user_id, user_sub)` — matches the refine workflow shape, so the same stub works.

- [ ] **Step 2: Run, expect fail** → FAIL (404 — route not registered).

- [ ] **Step 3: Implement.**

(a) `architecture/api/deps.py` — add after `get_workflow`:
```python
def get_refine_workflow(request: Request) -> "RefineMassingWorkflow":
    return request.app.state.refine_workflow


RefineWorkflowDep = Annotated["RefineMassingWorkflow", Depends(get_refine_workflow)]
```
add the import `from architecture.app.refine_workflow import RefineMassingWorkflow` (and replace the forward-ref strings with the type if you import eagerly), and add `"get_refine_workflow"` + `"RefineWorkflowDep"` to `__all__`.

(b) `main.py` lifespan — construct + cache it (after `workflow = MassingWorkflow(...)`):
```python
    refine_workflow = RefineMassingWorkflow(settings)
    app.state.refine_workflow = refine_workflow
```
add the import `from architecture.app.refine_workflow import RefineMassingWorkflow`.

(c) `architecture/api/routers/tools.py` — add the route + import. Add to imports:
```python
from architecture.api.deps import RefineWorkflowDep
from architecture.api.dtos import GenerateMassingRequest, RefineMassingRequest
```
and add the handler (after `generate_massing`):
```python
@router.post("/refine-massing")
async def refine_massing(
    req: RefineMassingRequest,
    user: UserContextDep,
    workflow: RefineWorkflowDep,
    settings: SettingsDep,
) -> StreamingResponse:
    logger.info(
        "refine_massing requested",
        extra={
            "base_storage_key": req.base_storage_key,
            "edits": len(req.edits),
            "user_id": str(user.user_id),
        },
    )
    return StreamingResponse(
        _ndjson_events(workflow, req, user, settings.stream_heartbeat_seconds),
        media_type="application/x-ndjson",
    )
```
> `_ndjson_events` is generic over `workflow.stream(req, user_id=..., user_sub=...)`; `RefineMassingWorkflow.stream` matches, so no change to the bridge.

- [ ] **Step 4: Run, expect pass** → PASS. Full suite: `uv run pytest -q`.

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/deps.py \
        backend/fastapi/agent-tools/main.py \
        backend/fastapi/agent-tools/architecture/api/routers/tools.py \
        backend/fastapi/agent-tools/tests/test_stream_endpoint.py
git commit -m "feat(agent-tools): POST /internal/tools/refine-massing route + wiring"
```

---

## Task 10: chat — RefineMassingTool descriptor

**Files:**
- Create: `backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/RefineMassingTool.java`
- Modify: `backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/ToolCatalog.java`
- Test: `backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/tool/RefineMassingToolTest.java` (create)

- [ ] **Step 1: Write the failing test** — create `RefineMassingToolTest.java`:

```java
package com.playground.chat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefineMassingToolTest {

    private static final ToolDescriptor REFINE = RefineMassingTool.REFINE;

    @Test
    void registered_in_catalog() {
        assertThat(ToolCatalog.descriptors())
                .extracting(ToolDescriptor::name)
                .contains("refine_massing");
    }

    @Test
    void schema_requires_base_attachment_id_and_edits() {
        assertThat(REFINE.parameterSchema()).contains("\"baseAttachmentId\"");
        assertThat(REFINE.parameterSchema()).contains("\"edits\"");
        assertThat(REFINE.parameterSchema()).contains("\"required\":[\"baseAttachmentId\",\"edits\"]");
    }

    @Test
    void description_steers_to_existing_model_only() {
        assertThat(REFINE.description()).contains("EXISTING");
        assertThat(REFINE.description()).contains("generate_massing");
    }

    @Test
    void display_name_and_name() {
        assertThat(REFINE.name()).isEqualTo("refine_massing");
        assertThat(REFINE.displayName()).isEqualTo("매싱 수정");
    }
}
```

- [ ] **Step 2: Run, expect fail** — `cd backend/springboot && ./gradlew :chat:chat-domain:test --tests "com.playground.chat.domain.tool.RefineMassingToolTest"` → FAIL (no `RefineMassingTool`).

- [ ] **Step 3: Implement.**

Create `RefineMassingTool.java`:
```java
package com.playground.chat.domain.tool;

import java.net.URI;
import java.time.Duration;

/**
 * M9 — the {@code refine_massing} tool descriptor. Edits an EXISTING massing
 * (.3dm) generated earlier in the conversation: re-runs the deterministic
 * massing algorithm on the prior program with typed edits applied. Hosted by
 * the {@code architecture} BC on the agent-tools service (ADR-19 §D2),
 * {@code POST /internal/tools/refine-massing} (application/x-ndjson stream).
 *
 * <p>chat injects a {@code [YOUR MODELS]} manifest of the session's model
 * artifacts; the LLM copies the {@code baseAttachmentId} of the model to edit.
 * chat resolves + validates that id to a storage key before dispatch — the LLM
 * never sees internal keys.
 */
public final class RefineMassingTool {

    private static final String INPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","required":["baseAttachmentId","edits"],"additionalProperties":false,
             "properties":{
               "baseAttachmentId":{"type":"string","format":"uuid",
                 "description":"수정할 기존 매싱 모델의 attachment id — [YOUR MODELS] 목록에서 정확히 복사. 절대 지어내지 말 것"},
               "edits":{"type":"array","minItems":1,
                 "description":"적용할 수정 목록 — 사용자가 요청한 변경을 각각 하나의 항목으로",
                 "items":{"type":"object","required":["op"],"additionalProperties":false,
                   "properties":{
                     "op":{"type":"string","enum":["RenameRoom","AddRoom","SetFloors","SetArea"],
                       "description":"수정 종류"},
                     "from":{"type":"string","description":"RenameRoom: 기존 실 이름"},
                     "to":{"type":"string","description":"RenameRoom: 새 실 이름"},
                     "name":{"type":"string","description":"AddRoom: 추가할 실 이름"},
                     "zone":{"type":"string","description":"AddRoom: 소속 존(선택)"},
                     "target":{"type":"string","description":"SetArea: 면적을 바꿀 존 또는 실 이름"},
                     "targetFloorsAbove":{"type":"integer","minimum":1,
                       "description":"SetFloors: 새 지상 층수"},
                     "areaM2":{"type":"number","exclusiveMinimum":0,
                       "description":"AddRoom/SetArea: 면적(㎡)"}}}}}}
            """;

    private static final String DESCRIPTION = """
            Apply edits to an EXISTING massing model (.3dm) and produce a revised \
            version. STRICT TRIGGER: invoke ONLY when the user's most recent message \
            explicitly asks to MODIFY / 수정 / 변경 / 층 추가 / 층 줄여 / 면적 조정 / edit / \
            change a massing that was ALREADY generated in THIS conversation. \
            A brand-new massing uses generate_massing, NOT this tool. \
            Provide baseAttachmentId by copying the exact id of the target model from \
            the [YOUR MODELS] list; if no prior massing exists, DO NOT call this tool — \
            use generate_massing or ask the user. Never invent the id, and never point \
            it at a non-model attachment (document, image). If several models exist and \
            the user is ambiguous, ask which one. \
            Express the change as one or more edits: RenameRoom{from,to}, \
            AddRoom{name,areaM2,zone?}, SetFloors{targetFloorsAbove}, SetArea{target,areaM2}. \
            A regeneration/edit request is only satisfied by invoking this tool in THIS \
            turn — never claim a model was modified from history. The revised .3dm is \
            delivered via a download button in the UI automatically — DO NOT write any \
            URL, file link, or download path in your text response. Relay the tool-result \
            summary and confirm completion.""";

    private static final URI DEFAULT_ENDPOINT =
            URI.create("http://agent-tools:18083/internal/tools/refine-massing");

    public static final ToolDescriptor REFINE = new ToolDescriptor(
            "refine_massing",
            "매싱 수정",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(60),
            Duration.ofSeconds(600));

    private RefineMassingTool() {
        // constants class — instantiation disallowed
    }

    private static URI resolveEndpoint() {
        String override = System.getenv("PLAYGROUND_REFINE_MASSING_TOOL_URL");
        if (override == null || override.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return URI.create(override);
    }
}
```

Modify `ToolCatalog.java` — change the `DESCRIPTORS` list:
```java
    private static final List<ToolDescriptor> DESCRIPTORS =
            List.of(MassingTool.MASSING, SearchTool.SEARCH, RefineMassingTool.REFINE);
```

- [ ] **Step 4: Run, expect pass** → PASS (4 tests). Regression: `./gradlew :chat:chat-domain:test`.

- [ ] **Step 5: Commit**

```bash
git add backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/RefineMassingTool.java \
        backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/tool/ToolCatalog.java \
        backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/tool/RefineMassingToolTest.java
git commit -m "feat(chat): refine_massing tool descriptor + catalog registration"
```

---

## Task 11: chat — session model-attachment query

**Files:**
- Modify: `backend/springboot/chat/chat-app/src/main/java/com/playground/chat/application/repository/AttachmentRepository.java`
- Modify: `backend/springboot/chat/chat-infra/src/main/java/com/playground/chat/infrastructure/persistence/AttachmentRepositoryJdbcAdapter.java`
- Test: `backend/springboot/chat/chat-infra/src/test/java/com/playground/chat/infrastructure/persistence/AttachmentRepositoryJdbcAdapterTest.java` (append or create — match the existing repo test pattern in this package; if none exists, create a `@JdbcTest`-style test using the project's test harness).

- [ ] **Step 1: Add the port method** — in `AttachmentRepository.java`, add:
```java
    /**
     * List the model (.3dm massing) attachments produced in a session, owned by
     * the caller, newest first. Used to build the {@code [YOUR MODELS]} prompt
     * manifest so the LLM can pick a {@code baseAttachmentId} for refine_massing.
     */
    List<Attachment> findModelAttachments(
            com.playground.chat.domain.model.id.SessionId sessionId,
            UserId caller,
            int limit);
```

- [ ] **Step 2: Write the failing adapter test** — add a test that inserts a message + a model attachment + a non-model attachment and asserts only the model row is returned. Use the existing test infrastructure for `AttachmentRepositoryJdbcAdapter` in this repo (follow the pattern of the sibling JDBC adapter tests — e.g. an in-container Postgres `@SpringBootTest`/Testcontainers or the project's `@JdbcTest` harness). Assertions:

```java
// given: a session with one message, one generate_massing .3dm attachment and
// one non-model (PDF) attachment for the SAME owner, plus a .3dm owned by ANOTHER user.
// when:
List<Attachment> models = repository.findModelAttachments(sessionId, owner, 30);
// then:
assertThat(models).extracting(Attachment::toolName).containsExactly("generate_massing");
assertThat(models).allMatch(a -> a.filename().endsWith(".3dm"));
```
> If the chat-infra module has no existing JDBC adapter test harness to copy, mirror the test setup used by `MessageRepositoryJdbcAdapter`'s tests (same module). Confirm the harness before writing; do not invent a Testcontainers config that the module doesn't already use.

- [ ] **Step 3: Run, expect fail** → FAIL (`findModelAttachments` undefined).

- [ ] **Step 4: Implement the adapter** — in `AttachmentRepositoryJdbcAdapter.java`, add (mirrors `findOwned`'s join shape):
```java
    @Override
    public List<Attachment> findModelAttachments(SessionId sessionId, UserId caller, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query(
                "SELECT a.id, a.message_id, a.kind, a.filename, a.content_type, "
                        + "a.size_bytes, a.storage_key, a.tool_name, a.brief_title, a.created_at "
                        + "FROM chat.message_attachments a "
                        + "JOIN chat.messages m ON m.id = a.message_id "
                        + "WHERE m.session_id = ? AND m.user_id = ? "
                        + "AND a.kind = ? "
                        + "AND a.tool_name IN ('generate_massing', 'refine_massing') "
                        + "AND a.filename ILIKE '%.3dm' "
                        + "ORDER BY a.created_at DESC "
                        + "LIMIT ?",
                attachmentRowMapper(),
                sessionId.value(),
                caller.value(),
                Attachment.KIND_TOOL_ARTIFACT,
                limit);
    }
```
add imports `com.playground.chat.domain.model.id.SessionId` (and `Attachment` is already imported).

- [ ] **Step 5: Run, expect pass** → PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/springboot/chat/chat-app/src/main/java/com/playground/chat/application/repository/AttachmentRepository.java \
        backend/springboot/chat/chat-infra/src/main/java/com/playground/chat/infrastructure/persistence/AttachmentRepositoryJdbcAdapter.java \
        backend/springboot/chat/chat-infra/src/test/java/com/playground/chat/infrastructure/persistence/AttachmentRepositoryJdbcAdapterTest.java
git commit -m "feat(chat): findModelAttachments query for [YOUR MODELS] manifest"
```

---

## Task 12: chat — UserModelRef + [YOUR MODELS] prompt block

**Files:**
- Create: `backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/model/UserModelRef.java`
- Modify: `backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/service/PromptTemplate.java`
- Test: `backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/service/PromptTemplateTest.java` (append)

- [ ] **Step 1: Create the domain record** — `UserModelRef.java`:
```java
package com.playground.chat.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the caller's session {@code [YOUR MODELS]} manifest — a massing
 * model (.3dm) already generated in this session, injected so the model can
 * resolve a reference ("the second model", "the library massing") to a concrete
 * {@code attachmentId} for {@code refine_massing}'s {@code baseAttachmentId}.
 *
 * <p>{@code ordinal} is 1-indexed in creation order. {@code label} is the
 * human-facing name (briefTitle, else filename).
 */
public record UserModelRef(int ordinal, UUID attachmentId, String label) {

    public UserModelRef {
        Objects.requireNonNull(attachmentId, "attachmentId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }
}
```

- [ ] **Step 2: Write the failing test** — append to `PromptTemplateTest.java`:
```java
    @Test
    void rendersModelsManifestWhenPresent() {
        var models = java.util.List.of(
                new com.playground.chat.domain.model.UserModelRef(
                        1, java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), "도서관"));
        String prompt = promptTemplate.assemble(
                java.util.List.of(), "3층으로 줄여", java.util.List.of(), models);
        assertThat(prompt).contains("[YOUR MODELS]");
        assertThat(prompt).contains("도서관");
        assertThat(prompt).contains("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void omitsModelsManifestWhenEmpty() {
        String prompt = promptTemplate.assemble(
                java.util.List.of(), "안녕", java.util.List.of(), java.util.List.of());
        assertThat(prompt).doesNotContain("[YOUR MODELS]");
    }
```
> The existing `PromptTemplateTest` calls `assemble(history, message, documents)` (3-arg). Those existing calls must be updated to the new 4-arg form (add `List.of()` as the 4th arg) in Step 4, or they won't compile.

- [ ] **Step 3: Run, expect fail** → FAIL (compile error — `assemble` has 3 params).

- [ ] **Step 4: Implement.**

In `PromptTemplate.java`, change `assemble` to take a 4th param and render the block. Replace the signature:
```java
    public String assemble(
            List<Message> truncatedHistory,
            String currentUserMessage,
            List<UserDocumentRef> documents,
            List<UserModelRef> models) {
```
and immediately AFTER the existing `[YOUR DOCUMENTS]` block (after its closing `sb.append('\n');`), insert:
```java
        if (models != null && !models.isEmpty()) {
            sb.append("[YOUR MODELS]\n");
            sb.append("3D massing models already generated in this session, in creation\n"
                    + "order. To MODIFY one with refine_massing, copy the matching id into\n"
                    + "baseAttachmentId. Never invent an id; if none matches, ask the user.\n");
            for (UserModelRef m : models) {
                String label = (m.label() == null || m.label().isBlank()) ? "(untitled)" : m.label();
                sb.append(m.ordinal()).append(". \"").append(label).append("\"")
                  .append(" id=").append(m.attachmentId()).append('\n');
            }
            sb.append('\n');
        }
```
add the import `import com.playground.chat.domain.model.UserModelRef;`. Update any existing 3-arg `assemble(...)` calls in `PromptTemplateTest.java` to pass `List.of()` (or the test models) as the 4th argument.

- [ ] **Step 5: Run, expect pass** → PASS. (The `ChatTurnService` call site is updated in Task 13 — until then chat-app won't compile, so run only `:chat:chat-domain:test` here.)

- [ ] **Step 6: Commit**

```bash
git add backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/model/UserModelRef.java \
        backend/springboot/chat/chat-domain/src/main/java/com/playground/chat/domain/service/PromptTemplate.java \
        backend/springboot/chat/chat-domain/src/test/java/com/playground/chat/domain/service/PromptTemplateTest.java
git commit -m "feat(chat): UserModelRef + [YOUR MODELS] prompt manifest block"
```

---

## Task 13: chat — ChatTurnService manifest wiring + baseAttachmentId transform

**Files:**
- Modify: `backend/springboot/chat/chat-app/src/main/java/com/playground/chat/application/service/ChatTurnService.java`
- Test: `backend/springboot/chat/chat-app/src/test/java/com/playground/chat/application/service/ChatTurnServiceToolCallingTest.java` (append)

Two changes: (A) fetch + inject the `[YOUR MODELS]` manifest (mirror the document manifest path), and (B) resolve+validate+transform `baseAttachmentId → baseStorageKey` for `refine_massing` in `handleToolInvocation`.

- [ ] **Step 1: Write the failing test** — append a focused test of the transform to `ChatTurnServiceToolCallingTest.java`. It dispatches a `refine_massing` tool call and asserts the dispatcher receives `baseStorageKey` (resolved) and NOT `baseAttachmentId`, and that a non-model id yields a tool_error without dispatch. Follow the file's existing harness for stubbing `toolDispatcherPort` and `attachmentRepository`. Skeleton:

```java
    @Test
    void refineMassing_resolvesBaseAttachmentIdToStorageKey() {
        // given a model attachment owned by the caller
        UUID attId = UUID.randomUUID();
        Attachment model = Attachment.toolArtifact(
                AttachmentId.of(attId), MessageId.generate(),
                "massing-x-20260609.3dm", "application/octet-stream", 100L,
                "architecture/massing/x/y.3dm", "generate_massing", "도서관", Instant.now());
        when(attachmentRepository.findOwned(eq(AttachmentId.of(attId)), any()))
                .thenReturn(Optional.of(model));
        // capture the args the dispatcher receives
        ArgumentCaptor<JsonNode> argsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(toolDispatcherPort.invoke(any(), eq("refine_massing"), argsCaptor.capture(), any(), any()))
                .thenReturn(new ToolInvocationResult.Success("id", "refine_massing",
                        objectMapper.createObjectNode().put("summary", "ok"), null));

        // when: drive a turn whose LLM emits a refine_massing call with baseAttachmentId
        // (reuse the file's helper that runs the tool-call flow with a scripted tool call)
        // ... run streamTurn with a scripted refine_massing tool_call args
        //     {"baseAttachmentId": attId, "edits":[{"op":"SetFloors","targetFloorsAbove":3}]}

        // then:
        JsonNode sent = argsCaptor.getValue();
        assertThat(sent.has("baseAttachmentId")).isFalse();
        assertThat(sent.get("baseStorageKey").asText()).isEqualTo("architecture/massing/x/y.3dm");
        assertThat(sent.get("edits").isArray()).isTrue();
    }
```
> Match the actual scripting mechanism this test class already uses to feed a `tool_call` through `handleToolInvocation` (e.g. a fake `ChatGenerationPort` that invokes the bound tool function). If the harness can't easily script a tool call, instead extract the transform into a small package-private method `resolveRefineArgs(JsonNode args, UserContext, SessionId)` on ChatTurnService and unit-test THAT directly — preferred for testability.

- [ ] **Step 2: Run, expect fail** → FAIL.

- [ ] **Step 3: Implement.**

(A) **Model manifest wiring** (mirror the document manifest):
- Add field + constant near the document-manifest ones:
```java
    /** Cap on the [YOUR MODELS] manifest injected into the prompt. */
    static final int MODEL_MANIFEST_LIMIT = 20;
```
- In `guardAndPrepare`, after the `documents` fetch block, add the model fetch (session-scoped, owner-checked) and map to `UserModelRef`:
```java
        // Session's massing models for the [YOUR MODELS] prompt section, so the
        // model can pick a baseAttachmentId for refine_massing. Local read of
        // chat.message_attachments; degrades to empty on failure.
        List<UserModelRef> models;
        try {
            List<Attachment> rows =
                    attachmentRepository.findModelAttachments(session.id(), request.caller(), MODEL_MANIFEST_LIMIT);
            // newest-first from SQL → present oldest-first (creation order) with 1-based ordinal
            models = new ArrayList<>(rows.size());
            int ord = 1;
            for (int i = rows.size() - 1; i >= 0; i--) {
                Attachment a = rows.get(i);
                String label = (a.briefTitle() != null && !a.briefTitle().isBlank())
                        ? a.briefTitle() : a.filename();
                models.add(new UserModelRef(ord++, a.id().value(), label));
            }
        } catch (RuntimeException e) {
            log.warn("model_manifest_lookup_failed sessionId=" + session.id() + " error=" + e.getMessage());
            models = List.of();
        }
```
- Add `List<UserModelRef> models` to the `Preparation` record + its constructor call (`return new Preparation(session, truncated, savedUser, firstTurn, documents, models);`).
- In `streamWithLock`, mirror the document gating + pass the 4th arg:
```java
        List<UserModelRef> promptModels = descriptors.isEmpty() ? List.of() : prep.models();
        String prompt = promptTemplate.assemble(
                prep.truncatedHistory(), request.message(), promptDocuments, promptModels);
```
- Add imports: `com.playground.chat.domain.model.UserModelRef`, `com.playground.chat.domain.model.Attachment` (if not present), `com.playground.chat.domain.model.id.AttachmentId`, `java.util.UUID`, and `com.fasterxml.jackson.databind.node.ObjectNode`.

(B) **baseAttachmentId transform** in `handleToolInvocation` — insert AFTER the `tool_call` emit (`sink.tryEmitNext(new ChatStreamEvent.ToolCall(...))`) and BEFORE `inFlightTools.incrementAndGet();`. Add a local `JsonNode argsForDispatch = args;` then:
```java
        if ("refine_massing".equals(desc.name())) {
            JsonNode baseIdNode = (args == null) ? null : args.get("baseAttachmentId");
            Attachment base = null;
            if (baseIdNode != null && baseIdNode.isTextual()) {
                try {
                    base = attachmentRepository
                            .findOwned(AttachmentId.of(UUID.fromString(baseIdNode.asText())), userCtx.userId())
                            .orElse(null);
                } catch (IllegalArgumentException ignored) {
                    base = null;  // non-UUID id
                }
            }
            if (base == null || !isModelAttachment(base)) {
                sink.tryEmitNext(new ChatStreamEvent.ToolError(
                        id, desc.name(), ToolErrorCode.UPSTREAM_4XX.name(),
                        "지정한 첨부는 수정 가능한 매싱 모델이 아닙니다. 어떤 모델을 수정할지 알려주세요."));
                ObjectNode err = objectMapper.createObjectNode();
                err.put("error", true);
                err.put("code", "REFINE_TARGET_NOT_FOUND");
                err.put("message", "baseAttachmentId가 이 세션의 매싱 모델을 가리키지 않습니다");
                return err;
            }
            ObjectNode transformed = ((ObjectNode) args).deepCopy();
            transformed.remove("baseAttachmentId");
            transformed.put("baseStorageKey", base.storageKey());
            argsForDispatch = transformed;
        }
```
then change the dispatch call to use the transformed args:
```java
        ToolInvocationResult result = toolDispatcherPort.invoke(
                id, desc.name(), argsForDispatch, userCtx,
                p -> sink.emitNext( /* unchanged progress relay */ ));
```
and add the private helper:
```java
    private static boolean isModelAttachment(Attachment a) {
        String tool = a.toolName();
        return ("generate_massing".equals(tool) || "refine_massing".equals(tool))
                && a.filename() != null && a.filename().endsWith(".3dm");
    }
```
> `attachmentRepository` is already a field on `ChatTurnService` (used by the artifact-persist path), so no new dependency. The transform happens BEFORE `inFlightTools.incrementAndGet()`, so the early-return on validation failure needs no decrement.

- [ ] **Step 4: Run, expect pass** → PASS. Then the full chat suite (now that `assemble`'s 4-arg signature is wired everywhere): `./gradlew :chat:chat-domain:test :chat:chat-app:test :chat:chat-infra:test :chat:chat-api:test`.

- [ ] **Step 5: Commit**

```bash
git add backend/springboot/chat/chat-app/src/main/java/com/playground/chat/application/service/ChatTurnService.java \
        backend/springboot/chat/chat-app/src/test/java/com/playground/chat/application/service/ChatTurnServiceToolCallingTest.java
git commit -m "feat(chat): [YOUR MODELS] manifest + refine_massing baseAttachmentId resolve/transform"
```

---

## Task 14: FE one-liner + full verification + spec note

**Files:**
- Modify: `frontend/src/features/chat-tool-card/ToolCardList.tsx`
- Modify: `docs/superpowers/specs/2026-06-09-massing-refine-design.md` (append implementation note)

- [ ] **Step 1: Widen the tool-name gate** — in `ToolCardList.tsx`, change line ~54:
```tsx
        if (card.toolCall.name !== 'generate_massing') {
```
to:
```tsx
        if (card.toolCall.name !== 'generate_massing' && card.toolCall.name !== 'refine_massing') {
```
> Verified upstream: the SSE parser (`shared/api/chat.sse.ts`) and the chat-stream reducer are fully tool-name-agnostic; `MassingResultCard`/`MassingErrorCard` props key on card `kind`, not tool name; in-flight cards already route generically. This is the only control-flow change FE needs. (CLAUDE.md frontend pre-flight: no layout/spacing/color/copy change — a rendering allowlist only; this spec is the design source.)

- [ ] **Step 2: FE build/lint check** — `cd frontend && npm run build` (or the repo's typecheck/lint task) → succeeds.

- [ ] **Step 3: Full agent-tools suite** — `cd backend/fastapi/agent-tools && uv run pytest -q` → all pass.

- [ ] **Step 4: Full chat suite** — `cd backend/springboot && ./gradlew :chat:chat-domain:test :chat:chat-app:test :chat:chat-infra:test :chat:chat-api:test` → BUILD SUCCESSFUL.

- [ ] **Step 5: Container rebuild smoke** (from worktree, `infra/.env` seeded):
```bash
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --build agent-tools chat-api
```
Expect both healthy. (Per the SP4 cycle convention, the authoritative rebuild happens post-merge from the main checkout; this is a build smoke.)

- [ ] **Step 6: Append implementation note** to the spec:
```markdown

---

## Implementation note (2026-06-09)

Implemented per this spec. Recipe = `NormalizedBrief` + floorHeightM/targetFloorsAbove/briefTitle,
embedded in `.glb` extras (`refineRecipe`) by `store_glb` (generate + refine both); the
`resolve_program` wrapper now surfaces `normalized`. refine_massing = `load_recipe` (download .glb,
parse recipe; missing → RECIPE_NOT_FOUND) → `apply_edits` (typed ops on NormalizedBrief; missing
target → REFINE_TARGET_NOT_FOUND) → reused `classify → derive → compute → serialize → store_3dm →
store_glb → respond`. chat: RefineMassingTool descriptor; `[YOUR MODELS]` manifest from
`message_attachments` (findModelAttachments); `baseAttachmentId` resolved+validated to
`baseStorageKey` in `handleToolInvocation` (only this tool transforms args). FE: one-line
ToolCardList allowlist. Mass-split deferred to the multi-mass cycle.
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/chat-tool-card/ToolCardList.tsx \
        docs/superpowers/specs/2026-06-09-massing-refine-design.md
git commit -m "feat(frontend): render refine_massing with the massing card + spec note"
```

---

## Done criteria

- A prior massing can be edited via `refine_massing` (rename/add room, change floors, resize) — the algorithm replays from `classify`, re-applying 건폐율/용적률/footprint/slot-fit.
- Infeasible edits (e.g. floors too few for coverage) fail via `MASSING_ALGORITHM_FAILED`; missing target via `REFINE_TARGET_NOT_FOUND`; legacy/non-recipe model via `RECIPE_NOT_FOUND`; non-model attachment rejected by chat before dispatch.
- The LLM only ever sees `baseAttachmentId` (from `[YOUR MODELS]`); chat resolves it to the storage key.
- generate path unchanged except it now embeds `refineRecipe` in the `.glb`.
- agent-tools + chat full suites green; FE builds.

## Manual E2E (user, after merge)

1. Generate a massing → "3층으로 줄여" → revised card with fewer floors.
2. → "노트북 열람실 2400 추가해줘" → revised card with the new room.
3. → "열람실 3000으로 키워줘" → resized (or graceful 건폐율 error if infeasible).
4. Point at a non-model attachment ("그 PDF 수정해줘") → graceful refusal.
5. A massing generated before this feature (no recipe) → "수정해줘" → "새로 생성" guidance.
