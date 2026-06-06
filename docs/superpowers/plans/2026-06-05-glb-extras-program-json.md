# programJson in .glb extras Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `programJson`(hotspot 좌표 + 실별 테이블)을 `.glb`의 glTF `extras`에 임베드하고, FE가 히스토리 카드에서 그것을 복원해 스트리밍 카드와 동일하게 렌더한다.

**Architecture:** wire 빌더를 `program_wire.py`로 추출해 `respond`(SSE)와 `store_glb`(extras)가 단일 소스를 공유. trimesh `scene.metadata` → `scenes[0].extras` 네이티브 직렬화 (검증 완료). FE는 의존성 없는 ~30줄 GLB 컨테이너 파서로 extras를 읽어 기존 `readProgramJson` 경로에 합류시킨다.

**Tech Stack:** Python 3.12 + trimesh, Pydantic v2 `model_dump(by_alias=True)`, TypeScript (DataView GLB 파싱), React 18.

**Spec:** `docs/superpowers/specs/2026-06-05-glb-extras-program-json-design.md`

**Worktree:** `git worktree add .claude/worktrees/glb-extras-program -b worktree-glb-extras-program main` 후 진입. Python은 `backend/fastapi/agent-tools/`에서 `uv sync --extra test` 1회 후 `uv run`; FE는 `frontend/`에서 `pnpm install --frozen-lockfile` 1회.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `backend/fastapi/agent-tools/architecture/app/program_wire.py` | Create | `build_program_json(boxes, inputs) -> ProgramJsonWire` 단일 소스 |
| `backend/fastapi/agent-tools/architecture/app/nodes/respond.py` | Modify | 로컬 빌더 제거, program_wire 사용 |
| `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py` | Modify | `serialize_glb(..., program_json=None)` extras 임베드 |
| `backend/fastapi/agent-tools/architecture/app/nodes/store_glb.py` | Modify | 빌더 호출 + dict 전달 |
| `backend/fastapi/agent-tools/tests/test_glb_serializer.py` | Modify | extras roundtrip 테스트 |
| `backend/fastapi/agent-tools/tests/test_workflow.py` | Modify | 업로드된 glb 바이트의 extras == SSE programJson 검증 |
| `frontend/src/shared/lib/glb-extras.ts` | Create | GLB 컨테이너 파서 (`fetchGlbProgramJson`) |
| `frontend/src/features/chat-tool-card/MassingResultCard.tsx` | Modify | 히스토리 카드 extras 복원 |
| `docs/design/M6-M8-brief-to-massing.md` | Modify | post-landing 노트 |

---

### Task 1: `program_wire.py` 추출 (behavior-neutral, suite 99 유지)

**Files:**
- Create: `backend/fastapi/agent-tools/architecture/app/program_wire.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/respond.py`

- [ ] **Step 1: program_wire.py 생성**

respond.py의 `_label_anchor`/`_build_rooms_wire`를 이동하고 `ProgramJsonWire` 조립까지 묶는다:

```python
"""programJson wire 빌더 — respond(SSE)와 store_glb(.glb extras)의 단일 소스.

extras 임베드 설계(2026-06-05-glb-extras-program-json D1): 두 소비자가 같은
빌더를 호출하므로 SSE 페이로드와 .glb extras가 항상 동일 데이터임이 구조적으로
보장된다. labelAnchor는 glb_serializer의 Z-up→Y-up 변환 + 층 슬릿을 반영한
실 박스 상면 중심 (room-split spec D6).
"""

from __future__ import annotations

from architecture.api.dtos import LabelAnchorWire, ProgramJsonWire, RoomWire
from architecture.domain.models import COMMON_AREA_NAME, MassingInputs, RoomBox, Zone
from architecture.infra.glb_serializer import FLOOR_GAP_M


def build_program_json(boxes: list[RoomBox], inputs: MassingInputs) -> ProgramJsonWire:
    zones = inputs.zones
    total_area = sum(z.area_m2 for z in zones)
    return ProgramJsonWire(
        rooms=_build_rooms_wire(boxes, zones),
        totalAreaM2=total_area,
        floorCount=inputs.target_floors_above,
        basementLevels=inputs.basement_levels,
    )


def _label_anchor(box: RoomBox) -> LabelAnchorWire:
    render_h = max(box.height - FLOOR_GAP_M, box.height * 0.5)
    return LabelAnchorWire(
        x=box.x + box.width / 2.0,
        y=box.z + render_h,
        z=-(box.y + box.depth / 2.0),
    )


def _build_rooms_wire(boxes: list[RoomBox], zones: list[Zone]) -> list[RoomWire]:
    zone_by_name = {z.name: z for z in zones}
    zone_order: list[str] = []
    for b in boxes:
        if b.zone not in zone_order:
            zone_order.append(b.zone)

    rows: list[RoomWire] = []
    for zname in zone_order:
        zboxes = [b for b in boxes if b.zone == zname]
        split = any(b.name != b.zone for b in zboxes)
        if not split:
            z = zone_by_name[zname]
            rows.append(RoomWire(name=z.name, areaM2=z.area_m2, zone=z.name))
            continue
        room_area = {r.name: r.area_m2 for r in zone_by_name[zname].rooms}
        for b in zboxes:
            if b.name == COMMON_AREA_NAME:
                rows.append(RoomWire(
                    name=b.name,
                    areaM2=round(b.width * b.depth, 1),
                    zone=zname,
                    floor=b.floor,
                ))
            else:
                rows.append(RoomWire(
                    name=b.name,
                    areaM2=room_area.get(b.name, round(b.width * b.depth, 1)),
                    zone=zname,
                    floor=b.floor,
                    labelAnchor=_label_anchor(b),
                ))
    return rows
```

(주의: `_build_rooms_wire`/`_label_anchor` 본문은 respond.py의 현행 코드와 **바이트 동일**해야 한다 — 이동만, 수정 금지. respond.py를 읽고 그대로 옮길 것. 위 블록은 그 결과물의 기대 형태다.)

- [ ] **Step 2: respond.py 정리**

- `_label_anchor`/`_build_rooms_wire` 삭제, import에서 `LabelAnchorWire`/`RoomWire`/`FLOOR_GAP_M`/`Zone` 등 미사용분 제거
- `from architecture.app.program_wire import build_program_json` 추가
- 본문 교체:

```python
    program_json = build_program_json(boxes, inputs)
    room_count = sum(1 for r in program_json.rooms if r.name != COMMON_AREA_NAME)
```

(기존 `rooms_wire = _build_rooms_wire(...)` + `ProgramJsonWire(...)` 조립 + `room_count` 줄을 대체. `total_area` 등 나머지는 그대로.)

- [ ] **Step 3: 전체 스위트** — `uv run pytest tests/ -q` → **99 passed** (behavior-neutral).

- [ ] **Step 4: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/program_wire.py \
        backend/fastapi/agent-tools/architecture/app/nodes/respond.py
git commit -m "refactor(agent-tools): extract program_wire builder — single source for SSE + extras"
```

---

### Task 2: `serialize_glb(program_json=)` + store_glb 임베드 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/store_glb.py`
- Test: `backend/fastapi/agent-tools/tests/test_glb_serializer.py`, `tests/test_workflow.py`

- [ ] **Step 1: 실패 테스트 — extras roundtrip**

`tests/test_glb_serializer.py` 상단 import에 `json`, `struct` 추가, 파일 끝에:

```python
def _glb_json_chunk(data: bytes) -> dict:
    # GLB 2.0 컨테이너: 12B 헤더(magic/version/length) + chunk0(JSON).
    assert data[:4] == b"glTF"
    json_len = struct.unpack("<I", data[12:16])[0]
    return json.loads(data[20 : 20 + json_len].decode("utf-8"))


def test_program_json_embedded_in_scene_extras():
    pj = {"rooms": [{"name": "시험실", "areaM2": 500.0, "floor": 1}], "floorCount": 2}
    data = serialize_glb([_box(h=3.5)], program_json=pj)
    doc = _glb_json_chunk(data)
    assert doc["scenes"][0]["extras"]["programJson"] == pj


def test_no_program_json_no_extras():
    data = serialize_glb([_box(h=3.5)])
    doc = _glb_json_chunk(data)
    assert "extras" not in doc["scenes"][0]
```

