# Room-Split Massing (실별 분할) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zone 박스를 층별 실(室) 단위로 분할 — FFD bin-packing으로 각 실을 한 층에 통째 배치, 잔여는 "공용·기타" 박스, zone-hue 명도 단계 색상 + model-viewer hotspot 라벨 + 실별 Program details 테이블.

**Architecture:** `sub_spaces`를 classify→derive로 운반해 `Zone.rooms`로 귀속시키고, derive가 층수 상한(`floors_max_by_rooms`)으로 슬롯 적합성을 보장한다. 알고리즘은 기존 zone 사각형 내부를 FFD 할당 + shelf 재분할한다. `RoomBox.zone` 필드가 색상·와이어·.3dm user-text의 공통 키. 분할 실패는 어떤 경우에도 zone 단위 통짜 강등으로 처리되어 턴은 항상 성공한다.

**Tech Stack:** Python 3.12 (Pydantic 2, LangGraph, trimesh+colorsys), Next.js 14 + @google/model-viewer hotspots.

**Spec:** `docs/superpowers/specs/2026-06-05-room-split-massing-design.md`

**Worktree:** Before Task 1: from the main checkout run `git worktree add .claude/worktrees/room-split-massing -b worktree-room-split-massing main` and enter it (`EnterWorktree({path: ...})`). All paths below are worktree-relative. Python commands run from `backend/fastapi/agent-tools/` with `uv run` (first run: `uv sync --extra test`). FE commands from `frontend/` with `pnpm` (first run: `pnpm install --frozen-lockfile`).

**Spec deviations locked during planning** (record in Task 8's spec amendment):
1. classify는 sub_spaces를 "통과만"이 아니라 **graded 사본**으로 전달 (등급 판정은 classify의 책임 — derive에 grading 로직 중복 방지).
2. 강등 트리거 4번째: **FFD 단편화** (총량은 맞지만 패킹 불가) — 알고리즘 레벨에서 해당 zone 통짜 강등.
3. `RoomWire.zone`은 신규 페이로드에서 **항상** 세팅 (미분할 zone 행도) — FE의 zone 색 슬롯 순서를 박스 순서와 일치시키기 위해. "실별 모드" 판별은 `floor != null`.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `backend/fastapi/agent-tools/architecture/domain/models.py` | Modify | `Room`, `Zone.rooms`, `ClassifiedBrief.sub_spaces`, `RoomBox.zone`, `COMMON_AREA_NAME` |
| `backend/fastapi/agent-tools/architecture/domain/algorithm.py` | Modify | FFD 할당 + zone 사각형 내부 재분할 |
| `backend/fastapi/agent-tools/architecture/app/nodes/classify.py` | Modify | graded sub_spaces 전달 |
| `backend/fastapi/agent-tools/architecture/app/nodes/derive.py` | Modify | 실 귀속 + floors cap + 강등 가드 1-3 |
| `backend/fastapi/agent-tools/architecture/app/nodes/respond.py` | Modify | 실별 rooms wire + labelAnchor + summary count |
| `backend/fastapi/agent-tools/architecture/api/dtos.py` | Modify | `LabelAnchorWire`, `RoomWire` 확장 |
| `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py` | Modify | zone 키 색상 + 실 명도 단계 + 공용 톤 |
| `backend/fastapi/agent-tools/architecture/infra/serializer.py` | Modify | user-text `zone` 1줄 |
| `backend/fastapi/agent-tools/tests/test_*.py` | Modify | 각 단계 TDD |
| `frontend/src/shared/api/chat.ts` | Modify | `MassingRoom` 확장 |
| `frontend/src/features/chat-tool-card/MassingResultCard.tsx` | Modify | hotspot + 4열 테이블 + readProgramJson 확장 |
| `docs/superpowers/specs/2026-06-05-room-split-massing-design.md` | Modify | 위 deviation 3건 반영 |
| `docs/design/M6-M8-brief-to-massing.md` | Modify | post-landing 노트 |

---

### Task 1: 모델 확장 + `RoomBox.zone` 배관 (동작 불변)

`RoomBox.zone`을 도입하고 모든 생산자/소비자를 `zone == name`(통짜)으로 통과시킨다. 이 태스크가 끝나면 스위트 전체가 green이고 동작·출력은 바이트 동일(색상 키만 name→zone, 통짜에선 등가).

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/domain/models.py`
- Modify: `backend/fastapi/agent-tools/architecture/domain/algorithm.py` (RoomBox 생성 2곳)
- Modify: `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py` (색상 키)
- Modify: `backend/fastapi/agent-tools/architecture/infra/serializer.py` (user-text)
- Test: `backend/fastapi/agent-tools/tests/test_glb_serializer.py` (`_box` 헬퍼), `tests/test_serializer.py` (픽스처)

- [ ] **Step 1: models.py 확장**

`Zone` 클래스 위에 추가:

```python
class Room(BaseModel):
    """A named sub-space placed whole on one floor (net area, 브리프 그대로 —
    design spec 2026-06-05-room-split-massing D2)."""

    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0)
```

`Zone`에 필드 추가:

```python
class Zone(BaseModel):
    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0)
    grade: Literal["above", "below"]
    # 실별 분할 대상 (빈 리스트 = 분할 없음). derive가 귀속·검증 후 채운다.
    rooms: list[Room] = Field(default_factory=list)
```

`ClassifiedBrief`에 필드 추가 (`zones` 바로 아래):

```python
    # classify가 등급 판정을 마친 sub-space 사본 — derive의 실 귀속 입력.
    sub_spaces: list[ProgramItem] = Field(default_factory=list)
```

`RoomBox` 직전에 상수, `RoomBox`에 필드 추가:

```python
# 분할 zone의 층별 잔여(공용·코어) 박스 이름 — algorithm/serializers/respond 공통.
COMMON_AREA_NAME = "공용·기타"


@dataclass(frozen=True, slots=True)
class RoomBox:
    """Algorithm output — one box per room. Coordinates per ADR-18 §11
    (lower-left origin, z = (floor-1) * floor_height)."""

    name: str
    zone: str  # owning zone — color key + 공용 구분. 통짜 박스는 zone == name.
    floor: int
    x: float
    y: float
    z: float
    width: float
    depth: float
    height: float
```

- [ ] **Step 2: algorithm.py — RoomBox 생성에 zone 추가**

`_pack_level`의 `boxes.append(RoomBox(...))`에 `zone=zone.name,`을 `name=zone.name,` 바로 다음 줄에 추가 (1곳뿐).

- [ ] **Step 3: glb_serializer.py — 색상 키 name→zone**

`serialize_glb`에서 두 줄 교체:

```python
    zone_slot.setdefault(box.name, len(zone_slot))
```
→
```python
    zone_slot.setdefault(box.zone, len(zone_slot))
```

```python
        rgb = _PALETTE[zone_slot[box.name] % len(_PALETTE)]
```
→
```python
        rgb = _PALETTE[zone_slot[box.zone] % len(_PALETTE)]
```

- [ ] **Step 4: serializer.py (.3dm) — user-text 1줄**

`attrs.SetUserString("roomName", box.name)` 다음에:

```python
            attrs.SetUserString("zone", box.zone)
```

- [ ] **Step 5: 테스트 픽스처 갱신**

`tests/test_glb_serializer.py`의 `_box`:

```python
def _box(name="lab", floor=1, x=0.0, y=0.0, z=0.0, w=2.0, d=3.0, h=10.0, zone=None):
    return RoomBox(
        name=name, zone=zone or name, floor=floor,
        x=x, y=y, z=z, width=w, depth=d, height=h,
    )
