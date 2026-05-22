# PRD: M8 — `massing-gen` BC + brief-to-massing tool

> **Source of truth:** `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §6 (M8 — `massing-gen` BC). 이 PRD는 사용자 / 리뷰어가 읽는 표면이고, `.3dm` 라이브러리 좌표(rhino3dm Node sidecar 버전 / 컨테이너 이미지), Korean brief 추출 prompt 템플릿, 출력 JSON Schema의 정확한 field 집합, MassingAlgorithm 의 over-area 처리 정책, 파일 저장 매체(BYTEA vs MinIO), orphan cleanup 전략, 그리고 ADR-08 amendment의 정확한 wording 같은 기술 컨트랙트는 **ADR-18**(`docs/adr/18-m8-massing-gen.md` — 후속 작성 예정 / 본 PRD가 raise하는 open questions를 닫는다)이 우선한다. Section references like "spec §6"는 그 spec 안의 위치를 가리킨다.
>
> **Parallel work:** 본 PRD와 함께 묶이는 per-milestone ADR(**ADR-18**)이 architect의 별도 세션에서 작성된다 — `rhino3dm` 라이브러리 결정(Node sidecar 채택 확정 / 대안 평가), Korean architecture brief 추출 prompt 템플릿 + few-shot 예시, 출력 `programJson` JSON Schema의 required / optional field 집합, MassingAlgorithm 의 over-area / over-height 처리 정책(error vs auto-adjust), `arch.outputs.file_bytes` 저장 매체(BYTEA P0 vs MinIO 즉시 도입), orphan cleanup 정책(cascade vs soft-delete), ADR-08 amendment의 정확한 wording(M6.1에서 Exception 1이 retired되었으므로 M8은 fresh exception이 필요), M8 LLM 호출 형태(Spring AI ChatClient vs 직접 WebClient), summary 문구의 i18n 처리(Korean vs 영어), tool descriptor parameter granularity 핀. **Stage-2 design은 이미 land되어 있다** — `docs/design/M6-M8-brief-to-massing.md` §2.3 / §2.4 / §2.5가 본 PRD와 같은 cycle에서 추가 변경 없이 그대로 사용된다.
>
> **Decision context:** spec §6의 in-scope 리스트는 다음 핵심 컴포넌트로 closed돼 있다 — `massing-gen-{api,app,domain,infra}` 4-module quadruplet, `POST /internal/tools/generate-massing` 엔드포인트, `BriefProgramExtractor` LLM call + JSON Schema validation, `MassingAlgorithm`(Spring-free, `-domain`), `Rhino3dmAdapter` → Node sidecar, `arch` schema + `arch.outputs` 테이블, `GET /api/arch/outputs/{id}` owner-only 다운로드, `rag-chat-domain.ToolCatalog` 에 `MassingTool` descriptor 추가. 본 PRD는 이 closed 리스트에 한정해서 사용자 시나리오 + 수락 기준을 명문화하고, spec §6의 "Key open questions for ADR-18" 9개 + 본 PRD가 surface한 2개의 추가 question을 본 PRD의 "Open questions" 섹션으로 운반한다 — 닫지 않는다. **scope creep 방지** invariant: non-rectangular site footprint, atrium / setback / irregular floor plate, multi-floor optimization, Grasshopper `.gh` 출력, 매싱 thumbnail / cover image, compliance verification 모드, floor plan 생성은 모두 본 PRD에서 명시적으로 OUT.

## 한 줄 설명

신규 `massing-gen` BC가 brief 문서 ID를 받아 LLM(Qwen3-32B via spark-inference-gateway)으로 room program을 추출하고, Spring-free `MassingAlgorithm`이 rectangular first-fit으로 box 리스트를 만들고, `rhino3dm-bridge` Node sidecar가 그것을 `.3dm` 바이너리로 직렬화해 `arch.outputs` 테이블에 저장하며, `/api/arch/outputs/{id}` owner-only 다운로드 엔드포인트와 `rag-chat`의 `ToolCatalog`에 등록된 `generate_massing` descriptor를 통해 chat 표면의 첫 번째 end-to-end 도메인 도구로 노출된다.

## Summary

M8은 **playground의 첫 번째 concrete tool BC**이자 **순수 docs/chat 플러밍을 넘어선 첫 end-to-end 도메인 기능**이다. M7(rag-chat tool-calling infra)이 M7 출시 시점에 `ToolCatalog.descriptors()`를 0개로 들고 있었고, M8은 그 catalog에 첫 entry를 추가하는 **단일 PR**을 통해 chat 표면에 "noun-verb" 방식의 tool-invocation 능력을 실제로 켠다. M7 infra가 같은 cycle에서 검증된 generic 무대 위에서, M8은 그 무대의 첫 번째 공연자(domain tool)이다.

vertical narrative: (1) architect가 M6/M6.1로 업로드한 경쟁 brief PDF가 `docs.documents` 테이블에 markdown body로 저장된 상태, (2) architect가 `/chat`에서 "이 brief 보고 매싱 만들어줘. 대지 20m × 10m, 층고 3.5m로" 같은 turn을 보냄, (3) LLM이 `tool_call(generate_massing, {briefDocId, siteWidth, siteDepth, floorHeight})` 으로 응답, (4) M7의 `ToolDispatcher`가 `POST http://massing-gen-api:18083/internal/tools/generate-massing` 호출(`X-User-Id` + `X-User-Sub` 헤더 forwarding 포함), (5) `massing-gen-app.BriefProgramExtractor`가 brief body를 fetch(메커니즘 = ADR-18 open question; cross-schema SELECT vs HTTP fresh exception)하고 Spring AI ChatClient로 Qwen3-32B에 Korean architecture system prompt + brief body를 보내 structured `programJson` 추출, (6) JSON-Schema validator가 추출 결과를 검증(실패 시 `tool_error` `SCHEMA_INVALID` 또는 M8-specific `BRIEF_EXTRACTION_FAILED`), (7) `massing-gen-domain.MassingAlgorithm`(Spring-free pure Java)이 program + site footprint + floor height를 받아 `List<RoomBox>` 결정, (8) `massing-gen-infra.Rhino3dmAdapter`가 box 리스트를 JSON으로 sidecar에 POST → `.3dm` 바이너리 응답, (9) 바이너리 + programJson + 메타데이터가 `arch.outputs` row로 persist(owner = `X-User-Id`), (10) tool 응답 JSON(`{fileUrl, programJson, totalAreaM2, floorCount, summary}`)이 ToolDispatcher로 돌아가 LLM의 다음 turn context에 주입, (11) LLM이 자연어 마무리("Brief에서 12실을 추출했어요. 1층에 로비+카페테리아…"), (12) SSE stream에 `tool_result` event가 emit되어 frontend가 `📁 generate_massing` 카드 + `↓ Download .3dm` 버튼을 렌더, (13) architect가 다운로드 → Rhino에서 열어 디자인 작업 시작.

저항성(resilience) 측면에서는 **두 개의 외부 호출**이 격리된다. (a) Spring AI ChatClient → spark-inference-gateway LLM call은 M4 ADR-14 §4의 `spark-gateway` Resilience4j 브레이커를 재사용한다. (b) `Rhino3dmAdapter` → `rhino3dm-bridge` sidecar HTTP call은 새 per-sidecar Resilience4j 브레이커 인스턴스(`rhino3dm-bridge`)로 보호된다 — sidecar 임계치는 ADR-14 §4의 `spark-gateway` template 재사용(50% over 60s window, minimum 10 calls, 30s OPEN, 1 half-open probe). 호출은 모두 동기 — M7과 동일하게 Kafka 없음.

지금 출시하는 이유: M7이 generic infra만 ship하고 user-visible feature를 0개로 마감했기 때문에, M8이 첫 concrete tool로 합류해야 brief-to-massing vertical이 **사용자가 실제 사용 가능한** 단계에 도달한다. M6(PDF 업로드) + M7(tool-calling infra) + M8(첫 도메인 tool) 셋이 모여 처음으로 playground가 단순 docs/chat 플랫폼을 넘어 **architectural early-stage automation**의 표면을 노출한다.

본 PRD는 ADR-08을 직접 amend하지 않는다 — ADR-18이 (a) `rag-chat-api` → `massing-gen-api` Exception 4 sub-row 추가, (b) `massing-gen-api` → `docs-api` brief body fetch 경로의 새 ADR-08 amendment 또는 cross-schema SELECT 정당화의 정확한 wording을 핀한다.

## User personas

