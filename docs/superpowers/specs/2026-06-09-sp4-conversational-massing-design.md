# 대화 기반 매싱 생성 (SP4) — Design Spec

**Date**: 2026-06-09
**Milestone**: M9 (agentic 확장)
**Prerequisites**: 매싱 도구 스트리밍 (PR #233), agent-tools `architecture` BC (ADR-19)

---

## Problem

`generate_massing`(3dm) 도구는 **완전히 문서-앵커**다 — `briefDocId`(업로드된 브리프 PDF)가 필수고, 파이프라인 첫 단계 `fetch_brief`가 docs-api에서 그 문서를 가져온다. 사용자가 브리프를 안 올리고 **대화로 요구사항을 설명**해도("도서관, 대지 4200, 연면적 9800, 일반열람실 2400, 3층") 매싱을 만들 수 없다. agentic 어시스턴트라면 정식 PDF 없이 대화만으로도 스케치 매싱을 생성할 수 있어야 한다.

## Decision (브레인스토밍 A안)

브리프의 **출처를 하나 더 추가**한다 — 인라인 `requirements` 자유 텍스트. 핵심: 공간 프로그램 도출(GROSS/NET·지상/지하 grade·footprint·층수·건폐율)은 agent-tools `architecture` BC가 소유한 도메인 로직이므로 **chat으로 옮기지 않는다**(B안 기각). 브리프 문서와 대화는 둘 다 "공간 프로그램의 출처"일 뿐 — `fetch_brief`만 출처를 추상화하고 `resolve_program`(locate→extract→reconcile→classify→derive) 이하는 무변경 재사용한다. chat은 대화에서 요구사항을 **프로즈로 합성해 전달**하는 오케스트레이터 역할만 한다 (SP3 "chat=corpus/도메인 무지" 철학과 일치).

근거(컨텍스트 매핑): `fetch_brief`가 유일한 docs-api 읽기이고 `state["detail"].body`를 만든다. 이후 노드는 `briefDocId`를 다시 읽지 않으므로, body를 인라인 텍스트로 채우면 파이프라인이 그대로 돈다.

---

## D1. agent-tools — 요청 DTO + `fetch_brief` 분기

- **`GenerateMassingRequest`** (`architecture/api/dtos.py`):
  - `brief_doc_id: UUID | None` (기존 required → optional).
  - **신규** `requirements: str | None` (alias `requirements`). 자유 텍스트 공간 프로그램.
  - **exactly-one 검증** (Pydantic `model_validator`): `brief_doc_id`와 `requirements` 중 정확히 하나만 비어있지 않아야 함. 둘 다 또는 둘 다 없음 → 422 (검증 에러).
  - 기존 optional override(siteWidth/siteDepth/floorHeight/targetFloors) 유지.
- **`fetch_brief` 노드** (`architecture/app/nodes/fetch_brief.py`):
  - `requirements`가 있으면 → docs-api 호출 **스킵**, 합성 `DocsDetailSubset`를 만들어 `state["detail"]`에 넣음. **`DocsDetailSubset`(shared_kernel/models.py)는 `body`/`extraction_status`만 default가 있고 `id`/`author_id`/`visibility`/`title`은 필수 무-default**이므로 전부 채워야 ValidationError가 안 난다 (이 세 필드는 다운스트림에서 안 읽히는 dead 필드지만 Pydantic이 요구):
    ```python
    DocsDetailSubset(
        id=uuid4(),
        author_id=state["user_id"],        # UserContext가 X-User-Id 필수라 항상 존재
        title=<generic 폴백>,               # D4 (non-null)
        body=req.requirements,
        visibility="private",
        extraction_status="extracted",
    )
    ```
  - 아니면(brief_doc_id) → 기존대로 `docs_client.get_document(...)` + extraction_status/body 검증.
  - `routers/tools.py`의 시작 로그 `str(req.brief_doc_id)`는 인라인 경로에서 `"None"`을 찍으므로 mode(doc/inline) 분기로 정리 (minor).
  - 다운스트림(locate/extract/reconcile/classify/derive/compute/serialize/store) **무변경** — `locate`는 짧은 텍스트를 (키워드 매치 시 추출, 아니면 전체) 통과시키고, `extract` LLM은 임의 텍스트에서 facts를 추출(빈 필드 null).

## D2. 대지면적 fail-fast (결정 (가))

파이프라인에서 `site_area_m2`는 **유일한 필수값**이다 — 없으면 `derive`가 `MassingError(BRIEF_NOT_READY)`를 던지고, `resolve_program._route`가 `MAX_EXTRACT_RETRIES`(2)까지 extract를 재시도한 뒤 422로 전파한다. 문서 경로에선 재추출(PDF 재스캔)이 의미 있지만, **인라인 경로에선 같은 짧은 텍스트를 재추출해도 무의미**하다.

- `resolve_program._route`: `BRIEF_NOT_READY` 처리 시 **`state["req"].requirements is not None`(인라인 경로)이면 재시도 없이 즉시 raise**; 문서 경로면 기존 retry 유지. (별도 state 플래그 불요 — `req`는 이미 state에 있음.)
- 이 에러는 도구 error 이벤트로 chat에 전달되고, chat LLM이 사용자에게 **대지면적을 되묻는다**. agent-tools 에러 메시지를 명확히: 예) "대지면적(site area)을 알 수 없습니다. 사용자에게 대지 규모를 물어보세요." (MassingErrorCode BRIEF_NOT_READY 재사용 또는 인라인 전용 메시지 — 구현 시 결정, 메시지는 사용자-되묻기를 유도하도록.)
- 데이터를 지어내지 않음 (명목 대지 역산 = (나) 기각).