```

`tests/test_serializer.py`의 RoomBox 생성부에도 `zone=<name과 동일 문자열>` 추가 (파일을 읽고 기존 생성 형태에 맞춰 최소 수정).

- [ ] **Step 6: 전체 스위트**

Run: `uv run pytest tests/ -q`
Expected: 78 passed (변경 전과 동일 수)

- [ ] **Step 7: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/domain/models.py \
        backend/fastapi/agent-tools/architecture/domain/algorithm.py \
        backend/fastapi/agent-tools/architecture/infra/glb_serializer.py \
        backend/fastapi/agent-tools/architecture/infra/serializer.py \
        backend/fastapi/agent-tools/tests/test_glb_serializer.py \
        backend/fastapi/agent-tools/tests/test_serializer.py
git commit -m "feat(agent-tools): Room/Zone.rooms/RoomBox.zone scaffolding — behavior-neutral"
```

---

### Task 2: classify — graded `sub_spaces` 전달 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/classify.py`
- Test: `backend/fastapi/agent-tools/tests/test_classify.py`

- [ ] **Step 1: 실패 테스트 추가**

`tests/test_classify.py` 끝에 추가 (파일의 기존 import/헬퍼 스타일을 따르되, 핵심은 아래 — `NormalizedBrief`/`ProgramItem` import가 없으면 추가):

```python
def test_sub_spaces_passed_through_graded() -> None:
    normalized = NormalizedBrief(
        zones=[
            NormalizedZone(name="연구영역", area_m2=26500.0, grade="above"),
            NormalizedZone(name="지하영역", area_m2=4500.0, grade="unknown"),
        ],
        sub_spaces=[
            ProgramItem(name="Middle Lab", area_m2=5680.0, grade="unknown",
                        parent_zone="연구영역", is_net=True),
            ProgramItem(name="지하주차", area_m2=4000.0, grade="unknown"),
        ],
        site_area_m2=14000.0,
    )
    classified = classify_brief(normalized)
    by_name = {s.name: s for s in classified.sub_spaces}
    # 등급이 판정된 사본으로 운반된다 (keyword inference: 지하 → below).
    assert by_name["Middle Lab"].grade == "above"
    assert by_name["지하주차"].grade == "below"
    # 원본 필드 보존.
    assert by_name["Middle Lab"].parent_zone == "연구영역"
    assert by_name["Middle Lab"].area_m2 == 5680.0
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/test_classify.py -v`
Expected: 신규 테스트 FAIL — `ClassifiedBrief.sub_spaces`가 빈 리스트

- [ ] **Step 3: 구현**

`classify_brief`의 `return ClassifiedBrief(...)` 직전에:

```python
    # Sub-spaces ride to derive as GRADED copies — grading is this node's
    # job; derive consumes `it.grade` directly (design spec deviation 1).
    graded_sub_spaces = [
        it.model_copy(update={"grade": _grade_item(it)})
        for it in normalized.sub_spaces
    ]
```

그리고 `ClassifiedBrief(` 인자에 `sub_spaces=graded_sub_spaces,` 추가 (`zones=` 다음 줄).

- [ ] **Step 4: 통과 확인 + 전체**

Run: `uv run pytest tests/test_classify.py tests/ -q`
Expected: all pass (79)

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/nodes/classify.py \
        backend/fastapi/agent-tools/tests/test_classify.py
git commit -m "feat(agent-tools): classify passes graded sub_spaces to derive"
```

---

### Task 3: derive — 실 귀속 + floors cap + 강등 가드 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/derive.py`
- Test: `backend/fastapi/agent-tools/tests/test_derive.py`

- [ ] **Step 1: 실패 테스트 추가**

`tests/test_derive.py`의 `_classified` 헬퍼는 그대로 두고, 파일 끝에 추가. import에 `ProgramItem`을 더한다 (`from architecture.domain.models import ClassifiedBrief, ProgramItem, Zone`):

```python
# --- 실별 분할 (design spec 2026-06-05-room-split-massing D3·D4·D7) ---


def _sub(name, area, *, grade="above", parent=None, is_net=True):
    return ProgramItem(name=name, area_m2=area, grade=grade,
                       parent_zone=parent, is_net=is_net)


def test_rooms_attributed_by_parent_zone() -> None:
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["Middle Lab"]
    assert zone.rooms[0].area_m2 == 5680.0


def test_rooms_attributed_by_unique_grade_when_no_parent() -> None:
    # parent_zone 없음 + above zone이 유일 → 그 zone에 귀속.
    classified = _classified(
        sub_spaces=[_sub("로비", 800.0, parent=None)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["로비"]


def test_rooms_unattributed_when_grade_ambiguous() -> None:
    # above zone이 2개면 parent 없는 실은 미배정 (D7).
    classified = _classified(
        zones=[
            Zone(name="연구영역", area_m2=20000.0, grade="above"),
            Zone(name="업무영역", area_m2=6500.0, grade="above"),
        ],
        sub_spaces=[_sub("로비", 800.0, parent=None)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert all(not z.rooms for z in inputs.zones)


def test_floors_capped_so_largest_room_fits_slot() -> None:
    # zone 24,000 / room 9,000(gross): driver 9,000 → floors=ceil(24000/9000)=3,
    # slot 8,000 < 9,000 → cap floor(24000/9000)=2 → footprint 12,000 (재검증:
    # site 25,000 × 0.6 = 15,000 OK). 최종 floors=2, slot 12,000 ≥ 9,000.
    classified = _classified(
        zones=[Zone(name="연구영역", area_m2=24000.0, grade="above")],
        footprint_driver_m2=9000.0,
        site_area_m2=25000.0,
        coverage_ratio_max=0.6,
        sub_spaces=[_sub("대공간", 9000.0, parent="연구영역", is_net=False)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 2
    zone = inputs.zones[0]
    assert [r.name for r in zone.rooms] == ["대공간"]
    # 슬롯 보장: max room ≤ zone_gross / floors.
    assert 9000.0 <= zone.area_m2 / inputs.target_floors_above + 1e-6


def test_guard1_coverage_blocks_floor_reduction_degrades_zone() -> None:
    # cap이 floors=2를 요구하지만 footprint 12,000 > buildable 16,000×0.6=9,600
    # → 층수 축소 불가 → 해당 zone 통짜 강등 (rooms 비움), floors는 기존 산정값.
    classified = _classified(
        zones=[Zone(name="연구영역", area_m2=24000.0, grade="above")],
        footprint_driver_m2=8000.0,   # ≤ buildable 9,600 → 기존 경로는 통과
        site_area_m2=16000.0,
        coverage_ratio_max=0.6,
        sub_spaces=[_sub("대공간", 9000.0, parent="연구영역", is_net=False)],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 3  # ceil(24000/8000) — 기존 산정 유지
    assert inputs.zones[0].rooms == []      # 강등


def test_guard2_target_floors_override_conflict_degrades_zone() -> None:
    # 오버라이드 6층 → slot 26500/6 ≈ 4,417 < Middle Lab 5,680 → 강등.
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(targetFloors=6), SETTINGS)
    assert inputs.target_floors_above == 6
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert zone.rooms == []


def test_guard3_room_sum_exceeds_zone_gross_degrades_zone() -> None:
    # Σ실 26,400 > 26,500×0.98 = 25,970 → 강등.
    classified = _classified(
        sub_spaces=[
            _sub("A", 13200.0, parent="연구영역"),
            _sub("B", 13200.0, parent="연구영역"),
        ],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert zone.rooms == []


def test_below_grade_room_fits_basement_slot_or_degrades() -> None:
    # 지하 zone 4,500 / basement 1층 슬롯 4,500 ≥ 실 4,000 → 배정.
    classified = _classified(
        sub_spaces=[_sub("지하주차장", 4000.0, grade="below", parent="지하영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    below = next(z for z in inputs.zones if z.grade == "below")
    assert [r.name for r in below.rooms] == ["지하주차장"]

    # 실 5,000 > 슬롯 4,500 → 강등 (지하는 층수 cap 대상 아님 — D3 amendment).
    classified2 = _classified(
        sub_spaces=[_sub("지하주차장", 5000.0, grade="below", parent="지하영역")],
    )
    inputs2 = derive_inputs(classified2, _req(), SETTINGS)
    below2 = next(z for z in inputs2.zones if z.grade == "below")
    assert below2.rooms == []


def test_kfi_fixture_splits_research_zone() -> None:
    # 기존 KFI 픽스처 + Middle Lab: floors 4 유지(cap floor(26500/5680)=4),
    # slot 6,625 ≥ 5,680 → 연구영역에 귀속.
    classified = _classified(
        sub_spaces=[_sub("Middle Lab", 5680.0, parent="연구영역")],
    )
    inputs = derive_inputs(classified, _req(), SETTINGS)
    assert inputs.target_floors_above == 4
    zone = next(z for z in inputs.zones if z.name == "연구영역")
    assert [r.name for r in zone.rooms] == ["Middle Lab"]
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/test_derive.py -v 2>&1 | tail -15`
Expected: 신규 테스트들 FAIL (`_classified`가 `sub_spaces` kwarg를 받지만 derive가 rooms를 채우지 않음 / cap 미구현)