| 페르소나 | 핵심 동기 | M8에서 가능한 것 |
|---|---|---|
| **Authenticated architect (primary)** | 자신이 업로드한 경쟁 brief PDF로부터 1차 매싱(.3dm)을 LLM과의 자연 대화로 받아 Rhino에서 디자인 작업의 시발점으로 사용 | `/chat`에서 brief 문서를 reference하며 "매싱 만들어줘. 대지 20m × 10m, 층고 3.5m로" 요청 → LLM이 `generate_massing` tool 호출 → `📁 generate_massing` 카드 + `↓ Download .3dm` 버튼 + `▾ Program details` accordion 렌더. 다운로드된 `.3dm`은 Rhino에서 정상 열림. summary 한 줄로 "12 rooms · 3 floors · 480 m² total" 확인. |
| **Authenticated architect (secondary — error path)** | brief가 아닌 PDF (CV / 마케팅 자료 / 스캔 손상)로 매싱을 시도한 경우 명확한 실패 이유와 다음 단계 | `tool_error` 카드(`warning` 팔레트) + `code: BRIEF_EXTRACTION_FAILED` + 사용자-actionable secondary action `↗ Try a different brief` (→ `/docs/new`). LLM이 자연어로 "이 PDF에서 room program을 찾지 못했어요. 다른 brief을 시도해보세요" 사과. composer는 비활성화되지 않음 — chat-level 실패가 아니라 artifact-specific 실패. |
| **rag-chat operator (= future-self, indirect)** | 한 tool BC가 망가졌을 때(`rhino3dm-bridge` OOM / sidecar timeout / LLM 추출 오류) 전체 chat 표면이 영향받지 않게 격리 | `rag-chat-domain.ToolCatalog`에 등록된 `generate_massing` descriptor의 per-tool Resilience4j 브레이커가 5xx burst를 격리 (M7 ADR-17 §5 — `tool-generate_massing` breaker name). sidecar는 별도 자체 브레이커(`rhino3dm-bridge`)로 더 격리. tool 호출이 30s timeout을 넘기면 M7의 `TIMEOUT` enum 코드로 SSE event 발행. operator metric: `resilience4j_circuitbreaker_state{name="tool-generate_massing"}` Grafana 대시보드(M5)에서 모니터링. |
| **Tool BC author (= future-self, tertiary — pattern setter)** | M8 이후 두 번째 tool BC(예: `slide-gen`)를 추가할 때 M8을 패턴 reference로 사용 | M8이 "tool BC 4-module quadruplet + Exception 4 sub-row + `ToolCatalog` 1줄 등록 + own schema + own download endpoint" 패턴을 처음으로 인스턴스화한다. 다음 tool BC는 M8을 복제 + 도메인 로직만 교체. |

## User stories with acceptance criteria

> 본 마일스톤은 **end-user에게 보이는 첫 번째 도메인 기능**이다. Story 1–5는 architect의 happy path, Story 6–8은 error path, Story 9–11은 tool BC author / operator 관점, Story 12는 M7 / M4 regression invariant.

### Authenticated architect — happy path

#### Story 1 — chat에서 brief를 reference하면 LLM이 `generate_massing`을 자동 호출
> As an authenticated architect with an already-uploaded brief PDF, I want to ask the chat "이 brief 보고 매싱 만들어줘. 대지 20m × 10m, 층고 3.5m로" so that the LLM autonomously decides to invoke the `generate_massing` tool — I don't need to know the tool's name.

- [ ] `rag-chat-domain.ToolCatalog.descriptors()`가 `generate_massing` `ToolDescriptor`를 carry한다 (M7 ADR-17 §1.1 + spec §6).
- [ ] descriptor의 `description` 필드가 "Given a brief document ID, extract the room program and generate a basic stacked massing model. Returns a Rhino .3dm file URL and a summary." (또는 동등 의미) — LLM이 tool 선택 결정에 사용. 정확한 wording은 ADR-18.
- [ ] `parameterSchema` (JSON-Schema string) 가 다음 shape을 노출:
  ```json
  {
    "type": "object",
    "required": ["briefDocId"],
    "properties": {
      "briefDocId": { "type": "string", "format": "uuid", "description": "ID of the brief document already uploaded to /docs" },
      "siteWidth": { "type": "number", "description": "Site width in meters (optional; default extracted from brief or 20)" },
      "siteDepth": { "type": "number", "description": "Site depth in meters (optional; default extracted from brief or 10)" },
      "floorHeight": { "type": "number", "default": 3.5, "description": "Floor-to-floor height in meters" }
    }
  }
  ```
  정확한 description copy / required-vs-optional 결정은 ADR-18 (예: brief에서 site 차원을 추출 못 했을 때 numerical default 적용 정책).
- [ ] `endpoint` 필드 = `http://massing-gen-api:<port>/internal/tools/generate-massing` — port는 ADR-18 (open question: spec §6은 18086 제안, 그러나 ADR-01 §A01.3는 18086을 metrics-api가 점유 중임을 명시 — open question Q-A 참조).
- [ ] `timeout` 필드 = `Duration.ofSeconds(60)` (working — ADR-18 핀. spec §5 working 값 30s 는 LLM extraction call 포함 시 부족할 수 있어 60s 검토).
- [ ] M7의 `WebClientToolDispatcher`가 LLM 응답에서 `tool_call(generate_massing, ...)`을 감지하면 descriptor lookup → WebClient POST → massing-gen-api 호출 (M7 ADR-17 §1).

#### Story 2 — `POST /internal/tools/generate-massing` 가 brief를 매싱으로 변환
> As the tool dispatcher invoking massing-gen, I want the BC to read the brief body, extract a room program, run the algorithm, serialize a `.3dm`, persist the row, and return a JSON summary — all in one synchronous round-trip — so that rag-chat can fold the result into the next LLM turn.

- [ ] `POST /internal/tools/generate-massing` 요청 body shape:
  ```json
  {
    "briefDocId": "11111111-2222-3333-4444-555555555555",
    "siteWidth": 20.0,
    "siteDepth": 10.0,
    "floorHeight": 3.5
  }
  ```
  `briefDocId` 필수; 나머지 optional.
- [ ] 요청 헤더 `X-User-Id`, `X-User-Sub`이 ToolDispatcher로부터 forwarding되어 도착해야 한다 (M7 ADR-17 + ADR-08 amendment A08.8 Exception 4). 두 헤더가 누락되면 `400 MISSING_USER_HEADERS` (또는 정확한 코드 — ADR-18).
- [ ] 응답 shape:
  ```json
  {
    "fileUrl": "/api/arch/outputs/<uuid>",
    "programJson": {
      "rooms": [
        { "name": "강의실 #1", "areaM2": 80.0 },
        ...
      ],
      "siteWidthM": 20.0,
      "siteDepthM": 10.0,
      "floorHeightM": 3.5
    },
    "totalAreaM2": 480.0,
    "floorCount": 3,
    "summary": "12 rooms · 3 floors · 480 m² total"
  }
  ```
  - `fileUrl`은 **relative URL** (gateway-issued session cookie가 다운로드 endpoint에 `X-User-Id`를 자동 전달; M7 ADR-17 §3 + spec §6).
  - `summary`의 정확한 i18n / 표기는 ADR-18 — design doc §2.3은 "12 rooms · 3 floors · 480 m² total" (영어+숫자 중립) 을 reference로 fix. open question Q-B: Korean vs English.
- [ ] 응답 페이로드 size cap = M7 ADR-17 §4의 16 KiB. programJson 안의 rooms 배열이 cap을 넘기면 truncate-and-warn이 적용된다 (M7 ADR-17 §4 정책 그대로). 30실 program 이 typical 한도 — ~5-10 KiB. 일반 brief는 cap 미달.
- [ ] `RoomBox`(domain VO)가 algorithm 결과로 만들어지고, 그것이 sidecar payload `{boxes: [{x, y, w, d, h, roomName, floor}, ...]}` 으로 직렬화되어 sidecar의 `/serialize` 호출에 사용된다.
- [ ] sidecar 응답 `.3dm` 바이너리가 `arch.outputs.file_bytes`(BYTEA P0; ADR-18이 MinIO 즉시 도입 결정할 수 있음)로 저장된다.

#### Story 3 — frontend가 `tool_result` 카드를 chat에 렌더
> As the architect waiting for the tool to complete, I want a structured `tool_result` card to appear below the LLM's assistant message — with tool name, summary, primary Download button, and an optional Program details accordion — so that I can immediately download the `.3dm` without scrolling through prose.

- [ ] design doc `docs/design/M6-M8-brief-to-massing.md` §2.3 (frame `78:1347`)이 happy-path 의 정확한 visual을 핀:
  - 카드 container 820 × 120 (collapsed), `surface` bg + `border` 1px + `radius.md` + `shadow.card`
  - 리딩 아이콘 `📁` 22px
  - tool 이름 `generate_massing` (font.body 15/600/text)
  - summary `12 rooms · 3 floors · 480 m² total` (font.small 13/500/text)
  - 1차 액션 `↓ Download .3dm` (144 × 32, accent bg, white label 13/600, anchored top-right)
  - 아코디언 `▸ Program details` (collapsed) at card bottom-left
- [ ] SSE `tool_result` event 의 `result` payload가 위 wire shape(Story 2)을 carry한다. frontend의 카드 렌더 컴포넌트가 그 payload에서 (`fileUrl`, `summary`, `programJson`) 을 mapping한다.
- [ ] `↓ Download .3dm` 버튼은 plain `<a href={fileUrl} download>` — JS fetch 없음. browser session cookie가 gateway에 의해 자동 첨부 → `X-User-Id`가 `/api/arch/outputs/{id}` 핸들러에 도착.
- [ ] 카드는 LLM의 자연어 메시지 **아래**에 렌더 (병행, 대체 아님). LLM은 여전히 prose로 무엇을 했는지 묘사한다 (design doc §2.3의 assistant body 예시).

#### Story 4 — `▾ Program details` 아코디언이 추출된 room program을 표로 보여줌
> As the architect wanting to verify the LLM's extraction before downloading, I want to click `▾ Program details` and see a 4-column table (FLOOR / ROOM / DIMENSIONS / AREA) of the rooms — so that I can confirm the matching against my mental model of the brief.

- [ ] aria 아코디언 expanded state visual은 design doc §2.4 (frame `78:1392`)에 핀.
- [ ] 카드 height: collapsed 120px → expanded ~340px. 카드는 chat scroll 영역 안에서 아래로 grow (composer는 viewport-locked로 고정; design doc §1.2).
- [ ] table 의 헤더: `FLOOR / ROOM / DIMENSIONS / AREA` (eyebrow 11/600/text.muted).
- [ ] table rows = programJson의 rooms 첫 6개 (또는 implementer 결정 — 전체 scrollable도 허용).
- [ ] table footer: `… and N more rooms across floor M (총 K실, L m²)` (font.small 12/500/text.muted) — 6 행 초과 시 표시.
- [ ] expand 후에도 `↓ Download .3dm` 버튼 위치 변동 없음 (top-right anchor; design doc §1.2).

