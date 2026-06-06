# programJson in .glb extras — Design Spec

**Date**: 2026-06-05
**Milestone**: M8 (post-fix iteration)
**Prerequisites**: room-split massing (PR #231)

---

## Problem

`programJson`(실별 hotspot 좌표 + 면적표)은 스트리밍 턴의 SSE에만 실려오고
어디에도 영속되지 않는다. `ChatPage`는 턴 종료(`done`) 시 영속 메시지를
refetch해 카드를 교체하므로, 사용자가 답변을 읽고 아코디언을 여는 시점엔
이미 히스토리 카드 — hotspot 라벨과 실별 테이블이 보이지 않는다.

## Decision

DB 영속화(B안) 대신 **`.glb` 아티팩트에 임베드(A안)** — 아티팩트가 자기
메타데이터를 갖고 다닌다. glTF 2.0 `extras`는 임의 JSON을 허용하는 표준
확장점이고, trimesh가 `scene.metadata` → `scenes[0].extras`로 네이티브
직렬화함을 확인했다 (2026-06-05).

## Design

### D1. wire 빌더 단일 소스 추출

`respond.py`의 `_build_rooms_wire` + `_label_anchor`를
`architecture/app/program_wire.py`로 이동 (`build_program_json(boxes,
inputs) -> ProgramJsonWire` 류의 단일 진입점으로 정리). `respond`(HTTP
wire)와 `store_glb`(extras)가 같은 빌더를 호출 — SSE와 extras가 항상
동일 데이터임을 구조적으로 보장한다.

### D2. store_glb — extras 임베드

`serialize_glb(boxes, *, program_json: dict | None = None)` 파라미터 추가.
주어지면 `scene.metadata["programJson"] = program_json` (trimesh →
`scenes[0].extras.programJson`). `store_glb`가 빌더로 dict를 만들어
(`ProgramJsonWire.model_dump(by_alias=True)`) 전달. 크기 영향 ~2KB.
best-effort 원칙 유지: extras 실패도 기존 try/except 안에서 턴을 막지 않음.

### D3. FE — GLB extras 파서 (의존성 0)

`frontend/src/shared/lib/glb-extras.ts` 신규:

```
fetchGlbProgramJson(url): Promise<Record<string, unknown> | null>
```

- `fetch(url)` (same-origin — 게이트웨이 세션 쿠키 자동)
- GLB 컨테이너 파싱: magic `0x46546C67`("glTF") + version 2 검증,
  chunk 0(JSON, length-prefixed) → `JSON.parse`
- `json.scenes?.[scene ?? 0]?.extras?.programJson ?? null`
- 모든 실패(HTTP 에러, magic 불일치, 파싱 실패, extras 없음) → `null`

### D4. FE — 히스토리 카드 복원

`MassingResultCard` result 분기:

- `toolResult.programJson`이 **없고** `outputUrl`이 있으면 마운트 시
  `${outputUrl}/preview`에서 extras를 1회 fetch → 로컬 상태로 보관 →
  `program`으로 사용. 이후 렌더는 스트리밍 카드와 동일 (hotspot + 4열
  테이블 + Program details 토글 노출).
- 스트리밍 카드(`programJson` 있음): fetch 없음, 동작 변화 0.
- 레거시 glb(extras 없음)·fetch 실패: `null` → 지금처럼 테이블/라벨 생략.
  카드 자체(summary/다운로드)는 절대 깨지지 않는다.
- model-viewer가 여는 URL과 동일 — 브라우저 캐시로 중복 다운로드 대부분
  회피. 최악 2×~10KB 수용.

## Testing

- Python: `serialize_glb(program_json=...)` roundtrip — GLB JSON chunk에서
  `scenes[0].extras.programJson` 복원 검증; workflow 테스트에 extras 존재
  assertion 추가; respond와 store_glb가 같은 빌더 출력 사용 검증
- FE: typecheck/lint/build (러너 없음). 파서는 순수 함수 — 수동 검증은
  실제 glb 바이트로 통합 단계에서
- 통합: 새 매싱 생성 → 새로고침 → 히스토리 카드에서 hotspot + 4열 테이블
  복원 확인

## Out of Scope

- DB 영속화 (`message_attachments.program_json`) — A안으로 대체
- `/preview` 응답 Cache-Control 헤더 (후속 최적화)
- 레거시 glb 백필 (extras 재생성)