- [ ] **Step 2: 실패 확인** — `uv run pytest tests/test_glb_serializer.py -v` → 신규 1번 FAIL (TypeError: unexpected keyword), 2번은 통과 가능성 있음 (현행 metadata 비어있음 — 실제 결과 확인).

주의: trimesh가 기본으로 `scene.metadata`에 뭔가 넣어 extras가 이미 존재하면 2번 테스트의 단언을 `assert "programJson" not in doc["scenes"][0].get("extras", {})`로 조정하고 보고할 것.

- [ ] **Step 3: serialize_glb 구현**

시그니처/말미 변경:

```python
def serialize_glb(
    boxes: list[RoomBox],
    *,
    program_json: dict | None = None,
) -> bytes:
```

`scene.add_geometry(_ground_plane(boxes), geom_name="ground")` 다음, `return` 직전에:

```python
    if program_json is not None:
        # glTF 2.0 extras (scenes[0].extras.programJson) — trimesh가
        # scene.metadata를 extras로 직렬화한다. FE가 히스토리 카드에서
        # 이것을 읽어 hotspot/테이블을 복원한다 (glb-extras spec D2).
        scene.metadata["programJson"] = program_json
```

docstring에 한 줄 추가: `program_json이 주어지면 scenes[0].extras에 임베드된다 (preview 메타데이터 영속화).`

- [ ] **Step 4: store_glb 연결**

`store_glb.py`: imports에 `from architecture.app.program_wire import build_program_json` 추가 — **주의: infra→app 역참조가 아니라 app(store_glb)→app(program_wire)이므로 레이어 위반 아님.** try 블록 안 `glb_bytes = serialize_glb(state["boxes"])` 를:

```python
        program_json = build_program_json(state["boxes"], state["inputs"]).model_dump(
            by_alias=True, mode="json"
        )
        glb_bytes = serialize_glb(state["boxes"], program_json=program_json)
```

(`mode="json"` — extras에 순수 JSON 타입만 들어가게. `by_alias` — FE `readProgramJson`이 기대하는 `areaM2`/`labelAnchor` 키와 일치.)

- [ ] **Step 5: workflow 검증 — extras == SSE**

`tests/test_workflow.py`의 glb 모니터패치를 바이트 보존형으로 교체:

```python
    glb_uploads: list[tuple[str, str, bytes]] = []
    monkeypatch.setattr(
        "architecture.app.nodes.store_glb.upload_to_key",
        lambda file_bytes, key, content_type, settings:
            glb_uploads.append((key, content_type, file_bytes)),
    )
```

기존 size 단언 줄(`glb_size > 0`)을 `len(glb_bytes) > 0`로 맞추고, 테스트 끝에 추가:

```python
    # .glb extras == SSE programJson (단일 빌더 보장 — glb-extras spec D1·D2).
    import json as _json
    import struct as _struct
    glb_bytes = glb_uploads[0][2]
    json_len = _struct.unpack("<I", glb_bytes[12:16])[0]
    doc = _json.loads(glb_bytes[20 : 20 + json_len].decode("utf-8"))
    extras_pj = doc["scenes"][0]["extras"]["programJson"]
    assert extras_pj == result.program_json.model_dump(by_alias=True, mode="json")
```

- [ ] **Step 6: 전체 스위트** — `uv run pytest tests/ -q` → **101 passed** (99 + 2).

- [ ] **Step 7: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/infra/glb_serializer.py \
        backend/fastapi/agent-tools/architecture/app/nodes/store_glb.py \
        backend/fastapi/agent-tools/tests/test_glb_serializer.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): embed programJson in glb scene extras"