#### Story 5 — `.3dm` 파일이 Rhino에서 정상 열림
> As the architect downloading the `.3dm`, I want the file to open in Rhinoceros without errors and contain box geometry labeled by room name so that I can immediately start massing-on-massing iteration.

- [ ] `GET /api/arch/outputs/{id}`이 `Content-Type: application/octet-stream` + `Content-Disposition: attachment; filename="massing-<briefSlug>-<timestamp>.3dm"` 헤더로 BYTEA(또는 MinIO에서 stream — ADR-18) 응답.
- [ ] Rhino 7+ 에서 파일 열기 시 에러 없음. 각 RoomBox 가 별도 closed brep / extrusion (sidecar 출력 — `rhino3dm` 라이브러리의 `Extrusion.Create()` 또는 동등 메서드) 으로 보임.
- [ ] (P0 working) 각 box 의 layer 이름 또는 user text attribute로 `roomName`이 보존된다. 정확한 메타데이터 channel (layer vs user text vs attribute name) 은 ADR-18.
- [ ] box geometry의 총 면적 (각 box의 width × depth 합) ≥ programJson의 `totalAreaM2`. 약간 over-area 허용 (rectangular packing의 빈틈 보상). 정확한 tolerance(예: ≤ 1.2× over) 는 ADR-18.
- [ ] 어떤 box도 site footprint(width × depth)를 침범하지 않음 — `siteWidthM × siteDepthM` 박스 안에 모든 floor의 layout이 fit.
- [ ] `floorCount = ceil(totalAreaM2 / (siteWidthM × siteDepthM))` (P0 algorithm — ADR-18이 정확한 수식 finalize. over-floor / over-height 처리 정책도 ADR-18).

### Authenticated architect — error path

#### Story 6 — non-brief PDF 업로드 시 graceful `tool_error`
> As an architect mistakenly trying to generate massing from a non-brief PDF (e.g., CV, marketing flyer, scanned image with no recognizable rooms), I want a clear `tool_error` card with a user-actionable secondary action — NOT a fake `.3dm` or a generic 500 — so that I know to try a different file.

- [ ] LLM extraction이 빈 / sparse programJson 을 반환하거나 JSON-Schema validation을 위반하면 `massing-gen-app`가 `400 BRIEF_EXTRACTION_FAILED` 응답 (`code` 필드 + user-facing message). 정확한 HTTP status 매핑은 ADR-18 (400 vs 422).
- [ ] M7의 `WebClientToolDispatcher`가 이 4xx를 받아 `tool_error` SSE event with `code: "UPSTREAM_4XX"` (M7 ADR-17 §2 enum)를 emit. message에는 M8 BC의 `BRIEF_EXTRACTION_FAILED` reason이 carry된다.
- [ ] frontend가 `tool_error` 카드를 design doc §2.5 (frame `78:1437`) 의 `warning` 팔레트로 렌더:
  - 카드 container 820 × 120, `warning.soft` bg + `warning` 1px border + `radius.md`
  - 리딩 아이콘 `⚠` (warning fg) 22px
  - tool 이름 `generate_massing` (warning fg 15/600)
  - summary `Could not extract room program — is this a competition brief PDF?` (font.body 15/500/text)
  - code line `code: BRIEF_EXTRACTION_FAILED  ·  3.2s elapsed` (font.small 12/500/text.muted)
  - 2차 액션 `↗ Try a different brief` (200 × 32, surface bg, warning border, warning fg 13/600)
- [ ] 2차 액션 클릭 → `/docs/new` 로 navigate (chat session 보존 — design doc §2.5 interactions).
- [ ] composer 활성화 유지 (artifact-level 실패; chat-level fatal 아님 — design doc §2.5).
- [ ] LLM은 자연어로 사과 + 진단 + 다음 단계 ("이 PDF에서 room program을 찾지 못했어요. 다른 brief을 시도해보세요" verbatim Korean — design doc §2.5 의 assistant body 예시).

#### Story 7 — sidecar timeout 시 사용자가 인지 가능한 `tool_error`
> As an architect whose massing-gen call hits the sidecar timeout (long-running .3dm serialization or sidecar OOM), I want the chat to surface a `TIMEOUT` `tool_error` quickly rather than hanging — so that I can retry or move on.

- [ ] `Rhino3dmAdapter`의 sidecar HTTP call에 timeout이 적용된다 (working — 30s; ADR-18 핀). 초과 시 `Rhino3dmAdapter`가 timeout exception → `massing-gen-api`가 `504 SIDECAR_TIMEOUT` 응답 (또는 `503` — ADR-18).
- [ ] M7의 dispatcher가 이를 receive — descriptor 자체의 timeout(60s working)과 별도. sidecar가 timeout을 trip시킨 경우 dispatcher는 `UPSTREAM_5XX` (또는 별도 처리) 이벤트 emit.
- [ ] frontend `tool_error` 카드: summary는 `Massing generation timed out (sidecar took longer than expected)`, code `code: TIMEOUT  ·  30.0s elapsed`, 2차 액션 `↻ Retry` (design doc §2.5 Per-state TIMEOUT 항목).
- [ ] sidecar 자체 Resilience4j 브레이커(`rhino3dm-bridge`)가 timeout burst 후 OPEN 상태가 되면 후속 호출은 즉시 실패 (M7 ADR-17 §5 isolation pattern과 동일 — 별도 breaker name).

#### Story 8 — over-area brief에 대한 명확한 fail
> As an architect whose extracted program total area exceeds what the algorithm can fit on the given site × max floor count, I want a clear `MASSING_ALGORITHM_FAILED` error rather than a corrupt `.3dm` or silent floor-explosion.

- [ ] MassingAlgorithm 이 `total room area > site footprint × max floors`(working — max floors = 10 default; ADR-18 핀) 케이스에 대해 `AlgorithmTolerance` exception 을 throw (또는 동등 도메인 에러).
- [ ] `massing-gen-api`가 이를 `422 MASSING_ALGORITHM_FAILED`로 매핑 (또는 `409` — ADR-18). LLM 추출 자체는 성공, algorithm 단계에서 실패한 케이스.
- [ ] frontend `tool_error` 카드: summary `Room program exceeds maximum buildable volume — narrower site or fewer rooms required`, code `MASSING_ALGORITHM_FAILED`, 2차 액션 `↻ Retry with different inputs` (또는 design doc §2.5 의 generic `↻ Retry`).
- [ ] LLM의 자연어 응답이 사용자에게 site를 키우거나 room을 줄이라는 hint를 제공 — 정확한 prompt 동작은 ADR-18.
- [ ] **Open question (ADR-18):** over-area 케이스에서 algorithm 이 auto-adjust(floor count 자동 증가)할지 vs error throw 할지의 정책. P0 working = error throw (사용자 control 보존).

### Tool BC author / operator — registration + resilience

#### Story 9 — `MassingTool` descriptor가 같은 PR에서 `ToolCatalog`에 추가됨
> As the M8 tool BC author, I want my `MassingTool` descriptor to land in `rag-chat-domain.ToolCatalog` as part of the same PR set so that M8 ship and tool registration are atomic.