참고: `_classified(**over)`는 `base.update(over)` 방식이라 `sub_spaces=` kwarg가 그대로 `ClassifiedBrief`로 전달된다 — 헬퍼 수정 불필요.

- [ ] **Step 3: 구현**

`derive.py`에 import 추가:

```python
from math import ceil, floor
from architecture.domain.models import ClassifiedBrief, MassingInputs, ProgramItem, Room, Zone
```

`derive_inputs` 위에 헬퍼 2개 추가:

```python
# Σ(zone 실) 허용 상한 — net 합이 gross를 사실상 채우면 추출 오류로 본다 (D4-3).
_ROOM_SUM_TOLERANCE = 0.98


def _attribute_rooms(classified: ClassifiedBrief) -> dict[str, list[Room]]:
    """sub_spaces → zone 귀속 (D7): parent_zone 명시 우선, 없으면 같은 grade의
    zone이 유일할 때만 귀속, 그 외 미배정."""
    zones_by_name = {z.name: z for z in classified.zones}
    by_grade: dict[str, list[Zone]] = {"above": [], "below": []}
    for z in classified.zones:
        by_grade[z.grade].append(z)

    rooms: dict[str, list[Room]] = {z.name: [] for z in classified.zones}
    for it in classified.sub_spaces:
        target = None
        if it.parent_zone and it.parent_zone in zones_by_name:
            target = zones_by_name[it.parent_zone]
        elif it.grade in by_grade and len(by_grade[it.grade]) == 1:
            target = by_grade[it.grade][0]
        if target is not None:
            rooms[target.name].append(Room(name=it.name, area_m2=it.area_m2))
    return rooms


def _drop_rooms(rooms: dict[str, list[Room]], zone_name: str, reason: str) -> None:
    if rooms.get(zone_name):
        logger.warning("room split degraded zone=%s reason=%s", zone_name, reason)
        rooms[zone_name] = []
```

`derive_inputs` 본문 수정 — 기존 `target_floors_above` 산정 블록을 다음으로 교체하고, 마지막 `MassingInputs(...)` 생성에서 rooms를 입힌다:

```python
    rooms_by_zone = _attribute_rooms(classified)

    # 가드 3 (D4): Σ실 > zone_gross × 0.98 → 추출 오류로 보고 강등.
    for z in zones:
        total = sum(r.area_m2 for r in rooms_by_zone.get(z.name, []))
        if total > z.area_m2 * _ROOM_SUM_TOLERANCE:
            _drop_rooms(rooms_by_zone, z.name,
                        f"room sum {total:.0f} > gross {z.area_m2:.0f} × {_ROOM_SUM_TOLERANCE}")

    # --- Floors: DERIVED from the footprint (request override wins) ---
    if req.target_floors:
        target_floors_above = req.target_floors
        # 가드 2 (D4): 오버라이드 층수에서 슬롯에 안 들어가는 zone은 강등.
        for z in zones:
            if z.grade != "above" or not rooms_by_zone.get(z.name):
                continue
            slot = z.area_m2 / target_floors_above
            biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
            if biggest > slot + 1e-6:
                _drop_rooms(rooms_by_zone, z.name,
                            f"override floors={target_floors_above}: room {biggest:.0f} > slot {slot:.0f}")
    elif above_gross > 0 and footprint > 0:
        target_floors_above = max(1, ceil(above_gross / footprint))

        # D3: 지상 zone마다 슬롯 ≥ 최대 실이 되도록 층수 상한을 적용.
        caps = []
        for z in zones:
            if z.grade != "above" or not rooms_by_zone.get(z.name):
                continue
            biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
            caps.append(max(1, floor(z.area_m2 / biggest)))
        if caps:
            capped = min(target_floors_above, min(caps))
            if capped < target_floors_above:
                # 층수가 줄면 풋프린트가 커진다 — 건폐율 재검증 (가드 1).
                new_footprint = above_gross / capped
                if new_footprint <= buildable_footprint + 1e-6:
                    target_floors_above = capped
                else:
                    for z in zones:
                        if z.grade != "above" or not rooms_by_zone.get(z.name):
                            continue
                        slot = z.area_m2 / target_floors_above
                        biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
                        if biggest > slot + 1e-6:
                            _drop_rooms(rooms_by_zone, z.name,
                                        "coverage cap blocks floor reduction "
                                        f"(need footprint {new_footprint:.0f} > buildable {buildable_footprint:.0f})")
    else:
        target_floors_above = settings.default_target_floors_above

    basement_levels = 1 if has_below else 0

    # D3 amendment: 지하 슬롯은 basement_levels가 결정 — 안 들어가면 강등.
    for z in zones:
        if z.grade != "below" or not rooms_by_zone.get(z.name):
            continue
        slot = z.area_m2 / max(1, basement_levels)
        biggest = max(r.area_m2 for r in rooms_by_zone[z.name])
        if biggest > slot + 1e-6:
            _drop_rooms(rooms_by_zone, z.name,
                        f"basement room {biggest:.0f} > slot {slot:.0f}")
```

(`basement_levels = 1 if has_below else 0` 기존 줄은 위 블록 안으로 흡수되므로 원래 위치의 중복 줄은 삭제. `floor_height_m`/FAR 게이트/`MassingInputs` 생성은 기존 그대로 두되, `MassingInputs(zones=...)`에 넘기는 zones를 rooms 입힌 사본으로 교체:)

```python
    zones_with_rooms = [
        z.model_copy(update={"rooms": rooms_by_zone.get(z.name, [])})
        for z in zones
    ]
```

`MassingInputs(zones=zones_with_rooms, ...)`로 전달.

