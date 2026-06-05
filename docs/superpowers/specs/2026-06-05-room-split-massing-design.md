# Room-Split Massing (실별 분할) — Design Spec

**Date**: 2026-06-05
**Milestone**: M8 (post-fix iteration)
**Prerequisites**: massing .glb preview (2026-06-05-massing-glb-preview), glb readability pass (PR #229)

---

## Problem

매싱 모델이 zone(gross) 단위 통짜 박스라 실별(室別) 구성이 보이지 않는다.
브리프 PDF에는 실별 면적표가 있고 extraction도 `sub_spaces`로 뽑고 있지만,
`classify` 단계에서 footprint driver 숫자 하나로 졸아든 뒤 버려진다.
실별 분할은 공모 요건의 일부이므로 "어떻게 분할했는지"가 모델과 카드에
보여야 한다.

---

## Goal

1. zone 박스를 층별로 실 단위 분할 — bin-packing(FFD)으로 각 실을 한 층에 통째 배치
2. 층마다 잔여 면적을 명시적 "공용·기타" 박스로 표현 (실 면적은 브리프 전용면적 그대로)
3. `.glb` preview: zone hue 유지 + 실별 명도 단계 색상, hotspot 라벨로 실명 표시
4. Program details 테이블을 실별 행(zone 그룹핑)으로 확장
5. 분할 불가능한 충돌은 zone 단위 통짜 강등 — 절대 턴을 실패시키지 않음

---

## Design Decisions

### D1. 층 배치 = bin-packing (First-Fit-Decreasing)

실이 몇 층에 가는지는 브리프에 없으므로 규칙으로 정한다: zone 소속 실들을
면적 내림차순으로, "잔여 용량이 충분한 가장 낮은 층"에 **통째로** 배치.
비례 슬라이스(실이 여러 층에 조각남)는 기각 — 실별 구분의 의미가 사라진다.

### D2. 잔여 면적 = 명시적 "공용·기타" 박스

실별 면적은 전용(net), zone 합계는 공용 포함(gross). 층마다
`슬롯 면적 − Σ(배치된 실)`을 `공용·기타` 박스로 채운다. 실 박스 면적이
브리프 면적표와 1:1로 일치한다. gross-adjust 부풀리기는 기각.

### D3. 층수 산정이 슬롯 보장을 내장한다

"가장 큰 실이 풋프린트를 정한다"(footprint driver)를 zone 단위로 확장:

```
floors_max_by_rooms = min over (실 있는 zone들):
    max(1, floor(zone_gross / 그 zone의 최대 실 면적))

target_floors = min(기존 산정 층수, floors_max_by_rooms)   # ≥ 1
```

층수가 줄면 풋프린트가 커지므로 건폐율 게이트를 재검증한다. 통과하면
"모든 실 ≤ 자기 zone의 층 슬롯"이 수학적으로 보장된다.

이 상한은 **지상 zone에만** 적용된다 — 지하 zone의 슬롯 수는
`basement_levels`가 결정하므로, 지하 실이 슬롯을 초과하면 강등 가드
(D4)로 처리한다 (basement_levels를 늘리는 건 v1 범위 밖).

### D4. 강등 가드 (3가지 진짜 충돌만)

다음 경우에만 해당 zone의 분할을 포기하고 통짜 박스로 강등 +
`consistency_note` 기록. 턴은 항상 성공한다.

1. 건폐율 캡 때문에 `floors_max_by_rooms`까지 층수를 못 줄이는 경우
   (실 크기 ↔ 대지 면적의 본질적 충돌)
2. 사용자가 `targetFloors`를 명시 오버라이드했고 그 층수에서 실이 슬롯에
   안 들어가는 경우
3. `Σ(zone 실 면적) > zone_gross × 0.98` (추출 오류 / net-gross 불일치)

### D5. 색상 = zone hue 유지 + 실별 명도 단계 (브레인스톰 A안)

- zone hue: 기존 5색 팔레트, 등장 순서 배정 (변경 없음)
- zone 내 실: 같은 hue의 HLS lightness를 실 순서대로 0.45→0.70 구간에
  균등 분배, 6단계 초과 시 순환
- 공용·기타: 해당 zone hue의 저채도(×0.35)·고명도(0.78) 톤
- 지하 dim(×0.72), 층 슬릿(0.15m), 지면 슬래브: 기존 그대로

### D6. 실명 라벨 = model-viewer hotspot (HTML 어노테이션)

glTF에 텍스트 프리미티브가 없으므로 파일에 넣지 않는다. `<model-viewer>`의
hotspot 슬롯으로 HTML 라벨을 3D 좌표에 앵커 — 회전해도 카메라를 향한다.
bin-packing 덕분에 각 실은 모델에 정확히 한 번 존재 → 라벨 수 = 실 수.
공용·기타 박스는 라벨 없음. 좌표는 wire의 `labelAnchor`(박스 상면 중심,
glTF Y-up 좌표)로 전달.

### D7. 일반화 가드 (브리프-불가지론)

색상·분할 규칙은 zone/실 "이름"이 아니라 등장 순서·면적 기반이므로 어떤
지침서에도 동일하게 적용된다:

- zone > 5개 → 팔레트 순환 재사용
- `zones_gross` 없는 브리프 → 기존 fallback(실=zone) 그대로, 분할 없음
- 일부 zone만 실 정보 보유 → 그 zone만 분할, 나머지 통짜 (혼재 허용)
- 실 20+ zone → 명도 단계 순환
- 실의 zone 귀속: `parent_zone` 명시 우선; 없으면 grade 일치 zone이
  유일할 때만 귀속; 그 외 미배정(분할 대상 제외)

---

## Data Flow

```
BriefAnalysis.program (실별 추출 — 기존)
    ↓ reconcile (기존: NormalizedBrief.sub_spaces 운반)
    ↓ classify — zones 그레이딩 + footprint driver (기존)
    |            + sub_spaces 통과                      ← 변경
ClassifiedBrief.sub_spaces                              ← 신규 필드
    ↓ derive — 실의 zone 귀속 매핑 (D7)
    |          + floors_max_by_rooms 층수 상한 (D3)
    |          + 강등 가드 1·2·3 (D4)
MassingInputs.zones[i].rooms : list[Room]               ← Zone 확장
    ↓ compute — zone 사각형 내부를 층별 FFD 분할 (D1·D2)
RoomBox(+zone 필드)                                     ← 확장
    ↓ serialize (.3dm — 실별 박스/레이어가 자연히 생김)
    ↓ store_3dm → store_glb (D5 색상)
    ↓ respond — programJson.rooms 실별 확장 + labelAnchor (D6)
    ↓ rag-chat (passthrough — 변경 없음)
    ↓ FE: 실별 테이블 + hotspot 라벨
```

---

## Touch Points

### 1. `architecture/domain/models.py`

```python
class Room(BaseModel):
    """A named sub-space placed whole on one floor (net area, 브리프 그대로)."""
    name: str = Field(min_length=1)
    area_m2: float = Field(gt=0)

class Zone(BaseModel):
    name: str
    area_m2: float
    grade: Literal["above", "below"]
    rooms: list[Room] = Field(default_factory=list)   # ← 신규 (빈 리스트 = 분할 없음)

class ClassifiedBrief(BaseModel):
    ...
    sub_spaces: list[ProgramItem] = Field(default_factory=list)  # ← 신규

@dataclass(frozen=True, slots=True)
class RoomBox:
    name: str
    zone: str          # ← 신규 — 색상 키 + 공용 구분. 통짜 박스는 zone == name
    floor: int
    x: float; y: float; z: float
    width: float; depth: float; height: float
```

`MassingInputs` 검증에 D4-3 추가: zone마다 `Σrooms ≤ area_m2 × 0.98`,
위반 시 검증 실패가 아니라 **derive가 사전에 rooms를 비우고 note 기록**
(MassingInputs는 항상 유효한 입력만 받는 기존 계약 유지).

### 2. `architecture/app/nodes/classify.py`

`ClassifiedBrief(... sub_spaces=normalized.sub_spaces)` — 통과만. 기존
footprint driver 로직 불변.

### 3. `architecture/app/nodes/derive.py`

- 실의 zone 귀속 (D7 규칙) → zone별 rooms 후보
- `floors_max_by_rooms` 계산, `target_floors = min(기존, 상한)` (D3).
  단 `req.targetFloors` 오버라이드가 있으면 오버라이드 우선 (기존 의미 유지)
- 건폐율 게이트 재검증: 실패 시 가드 1 → 충돌 zone들 rooms 비움 +
  층수는 기존 산정값 복원
- 가드 2·3 검사 → 해당 zone rooms 비움 + note
- `MassingInputs.zones[i].rooms` 채워서 반환

### 4. `architecture/domain/algorithm.py`

`_pack_level` 확장 (또는 `_pack_level_rooms` 신설):

- zone별 per-floor 슬롯 면적·사각형은 기존 계산 그대로
- rooms가 있는 zone: FFD로 실→층 할당(사전 단계, zone당 1회) 후, 각 층에서
  zone 사각형 내부를 [배치된 실들 + 공용 잔여]로 shelf 분할.
  실 사각형은 square-aspect, zone 사각형 경계를 넘지 않음.
  공용 박스 면적 = 슬롯 − Σ(그 층의 실) (> 0일 때만 생성)
- rooms가 없는 zone: 기존 동작 (통짜, RoomBox.zone = zone.name)
- RoomBox: 실 박스 `name=실명, zone=zone명`; 공용 박스
  `name="공용·기타", zone=zone명`

### 5. `architecture/infra/glb_serializer.py`

- 색상 키를 `box.name` → `box.zone`으로 교체 (팔레트 슬롯 = zone 등장 순서)
- zone 내 실 lightness 단계 (D5): `colorsys` RGB↔HLS 변환,
  실 순서 = zone 내 첫 등장 순서. `name == "공용·기타"` → 공용 톤
- 통짜 박스(`zone == name`)는 현재와 동일한 zone 원색

### 6. `architecture/infra/serializer.py` (.3dm)

변경 없음 — RoomBox 리스트가 실별로 풍부해지면 레이어(실명 단위)와 박스가
자연히 따라간다. user-text에 `zone` 추가 1줄만:
`attrs.SetUserString("zone", box.zone)`.

### 7. `architecture/app/nodes/respond.py` + `api/dtos.py`

`RoomWire` 확장 (모두 optional — 하위호환):

```python
class RoomWire(BaseModel):
    name: str
    area_m2: float = Field(alias="areaM2")
    zone: str | None = None
    floor: int | None = None
    label_anchor: LabelAnchorWire | None = Field(default=None, alias="labelAnchor")

class LabelAnchorWire(BaseModel):
    x: float; y: float; z: float   # glTF Y-up, 박스 상면 중심
```

- `programJson.rooms` = **실별 박스 목록** (공용·기타 포함, 공용은
  labelAnchor 없음). 분할이 전혀 없으면 기존처럼 zone 목록 (zone/floor/
  labelAnchor = null) — 구 shape와 동일하게 직렬화됨
- labelAnchor 산출: RoomBox (Rhino Z-up) → glTF 좌표 변환 `(cx, z_top, -cy)`
  — glb_serializer의 회전과 동일 규칙. 층 슬릿(0.15m) 반영해
  `z_top = box.z + render_h`
- summary의 "N실": 명명된 실 수 (공용 제외), 분할 없으면 zone 수 (기존)

### 8. frontend — `shared/api/chat.ts`

`MassingProgramJson.rooms[]`에 `zone?: string; floor?: number;
labelAnchor?: {x: number; y: number; z: number}` 추가.

### 9. frontend — `MassingResultCard.tsx`

- **hotspot**: `programJson.rooms` 중 `labelAnchor`가 있는 실만
  `<button slot="hotspot-room-{i}" data-position="{x} {y} {z}">` 생성
  (`<model-viewer>` 자식으로). 12px, zone hue 색점 + 실명, `bg-surface/90`
  + `rounded-md` + `shadow-card`. 공용·기타는 라벨 없음
- **Program details 테이블**: `zone` 필드가 있으면 ZONE / ROOM / FLOOR /
  AREA 4열 + zone 그룹핑(첫 행에만 zone 표기). 없으면(구 페이로드·미분할)
  기존 2열 그대로
- hotspot 색점의 zone hue: FE에 팔레트 사본 상수 (등장 순서 5색 순환 —
  serializer와 동일 규칙, 주석으로 상호 참조 고정)

### 10. tests

- `test_derive.py`: floors_max_by_rooms 3케이스 (정상 축소 / 건폐율 충돌
  강등 / 오버라이드 충돌 강등), zone 귀속 규칙, Σ실>합계 강등
- `test_algorithm.py`: FFD 배치(큰 실부터 낮은 층), 공용 잔여 면적 합 검증
  (층별 슬롯 − Σ실), zone 사각형 경계 준수, rooms 없는 zone 기존 동작
- `test_glb_serializer.py`: 같은 zone 실들 hue 동일(HLS H 일치) + lightness
  상이, 공용 톤, zone 키 색상 (기존 테스트는 zone 필드 추가만 반영)
- `test_workflow.py`: programJson.rooms 실별 shape + labelAnchor 존재 검증
  (KFI 픽스처는 Middle Lab 1개 실 → 1 라벨)
- FE: typecheck / lint / build

---

## Error Handling

| 상황 | 동작 |
|------|------|
| 건폐율 캡으로 층수 축소 불가 (가드 1) | 충돌 zone 통짜 강등 + note, 층수 기존 산정값 |
| targetFloors 오버라이드 충돌 (가드 2) | 해당 zone 통짜 강등 + note |
| Σ실 > zone합계 ×0.98 (가드 3) | 해당 zone 통짜 강등 + note |
| sub_spaces 없음 / zone 귀속 실패 | 분할 없음 — 현재와 동일 출력 |
| hotspot 좌표 없는 실 (구 페이로드) | 라벨 미표시, 테이블은 가능한 열만 |

분할 로직의 어떤 실패도 massing 생성 자체를 실패시키지 않는다.

---

## Backward Compatibility

- **wire**: `RoomWire` 신규 필드 전부 optional — 구 카드(히스토리)는 기존
  2열 테이블로 렌더, hotspot 없음
- **히스토리 카드**: `programJson`은 현재도 미영속(스트리밍 턴에서만 존재)
  — 실별 테이블·hotspot도 동일하게 스트리밍 한정. 영속화는 out of scope
- **.3dm**: 실별 박스·레이어가 추가되지만 형식·계약 불변

---

## Out of Scope

- 다층 통합 박스 (대공간/아트리움 표현) — v2
- `programJson` 영속화 (히스토리 카드의 실별 정보 복원)
- `.3dm` 레이어 색상 (사용자가 보류 결정)
- hotspot 클릭 → 실 하이라이트 인터랙션
- 실별 층 지정이 브리프에 명시된 경우의 존중 (추출 스키마 확장 필요 — v2)