```

---

### Task 3: FE — GLB extras 파서 + 히스토리 카드 복원

**Files:**
- Create: `frontend/src/shared/lib/glb-extras.ts`
- Modify: `frontend/src/features/chat-tool-card/MassingResultCard.tsx`

- [ ] **Step 1: glb-extras.ts 생성**

```typescript
/**
 * Minimal GLB(glTF-Binary 2.0) container reader — pulls
 * `scenes[0].extras.programJson` out of a .glb without three.js.
 *
 * The architecture BC embeds the massing program (hotspot anchors + room
 * table) into the preview .glb at generation time (glb-extras spec D2), so
 * a history card can restore what the streaming SSE payload carried.
 * Container layout: 12-byte header (magic "glTF", version, length) followed
 * by length-prefixed chunks; chunk 0 is always JSON.
 *
 * Every failure path resolves to `null` — legacy .glb without extras, HTTP
 * errors, malformed bytes. Callers degrade exactly like a missing
 * programJson today.
 */
export async function fetchGlbProgramJson(
  url: string,
): Promise<Record<string, unknown> | null> {
  try {
    const res = await fetch(url); // same-origin — gateway session cookie rides along
    if (!res.ok) return null;
    const buf = await res.arrayBuffer();
    if (buf.byteLength < 20) return null;
    const view = new DataView(buf);
    if (view.getUint32(0, true) !== 0x46546c67) return null; // "glTF"
    if (view.getUint32(4, true) !== 2) return null; // version 2 only
    const jsonLength = view.getUint32(12, true);
    if (view.getUint32(16, true) !== 0x4e4f534a) return null; // chunk0 type "JSON"
    if (20 + jsonLength > buf.byteLength) return null;
    const jsonText = new TextDecoder().decode(new Uint8Array(buf, 20, jsonLength));
    const doc = JSON.parse(jsonText) as {
      scenes?: { extras?: { programJson?: unknown } }[];
    };
    const pj = doc.scenes?.[0]?.extras?.programJson;
    return typeof pj === 'object' && pj !== null
      ? (pj as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}
```

- [ ] **Step 2: MassingResultCard 복원 로직**

(a) import 추가: `import { fetchGlbProgramJson } from '@/shared/lib/glb-extras';`

(b) 컴포넌트 최상단(기존 `const [open, setOpen] = useState(false);` 옆 — **in-flight early return보다 위**, hooks 규칙)에 추가:

```tsx
  // History cards lack `toolResult.programJson` (the SSE-only payload is
  // replaced on done-refetch); the .glb carries it in scene extras since
  // glb-extras spec D4 — fetch once and merge into the same render path.
  const [extrasProgram, setExtrasProgram] = useState<Record<string, unknown> | null>(null);
  const outputUrl = state.kind === 'result' ? state.toolResult.outputUrl : undefined;
  const hasStreamedProgram = state.kind === 'result' && !!state.toolResult.programJson;
  useEffect(() => {
    if (state.kind !== 'result' || hasStreamedProgram || !outputUrl) return;
    let cancelled = false;
    void fetchGlbProgramJson(`${outputUrl}/preview`).then((pj) => {
      if (!cancelled && pj) setExtrasProgram(pj);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.kind, hasStreamedProgram, outputUrl]);
```

(c) result 분기의 `const program = readProgramJson(state.toolResult.programJson);` 을:

```tsx
  const program = readProgramJson(state.toolResult.programJson ?? extrasProgram ?? undefined);
```

(extras도 같은 `readProgramJson` 가드를 통과 — 키가 `areaM2`/`labelAnchor`로 동일하므로 그대로 파싱된다.)

- [ ] **Step 3: 검증** — `pnpm typecheck && pnpm lint && pnpm build` → 전부 clean, zero warnings. eslint가 (b)의 disable 주석을 거부하면 deps 배열을 lint가 요구하는 형태로 맞추고 보고.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/shared/lib/glb-extras.ts \
        frontend/src/features/chat-tool-card/MassingResultCard.tsx
git commit -m "feat(frontend): restore programJson from glb extras on history cards"
```

---

### Task 4: 통합 검증 + design-doc

**Files:**
- Modify: `docs/design/M6-M8-brief-to-massing.md`

- [ ] **Step 1: 재빌드** (CRITICAL: `--env-file infra/.env`, worktree 루트에서 실행해야 worktree 코드가 빌드됨)

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build agent-tools frontend
```

healthy 대기 후, 컨테이너가 신코드인지 확인:
`docker exec agent-tools python -c "from architecture.app.program_wire import build_program_json; print('ok')"`

- [ ] **Step 2: 실생성 E2E** — 내부 엔드포인트로 생성 후 업로드된 .glb의 extras 검증:

```bash
docker exec agent-tools python -c "
import httpx, json, struct
from minio import Minio
from shared_kernel.config import get_settings
r = httpx.post('http://localhost:18083/internal/tools/generate-massing',
    json={'briefDocId': '7e816ae7-b528-43d9-82ab-e0c6668412f4'},
    headers={'X-User-Id': '690df887-12c0-4165-944e-28426702634f'}, timeout=180.0)
assert r.status_code == 200, r.text[:200]
key = r.json()['artifact']['storageKey'][:-len('.3dm')] + '.glb'
s = get_settings()
c = Minio(s.minio_endpoint.removeprefix('http://'), access_key=s.minio_access_key,
          secret_key=s.minio_secret_key, secure=False)
data = c.get_object(s.minio_bucket, key).read()
jl = struct.unpack('<I', data[12:16])[0]
doc = json.loads(data[20:20+jl].decode())
pj = doc['scenes'][0]['extras']['programJson']
labeled = [x for x in pj['rooms'] if x.get('labelAnchor')]
print('extras rooms:', len(pj['rooms']), '| labeled:', len(labeled))
assert labeled, 'no labelAnchor in extras'
print('OK')
"
```

Expected: `OK`.

- [ ] **Step 3: design-doc 노트** — `docs/design/M6-M8-brief-to-massing.md`의 room-split 인용 블록 뒤에:

```markdown
> **2026-06-05 — programJson 영속화 (.glb extras):** `programJson`은 이제
> 생성 시점에 `.glb`의 `scenes[0].extras`에도 임베드된다 (SSE와 단일 빌더
> `program_wire.build_program_json` 공유). 히스토리 카드는
> `shared/lib/glb-extras.ts`의 의존성-제로 GLB 파서로 그것을 복원해
> 스트리밍 카드와 동일하게 hotspot 라벨 + 실별 테이블을 렌더한다. 레거시
> .glb(extras 없음)는 지금처럼 테이블/라벨 없이 graceful 생략. Spec:
> `docs/superpowers/specs/2026-06-05-glb-extras-program-json-design.md`.
```

- [ ] **Step 4: Commit**

```bash
git add docs/design/M6-M8-brief-to-massing.md
git commit -m "docs(design): note programJson persistence via glb extras"
```

- [ ] **Step 5: 수동 확인 (사용자)** — 새 매싱 생성 → **새로고침** → 히스토리 카드에서 Program details 4열 + 3D 미리보기 hotspot 라벨 복원 확인.

---

## Self-Review

1. **Spec coverage:** D1(빌더 추출·단일 소스)→T1, D2(extras 임베드·best-effort)→T2 (기존 try/except 내부라 원칙 유지), D3(파서·실패→null)→T3, D4(히스토리 복원·스트리밍 무변화·레거시 graceful)→T3, Testing 절→T2 (roundtrip + workflow extras==SSE) / T3(typecheck·lint·build) / T4(실생성 E2E). Out of scope 항목 침범 없음.
2. **Placeholder scan:** 없음. T1 Step 1의 "respond.py를 읽고 그대로 옮길 것"은 이동-만 지시로 의도된 것 (기대 형태 코드 동봉).
3. **Type consistency:** `build_program_json(boxes, inputs) -> ProgramJsonWire` T1 정의 ↔ T2 store_glb 호출 + `.model_dump(by_alias=True, mode="json")` ↔ T2 workflow 단언의 `result.program_json.model_dump(by_alias=True, mode="json")` 대칭 ✓. `serialize_glb(boxes, *, program_json=None)` T2 정의 ↔ store_glb 호출 ✓. `fetchGlbProgramJson(url) -> Record<string,unknown> | null` T3 정의 ↔ 사용처 ✓.