- [ ] **Step 4: 통과 확인 + 전체**

Run: `uv run pytest tests/test_derive.py -v 2>&1 | tail -20 && uv run pytest tests/ -q 2>&1 | tail -2`
Expected: 신규 9건 포함 전체 pass

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/app/nodes/derive.py \
        backend/fastapi/agent-tools/tests/test_derive.py
git commit -m "feat(agent-tools): derive attributes rooms + floors cap guarantees slot fit"
```

---

### Task 4: algorithm — FFD 배치 + zone 사각형 재분할 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/domain/algorithm.py`
- Test: `backend/fastapi/agent-tools/tests/test_algorithm.py`

- [ ] **Step 1: 실패 테스트 추가**

`tests/test_algorithm.py` import에 `Room`, `COMMON_AREA_NAME` 추가 후 파일 끝에:

```python
# --- 실별 분할 (design spec 2026-06-05-room-split-massing D1·D2) ---


def _split_inputs(**over) -> MassingInputs:
    base = dict(
        zones=[
            Zone(
                name="연구",
                area_m2=1200.0,
                grade="above",
                rooms=[
                    Room(name="대실험실", area_m2=500.0),
                    Room(name="실험실A", area_m2=300.0),
                    Room(name="실험실B", area_m2=250.0),
                ],
            ),
        ],
        site_area_m2=2000.0,
        coverage_cap=0.6,
        target_floors_above=2,   # slot = 600
        basement_levels=0,
        floor_height_m=3.5,
    )
    base.update(over)
    return MassingInputs(**base)


def test_ffd_places_each_room_whole_on_one_floor() -> None:
    boxes = compute_massing(_split_inputs())
    rooms = [b for b in boxes if b.name not in ("연구", COMMON_AREA_NAME)]
    # FFD: 대실험실(500)→f1; 실험실A(300)→f1? 잔여 100 < 300 → f2;
    # 실험실B(250)→f2 (잔여 300). 각 실은 정확히 1개 박스.
    by_name = {b.name: b for b in rooms}
    assert len(rooms) == 3
    assert by_name["대실험실"].floor == 1
    assert by_name["실험실A"].floor == 2
    assert by_name["실험실B"].floor == 2
    # 실 박스는 zone 키를 가진다.
    assert all(b.zone == "연구" for b in rooms)


def test_remainder_common_boxes_fill_each_floor() -> None:
    boxes = compute_massing(_split_inputs())
    common = [b for b in boxes if b.name == COMMON_AREA_NAME]
    by_floor = {b.floor: b for b in common}
    # f1: 600 − 500 = 100; f2: 600 − 550 = 50. 면적 = width × depth 근사.
    assert by_floor[1].width * by_floor[1].depth == pytest.approx(100.0, rel=0.05)
    assert by_floor[2].width * by_floor[2].depth == pytest.approx(50.0, rel=0.05)
    assert all(b.zone == "연구" for b in common)


def test_split_boxes_stay_inside_zone_rect() -> None:
    inputs = _split_inputs()
    boxes = compute_massing(inputs)
    side = math.sqrt(1200.0 / 2)  # 단일 zone → zone rect = 풋프린트 전체
    for b in boxes:
        assert b.x >= -1e-6 and b.y >= -1e-6
        assert b.x + b.width <= side + 1e-3
        assert b.y + b.depth <= side * 1.5  # shelf 클램프 관용치 (기존 packer 의미론)


def test_zone_without_rooms_unsplit_alongside_split_zone() -> None:
    inputs = _split_inputs(
        zones=[
            Zone(name="연구", area_m2=1200.0, grade="above",
                 rooms=[Room(name="대실험실", area_m2=500.0)]),
            Zone(name="업무", area_m2=800.0, grade="above"),
        ],
    )
    boxes = compute_massing(inputs)
    work = [b for b in boxes if b.zone == "업무"]
    # 미분할 zone: 층당 1박스, name == zone.
    assert len(work) == 2
    assert all(b.name == "업무" for b in work)


def test_ffd_fragmentation_degrades_zone_unsplit() -> None:
    # 총량은 맞지만(6×350=2,100 ≤ 2,400) 슬롯 600엔 350이 1개씩만 →
    # 4슬롯에 6실 패킹 불가 → 통짜 강등 (deviation 2).
    inputs = _split_inputs(
        zones=[
            Zone(name="연구", area_m2=2400.0, grade="above",
                 rooms=[Room(name=f"실{i}", area_m2=350.0) for i in range(6)]),
        ],
        target_floors_above=4,
    )
    boxes = compute_massing(inputs)
    assert all(b.name == "연구" and b.zone == "연구" for b in boxes)
    assert len(boxes) == 4


def test_below_grade_rooms_split_in_basement() -> None:
    inputs = _split_inputs(
        zones=[
            Zone(name="지하", area_m2=600.0, grade="below",
                 rooms=[Room(name="주차장", area_m2=400.0)]),
        ],
        target_floors_above=1,
        basement_levels=1,
    )
    # above가 비므로 위 zones만으로는 above_footprint=0 → 지상 박스 없음.
    boxes = compute_massing(inputs)
    parking = next(b for b in boxes if b.name == "주차장")
    assert parking.floor == -1 and parking.zone == "지하"
    common = next(b for b in boxes if b.name == COMMON_AREA_NAME)
    assert common.floor == -1
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/test_algorithm.py -v 2>&1 | tail -12`
Expected: 신규 6건 FAIL (분할 미구현 — 실들이 박스로 안 나옴)

- [ ] **Step 3: 구현**

`algorithm.py` import에 `Room`, `COMMON_AREA_NAME` 추가. `_pack_level` 아래에 두 헬퍼 추가:

```python
def _assign_rooms_ffd(
    rooms: list[Room], n_levels: int, slot_area: float
) -> dict[int, list[Room]] | None:
    """First-fit-decreasing room→level 할당 (1-indexed level).

    derive가 "최대 실 ≤ 슬롯"은 보장하지만 단편화로 전체 패킹이 실패할 수
    있다 — 그때 None을 반환하고 호출부가 zone을 통짜로 강등한다 (design
    spec deviation 2)."""
    if not rooms:
        return None
    remaining = [slot_area] * n_levels
    out: dict[int, list[Room]] = {}
    for room in sorted(rooms, key=lambda r: r.area_m2, reverse=True):
        for i in range(n_levels):
            if room.area_m2 <= remaining[i] + 1e-6:
                out.setdefault(i + 1, []).append(room)
                remaining[i] -= room.area_m2
                break
        else:
            return None
    return out


def _subdivide_zone_rect(
    *,
    zone_name: str,
    x0: float,
    y0: float,
    rect_w: float,
    rect_d: float,
    rooms: list[Room],
    slot_area: float,
    floor: int,
    z: float,
    height: float,
) -> list[RoomBox]:
    """zone 사각형 내부를 [실들 + 공용 잔여]로 shelf 분할 (D1·D2).

    실 박스는 square-aspect, zone 사각형 경계로 클램프 — zone 레벨 packer와
    동일한 관용 의미론. 잔여(슬롯 − Σ실)는 슬롯의 1% 초과일 때만 공용 박스."""
    entries: list[tuple[str, float]] = [
        (r.name, r.area_m2)
        for r in sorted(rooms, key=lambda r: r.area_m2, reverse=True)
    ]
    remainder = slot_area - sum(r.area_m2 for r in rooms)
    if remainder > slot_area * 0.01:
        entries.append((COMMON_AREA_NAME, remainder))

    boxes: list[RoomBox] = []
    shelf_x = 0.0
    shelf_y = 0.0
    shelf_h = 0.0
    for name, area in entries:
        w = sqrt(area)
        d = area / w if w > 0 else 0.0
        width = min(w, rect_w)
        if shelf_x + width > rect_w + 1e-6:
            shelf_y += shelf_h
            shelf_x = 0.0
            shelf_h = 0.0
        remaining_depth = rect_d - shelf_y if shelf_y < rect_d else rect_d
        depth = min(d, remaining_depth)
        boxes.append(
            RoomBox(
                name=name,
                zone=zone_name,
                floor=floor,
                x=x0 + shelf_x,
                y=y0 + shelf_y,
                z=z,
                width=width,
                depth=depth,
                height=height,
            )
        )
        shelf_x += width
        if depth > shelf_h:
            shelf_h = depth
    return boxes
```