## D3. chat — 도구 descriptor

- **`MassingTool.INPUT_SCHEMA`** (`chat-domain/.../tool/MassingTool.java`):
  - `required` 배열에서 `briefDocId` 제거.
  - `properties`에 `requirements` 추가: `{"type":"string", "description":"대화에서 합성한 자유 텍스트 공간 프로그램 — 실 유형·면적·대지면적·층수 등. briefDocId 대신 사용."}`.
  - 둘 다 optional; exactly-one은 agent-tools가 검증(D1). (`additionalProperties:false` 유지.)
- **`DESCRIPTION`/STRICT-TRIGGER 갱신**: "3D 매싱 생성. **정확히 하나의 출처를 제공**하라 — 사용자가 업로드된 브리프를 가리키면 `[YOUR DOCUMENTS]`의 `briefDocId`; 사용자가 대화로 프로그램(실·면적·대지·층수)을 설명하면 대화에서 합성한 자유 텍스트를 `requirements`에. **대지면적을 모르면 호출하기 전에 먼저 사용자에게 물어보라.**" (기존 regen-honesty 단락 유지.)
- args는 chat에서 변형 없이 agent-tools로 통과(`handleToolInvocation` 무변경) — exactly-one/site-area는 agent-tools가 강제.

## D4. 산출물 title (인라인 경로)

문서 경로는 brief title에서 파일명/`briefTitle`을 파생한다. 인라인 경로엔 문서 title이 없으므로 합성 detail의 `title`을 **non-null generic**으로 채운다 (D1의 `title=<generic 폴백>`).
- **주의**: agent-tools는 `briefTitle`을 항상 non-null로 보낸다 (`MassingResult.brief_title: str`, `respond.py`가 `state["detail"].title`을 그대로 사용). 즉 인라인 경로는 "title 부재"가 아니라 **합성이 generic 문자열을 만들어 넣는 것** — FE의 null 처리 경로는 사용되지 않는다. `store_3dm`의 `briefslug(detail.title)`는 빈/공백 title을 `"brief"`로 degrade하므로 파일명도 안전.
- generic title 예: `매싱 요청` 또는 `massing-{date}`, 혹은 사용자가 대화에서 언급한 프로젝트명. 정확한 문자열은 구현 시 결정(데이터 안 지어내되 사람이 읽을 폴백). chat attachment `briefTitle`은 nullable end-to-end라 안전하지만, 실제론 항상 generic 문자열이 채워진다.

## D5. 에러·정합

| 상황 | 동작 |
|------|------|
| briefDocId·requirements 둘 다 / 둘 다 없음 | agent-tools 422 (exactly-one model_validator) |
| requirements + 대지면적 누락 | 즉시 `BRIEF_NOT_READY` (재추출 스킵, D2) → tool error → LLM이 대지 되묻기 |
| requirements 불완전 (건폐율·층수 등 누락, 대지는 있음) | 기존 default·도출 재사용 (coverage 0.6 등) — 정상 생성 |
| briefDocId 경로 | 기존과 100% 동일 (회귀 없음) |
| 대화로 충분한 요구사항(대지 포함) | 정상 생성 — 브리프 PDF와 동일한 .3dm/.glb 산출 |

## D6. FE

**무변경**. 산출물(.3dm/.glb)·결과 카드·미리보기·다운로드는 입력 출처와 무관하게 동일. 스트리밍 10단계 중 `fetch_brief`(stage 1, "브리프 조회") 라벨이 인라인 경로엔 의미상 약간 안 맞지만 즉시 통과(네트워크 없음)라 무해 — 선택적 relabel은 out of scope.

## D7. 테스트

- agent-tools:
  - `GenerateMassingRequest` exactly-one 검증 (둘 다 → 에러, 둘 다 없음 → 에러, 하나만 → ok).
  - `fetch_brief` 인라인 분기: requirements 주어지면 docs_client **미호출**(mock 0회), 합성 detail(body=requirements, status="extracted") 생성.
  - 인라인 end-to-end: 대지면적 포함 requirements 텍스트 → `MassingInputs` 산출(zones/site/floors). 대지면적 누락 requirements → `_route`가 **재시도 없이** 즉시 BRIEF_NOT_READY (extract 1회만 호출 단언).
  - 문서 경로 회귀: 기존 fetch_brief/resolve_program 테스트 green.
- chat: `MassingTool` 스키마에 `requirements` 존재 + `briefDocId` non-required; DESCRIPTION 갱신. (ToolCatalog/스키마 테스트가 있으면 갱신.)
- 통합(수동/라이브): 대화로 "도서관, 대지 4200, 연면적 9800, 일반열람실 2400, 3층" → 매싱 생성 카드+미리보기. 대지 누락 시 LLM이 되묻는지.

## Out of Scope

- **B안** (chat-측 program 구조화 — 도메인 도출을 chat으로 이관). 기각.
- 멀티-턴 매싱 **리파인**("3층으로 줄여줘", "동 2개로 나눠줘" 류 기존 매싱 수정).
- 음성/이미지 입력, 명목 대지 역산((나) 기각).
- `fetch_brief` 스트리밍 라벨 relabel.
- ADR-19 정식 어멘드 (spec이 기록).
