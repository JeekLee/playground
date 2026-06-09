# 멀티-턴 매싱 리파인 (Massing Refine) — Design Spec

**Date**: 2026-06-09
**Milestone**: M9 (agentic 확장)
**Prerequisites**: SP4 대화 기반 매싱 (PR #245), 매싱 스트리밍 (PR #233), glb-extras programJson (2026-06-05), agent-tools `architecture` BC (ADR-19/20)

---

## Problem

SP4가 대화로 매싱을 **새로** 만드는 단발 생성을 줬다. 하지만 사용자는 곧바로 **수정**을 원한다 — "2층을 노트북 열람실로", "3층으로 줄여", "열람실 3000으로 키워". 현재 `generate_massing`은 **stateless 단발**이라 "직전 모델 수정" 개념이 없다. 그래서 후속 수정 요청에서 LLM은 매번 **빈약한 새 `requirements`**를 합성해 처음부터 생성하고, 원래 program(대지·연면적·층수)을 유실해 엉뚱한 작은 매싱을 만든다. (라이브 E2E에서 확인: 4층·9800㎡ → "2층 추가" 요청이 2층·3600㎡로 축소됨.)

agentic 어시스턴트라면 "방금 만든 모델을 이렇게 바꿔줘"가 자연스럽게 동작해야 한다.

## Decision (브레인스토밍 결론)

별도 **`refine_massing`** 도구를 추가한다. 핵심 아이디어:

1. **재생용 recipe를 산출물에 심는다.** 생성 시 재도출에 필요한 입력(`ClassifiedBrief` + 해석된 floor_height·target_floors)을 `.glb` extras에 임베드한다. 출력 `programJson`은 그대로 유지(FE 히스토리 카드용).
2. **refine = recipe 로드 → 타입드 편집 연산 적용 → `derive`부터 재실행.** LLM 브리프 추출(느린 단계)은 건너뛴다. 건폐율·용적률·footprint·slot-fit 게이트는 `derive_inputs` 재실행으로 **전부 다시 통과**한다 — 숫자만 바꿔 렌더하는 게 아니다.
3. **타겟은 Attachment 단위.** chat이 세션의 *모델 첨부* manifest(`[YOUR MODELS]`)를 주입하고, LLM이 `baseAttachmentId`로 지목한다. chat이 소유·모델 검증 후 `baseStorageKey`로 해석해 도구에 넘긴다. 비-모델 지목·레거시·비현실 수정은 모두 우아하게 실패한다.

분해 결정: **매스 분할("동 2개로 나눠")은 별도 "다중 매스(N동)" 사이클로 분리** — 현재 단일-스택 알고리즘을 N-footprint로 확장하는 BE+FE 대공사라 refine v1에서 제외. 다중 매스가 들어오면 mass-split은 edit 연산 하나로 자동 편입된다.

---

## D1. recipe 영속화 — `.glb` extras `refineRecipe` (생성 측)

- **무엇을 담나** — `derive_inputs`의 입력 + 해석된 파라미터:
  - `classifiedBrief`: `ClassifiedBrief` 전체 (`zones[grade·area·rooms]`, `sub_spaces`, `footprint_driver_m2`, `site_area_m2`, `coverage_ratio_max`(건폐율), `floor_area_ratio_max`(용적률), `total_gfa_m2`, `floor_limit`, `consistency_note`). → 재도출에 필요한 모든 제약이 여기 있다.
  - `floorHeightM`: 해석된 층고 (`MassingInputs.floor_height_m`).
  - `targetFloorsAbove`: 해석된 지상 층수. refine이 `derive_inputs(req.target_floors=targetFloorsAbove)`로 넘겨 **비-층수 편집 시 층수가 고정**되게 한다.
- **어디에** — `glb_serializer.build_glb`에 `scene.metadata["refineRecipe"] = recipe_dict` 한 줄 추가. trimesh가 `scenes[0].extras.refineRecipe`로 직렬화 (기존 `programJson` 임베드와 동일 메커니즘, glb-extras spec). 출력 `programJson`은 그대로 둔다.
- **왜 `MassingInputs`가 아니라 `ClassifiedBrief`인가** — `MassingInputs`(post-derive)는 게이트가 *통과된* 결과라 재검증 시 건폐율 게이트밖에 못 본다. 용적률·footprint-driver·slot-fit은 `derive_inputs`(입력=`ClassifiedBrief`)에만 있다. 진짜 알고리즘을 거치려면 `ClassifiedBrief`를 담아 `derive`부터 재실행해야 한다.
- **양 경로 공통** — `generate_massing`의 doc·inline 양쪽 산출물 모두 recipe를 담는다 (refine은 출처와 무관). serialize/store 공통 경로 1곳 수정으로 커버.
- **직렬화** — `ClassifiedBrief` 등은 Pydantic이라 `model_dump(mode="json")`로 평범한 JSON dict. trimesh extras는 JSON 직렬화 가능 객체만 받으므로 dict로 변환해 넣는다.

## D2. `refine_massing` 도구 descriptor (chat-domain, Java)

- **신규 `RefineMassingTool`** (`MassingTool`/`SearchTool` 패턴):
  - `name = "refine_massing"`, `displayName = "매싱 수정"`.
  - `INPUT_SCHEMA`: `{ baseAttachmentId: string(uuid), edits: array }`, `required:["baseAttachmentId","edits"]`, `additionalProperties:false`. `edits`는 `minItems:1`, 각 항목 = `{ op: enum["RenameRoom","AddRoom","SetFloors","SetArea"], ... }` — **`op` 디스크리미네이터 + op별 필드의 합집합**(D3). chat 스키마는 `op` enum + 필드 합집합을 느슨히 선언(briefDocId 스타일), **op별 정밀 검증은 agent-tools Pydantic discriminated union**(SP4의 "스키마는 경계, 도메인 검증은 도구" 패턴 그대로).
  - `DESCRIPTION` STRICT TRIGGER: "이미 생성된 **모델(.3dm)을 수정/변경**하는 요청일 때만 — '2층을 X로', 'N층으로 줄여/늘려', '면적을 Y로'. **새 모델 생성은 `generate_massing`**. `[YOUR MODELS]`에서 수정할 모델의 `baseAttachmentId`를 정확히 베껴라(절대 지어내지 말 것). 모델이 여러 개면 사용자에게 어느 것인지 물어라. 수정 대상이 모델이 아니면(문서·이미지 등) 호출하지 말 것."
  - 타임아웃: idle 60s / total 600s (generate와 동일 — derive부터지만 LLM edit-매핑은 chat측, 도구는 재도출+직렬화라 짧지만 여유).
  - `ToolCatalog.descriptors()`에 한 줄 등록.
- **`generate_massing`은 무변경** (recipe 임베드는 agent-tools 측 변경; descriptor/스키마 그대로).

## D3. 편집 연산 (도메인, agent-tools)

타입드 discriminated union. **v1 = 4 연산** (3 카테고리). LLM은 NL→ops 매핑만, 도구가 결정적으로 적용.

| op | 필드 | 의미 | 카테고리 |
|----|------|------|----------|
| `RenameRoom` | `from`(기존 실명), `to`(새 실명) | 명명된 실 이름 변경 | 실/용도 |
| `AddRoom` | `name`, `areaM2`, `zone?` | 명명된 실 추가 (없던 용도 도입) | 실/용도 |
| `SetFloors` | `targetFloorsAbove`(≥1) | 지상 층수 변경 | 층수 |
| `SetArea` | `target`(zone 또는 실명), `areaM2`(>0) | zone/실 면적 변경 | 면적 |

- **적용 형태** — `apply(op, recipe) -> recipe`, op당 순수함수 (단위테스트 1:1). `RenameRoom`/`AddRoom`/`SetArea`는 `classifiedBrief.sub_spaces`(또는 `zones[].area_m2`)를 편집, `SetFloors`는 `targetFloorsAbove` 파라미터를 변경.
- **순차 적용** — `edits[]`를 받은 순서대로 누적 적용 ("2층 바꾸고 3층으로 줄여" = `[AddRoom/Rename, SetFloors]`).
- **층 배치 주의(중요·스펙 명문화)** — 명명된 실의 **층 배치는 알고리즘이 결정**한다(room-split: 명명 실이 낮은 층부터, `공용·기타`가 잔여 층 채움). 즉 v1은 "**2층**에 콕 집어" 같은 명시적 층 지정을 직접 지원하지 않는다. 사용자가 "2층을 노트북 열람실로"라 하면 → `AddRoom(노트북 열람실, ≈1개층 면적)`로 표현되고, 그 실은 program 순서상 다음 낮은 빈 층(일반열람실이 1층이면 2층)에 놓인다. 결과적으로 의도가 충족되는 경우가 많지만 **결정론적 층 핀 고정은 v2 후보**(`Room`에 floor 필드 추가 필요). 이 한계는 DESCRIPTION/사용자 응답에서 정직하게 다룬다.
- **타겟 못 찾음/모호** — `RenameRoom.from`/`SetArea.target`이 recipe에 없으면 명확한 도구 에러(`REFINE_TARGET_NOT_FOUND`) → LLM이 되묻기. (LLM은 직전 `programJson`/manifest로 현재 실 목록을 알고 있어 보통 정확히 지목.)
- **basement** — v1 `SetFloors`는 지상 층수만. 지하 레벨은 `derive`가 below-grade zone 유무로 산정(기존 동작) — 지하 편집은 out of scope.

## D4. refine 워크플로 (agent-tools)

- **신규 라우트** `POST /internal/tools/refine-massing` — 요청 DTO `RefineMassingRequest { baseStorageKey: str, edits: list[EditOp] }`. NDJSON 스트리밍(기존 generate 라우트와 동일 브리지/heartbeat 재사용).
- **흐름**:
  ```
  .glb GET (MinIO, baseStorageKey의 .3dm → 동일 prefix .glb)
   → extras.refineRecipe 파싱  (없으면 RECIPE_NOT_FOUND)
   → edits 순차 apply → 편집된 ClassifiedBrief + params
   → derive_inputs(classified, synthetic_req{target_floors=targetFloorsAbove, floor_height=floorHeightM}, settings)
         ← 건폐율·용적률·footprint·slot-fit 전부 재적용
   → compute → serialize → store_3dm → store_glb (새 refineRecipe 재임베드)
   → respond {result, artifact}
  ```
- **앞단 미실행** — `fetch_brief`/`locate`/`extract`/`reconcile`/`classify`는 안 탄다(느린 LLM 추출 스킵). refine 그래프는 `load_recipe → apply_edits → derive → compute → serialize → store_3dm → store_glb → respond`.
- **스트리밍 스테이지** — refine용 라벨(예: "모델 불러오기" → "수정 적용" → "층수·풋프린트 산정" → "매싱 계산" → "3D 모델 생성" → "파일 저장" → "미리보기 생성"). FE는 라벨 verbatim 렌더. stageCount는 refine 단계 수에 맞춤.
- **체인 수정** — refine 산출물도 새 `.glb`에 *편집 후* `refineRecipe`를 다시 임베드 → refine된 모델을 또 refine 가능.
- **`derive_inputs` req 함정(주의)** — `derive_inputs(classified, req, settings)`는 `req`에서 `target_floors`·`floor_height`**만** 읽는다. refine에서 `GenerateMassingRequest`를 재구성하면 SP4의 exactly-one validator(briefDocId/requirements 정확히 하나)에 걸린다. 따라서 **`GenerateMassingRequest`를 만들지 말고**, `target_floors`/`floor_height` 두 속성만 가진 경량 params 객체(duck-typed)를 넘기거나 `derive_inputs` 시그니처를 두 값 직접 받도록 소폭 리팩터. 구현 시 후자(명시 시그니처)가 더 깔끔.
- **`store_3dm` 키 슬러그** — 기존 `briefslug(detail.title)` 대신 recipe엔 title이 없을 수 있으므로 refine 파일명은 generic 슬러그(예: `massing-refined-<ts>.3dm`) 또는 직전 파일명 파생 — 구현 시 결정(빈/공백은 `"massing"`로 degrade하는 기존 가드 재사용).

## D5. 타겟팅 & manifest (chat)

- **`[YOUR MODELS]` manifest 주입** — chat이 **세션의 모델 첨부**를 모아 프롬프트에 주입. `[YOUR DOCUMENTS]`(`UserDocumentManifestPort`→`PromptTemplate`) 패턴을 따르되 **chat 자기 DB**(`message_attachments`)에서 조회(외부 호출 없음).
  - **모델 첨부 정의**: `kind='tool-artifact'` AND `tool_name ∈ {generate_massing, refine_massing}` AND `filename` `.3dm`.
  - manifest 항목: `{ attachmentId, filename, briefTitle }` (program 내용은 안 실음 — chat은 의미 모름).
  - 세션에 모델 첨부 0개면 manifest 미주입(또는 빈 섹션) → LLM은 refine을 트리거하지 않음.
- **`baseAttachmentId` 해석·검증** (chat-app, dispatch 전): `baseAttachmentId` → attachment 행 조회 → **(a) 호출자 소유 (b) 모델(.3dm)** 확인 → OK면 `storage_key`를 `baseStorageKey`로 치환해 도구에 전달.
  - **이 도구만 args 변형** — SP4의 "args 무변형 통과"에서 의도적으로 벗어남: refine 입력은 "이전 산출물 참조"라 chat만 해석 가능하고, **내부 storage_key를 LLM에 노출하지 않기 위해** id→key 해석을 chat이 한다. chat은 attachment 메타(소유·contentType·filename)만 다루고 program 의미는 모름 → domain-agnostic 유지.
  - agent-tools는 `baseStorageKey`로 MinIO에서 직접 `.glb`를 GET (자기 산출 파일을 포인터로 읽음 — 상태 저장 아님, **stateless 유지**).

## D6. 에러·정합

| 상황 | 처리 |
|------|------|
| `baseAttachmentId` 없음/타 유저/**모델 아님**(PDF·이미지 등) | chat이 dispatch 거부 → tool_error → LLM "그 첨부는 수정 가능한 모델이 아닙니다 / 어떤 모델을 수정할지 알려주세요" |
| 세션에 모델 첨부 0개 | manifest 비어 refine 트리거 안 됨 (또는 "수정할 모델이 없습니다") |
| `.glb`에 `refineRecipe` 없음 (이 기능 이전 **레거시 모델**) | agent-tools `RECIPE_NOT_FOUND`(422) → "이 모델은 수정 정보를 담고 있지 않아 새로 생성해야 합니다" |
| 편집 타겟 못 찾음/모호 (`RenameRoom.from` 등) | `REFINE_TARGET_NOT_FOUND`(422) → LLM 되묻기 |
| 비현실 편집 (3층이 건폐율 초과 등) | `derive`가 `MASSING_ALGORITHM_FAILED`(422) → LLM 설명/대안 (기존 동작 재사용) |
| 정상 편집 | 새 `.3dm`/`.glb` + 결과 카드 (generate와 동일 envelope·다운로드) |

- 신규 에러코드 `RECIPE_NOT_FOUND`, `REFINE_TARGET_NOT_FOUND`는 `MassingErrorCode`에 추가(422 매핑). 기존 `MASSING_ALGORITHM_FAILED` 재사용.

## D7. FE

- **거의 무변경, 한 줄만** — `ToolCardList.tsx`의 카드 게이트(`card.toolCall.name !== 'generate_massing'`)에 `refine_massing`을 허용해 동일 `MassingResultCard`로 렌더. 카드·3D 프리뷰·프로그램 표·다운로드·에러 카드(`MassingErrorCard`)는 입력 도구와 무관하게 동일.
- 스트리밍 스테이지 라벨은 도구가 verbatim으로 주므로 refine용 라벨이 그대로 표시됨(FE 코드 변경 불필요).
- CLAUDE.md frontend pre-flight: 시각적 변경이 아니라 **렌더 도구 허용목록**만 — 레이아웃/스페이싱/색/카피 무변경. 이 spec이 design 소스 역할, 별도 design-doc 변경 불요(필요 시 한 줄 노트).

## D8. 테스트

- **agent-tools**:
  - edit op applier 단위테스트 4종 (RenameRoom/AddRoom/SetFloors/SetArea) — `apply(op, recipe)` 결과 검증.
  - recipe 임베드: generate 산출 `.glb` extras에 `refineRecipe`(ClassifiedBrief) 존재 + 재파싱 가능.
  - refine end-to-end: recipe 담은 `.glb`(테스트 fixture) → edits → `derive` 재실행 → `{result, artifact}` envelope(새 programJson·attachment).
  - 건폐율 초과 편집(`SetFloors` 과소) → `MASSING_ALGORITHM_FAILED`.
  - `refineRecipe` 없는 `.glb` → `RECIPE_NOT_FOUND`.
  - 타겟 못 찾는 `RenameRoom` → `REFINE_TARGET_NOT_FOUND`.
- **chat**:
  - `RefineMassingTool` 스키마(baseAttachmentId + edits, required) + DESCRIPTION + ToolCatalog 등록.
  - `[YOUR MODELS]` manifest 조립: 세션 모델첨부만 필터, 비-모델/타유저 제외.
  - `baseAttachmentId` 해석/검증: 모델→storage_key 치환, 비-모델/없음/타유저 → dispatch 거부(tool_error).
- **통합(수동/라이브)**: 매싱 생성 → "2층을 노트북 열람실로" / "3층으로 줄여" / "열람실 3000으로" → 수정된 카드+프리뷰. PDF 첨부 지목 → 우아한 거부. 레거시(기능 이전) 모델 수정 시도 → "새로 생성" 안내.

## Out of Scope

- **매스 분할 / 다중 매스(N동)** — 별도 "다중 매스" 사이클(BE 알고리즘 + serialize + programJson 스키마 + FE 전반). 들어오면 mass-split refine은 edit 연산으로 편입.
- **명시적 층 핀 고정** ("정확히 2층에") — v1은 알고리즘 배치 순서를 따름. `Room.floor` 도입은 v2 후보.
- **`RemoveRoom` / 지하 레벨 편집** — v1 제외(필요 시 enum 추가로 쉬운 확장).
- **옛 모델 자동 선택 UI / 썸네일 선택기** — manifest + LLM 되묻기로 충분.
- **`generate_massing` 변경** — recipe 임베드는 agent-tools 측만; descriptor/스키마 무변경.
- **ADR 정식 어멘드** — 이 spec이 기록.