`compute_massing` 수정 — above 블록에서 FFD 사전 할당을 만들고 `_pack_level`에 전달:

```python
    if above_area > 0:
        footprint_area = above_area / inputs.target_floors_above
        side = sqrt(footprint_area)
        # zone별 실→층 FFD 사전 할당 (None = 분할 없음/강등).
        assignments = {
            z.name: _assign_rooms_ffd(
                z.rooms,
                inputs.target_floors_above,
                z.area_m2 / inputs.target_floors_above,
            )
            for z in above
        }
        slot_by_zone = {
            z.name: z.area_m2 / inputs.target_floors_above for z in above
        }
        per_floor_zones = [...]  # 기존 그대로
        for floor in range(1, inputs.target_floors_above + 1):
            boxes.extend(
                _pack_level(
                    per_floor_zones,
                    side=side,
                    floor=floor,
                    z=(floor - 1) * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                    assignments=assignments,
                    slot_by_zone=slot_by_zone,
                )
            )
```

below 블록도 대칭으로 (`levels`를 n_levels로, floor 키는 양수 level → RoomBox.floor는 `-level`):

```python
        assignments_below = {
            z.name: _assign_rooms_ffd(z.rooms, levels, z.area_m2 / levels)
            for z in below
        }
        slot_below = {z.name: z.area_m2 / levels for z in below}
        for level in range(1, levels + 1):
            boxes.extend(
                _pack_level(
                    per_level_zones,
                    side=side,
                    floor=-level,
                    z=-level * inputs.floor_height_m,
                    height=inputs.floor_height_m,
                    assignments=assignments_below,
                    slot_by_zone=slot_below,
                    level_key=level,
                )
            )
```

`_pack_level` 시그니처/본문 확장 — zone 사각형 산출까지는 동일, 박스 생성 분기만 추가:

```python
def _pack_level(
    zones: list[Zone],
    *,
    side: float,
    floor: int,
    z: float,
    height: float,
    assignments: dict[str, dict[int, list[Room]] | None] | None = None,
    slot_by_zone: dict[str, float] | None = None,
    level_key: int | None = None,
) -> list[RoomBox]:
```

기존 루프의 `boxes.append(RoomBox(...))` 자리를:

```python
        assignment = (assignments or {}).get(zone.name)
        if assignment is None:
            boxes.append(
                RoomBox(
                    name=zone.name,
                    zone=zone.name,
                    floor=floor,
                    x=shelf_x,
                    y=shelf_y,
                    z=z,
                    width=width,
                    depth=depth,
                    height=height,
                )
            )
        else:
            key = level_key if level_key is not None else floor
            boxes.extend(
                _subdivide_zone_rect(
                    zone_name=zone.name,
                    x0=shelf_x,
                    y0=shelf_y,
                    rect_w=width,
                    rect_d=depth,
                    rooms=assignment.get(key, []),
                    slot_area=(slot_by_zone or {})[zone.name],
                    floor=floor,
                    z=z,
                    height=height,
                )
            )
```

주의: `per_floor_zones`/`per_level_zones`의 `Zone(...)` 사본 생성은 rooms를 복사하지 않음 (분할은 assignment 기반이므로 불필요 — 기존 코드 불변).

- [ ] **Step 4: 통과 확인 + 전체**

Run: `uv run pytest tests/test_algorithm.py -v 2>&1 | tail -14 && uv run pytest tests/ -q 2>&1 | tail -2`
Expected: 신규 6건 포함 전체 pass. (기존 `test_every_above_floor_carries_full_program` 등은 rooms 없는 픽스처라 무영향)

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/domain/algorithm.py \
        backend/fastapi/agent-tools/tests/test_algorithm.py
git commit -m "feat(agent-tools): FFD room placement + zone-rect subdivision with 공용 remainder"
```

---

### Task 5: glb 색상 — 실 명도 단계 + 공용 톤 (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/infra/glb_serializer.py`
- Test: `backend/fastapi/agent-tools/tests/test_glb_serializer.py`

- [ ] **Step 1: 실패 테스트 추가**

`tests/test_glb_serializer.py` import에 `colorsys` 추가, 끝에:

```python
def _hls(color: tuple[float, ...]) -> tuple[float, float, float]:
    import colorsys
    return colorsys.rgb_to_hls(*color[:3])


def test_room_boxes_share_zone_hue_with_distinct_lightness():
    data = serialize_glb([
        _box(name="대실험실", zone="연구", floor=1, h=3.5),
        _box(name="실험실A", zone="연구", floor=1, x=5.0, h=3.5),
    ])
    scene = _load(data)
    c1 = _color(next(g for n, g in scene.geometry.items() if n.startswith("대실험실")))
    c2 = _color(next(g for n, g in scene.geometry.items() if n.startswith("실험실A")))
    h1, l1, _ = _hls(c1)
    h2, l2, _ = _hls(c2)
    assert h1 == pytest.approx(h2, abs=0.02)   # 같은 zone → 같은 hue
    assert abs(l1 - l2) > 0.03                  # 실 구분 → 명도 차이


def test_common_box_is_pale_desaturated():
    data = serialize_glb([
        _box(name="대실험실", zone="연구", floor=1, h=3.5),
        _box(name="공용·기타", zone="연구", floor=1, x=5.0, h=3.5),
    ])
    scene = _load(data)
    room = _color(next(g for n, g in scene.geometry.items() if n.startswith("대실험실")))
    common = _color(next(g for n, g in scene.geometry.items() if n.startswith("공용·기타")))
    _, l_room, s_room = _hls(room)
    _, l_common, s_common = _hls(common)
    assert l_common > l_room      # 더 밝고
    assert s_common < s_room      # 더 낮은 채도


def test_unsplit_zone_box_keeps_base_palette_color():
    # zone == name → 기존 원색 그대로 (readability pass와 동일 출력).
    data = serialize_glb([_box(name="연구", zone="연구", floor=1, h=3.5)])
    scene = _load(data)
    c = _color(next(g for n, g in scene.geometry.items() if n.startswith("연구")))
    assert c[:3] == (round(142 / 255, 2), round(152 / 255, 2), round(90 / 255, 2))
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/test_glb_serializer.py -v 2>&1 | tail -8`
Expected: 신규 1·2번 FAIL (실/공용이 zone 원색으로 나옴), 3번 PASS

- [ ] **Step 3: 구현**

`glb_serializer.py` import에 `import colorsys`와 `COMMON_AREA_NAME` 추가
(`from architecture.domain.models import COMMON_AREA_NAME, RoomBox`). 상수 추가:

```python
# 실 명도 단계 (D5): zone hue 유지, lightness를 이 구간에 균등 분배.
_ROOM_LIGHT_MIN = 0.45
_ROOM_LIGHT_MAX = 0.70
_ROOM_LIGHT_STEPS = 6
# 공용·기타: 저채도·고명도 톤.
_COMMON_LIGHT = 0.78
_COMMON_SAT_SCALE = 0.35
```

헬퍼 추가:

```python
def _with_hls(rgb: tuple[int, int, int], *, light: float, sat_scale: float = 1.0) -> tuple[int, int, int]:
    h, _, s = colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))
    r, g, b = colorsys.hls_to_rgb(h, light, s * sat_scale)
    return (int(r * 255), int(g * 255), int(b * 255))


def _room_step_color(zone_rgb: tuple[int, int, int], step: int) -> tuple[int, int, int]:
    span = _ROOM_LIGHT_MAX - _ROOM_LIGHT_MIN
    light = _ROOM_LIGHT_MIN + span * ((step % _ROOM_LIGHT_STEPS) / max(_ROOM_LIGHT_STEPS - 1, 1))
    return _with_hls(zone_rgb, light=light)
```

`serialize_glb`에서 zone_slot 산출 직후에 실 슬롯 산출 추가:

```python
    # zone 내 실 슬롯 — 첫 등장 순서 (공용·통짜 제외).
    room_slot: dict[tuple[str, str], int] = {}
    rooms_seen: dict[str, int] = {}
    for box in boxes:
        if box.name == box.zone or box.name == COMMON_AREA_NAME:
            continue
        key = (box.zone, box.name)
        if key not in room_slot:
            room_slot[key] = rooms_seen.get(box.zone, 0)
            rooms_seen[box.zone] = room_slot[key] + 1
```

루프의 색상 결정부 교체:

```python
        zone_rgb = _PALETTE[zone_slot[box.zone] % len(_PALETTE)]
        if box.name == COMMON_AREA_NAME:
            rgb = _with_hls(zone_rgb, light=_COMMON_LIGHT, sat_scale=_COMMON_SAT_SCALE)
        elif box.name == box.zone:
            rgb = zone_rgb
        else:
            rgb = _room_step_color(zone_rgb, room_slot[(box.zone, box.name)])
        dim = _BELOW_GRADE_DIM if box.floor < 0 else 1.0
```

- [ ] **Step 4: 통과 확인 + 전체**

Run: `uv run pytest tests/test_glb_serializer.py tests/ -q 2>&1 | tail -2`
Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/infra/glb_serializer.py \
        backend/fastapi/agent-tools/tests/test_glb_serializer.py
git commit -m "feat(agent-tools): room lightness steps + 공용 tone in glb preview"
```

---

### Task 6: respond/dtos — 실별 wire + labelAnchor + summary (TDD)

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/dtos.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/respond.py`
- Test: `backend/fastapi/agent-tools/tests/test_workflow.py`

- [ ] **Step 1: 실패 테스트 — workflow 픽스처가 이미 분할을 유발함**

KFI 픽스처(`_FIXED_ANALYSIS`)는 Middle Lab(5,680, parent 연구영역)을 갖고 있어 Task 3 이후 derive가 연구영역을 분할한다. `test_graph_runs_path_and_builds_envelope`에서 기존 `assert len(result.program_json.rooms) == 2`를 다음으로 교체:

```python
    # 실별 분할 (design spec 2026-06-05-room-split-massing): 연구영역은
    # Middle Lab + 층별 공용·기타로 분할, 지하영역은 통짜.
    rooms = result.program_json.rooms
    by_name = {}
    for r in rooms:
        by_name.setdefault(r.name, []).append(r)

    lab = by_name["Middle Lab"][0]
    assert lab.zone == "연구영역"
    assert lab.floor == 1                  # FFD: 가장 낮은 층
    assert lab.area_m2 == 5680.0           # 브리프 전용면적 그대로 (D2)
    assert lab.label_anchor is not None    # hotspot 좌표
    # 상면 중심: glTF Y = z_top = (floor-1)*h + (h - 0.15)
    assert lab.label_anchor.y == pytest.approx(3.5 - 0.15)

    commons = by_name.get("공용·기타", [])
    assert len(commons) == 4               # 연구영역 4층 각각
    assert all(c.zone == "연구영역" and c.label_anchor is None for c in commons)

    basement = by_name["지하영역"][0]
    assert basement.zone == "지하영역"     # 미분할 zone도 zone 세팅 (deviation 3)
    assert basement.floor is None          # 미분할 신호

    # summary: 명명 실(Middle Lab) + 미분할 zone(지하영역) = 2실 — 기존 문자열 유지.
    assert result.summary == "2실 · 지상 4층 + 지하 1층 · 총 31000 m²"
```

파일 상단에 `import pytest` 추가 (없다면).

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/test_workflow.py -v 2>&1 | tail -8`
Expected: FAIL — `RoomWire`에 zone/floor/label_anchor 없음 (혹은 rooms 구성 불일치)

- [ ] **Step 3: dtos.py 확장**

`RoomWire`를 다음으로 교체 (+ 위에 `LabelAnchorWire`):

```python
class LabelAnchorWire(BaseModel):
    """Hotspot anchor — glTF Y-up 좌표, 실 박스 상면 중심 (room-split spec D6)."""

    x: float
    y: float
    z: float


class RoomWire(BaseModel):
    """Single room entry in the response programJson.

    실별 분할(2026-06-05) 이후: 분할 zone의 실/공용 행은 `floor`를 갖고,
    미분할 zone 행은 floor=None (zone은 항상 세팅 — FE 색 슬롯 순서 일치용).
    `labelAnchor`는 명명된 실에만 (공용·기타/미분할 zone은 None)."""

    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0, alias="areaM2")
    zone: str | None = None
    floor: int | None = None
    label_anchor: LabelAnchorWire | None = Field(default=None, alias="labelAnchor")

    model_config = {"populate_by_name": True}
```

- [ ] **Step 4: respond.py 재작성**

```python
"""respond node — build the `{result, artifact}` envelope (ADR-20 §D3 revised).

The `store_3dm` node has already uploaded the .3dm to MinIO and stashed the
object key in `state["storage_key"]`. This node assembles the response:
- `result`: LLM-visible massing summary (floors, area, program JSON, Korean summary)
- `artifact`: metadata only — filename, contentType, sizeBytes, storageKey

programJson.rooms (room-split spec 2026-06-05): 분할 zone은 실/공용 박스가
행으로, 미분할 zone은 zone 1행. 행 순서는 박스의 zone 첫 등장 순서 — FE의
zone 색 슬롯이 glb와 일치하는 근거. labelAnchor는 glb의 Z-up→Y-up 변환
(x, z, -y) + 층 슬릿(FLOOR_GAP_M)을 반영한 실 박스 상면 중심.
"""

from __future__ import annotations

from architecture.api.dtos import (
    GenerateMassingResponse,
    LabelAnchorWire,
    MassingArtifact,
    MassingResult,
    ProgramJsonWire,
    RoomWire,
)
from architecture.app.state import MassingState
from architecture.domain.models import COMMON_AREA_NAME, RoomBox, Zone
from architecture.domain.summary import format_summary
from architecture.infra.glb_serializer import FLOOR_GAP_M


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