- [ ] `rag-chat-domain.ToolCatalog.descriptors()` 가 0 entries → 1 entry (`generate_massing`)로 전이 (M7 ADR-17 §1.1 invariant — descriptors() 호출 자체는 동일 API).
- [ ] descriptor 등록은 `ToolCatalog` 클래스 내 single static field 추가 또는 동등 형태 (정확한 등록 메커니즘은 ADR-17 §1.1 = `MassingTool` static field in M8-owned `rag-chat-domain.tool.MassingTool` class, registered via `ToolCatalog`'s assembly method).
- [ ] descriptor의 immutable property들이 위 Story 1 의 shape을 가진다.
- [ ] Spring annotation 없음 — `rag-chat-domain`의 Spring-free invariant 유지 (M4 ADR-14 / M7 ADR-17 invariant).
- [ ] M7 cycle 의 합성 `echo` test fixture(M7 PRD Story 10)는 그대로 유지 — `generate_massing` 추가가 fixture를 깨면 안 된다.

#### Story 10 — per-tool Resilience4j 브레이커 두 개 (tool-level + sidecar-level)
> As the operator, I want two independent circuit breakers protecting the M8 critical path — one for the `generate_massing` tool overall (M7's per-descriptor pattern), and one for the `rhino3dm-bridge` sidecar — so that sidecar failures don't poison the LLM extraction layer and vice versa.

- [ ] M7 ADR-17 §5 의 per-descriptor 브레이커 `tool-generate_massing` 가 `WebClientToolDispatcher`에 등록된다 (M7 invariant — M8이 새로 만들지 않고 자동 등록).
- [ ] `massing-gen-infra`에 새 Resilience4j 브레이커 `rhino3dm-bridge` 가 등록된다 (M8 신규). 임계치는 M7 ADR-17 §5 의 `spark-gateway` template 재사용 (50% over 60s window, minimum 10 calls, 30s OPEN, 1 half-open probe). 정확한 수치 핀은 ADR-18 — working = M7과 동일.
- [ ] LLM 추출 call (Spring AI ChatClient → spark-inference-gateway) 은 M4 ADR-14 §4 의 기존 `spark-gateway` 브레이커를 **재사용** — M8이 새 인스턴스를 만들지 않는다 (또는 dedicated `spark-gateway-massing` 별도 인스턴스 — ADR-18 결정).
- [ ] WireMock(혹은 동등 sidecar stub) 통합 테스트가 sidecar 5xx burst → `rhino3dm-bridge` 브레이커 OPEN → 다음 호출이 sidecar HTTP call 없이 즉시 실패 → `massing-gen-api` 가 5xx 응답 → dispatcher 가 `UPSTREAM_5XX` 발행을 확인.
- [ ] sidecar 브레이커 OPEN 상태에서 generate_massing 호출이 빠르게(LLM 호출은 발생 — 비용 발생) 실패하는 vs LLM 호출 자체도 skip 하는 정책 — ADR-18.

#### Story 11 — `arch.outputs` 행이 owner-tagged + tenant-isolated
> As the operator, I want every generated `.3dm` to be owner-tagged with the originating `X-User-Id` so that the download endpoint can enforce owner-only access and so that future per-user quotas have a foundation.

- [ ] `arch.outputs` 테이블 schema (P0 working — ADR-18 finalize):
  ```sql
  CREATE TABLE arch.outputs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brief_doc_id    UUID         NOT NULL,                  -- docs.documents.id (app-level FK)
    user_id         UUID         NOT NULL,                  -- identity.users.id (app-level FK)
    file_bytes      BYTEA        NOT NULL,                  -- the .3dm binary (P0; MinIO 대안은 open question)
    program_json    JSONB        NOT NULL,                  -- the extracted room program
    total_area_m2   REAL         NOT NULL,
    floor_count     INT          NOT NULL,
    summary         TEXT         NOT NULL,                  -- summary string emitted to chat
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
  );
  CREATE INDEX idx_arch_outputs_user ON arch.outputs (user_id, created_at DESC);
  CREATE INDEX idx_arch_outputs_brief ON arch.outputs (brief_doc_id);
  ```
  Flyway migration 파일 이름은 ADR-18 (예: `V20260523xxxx__arch_outputs.sql`).
- [ ] `arch` schema 가 도입되며 `massing-gen-api`의 Hikari `connection-init-sql`이 `SET search_path TO arch, public` 으로 설정된다 (ADR-05 schema-per-BC invariant).
- [ ] row insert 시 `user_id`는 ToolDispatcher가 forwarding한 `X-User-Id` 헤더 값을 사용 (M7 ADR-17 §3 invariant — request-scoped `UserId` 또는 동등 핀 mechanism).
- [ ] `GET /api/arch/outputs/{id}` 호출 시 gateway가 cookie session에서 `X-User-Id`를 첨부 → `massing-gen-api`가 `user_id == X-User-Id` 검증 → 다르면 `404` (M2 docs BC의 tenant-isolation pattern 미러; ADR-12 §10에서 핀).
- [ ] **Orphan cleanup policy (ADR-18 open question):** `arch.outputs.brief_doc_id`가 가리키는 `docs.documents` row가 삭제되었을 때 cascade(SET NULL 또는 DELETE) vs soft-delete vs untouched. P0 working = untouched (`arch.outputs`는 brief 삭제와 무관하게 영구).

### Cross-milestone — M7 / M4 regression invariant

#### Story 12 — `ToolCatalog` 등록 외에 rag-chat 코드 0줄 변경
> As an end user using the chat surface without M8's tool (in the rare deployment where M8 BC is down but rag-chat is up), I want chat to behave exactly as M7 — generic infra works, just no `generate_massing` available — so that M8's failure mode is isolated to M8 itself.

- [ ] `rag-chat-{api,app,domain,infra}`의 코드(`ToolCatalog`의 1줄 등록 외) 가 본 PRD cycle PR set에서 0줄 변경된다 — M7 invariant.
- [ ] M7의 합성 `echo` test fixture(M7 PRD Story 10)가 그대로 통과한다.
- [ ] M7의 다른 acceptance 통합 테스트들(WireMock per-tool, depth cap, circuit breaker isolation)도 모두 통과 — M7 ADR-17 §13의 invariant.
- [ ] `generate_massing` 통합 테스트는 별도 — WireMock sidecar stub + WireMock spark-inference-gateway stub 으로 BC-level end-to-end. 또한 실제 `rhino3dm-bridge` sidecar를 띄운 통합 테스트도 추가 (test compose에 sidecar 추가).
- [ ] M4 chat surface의 happy-path 통합 테스트(M4 ADR-14 §1의 SSE sequence)가 그대로 통과 — chat 표면 변경 없음.

## UX surfaces

Per design doc `docs/design/M6-M8-brief-to-massing.md` (§2.3, §2.4, §2.5 — 본 PRD cycle 변경 없이 reuse).

| Route | Auth | M8 변경 |
|---|---|---|
| `/chat` | required (M4 invariant) | LLM이 `generate_massing` tool 호출을 결정한 turn에서 `tool_result` 카드 (frame `78:1347`) 또는 `tool_error` 카드 (frame `78:1437`)가 assistant 메시지 아래에 렌더된다. composer / sidebar / topbar / tab strip / breadcrumb는 M4 verbatim. `▾ Program details` 아코디언이 카드 안에서 expand 가능 (frame `78:1392`). |
| `/chat/sessions/{id}/messages` | required (M4 invariant) | message history 가 `tool_result` / `tool_error` 추적을 persist 하는지는 M7 ADR-17 의 결정에 따른다(P0 working = in-memory only; persistence는 M7.1). M8은 이 정책을 변경하지 않는다. |

| Endpoint | Auth | M8 변경 |
|---|---|---|
| `POST /internal/tools/generate-massing` | internal (no gateway forwarding — Exception 4 invariant per M7 ADR-17 §A08.8) | 신규 M8 엔드포인트. body shape + 응답 shape은 Story 2 / Wire-shape contracts. |
| `GET /api/arch/outputs/{id}` | required (owner-only) | 신규 M8 엔드포인트. `application/octet-stream` 응답 with `Content-Disposition: attachment`. owner mismatch → 404. |
| `POST /api/rag/chat` | required (M4 invariant) | M7이 이미 변경 — M8은 추가 변경 없음. tool result가 stream에 carry되는 흐름은 M7 ADR-17 §3.1. |

신규 라우트 두 개 (`/internal/tools/generate-massing` + `/api/arch/outputs/{id}`). 다른 surface는 M4 / M7 verbatim.

## Bounded Context: massing-gen (M8 신규)

이번 마일스톤에서 **새 BC 가 도입된다** — M6(docs 확장) / M7(rag-chat 확장)과 달리 M8은 fresh 4-module quadruplet을 만든다.

- **책임 (Responsibility):** brief 문서 ID로부터 room program 추출 → rectangular massing 생성 → `.3dm` 파일 produce + 저장 + owner-only 다운로드 노출. rag-chat의 ToolCatalog에 `generate_massing` descriptor를 등록한다(같은 PR). 어떤 다른 도메인(slide-gen / image-gen 등) 도구의 책임은 가지지 않는다.

- **모듈 quadruplet (ADR-01 invariant):**
  - `massing-gen-api` — Spring Boot runnable. 포트 핀은 ADR-18 (open question Q-A: 18083 또는 18085 가 free; spec §6의 18086은 metrics-api 점유로 stale).
  - `massing-gen-app` — Spring application layer (use cases, ports).
  - `massing-gen-domain` — Spring-free pure Java. `MassingAlgorithm`, `RoomBox` VO, `Program` VO, `MassingError` enum 거주.
  - `massing-gen-infra` — Spring adapters. `Rhino3dmAdapter`, JPA repository, Spring AI ChatClient adapter (BriefProgramExtractor — `-app`이 호출), Resilience4j 브레이커 설정.

- **새 도메인 컴포넌트 (in `massing-gen-domain`, Spring-free):**
  - `Program` — VO. 필드 `(List<Room> rooms, SiteFootprint site, float floorHeightM)`. immutable record.
  - `Room` — VO. 필드 `(String name, float areaM2)`.
  - `SiteFootprint` — VO. 필드 `(float widthM, float depthM)`.
  - `RoomBox` — algorithm 출력 VO. 필드 `(int floor, float x, float y, float widthM, float depthM, float heightM, String roomName)`.
  - `MassingAlgorithm` — pure 알고리즘. 메서드 `compute(Program program, int maxFloors): List<RoomBox>`. 알고리즘: total area → floor count → per-floor area-balanced packing(first-fit) → box 리스트. over-area 처리 정책은 ADR-18 (exception throw vs auto-adjust).
  - `MassingErrorCode` — enum. P0 working set: `BRIEF_EXTRACTION_FAILED`, `MASSING_ALGORITHM_FAILED`, `SIDECAR_TIMEOUT`, `SIDECAR_FAILED`. 정확한 set + HTTP status 매핑 + i18n message는 ADR-18.

- **새 app 컴포넌트 (in `massing-gen-app`):**
  - `GenerateMassingUseCase` — orchestrator. (a) brief body fetch (port: `BriefBodyPort`), (b) `BriefProgramExtractor` 호출, (c) `MassingAlgorithm.compute(...)`, (d) `Rhino3dmAdapter.serialize(...)`, (e) `ArchOutputRepository.save(...)`, (f) 응답 DTO 조립.
  - `BriefProgramExtractor` — LLM 호출 wrapper. Spring AI ChatClient를 통한 Korean architecture system prompt + brief body → structured JSON 추출. JSON-Schema validator(working = everit-json-schema 또는 Jackson schema validator — ADR-18) 로 응답 검증. 실패 시 `BRIEF_EXTRACTION_FAILED` throw.
  - `BriefBodyPort` — port interface. brief body를 fetch하는 추상. 구현(JPA cross-schema SELECT vs WebClient HTTP)은 ADR-18 결정.

- **새 infra 컴포넌트 (in `massing-gen-infra`):**
  - `Rhino3dmAdapter` — `Rhino3dmPort` 구현. `rhino3dm-bridge` sidecar에 WebClient POST. Resilience4j 브레이커(`rhino3dm-bridge`)로 wrap. response = `.3dm` byte array.
  - `ArchOutputJpaRepository` — `arch.outputs` 테이블 JPA repository.
  - `SpringAiBriefExtractorAdapter` — Spring AI ChatClient(또는 직접 WebClient — open question Q-C)로 spark-inference-gateway 호출.
  - `BriefBodyAdapter` — `BriefBodyPort` 구현. (a) cross-schema SELECT `docs.documents WHERE id = :briefDocId` (M4의 cross-schema SELECT pattern 재사용; ADR-08 amendment 필요할 수 있음) **또는** (b) WebClient → docs-api `/internal/docs/public/{id}/body` HTTP (ADR-08 new exception 필요 — M6.1에서 retired된 Exception 1 의 fresh 부활). 정확한 채택은 ADR-18.

- **새 컨테이너 (in `infra/docker-compose.yml`):**
  - `rhino3dm-bridge` — Node 18-alpine 베이스 + `rhino3dm` npm 패키지. 단일 엔드포인트 `POST /serialize { boxes: [...] } → application/octet-stream .3dm`. compose-internal 포트 4000, host에 노출 안 함. 정확한 dockerfile 위치 / npm pin 은 ADR-18.

- **소유 데이터:** 새 schema `arch` + 단일 테이블 `arch.outputs` (Story 11).

- **이벤트 (Kafka 표면 = 0):** M8은 Kafka producer / consumer 가 아니다. 모든 호출 동기 HTTP. (M7과 동일 정책 — Exception 4 invariant.)

- **외부 의존성 (신규 도입):**
  - `rhino3dm-bridge` sidecar 컨테이너 (M8 신규).
  - Spring AI 1.0.0 GA `ChatClient` (M4 / M7 에서 이미 도입됨 — 재사용; M8은 새 ChatClient bean 등록은 가능하지만 starter 자체는 재사용).
  - everit-json-schema (또는 Jackson 내장 validator — ADR-18 결정) — JSON Schema validator. P0가 필요로 하는 새 라이브러리.

- **위치 invariant:**
  - `massing-gen-domain`은 Spring-free 유지 (ADR-02). `MassingAlgorithm` / `RoomBox` / `Program` / `Room` / `SiteFootprint` 모두 Spring annotation 사용 금지.
  - `Rhino3dmAdapter` / Spring AI imports / Resilience4j imports는 `massing-gen-infra`에만.
  - JPA / Hibernate imports는 `massing-gen-infra`에만 (M2 / M4 invariant 미러).

## Wire-shape contracts

본 PRD가 wire-shape를 pin — frontend / ADR-18 / implementer가 이 shape을 깨면 안 된다.

### `POST /internal/tools/generate-massing` — request

```json
{
  "briefDocId": "11111111-2222-3333-4444-555555555555",
  "siteWidth": 20.0,
  "siteDepth": 10.0,
  "floorHeight": 3.5
}
```

- `briefDocId` (required, UUID).
- `siteWidth` / `siteDepth` / `floorHeight` (optional, number). 누락 시 (a) brief 추출에서 발견된 값 vs (b) numerical default(예: 20/10/3.5) — 정책은 ADR-18.
- 추가 필드는 무시 (forward-compat).

### `POST /internal/tools/generate-massing` — response (200)

```json
{
  "fileUrl": "/api/arch/outputs/a3f2b9c1-7e5d-4abc-9def-1234567890ab",
  "programJson": {
    "rooms": [
      { "name": "로비 (Lobby)", "areaM2": 48.0 },
      { "name": "카페테리아 (Cafeteria)", "areaM2": 30.0 },
      ...
    ],
    "siteWidthM": 20.0,
    "siteDepthM": 10.0,
    "floorHeightM": 3.5
  },
  "totalAreaM2": 480.0,
  "floorCount": 3,
  "summary": "12 rooms · 3 floors · 480 m² total"
}
```

- `fileUrl` — **relative URL** (gateway cookie auth 의존; M7 ADR-17 §3 invariant).
- `programJson` — 추출된 프로그램. shape은 ADR-18 의 JSON Schema 가 finalize. rooms 배열의 각 원소는 최소 `name` + `areaM2`; 추가 필드(roomType / required / optional)는 ADR-18.
- `totalAreaM2` — `sum(rooms[].areaM2)`.
- `floorCount` — `ceil(totalAreaM2 / (siteWidthM × siteDepthM))` working — ADR-18 finalize.
- `summary` — 1줄 요약. design doc §2.3 가 `12 rooms · 3 floors · 480 m² total`(영어+숫자 중립)을 reference 로 fix. i18n 정책은 ADR-18 (open question Q-D — Korean vs 영어).
- 총 응답 size cap = M7 ADR-17 §4의 **16 KiB**. 초과 시 dispatcher 가 truncate-and-warn 적용.

### `POST /internal/tools/generate-massing` — response (4xx / 5xx)

```json
{
  "code": "BRIEF_EXTRACTION_FAILED",
  "message": "Could not extract room program from brief — is this a competition brief PDF?"
}
```

- `code` — M8-specific error code enum: `BRIEF_EXTRACTION_FAILED` (400/422), `MASSING_ALGORITHM_FAILED` (422/409), `SIDECAR_TIMEOUT` (504), `SIDECAR_FAILED` (502/503). 정확한 set / HTTP status / i18n message는 ADR-18.
- M7 dispatcher 가 4xx 응답을 받으면 `tool_error` SSE event with `code: "UPSTREAM_4XX"` (M7 ADR-17 §2 enum) 으로 매핑. M8-specific code는 `tool_error.message`에 carry된다 — frontend가 message 안의 `BRIEF_EXTRACTION_FAILED` 패턴을 인식해 design doc §2.5 의 "Try a different brief" 액션을 선택할지, 또는 M7 ADR-17 §3.2 의 별도 detail field 를 추가할지는 **open question Q-E**.
- **층위 결재 (PRD가 pin):** M8 BC가 발행하는 `BRIEF_EXTRACTION_FAILED` / `MASSING_ALGORITHM_FAILED` / `SIDECAR_TIMEOUT` / `SIDECAR_FAILED` 는 **M7의 7-value `ToolErrorCode` enum 위에 layer 된 M8-specific 분류이다**. M7의 enum은 wire-level transport 분류(`UPSTREAM_4XX` / `UPSTREAM_5XX` / `TIMEOUT` / `CIRCUIT_OPEN` / `MAX_DEPTH` / `SCHEMA_INVALID` / `INTERNAL`); M8의 enum은 도메인 분류. frontend는 두 layer 모두 인식해야 (M7 enum 으로 카드 팔레트를 결정, M8 code 로 secondary action label / copy를 결정).

### `arch.outputs` table

Story 11에 표시된 schema (P0 working — ADR-18 finalize).

### sidecar contract (`rhino3dm-bridge`)

```
POST http://rhino3dm-bridge:4000/serialize
Content-Type: application/json

{
  "boxes": [
    { "x": 0.0, "y": 0.0, "z": 0.0, "width": 8.0, "depth": 6.0, "height": 3.5, "roomName": "로비 (Lobby)", "floor": 1 },
    ...
  ]
}
```

응답:
```
Content-Type: application/octet-stream
Body: <binary .3dm bytes>
```

- 정확한 box 좌표 origin (frontside lower-left vs centroid), Z-축 방향, layer assignment, user-text attribute 처리 등은 ADR-18.
- sidecar 실패 시 5xx response with `application/json` `{code, message}` body — `Rhino3dmAdapter`가 이를 `SidecarException`으로 변환.

### SSE event payloads (M7 invariant — M8은 이 shape을 깨지 않는다)

`tool_result`:
```json
{
  "id": "call_<opaque>",
  "name": "generate_massing",
  "result": {
    "fileUrl": "/api/arch/outputs/<uuid>",
    "programJson": { "rooms": [ ... ], "siteWidthM": 20, "siteDepthM": 10, "floorHeightM": 3.5 },
    "totalAreaM2": 480.0,
    "floorCount": 3,
    "summary": "12 rooms · 3 floors · 480 m² total"
  }
}
```

`tool_error`:
```json
{
  "id": "call_<opaque>",
  "name": "generate_massing",
  "code": "UPSTREAM_4XX",
  "message": "BRIEF_EXTRACTION_FAILED: Could not extract room program from brief — is this a competition brief PDF?"
}
```

- `id` (correlation, M7 ADR-17 §3.2 invariant).
- `name` 는 `generate_massing` 고정.
- `code` 는 M7의 7-value enum 중 하나.
- `message` 는 M8 BC가 응답한 `code` + `message` 가 carry된다 (M7 invariant — dispatcher 가 통과).

## Non-functional requirements

- **`massing-gen-domain`은 Spring-free 유지** (ADR-02). `MassingAlgorithm` 은 pure Java, JUnit 으로 단위 테스트 가능. Spring AI / Rhino3dm / Resilience4j imports는 `massing-gen-infra`에만.
- **M7 regression invariant (Story 12).** `rag-chat-*` 코드 가 `ToolCatalog` 1줄 등록 외 0줄 변경. M7 합성 `echo` test fixture 통과.
- **M4 regression invariant.** chat 표면의 SSE happy-path / abort path / rate limit path 가 그대로 통과 — M4 ADR-14 invariant.
- **M2 docs body invariant.** M8 BC가 brief body를 fetch할 때 docs.documents 스키마 / 컬럼 / Body 추출 결과 를 변경하지 않는다 (read-only).
- **ADR-08 amendment 필요.** ADR-18이 (a) Exception 4 sub-row `rag-chat-api → massing-gen-api POST /internal/tools/generate-massing` 추가, (b) `massing-gen-api → docs-api` body fetch 경로(HTTP vs cross-schema SELECT 채택에 따라) 의 새 exception 또는 cross-schema SELECT 정당화 추가. **본 PRD 는 ADR-08 을 직접 변경하지 않는다.**
- **M6.1에서 Exception 1이 retired되었음을 반영.** spec §6은 "Exception 1을 M3+M8로 widen"하는 정책을 working으로 들고 있으나, M6.1에서 rag-ingestion BC가 docs로 dissolved되며 Exception 1 자체가 retired되었다 (ADR-08 §A08.1). M8은 brief body fetch 메커니즘으로 (a) cross-schema SELECT(M4 ADR-14 §3 pattern 재사용) 또는 (b) `/internal/docs/public/{id}/body` 경로의 fresh ADR-08 exception(현재 라우트는 defensive 보존 중 — ADR-08 §A08.1) 중 ADR-18이 선택해야 한다. (open question Q-F)
- **per-tool circuit breaker (M7 invariant).** `tool-generate_massing` 브레이커가 M7의 per-descriptor 자동 등록 흐름으로 만들어진다 — M8은 이를 별도로 wire-up 하지 않는다.
- **per-sidecar circuit breaker (M8 신규).** `rhino3dm-bridge` 브레이커가 `massing-gen-infra`에 등록된다. 임계치는 M7 ADR-17 §5 `spark-gateway` template 재사용 working.
- **timeout enforcement.** descriptor 의 `timeout`(working 60s — ADR-18 핀)이 M7 dispatcher 의 WebClient call에 적용 (M7 invariant). 별도로 `Rhino3dmAdapter`의 sidecar HTTP call이 자체 timeout(working 30s — ADR-18 핀)을 가진다.
- **tool result size cap (M7 invariant).** 응답 페이로드 16 KiB cap (M7 ADR-17 §4). programJson 안의 rooms 배열 + 메타데이터가 이 cap 안에 들어가야 — typical 30실 program 은 ~5-10 KiB. cap 초과 시 M7 dispatcher truncate-and-warn 적용.
- **owner-only download.** `GET /api/arch/outputs/{id}`의 owner mismatch 시 `404` (404 != 403 — tenant isolation invariant; M2 docs BC pattern). 정확한 mapping은 ADR-18.
- **Content-Disposition filename.** `attachment; filename="massing-<briefSlug>-<timestamp>.3dm"` — briefSlug 가 어디서 derive되는지(docs.documents.title 의 slugify vs brief_doc_id) 는 ADR-18.
- **JSON Schema validation.** `BriefProgramExtractor`가 LLM 응답을 schema로 validate. validator 라이브러리(everit-json-schema 2.x 또는 Jackson 내장)는 ADR-18.
- **LLM call observability.** `BriefProgramExtractor` 의 LLM call이 (a) 호출 duration ms, (b) input prompt token count, (c) response token count, (d) retry count(있다면), (e) schema validation pass/fail 을 INFO 구조화 로그로 기록. 정확한 필드는 ADR-18.
- **sidecar observability.** `Rhino3dmAdapter`의 sidecar HTTP call이 (a) box count, (b) sidecar response duration ms, (c) response byte size, (d) breaker state transition 을 기록. Micrometer metric: `rhino3dm_bridge_call_duration_ms`, `rhino3dm_bridge_response_bytes` — 정확한 이름은 ADR-18.
- **rate limit.** M4의 chat-level token bucket이 그대로 적용된다 (M7 invariant — M4 ADR-14 §5). massing-gen-specific per-user rate limit은 P0에서 적용하지 않는다 (chat-level 이 cover). 비용 우려 발생 시 M8.1 에서 추가 (Open Question).
- **cost protection.** LLM 호출(spark-inference-gateway 로컬 GPU; OpenAI API 가 아님 — 비용 = GPU 분/시간) + sidecar 호출(local Node container; 비용 ≈ 0) 모두 personal-scale 에서 acceptable. 운영 dashboard(M5)에서 massing-gen call rate 가 spark-inference-gateway 큐 점유에 미치는 영향을 추적 가능해야 한다.

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 사용자 시나리오와 기술 검증 둘 다 포함.

### User-facing scenarios

- [ ] **Happy path end-to-end**: architect가 `/chat`에서 brief 문서를 reference하며 "이 brief 보고 매싱 만들어줘. 대지 20m × 10m, 층고 3.5m로" 요청 → LLM이 `generate_massing` tool 호출 → `tool_result` 카드(`📁 generate_massing` + summary + `↓ Download .3dm` + `▾ Program details`)가 chat 에 렌더 → 다운로드된 `.3dm` 이 Rhino 7+ 에서 정상 열림 (Story 1, 2, 3, 5).
- [ ] **Program details accordion**: `▾ Program details` 클릭 시 4열 표(FLOOR / ROOM / DIMENSIONS / AREA) + footer `… and N more rooms`이 expand. 카드 height collapsed 120px → expanded ~340px. `↓ Download .3dm` 버튼 위치 불변 (Story 4).
- [ ] **non-brief PDF → `tool_error` with `BRIEF_EXTRACTION_FAILED`**: CV 같은 non-brief PDF 로 massing 요청 시 `tool_error` 카드(`warning` 팔레트, `⚠`, `code: BRIEF_EXTRACTION_FAILED`, `↗ Try a different brief` 액션)가 렌더. LLM 자연어 응답이 사과 + 다음 단계 hint (Story 6).
- [ ] **sidecar timeout → `tool_error` with TIMEOUT**: 의도적으로 sidecar stub의 응답을 지연시키면 30s 후 `tool_error` 카드(`code: TIMEOUT`, `↻ Retry` 액션)가 렌더 (Story 7).
- [ ] **over-area → `MASSING_ALGORITHM_FAILED`**: 작은 site × 큰 total area 조합으로 algorithm tolerance 위반 시 `tool_error` 카드(`code: MASSING_ALGORITHM_FAILED`, `↻ Retry with different inputs`)가 렌더 (Story 8).
- [ ] **owner-only download**: user A가 생성한 `.3dm`을 user B의 cookie 로 다운로드 시도 → 404 (tenant isolation; Story 11).

### Technical validation — module structure

- [ ] `backend/massing-gen/{massing-gen-api, massing-gen-app, massing-gen-domain, massing-gen-infra}` 4-module quadruplet 이 생성된다. `settings.gradle.kts`에 include 됨.
- [ ] `massing-gen-domain`에 `MassingAlgorithm`, `RoomBox`, `Program`, `Room`, `SiteFootprint`, `MassingErrorCode` 가 존재하고 Spring annotation을 사용하지 않는다.
- [ ] `massing-gen-app`에 `GenerateMassingUseCase`, `BriefProgramExtractor`, `BriefBodyPort`, `Rhino3dmPort` 가 존재.
- [ ] `massing-gen-infra`에 `Rhino3dmAdapter`, `ArchOutputJpaRepository`, `SpringAiBriefExtractorAdapter`, `BriefBodyAdapter` 가 존재. Spring annotation / JPA annotation 모두 여기에만.
- [ ] `massing-gen-api`가 포트(18083 또는 18085 — ADR-18)에 bind 되어 동작.

### Technical validation — endpoints + schema

- [ ] `POST /internal/tools/generate-massing` 가 위 Wire-shape contracts 의 request/response shape 으로 동작. WireMock + 실제 sidecar 통합 테스트 두 path 모두 통과.
- [ ] `GET /api/arch/outputs/{id}` 가 owner-only로 동작. `Content-Type: application/octet-stream` + `Content-Disposition: attachment; filename="..."` 헤더 발행.
- [ ] `arch` schema 가 Flyway migration 으로 만들어지고 `arch.outputs` 테이블 + 두 index가 존재.
- [ ] `arch.outputs` 의 row는 `user_id` 컬럼에 forwarding된 `X-User-Id` 값을 가진다 — 통합 테스트로 단언.
- [ ] `rhino3dm-bridge` sidecar 컨테이너가 `infra/docker-compose.yml` 에 추가됨. `POST /serialize` 호출 후 `.3dm` byte stream 응답. 정상 Rhino-open 검증.

### Technical validation — rag-chat integration

- [ ] `rag-chat-domain.ToolCatalog.descriptors()` 가 1 entry (`generate_massing`) 를 반환 (M7 invariant 유지).
- [ ] `generate_massing` descriptor 의 `name` / `description` / `parameterSchema` / `endpoint` / `timeout` 이 Story 1의 shape 으로 검증.
- [ ] M7의 `WebClientToolDispatcher`가 `generate_massing`을 dispatch할 때 자동으로 per-tool breaker `tool-generate_massing` 등록 — 단위 테스트로 확인.
- [ ] M7의 합성 `echo` test fixture 가 그대로 통과 — `generate_massing` 추가가 fixture를 깨지 않는다.
- [ ] M4 chat 표면의 happy-path / abort path / rate limit path 통합 테스트 모두 통과 — chat 표면 0줄 변경.

### Technical validation — resilience

- [ ] `tool-generate_massing` 브레이커가 5xx burst 후 OPEN. 후속 dispatcher 호출이 즉시 `tool_error` `CIRCUIT_OPEN` 발행 (M7 invariant 재확인).
- [ ] `rhino3dm-bridge` 브레이커(M8 신규)가 sidecar 5xx burst 후 OPEN. 후속 호출이 sidecar HTTP call 없이 실패 — `massing-gen-api`가 5xx 응답 → dispatcher 가 `UPSTREAM_5XX` 발행.
- [ ] sidecar timeout (working 30s) 초과 시 `Rhino3dmAdapter`가 timeout exception → `massing-gen-api`가 `SIDECAR_TIMEOUT` 응답 → dispatcher 가 `TIMEOUT` 발행.
- [ ] descriptor timeout(working 60s) 초과 시 dispatcher 자체가 timeout 발행 (M7 invariant).
- [ ] 두 브레이커가 **독립**: `rhino3dm-bridge` OPEN 이 다른 tool(가상의 `echo`)의 호출에 영향 없음, 그리고 `tool-generate_massing` OPEN 이 다른 sidecar 호출(가상의 second sidecar)에 영향 없음 (M7 isolation invariant 미러).

### Technical validation — domain logic

- [ ] `MassingAlgorithm.compute(program, maxFloors)` 단위 테스트: (a) 일반 케이스에서 total box area ≥ total room area, (b) floor count = ceil(totalArea / siteArea), (c) 모든 box 가 site footprint 안에 fit, (d) over-area 케이스에서 정의된 정책(throw vs auto-adjust — ADR-18) 동작.
- [ ] `BriefProgramExtractor` 단위 테스트(WireMock spark-inference-gateway): (a) 정상 brief → programJson 추출, (b) 빈 응답 → `BRIEF_EXTRACTION_FAILED` throw, (c) schema 위반 응답 → `BRIEF_EXTRACTION_FAILED` throw.
- [ ] `.3dm` 파일 fixture 가 test resources 에 포함되어 (또는 실제 sidecar 호출 결과를 사용하여) Rhino3dmAdapter 의 wire 단언 가능.

### M7 / M4 / M6 regression (invariant)

- [ ] M7 acceptance 통합 테스트 슈트 0줄 변경 + 모두 통과.
- [ ] M4 chat 통합 테스트 슈트 0줄 변경 + 모두 통과.
- [ ] M6 PDF 업로드 통합 테스트 슈트 0줄 변경 + 모두 통과 (M8은 docs body 를 read-only로 fetch — docs schema / 컬럼 / 업로드 흐름 변경 없음).

### Cross-doc invariants

- [ ] **ADR-08 amendment**: ADR-18 작성 시 (a) Exception 4 sub-row `rag-chat-api → massing-gen-api` 추가, (b) `massing-gen-api → docs-api` body fetch 경로 의 새 exception 또는 cross-schema SELECT 정당화 추가. **본 PRD 는 ADR-08 을 변경하지 않는다.**
- [ ] **ADR-01 amendment**: ADR-18 작성 시 ADR-01 §A01.3 의 port table을 update — `massing-gen-api`가 18083 또는 18085 를 점유하는 행을 추가. 모듈 count 도 update (4 modules 추가). **본 PRD 는 ADR-01 을 변경하지 않는다.**
- [ ] **ADR-05 amendment**: ADR-18 작성 시 `arch` schema 가 schema-per-BC 목록에 추가됨. **본 PRD 는 ADR-05 를 변경하지 않는다.**
- [ ] **ADR-00 (index) 행 추가**: ADR-18이 land될 때 ADR-00 index에 row 추가 — 본 PRD cycle은 ADR-00을 변경하지 않는다.
- [ ] **roadmap.md §M8**: 본 PRD의 acceptance bullet 들과 `docs/roadmap.md` §M8의 acceptance bullet 사이 충돌이 없다. 충돌이 있다면 본 PRD가 우선 — 단, roadmap.md의 "port 18086 candidate"는 stale (Q-A 참조). roadmap.md의 "ADR-08 Exception 1 widened"도 stale (Q-F 참조 — Exception 1은 M6.1에서 retired). roadmap update 는 architect / ADR-18 가 처리.
- [ ] **GitHub issue #164 body refresh**: 본 PRD가 land된 후 issue #164 의 placeholder body가 본 PRD에 대한 link + 8-10개의 구체적 acceptance item 으로 refresh.
- [ ] **Design doc**: `docs/design/M6-M8-brief-to-massing.md`는 본 PRD cycle PR set 에서 변경하지 않는다 (이미 land된 상태이며 본 PRD 와 sync). 두 frame rename TODO(78:1392 / 78:1437) 는 별개 operator manual task.

## Out of scope

### M8.1 (same milestone bucket, ship if cycle has slack)

- **Non-rectangular site footprint.** P0 = rectangular site only (`width × depth`). 비대칭 / L-자 / 불규칙 site 는 별도 input shape + 새 algorithm. (spec §6 "Scope (out — M8.1)" verbatim.)
- **Atrium / setbacks / irregular floor plates.** P0 = 모든 floor가 동일 site footprint 안에 직사각형으로만. (spec §6.)
- **Multi-floor optimization beyond first-fit.** P0 algorithm = first-fit + area balance. 더 정교한 packing (skyline / shelf / 2D bin packing optimal) 은 M8.1. (spec §6.)
- **User input hints** ("2층은 cafeteria로 배치"). P0 = LLM 추출 + algorithm 결정에 사용자 hint 없음. (spec §6.)
- **Grasshopper parametric output (`.gh` 파일)**. P0 = `.3dm` static only. (spec §6.)
- **Cover image / thumbnail preview of the massing.** P0 = 카드에는 텍스트 summary + Download 버튼만; 작은 3D preview 이미지 없음. M8.1 가 sidecar에 추가 endpoint `POST /render-preview` 도입 검토. (spec §6.)
- **Per-room hover → 3D preview highlight** (design doc §2.4 의 future). P0 = table row는 informational; click no-op.
- **Brief 와 .3dm 의 양방향 link** (지난 매싱 결과를 doc detail 페이지에서 추적). P0 = `arch.outputs.brief_doc_id` 는 저장되지만 docs detail UI 에 표시되지 않는다. M8.1 에서 docs detail 에 "이 brief의 생성된 매싱들" 섹션 추가 검토.
- **Per-user rate limit on massing-gen.** P0 = chat-level token bucket이 cover. 비용 우려 발생 시 추가.
- **Multiple massings per chat turn** (LLM 이 한 turn에서 여러 site dimensions 로 generate_massing 을 여러 번 호출). M7 ADR-17 §1.2 의 depth cap (5) 가 막아주지만 의도된 use case 가 아님 — UX 검토 필요.

### P2 (별도 후속 마일스톤)

- **LLM-direct OpenNURBS command generation.** Qwen3-32B 가 직접 .3dm 명령을 생성하는 방향. P0 의 algorithm + sidecar 가 deterministic 한 vs LLM-direct 의 creative output 사이의 trade-off 는 별개 마일스톤. (spec §6.)
- **Compliance verification mode** (기존 .3dm + brief → PASS/FAIL 보고서). 별도 도메인 마일스톤. (spec §6.)
- **Floor plan generation** (rooms with walls, doors, windows). 별도 도메인 + 새 BC. (spec §6.)
- **Facade studies.** 별도 도메인 마일스톤. (spec §6.)
- **Massing-on-massing iteration** (이전 `.3dm` 을 input 으로 받아 변형). 별도 input shape + diff semantics.
- **`.gh` Grasshopper export**, `.rvt` Revit export, `.ifc` IFC export. 모두 별도 sidecar / 별도 마일스톤.
- **massing-gen 의 multi-user sharing** (user A 의 결과를 user B 가 view). user 격리 invariant 와 충돌 — 검토 자체가 P2.
- **dynamic descriptor registration** (운영 중 hot-add). M7 P0 hardcoded 의 한계 — M7.1 또는 P2.

## Dependencies

- **요구:** M0 — compose stack (Postgres + Redis + Kafka [unused by massing-gen] + gateway), spark-inference-gateway가 host process로 동작 중 (`host.docker.internal:10080`, ADR-04).
- **요구:** M1 (Identity) — `X-User-Id` / `X-User-Sub` 헤더 인젝션 (cookie session → header forwarding via gateway; M7 dispatcher가 그것을 M8로 propagate; arch.outputs.user_id 의 source).
- **요구:** M2 + M6 + M6.1 (Docs + PDF + async extraction) — **직접 의존**. M8 의 `BriefBodyPort`가 `docs.documents.body`(markdown 으로 정규화됨; M6.1 async extraction completed 상태)를 fetch한다. 추출이 `processing` / `failed` 인 brief에 대한 massing 호출은 거부되어야 함 — frontend composer가 `[doc:{id}]` context-injection 단계에서 reject (design doc 2026-05-22 amendment 의 invariant). 그러나 backend M8 BC 자체도 `extraction_status = 'completed'` 가 아닌 doc에 대한 호출에서 `BRIEF_EXTRACTION_FAILED`(또는 다른 명확한 code)를 응답해야 — ADR-18 결정.
- **요구:** M3 (RAG-Ingestion / M6.1 후 docs로 consolidated). **간접 의존 only.** M8 자체는 ingestion / 검색을 호출하지 않는다.
- **요구:** M4 (RAG-Chat) — **직접 출시 완료**. M8 의 tool 호출이 M4의 SSE / multi-turn / Spring AI ChatClient 위에서 동작.
- **요구:** M7 (rag-chat tool-calling) — **직접 출시 완료**. M8 의 `MassingTool` descriptor가 M7 의 `ToolCatalog` 위에 등록된다. M7 의 `WebClientToolDispatcher`, per-tool breaker, depth cap, SSE event grammar (`tool_call` / `tool_result` / `tool_error`) 모두 land된 상태여야 한다.
- **요구:** ADR-18 (`docs/adr/18-m8-massing-gen.md` — 후속) — `.3dm` 라이브러리 결정, Korean brief 추출 prompt + few-shot 예시, programJson JSON Schema 정확한 field 집합, MassingAlgorithm tolerance / over-area 정책, 파일 저장 매체 (BYTEA P0 vs MinIO 즉시), orphan cleanup 정책, ADR-08 amendment 정확한 wording, ADR-01 port 핀(18083 vs 18085), summary i18n 정책, LLM 호출 형태(Spring AI ChatClient vs 직접 WebClient), JSON Schema validator 라이브러리, MassingErrorCode enum set + HTTP status 매핑, sidecar 응답 box 좌표 origin + layer assignment 의 정확한 형태, Resilience4j 임계치 핀, sidecar npm pin, Flyway migration 파일 이름.
- **신규 외부 의존성 (이번 마일스톤에서 도입):**
  - `rhino3dm-bridge` sidecar 컨테이너 (Node 18-alpine + `rhino3dm` npm 패키지).
  - JSON Schema validator 라이브러리 (everit-json-schema 2.x vs Jackson 내장 — ADR-18).
- **재사용 외부 의존성**: Spring AI 1.0.0 GA (M4 / M7에서 도입; M8은 새 ChatClient bean 등록만), Resilience4j 2.2.0 (M4 / M7에서 도입; M8은 새 sidecar breaker 등록만).

## Open questions for ADR-18

ADR-18 (architect)이 해소할 사항. 본 PRD는 wire-shape contract + scope boundary + 도메인 컴포넌트의 위치 invariant 를 닫았고, 나머지 라이브러리 / 알고리즘 / 정책 핀은 ADR-18.

### Spec §6에서 carry되는 9개 (verbatim)

1. **`.3dm` 라이브러리 결정** — `rhino3dm.js` Node sidecar(working) vs OpenNURBS C JNI vs `rhino3dm.py` Python sidecar. P0 working = Node sidecar (smallest activation energy, active library). 정확한 sidecar npm pin + 컨테이너 베이스 이미지(`node:18-alpine` 가정)는 ADR-18.
2. **Korean brief 추출 prompt 템플릿** — Korean architecture competition brief 의 typical 구조(site / 실명+면적표 / FAR / height limit)를 cover하는 system prompt + few-shot 예시. Corpus 가 필요(architect 가 보유한 brief 들 + 합성). 정확한 prompt copy 는 ADR-18 / Stage-3 implementer.
3. **출력 programJson JSON Schema** — rooms (배열, required) / siteWidthM / siteDepthM / heightLimitM / FAR / floorAreaRatio 등 어느 필드가 required vs optional 인지. 본 PRD 는 working set (rooms.name + areaM2 required, site dimensions optional) 만 핀.
4. **MassingAlgorithm tolerance — over-area 처리** — 추출 total area > site × max_floors 케이스에 (a) `MassingErrorCode.MASSING_ALGORITHM_FAILED` throw, (b) auto-adjust(max_floors 증가), (c) auto-scale(rooms 축소) 중 어느 정책. P0 working = (a) throw. max_floors default 값(working = 10) 도 핀.
5. **파일 저장 매체** — `arch.outputs.file_bytes` BYTEA(P0 working) vs MinIO(M6.1에서 이미 도입됨)로 즉시 분리. M6.1이 MinIO 를 도입했으므로 ADR-18은 그것을 재사용할 좋은 기회 — 단 BYTEA 가 simpler 하고 personal-scale 에서 충분. trade-off 평가 필요.
6. **Orphan cleanup 정책** — `arch.outputs.brief_doc_id`가 가리키는 docs.documents row 가 삭제되었을 때 (a) cascade DELETE(orphan 자동 제거), (b) cascade SET NULL(arch.outputs는 유지, brief 추적성 잃음), (c) soft-delete(`arch.outputs.deleted_at` 추가), (d) untouched(P0 working — `.3dm`은 영구). 사용자 mental model 평가 필요.
7. **BC 이름** — `massing-gen`(functional, working) vs `arch-massing`(domain-prefixed) vs `massing`(short) 등. spec §6 working = `massing-gen`. 다른 후보(`slide-gen`, `image-gen`)와의 명명 일관성으로 lean toward `massing-gen` — ADR-18 confirm.
8. **Tool descriptor parameter granularity** — `parameterSchema` 의 properties 가 (working) `briefDocId` + `siteWidth` + `siteDepth` + `floorHeight` 4개 minimal 인지, 또는 `heightLimit` / `farTarget` / `coreLocation` 같은 추가 hint 까지 노출할지. P0 working = minimal — 더 많은 입력은 LLM context inflation + 사용자 confusion 비용. 추가 granularity는 M8.1.
9. **Per-user rate limit on massing-gen** — chat-level token bucket 외에 tool-specific 호출 quota 필요한지. P0 working = no separate limit (chat-level이 cover; massing 호출 = LLM 1 round-trip + sidecar 1 호출 ≈ chat 1 turn 비용). 운영 관측 후 M8.1 reassess.

### 본 PRD가 추가로 surface한 5개

10. **(Q-A) `massing-gen-api` port pin** — spec §6 은 port 18086 을 제안하나, ADR-01 §A01.3은 18086이 `metrics-api`(M5)에 의해 점유 중임을 명시. 18083(M6.1에서 retired된 `rag-ingestion-api`가 freed) 또는 18085(ADR-01 v2에서 reserved) 가 후보. 어느 것이 lean intent 에 더 부합 — 아니면 새 port(18087+) 할당? **ADR-18 + ADR-01 port table update 필요**.
11. **(Q-B) `summary` i18n 정책** — design doc §2.3 의 reference 는 `12 rooms · 3 floors · 480 m² total` (영어+숫자 중립). 그러나 사용자 메시지가 Korean 이고 LLM 자연어 응답도 Korean 인 cycle에서 summary 만 영어인 것이 일관성 측면에서 어색한지? 후보: (a) 영어 fix, (b) Korean fix("12실 · 3층 · 총 480 m²"), (c) LLM 이 결정한 user message 언어에 follow. ADR-18 결정. 본 PRD 는 (a) 를 working pin.
12. **(Q-C) LLM 호출 형태** — `BriefProgramExtractor`가 (a) Spring AI 1.0.0 GA `ChatClient`(M4 / M7 pattern, observability 일관성 ↑, dependency weight ↑) vs (b) 직접 `WebClient` → spark-inference-gateway(가벼움, M8 무관한 Spring AI starter 의존 없음). P0 working = (a). 단 `massing-gen-app`가 Spring AI starter 를 가져오게 되어 의존 무게가 늘 어남 — trade-off 평가.
13. **(Q-D) frontend M8-specific error code 인식 메커니즘** — Wire-shape contracts 의 4xx 응답에서 M8 BC는 `BRIEF_EXTRACTION_FAILED` / `MASSING_ALGORITHM_FAILED` 같은 도메인 코드를 carry. M7 dispatcher 가 이를 SSE `tool_error.message`에 그대로 carry — frontend 가 카드 의 secondary action label("Try a different brief" vs "Retry with different inputs" vs "Retry") 을 결정하려면 어떻게 파싱? 후보: (a) message 안의 `<CODE>:` prefix 패턴 정규식 추출, (b) M7 ADR-17 §3.2 의 `tool_error` payload 에 별도 `detail.code` 필드 추가(M7 amendment 필요), (c) frontend 가 tool name + M7 enum code 조합으로만 분기 (M8 code 무시; 모든 M8 에러에 generic "Retry" 만 노출). design doc §2.5는 4개의 sub-state(`BRIEF_EXTRACTION_FAILED` / `MASSING_ALGORITHM_FAILED` / `TIMEOUT` / `TOOL_5XX`)를 명시 — (a) 또는 (b) 필요. ADR-18 + 필요 시 M7 ADR-17 amendment.
14. **(Q-E) brief body fetch 메커니즘 — cross-schema SELECT vs fresh ADR-08 exception** — spec §6 은 "Exception 1 widened from M3 only to M3 + massing-gen" 정책을 working으로 들고 있으나, M6.1 에서 Exception 1 자체가 retired(rag-ingestion BC dissolved into docs; ADR-08 §A08.1). M8의 brief body fetch는 (a) M4 ADR-14 §3 cross-schema SELECT pattern 재사용 (`SET search_path TO arch, docs, public` — narrow read on `docs.documents`), (b) docs-api 의 defensive 보존된 `/internal/docs/public/{id}/body` 라우트를 fresh ADR-08 exception 으로 부활. **ADR-18 + ADR-08 amendment 필요**. (a) 가 lean — M4 가 이미 같은 패턴; (b) 가 BC isolation 측면에서 더 strict — trade-off.

---

> **PRD vs ADR:** 이 문서는 사용자 (= architect / operator / future-self tool BC author) 와 리뷰어가 읽는 표면이다. `.3dm` 라이브러리 정확한 버전 핀, Korean brief prompt 템플릿, programJson JSON Schema field 집합, MassingAlgorithm over-area 정책, 파일 저장 매체, orphan cleanup, port 핀(18083 vs 18085), summary i18n, LLM 호출 형태, JSON Schema validator 라이브러리, MassingErrorCode enum set, sidecar 응답 box 좌표 origin 등의 기술 컨트랙트는 ADR-18 이 우선한다. PRD 가 ADR-18 과 어긋나 보이면 ADR-18 을 따른다.
>
> **PRD vs spec:** spec (`docs/superpowers/specs/2026-05-19-post-m5-roadmap.md`) §6 의 in-scope / out-of-scope (M8.1 + P2) / 9개 open question 을 본 PRD 가 verbatim 으로 carry 한다 — 닫지 않는다. spec §6 의 working port 18086 은 stale(M5 점유) — open question Q-A 로 catalogue. spec §6 의 "Exception 1 widened" 정책도 stale(M6.1 에서 Exception 1 retired) — open question Q-E 로 catalogue.
>
> **PRD vs design doc:** `docs/design/M6-M8-brief-to-massing.md` §2.3 / §2.4 / §2.5 가 happy-path / expanded / error 의 visual을 핀했다. 본 PRD 는 design doc을 변경하지 않으며 design doc 이 핀한 카드 anatomy / palette / interaction을 그대로 reference 한다. design doc 의 frame rename TODO(78:1392 / 78:1437) 는 별개 operator manual task.