def respond(state: MassingState) -> dict:
    inputs = state["inputs"]
    boxes: list[RoomBox] = state["boxes"]

    zones = inputs.zones
    total_area = sum(z.area_m2 for z in zones)
    floors_above = inputs.target_floors_above
    basement_levels = inputs.basement_levels

    rooms_wire = _build_rooms_wire(boxes, zones)
    program_json = ProgramJsonWire(
        rooms=rooms_wire,
        totalAreaM2=total_area,
        floorCount=floors_above,
        basementLevels=basement_levels,
    )
    # "N실" = 명명 실 + 미분할 zone (공용·기타 제외). 분할이 없으면 zone 수
    # 그대로라 기존 summary와 동일하다 (room-split spec).
    room_count = sum(1 for r in rooms_wire if r.name != COMMON_AREA_NAME)
    summary = format_summary(
        room_count=room_count,
        floors_above=floors_above,
        basement_levels=basement_levels,
        total_area_m2=total_area,
    )

    storage_key: str = state["storage_key"]
    file_bytes: bytes = state["file_bytes"]
    # Reconstruct filename from storageKey (last path segment).
    filename = storage_key.rsplit("/", 1)[-1]

    return {
        "response": GenerateMassingResponse(
            result=MassingResult(
                programJson=program_json,
                totalAreaM2=total_area,
                floorCount=floors_above,
                basementLevels=basement_levels,
                summary=summary,
                briefTitle=state["detail"].title,
            ),
            artifact=MassingArtifact(
                filename=filename,
                contentType="application/octet-stream",
                sizeBytes=len(file_bytes),
                storageKey=storage_key,
            ),
        )
    }
```

- [ ] **Step 5: 통과 확인 + 전체**

Run: `uv run pytest tests/test_workflow.py -v 2>&1 | tail -6 && uv run pytest tests/ -q 2>&1 | tail -2`
Expected: all pass

- [ ] **Step 6: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/dtos.py \
        backend/fastapi/agent-tools/architecture/app/nodes/respond.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): room-level programJson wire + labelAnchor + summary room count"
```

---

### Task 7: FE — hotspot 라벨 + 실별 테이블

**Files:**
- Modify: `frontend/src/shared/api/chat.ts`
- Modify: `frontend/src/features/chat-tool-card/MassingResultCard.tsx`

- [ ] **Step 1: chat.ts 타입 확장**

`MassingRoom`을 다음으로 교체:

```typescript
export interface MassingRoom {
  name: string;
  areaM2: number;
  /** Owning zone — set on all room-split payloads (color-slot ordering key). */
  zone?: string;
  /** 1-based floor (negative = basement). Present only on split-zone rows. */
  floor?: number;
  /** Hotspot anchor — glTF Y-up coords at the room box's top-face center. */
  labelAnchor?: { x: number; y: number; z: number };
}
```

`MassingProgramJson`의 JSDoc에서 "The wire ships ONLY ... no per-room floor / dimensions" 문단을 다음으로 교체:

```
 * Room-split payloads (2026-06-05) carry per-room `zone` / `floor` /
 * `labelAnchor` — split-zone rows have `floor`, unsplit-zone rows don't.
 * Legacy payloads carry `{name, areaM2}` only; every new field is optional
 * so old cards render unchanged.
```

- [ ] **Step 2: MassingResultCard.tsx — readProgramJson 확장**

`readProgramJson`의 rooms 매핑을 다음으로 교체:

```typescript
  const rooms = roomsRaw.flatMap((entry): MassingProgramJson['rooms'][number][] => {
    if (!entry || typeof entry !== 'object') return [];
    const e = entry as {
      name?: unknown;
      areaM2?: unknown;
      zone?: unknown;
      floor?: unknown;
      labelAnchor?: unknown;
    };
    if (typeof e.name !== 'string' || typeof e.areaM2 !== 'number') return [];
    const anchorRaw = e.labelAnchor as { x?: unknown; y?: unknown; z?: unknown } | undefined;
    const labelAnchor =
      anchorRaw &&
      typeof anchorRaw.x === 'number' &&
      typeof anchorRaw.y === 'number' &&
      typeof anchorRaw.z === 'number'
        ? { x: anchorRaw.x, y: anchorRaw.y, z: anchorRaw.z }
        : undefined;
    return [{
      name: e.name,
      areaM2: e.areaM2,
      zone: typeof e.zone === 'string' ? e.zone : undefined,
      floor: typeof e.floor === 'number' ? e.floor : undefined,
      labelAnchor,
    }];
  });
```

- [ ] **Step 3: zone 팔레트 + hotspot**

파일 상단 (Spinner 위)에 추가:

```typescript
// glb_serializer.py의 _PALETTE와 동일 순서/값 — zone 첫 등장 순서로 순환.
// (서버가 rooms 행을 박스의 zone 등장 순서로 내려보내므로 인덱스가 일치한다.)
const ZONE_PALETTE = ['#8E985A', '#BC8464', '#6E829B', '#C8B280', '#96788C'] as const;

function zoneColorMap(rooms: MassingProgramJson['rooms']): Map<string, string> {
  const map = new Map<string, string>();
  for (const room of rooms) {
    if (room.zone && !map.has(room.zone)) {
      map.set(room.zone, ZONE_PALETTE[map.size % ZONE_PALETTE.length]!);
    }
  }
  return map;
}
```

`PreviewAccordion`의 props를 확장하고 hotspot을 렌더:

```typescript
function PreviewAccordion({
  previewUrl,
  rooms,
}: {
  previewUrl: string;
  rooms: MassingProgramJson['rooms'];
}) {
```

(기존 본문 유지, `<model-viewer ...>`를 self-closing에서 children 보유로 변경:)

```tsx
          <model-viewer
            ref={viewerRef}
            id="massing-3d-preview"
            src={previewUrl}
            camera-controls
            auto-rotate
            shadow-intensity="1"
            style={{ width: '100%', height: '100%' }}
          >
            {rooms
              .filter((r) => r.labelAnchor)
              .map((r, i) => (
                <button
                  key={`${r.zone}-${r.name}-${i}`}
                  type="button"
                  slot={`hotspot-room-${i}`}
                  data-position={`${r.labelAnchor!.x} ${r.labelAnchor!.y} ${r.labelAnchor!.z}`}
                  data-normal="0 1 0"
                  className="pointer-events-none inline-flex items-center gap-xs rounded-md bg-surface/90 px-xs py-[2px] text-[11px] font-medium text-text shadow-card"
                >
                  <span
                    aria-hidden="true"
                    className="inline-block h-[8px] w-[8px] rounded-full"
                    style={{ backgroundColor: zoneColorMap(rooms).get(r.zone ?? '') ?? '#8E985A' }}
                  />
                  {r.name}
                </button>
              ))}
          </model-viewer>
```

호출부 (result 카드 footer):

```tsx
            {hasDownloadUrl && (
              <PreviewAccordion
                previewUrl={`${state.toolResult.outputUrl}/preview`}
                rooms={program?.rooms ?? []}
              />
            )}
```

- [ ] **Step 4: 실별 4열 테이블**

`ProgramDetailsTable` 본문에서 분할 모드 판별 + 분기:

```typescript
  const isSplit = rooms.some((r) => typeof r.floor === 'number');
```

`isSplit`일 때 테이블 헤더/행을 4열로 (기존 2열 JSX는 `!isSplit` 분기로 유지):

```tsx
      <table className="w-full border-collapse text-left">
        <thead>
          <tr>
            {isSplit && (
              <th scope="col" className="pb-xs text-eyebrow text-text-muted">Zone</th>
            )}
            <th scope="col" className="pb-xs text-eyebrow text-text-muted">Room</th>
            {isSplit && (
              <th scope="col" className="pb-xs text-right text-eyebrow text-text-muted">Floor</th>
            )}
            <th scope="col" className="pb-xs text-right text-eyebrow text-text-muted">Area</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((room, idx) => (
            <tr key={`${room.name}-${idx}`} className="border-t border-border first:border-t-0">
              {isSplit && (
                <td className="py-[10px] text-[13px] text-text-muted">
                  {idx === 0 || visible[idx - 1]?.zone !== room.zone ? room.zone ?? '' : ''}
                </td>
              )}
              <td className="py-[10px] text-[13px] text-text">{room.name}</td>
              {isSplit && (
                <td className="py-[10px] text-right font-mono text-[13px] text-text-muted">
                  {typeof room.floor === 'number'
                    ? room.floor > 0 ? `${room.floor}F` : `B${-room.floor}`
                    : '—'}
                </td>
              )}
              <td className="py-[10px] text-right font-mono text-[13px] text-text-muted">
                {formatAreaM2(room.areaM2)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
```

`VISIBLE_CAP`을 6 → 10으로 상향 (실별 행 증가 대응; overflow 라인은 기존 로직 그대로).

컴포넌트 JSDoc의 "The accordion table therefore renders the 2 columns..." 문단을 실별 4열 모드 설명으로 갱신:

```
 * The accordion table renders 2 columns (ROOM / AREA) for legacy
 * zone-only payloads and 4 columns (ZONE / ROOM / FLOOR / AREA, zone
 * group-headed) for room-split payloads (2026-06-05). Hotspot labels
 * ride `labelAnchor` on named-room rows.
```

- [ ] **Step 5: 검증**

Run (frontend/): `pnpm typecheck && pnpm lint && pnpm build`
Expected: 모두 clean, zero warnings

- [ ] **Step 6: Commit**

```bash
git add frontend/src/shared/api/chat.ts \
        frontend/src/features/chat-tool-card/MassingResultCard.tsx
git commit -m "feat(frontend): room hotspot labels + zone-grouped 4-col program table"
```

---

### Task 8: 통합 검증 + spec/design-doc 동기화

**Files:**
- Modify: `docs/superpowers/specs/2026-06-05-room-split-massing-design.md`
- Modify: `docs/design/M6-M8-brief-to-massing.md`

- [ ] **Step 1: 컨테이너 재빌드** (`--env-file infra/.env` 주의 — `.env.example` 금지)

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build agent-tools frontend
```

healthy 확인: `until docker inspect agent-tools --format='{{.State.Health.Status}}' | grep -q healthy; do sleep 3; done` (frontend 동일).

- [ ] **Step 2: 서버 내부 계약 검증** — 컨테이너 안에서 KFI 모양의 합성 입력으로 분할 확인:

```bash
docker exec agent-tools python -c "
from architecture.domain.models import MassingInputs, Room, Zone, COMMON_AREA_NAME
from architecture.domain.algorithm import compute_massing
inputs = MassingInputs(
    zones=[
        Zone(name='연구영역', area_m2=26500.0, grade='above',
             rooms=[Room(name='Middle Lab', area_m2=5680.0)]),
        Zone(name='지하영역', area_m2=4500.0, grade='below'),
    ],
    site_area_m2=14000.0, coverage_cap=0.6,
    target_floors_above=4, basement_levels=1, floor_height_m=3.5)
boxes = compute_massing(inputs)
named = [b for b in boxes if b.name == 'Middle Lab']
common = [b for b in boxes if b.name == COMMON_AREA_NAME]
print('Middle Lab:', [(b.floor, round(b.width*b.depth)) for b in named])
print('공용 floors:', sorted(b.floor for b in common))
assert len(named) == 1 and named[0].floor == 1
assert sorted(b.floor for b in common) == [1, 2, 3, 4]
print('OK')
"
```

Expected: `OK`

- [ ] **Step 3: spec amendment** — `docs/superpowers/specs/2026-06-05-room-split-massing-design.md`의 D4 섹션 끝에 추가:

```markdown
**구현 중 확정된 deviation (2026-06-05 plan):**
1. classify는 sub_spaces를 graded 사본으로 전달 (등급 판정은 classify 책임).
2. 강등 트리거 4: FFD 단편화 (Σ는 맞지만 슬롯 패킹 불가) — 알고리즘
   레벨에서 해당 zone 통짜 강등 (`_assign_rooms_ffd` → None).
3. `RoomWire.zone`은 신규 페이로드에서 항상 세팅 (미분할 zone 행 포함) —
   FE zone 색 슬롯 순서를 박스 순서와 일치시키는 키. "실별 모드" 판별은
   `floor != null`.
```

- [ ] **Step 4: design-doc 노트** — `docs/design/M6-M8-brief-to-massing.md`의 기존 "3D 미리보기" 인용 블록 뒤에 추가:

```markdown
> **2026-06-05 — 실별 분할 (room-split):** 매싱이 zone 통짜에서 실 단위로
> 세분화됐다. FFD bin-packing으로 각 실이 한 층에 통째 배치되고 잔여는
> `공용·기타` 박스. `.glb`는 zone hue + 실별 명도 단계 + 공용 저채도 톤,
> 실명은 model-viewer hotspot 라벨(HTML 어노테이션)로 표시. Program
> details는 zone 그룹핑 4열(ZONE/ROOM/FLOOR/AREA) 테이블 — 레거시
> 페이로드는 기존 2열 유지. Spec:
> `docs/superpowers/specs/2026-06-05-room-split-massing-design.md`.
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-06-05-room-split-massing-design.md \
        docs/design/M6-M8-brief-to-massing.md
git commit -m "docs: room-split spec deviations + design-doc note"
```

- [ ] **Step 6: 수동 E2E (사용자)** — 채팅에서 매싱 생성 → 카드에서 (a) 모델이 실별 색 단계로 분할, (b) hotspot 실명 라벨, (c) Program details 4열 테이블 확인.

---

## Self-Review

1. **Spec coverage:** D1(FFD)→T4, D2(공용 박스)→T4, D3(층수 상한+지하 예외)→T3, D4(가드 3종)→T3 (+deviation 2 FFD→T4), D5(색)→T5, D6(hotspot)→T6·T7, D7(귀속·일반화)→T3, wire(§7)→T6, FE(§8·9)→T7, .3dm user-text(§6)→T1, summary→T6, 테스트(§10) 전 항목 매핑, Error Handling 표→T3·T4, 하위호환→T6(optional 필드)·T7(2열 fallback). 누락 없음.
2. **Placeholder scan:** `per_floor_zones = [...]  # 기존 그대로`는 기존 코드 무변경 표시로 의도된 것 (Task 4 Step 3 주의문 포함). 그 외 없음.
3. **Type consistency:** `Room(name, area_m2)` T1 정의 ↔ T3 `_attribute_rooms` ↔ T4 `_assign_rooms_ffd(rooms: list[Room])` 일치. `RoomBox.zone` 위치(name 다음) T1 ↔ T4 생성부 keyword 사용 일치. `COMMON_AREA_NAME` T1 정의 ↔ T4/T5/T6 import 일치. `LabelAnchorWire{x,y,z}` T6 ↔ FE `{x,y,z}` T7 일치. `FLOOR_GAP_M` import 경로(infra.glb_serializer) T6 respond 일치. summary 검증 문자열("2실...")은 room_count 공식(명명 실 1 + 미분할 zone 1)과 일치.
