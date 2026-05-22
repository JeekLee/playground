# PRD: M7 — RAG-Chat tool-calling infrastructure

> **Source of truth:** `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §5 (M7 — rag-chat tool-calling infrastructure). 이 PRD는 사용자 / 리뷰어가 읽는 표면이고, Spring AI 1.0 `ChatClient.tools(...)` 정확한 호출 시그니처, Resilience4j circuit breaker 임계치 핀, multi-turn 스케줄러 / 동시성 정책, SSE 이벤트 ordering 보장, tool-result max size 같은 기술 컨트랙트는 **ADR-17**(`docs/adr/17-m7-tool-calling.md` — 후속 작성 예정 / 본 PRD가 raise하는 open questions를 닫는다)이 우선한다. Section references like "spec §5"는 그 spec 안의 위치를 가리킨다.
>
> **Parallel work:** 본 PRD와 함께 묶이는 per-milestone ADR(**ADR-17**)이 architect의 별도 세션에서 작성된다 — Spring AI 1.0 GA function-calling API의 정확한 호출 형태와 안정성 평가, `ChatClient.tools(...)` 와 streaming pipeline의 통합 시그니처, ADR-08 Exception 4의 정확한 표 형식, ADR-14 §5.2 SSE grammar amendment의 wording, Resilience4j 임계치 (per-tool failure rate / OPEN duration / minimum calls) 핀, tool 실패 시 Spring AI retry 정책, max tool-call depth env-var 이름, tool-result max payload size 핀, multi-turn SSE event ordering 보장 등이 ADR-17의 책임이다.
>
> **Decision context:** spec §5의 in-scope 리스트는 다음 5개 컴포넌트로 closed돼 있다 — `ToolCatalog` constants 클래스, `ToolDispatcher` adapter, `ChatTurnUseCase` 확장, SSE event grammar 확장, frontend `tool_result` rendering 컨트랙트. 본 PRD는 이 closed 리스트에 한정해서 사용자 시나리오 + 수락 기준을 명문화하고, spec §5의 "Key open questions for ADR-17" 6개를 본 PRD의 "Open questions" 섹션으로 운반한다 — 닫지 않는다. **scope creep 방지** invariant: parallel tool execution, streaming tool-progress, Redis 결과 캐싱, tool ACL beyond auth-only, dynamic registry, MCP / OpenAI plugin connectors, user-defined tools 는 모두 본 PRD에서 명시적으로 OUT.

## 한 줄 설명

M4의 chat 표면이 LLM의 `tool_call` 응답을 받으면 hardcoded `ToolCatalog`에 등록된 tool BC를 WebClient + Resilience4j 회로 차단기로 호출하고, 그 결과를 다음 LLM turn에 다시 주입해서 자연어 응답으로 마무리하며, 이 전체 흐름을 SSE event grammar에 `tool_call` / `tool_result` / `tool_error` 3개의 신규 이벤트로 투명하게 전파한다 — domain-neutral한 generic infra만 land.

## Summary

M7은 **M4 rag-chat의 순수 additive 확장**이다. 새 BC도, 새 module quadruplet도, 새 port도, 새 schema도 도입하지 않는다 — `rag-chat-domain`에 `ToolCatalog` constants 클래스 하나, `rag-chat-infra`에 `ToolDispatcher` adapter 하나, `rag-chat-app`의 기존 `StreamChatTurnUseCase`에 multi-turn function-calling 분기 한 묶음, ADR-14의 SSE event grammar에 3개의 신규 event 이름이 추가된다.

M7 자체는 **end-user에게 보이는 새 기능을 ship하지 않는다**. 출시 시점에 `ToolCatalog`는 0개의 descriptor를 들고 있을 수도 있다 — 그 경우 chat 흐름은 M4 대비 변화 없이 자연어 응답만 흐른다. M7의 가치는 M8(`massing-gen` BC, 후속 cycle)이 `ToolCatalog`에 자기 descriptor를 추가하는 **단일 PR**만으로 chat 표면에 noun-verb 방식의 tool-invocation 능력을 켤 수 있게 만드는 데 있다. 즉 M7 = "M4가 LLM-driven 호출을 받아서 외부 tool BC로 dispatching할 수 있는 hot-swappable 무대".

핵심 흐름은: (1) `ChatTurnUseCase`가 Spring AI `ChatClient.tools(toolCatalog.descriptors())`를 호출, (2) LLM이 `tool_call` 필드와 함께 응답하면 `ToolDispatcher.invoke(name, args)`가 descriptor lookup → WebClient HTTP POST → tool BC의 `/internal/tools/<name>` 엔드포인트, (3) 결과를 Spring AI의 tool-result message로 wrap해서 다음 turn LLM 호출의 context에 주입, (4) LLM이 추가 tool 호출 없이 자연어로 마무리할 때까지 반복 (depth cap = 5), (5) SSE stream에는 `phase` / `token` / `done` 등 기존 이벤트와 interleave된 형태로 `tool_call` (LLM이 tool 부르기로 결정), `tool_result` (tool 성공 응답), `tool_error` (tool 실패) 이벤트가 emit된다.

저항성(resilience) 측면에서는 **per-tool Resilience4j circuit breaker**가 추가된다 — M4 ADR-14 §4의 단일 `spark-gateway` 브레이커는 LLM 호출용이고, M7은 그것과 별개로 tool descriptor당 하나의 브레이커를 등록한다(개별 tool BC가 고장났을 때 chat 표면 전체가 영향받지 않도록 격리). 호출은 항상 동기 (Kafka 사용 안 함 — async semantic은 LLM context flow를 끊는다. ADR-08 amendment에서 Exception 4로 명문화된다).

ADR-08은 본 cycle에서 amendment를 받는다 — Exception 4 추가 (`rag-chat-api` → tool-implementer BCs HTTP for LLM function-calling). 신규 tool BC가 추가될 때마다 Exception 4의 sub-row가 추가되는 governance pattern이 함께 도입된다 (M8 시점에 `massing-gen-api`가 첫 sub-row). 단, **본 PRD가 ADR-08을 직접 amend하지는 않는다** — ADR-17이 ADR-08 amendment의 정확한 wording을 핀한다.

지금 출시하는 이유: spec §5의 sizing 노트가 명시하듯이 M7은 generic infra이고 user-visible feature가 없어서 **M8과 bundling하면 검증이 둔해진다**. M7만 단독으로 ship해서 (a) Spring AI 1.0 GA function-calling 표면의 안정성, (b) SSE event grammar 확장의 frontend 통합, (c) 회로 차단기 / depth cap 같은 운영 안전장치를 격리해서 검증한다. M8이 `ToolCatalog`에 첫 entry를 추가할 때는 infra가 이미 검증된 상태여야 한다.

## User personas

| 페르소나 | 핵심 동기 | M7에서 가능한 것 |
|---|---|---|
| **Tool BC author (= future-self, primary)** — M8 작성자가 첫 사례 | 새 domain tool(예: `generate_massing`)을 chat 표면에서 LLM이 호출 가능하게 하려고 함 | `rag-chat-domain.ToolCatalog`에 `ToolDescriptor` 상수 하나 추가 + 자신의 `/internal/tools/<name>` POST 엔드포인트 구현 = chat 표면 코드 변경 0줄로 새 tool 노출. `ToolDescriptor`는 `(name, description, jsonSchema, endpointUri, timeout)` 5개 필드의 immutable record. Spring AI는 `description` + `jsonSchema`를 LLM 시스템 프롬프트에 자동 주입한다. |
| **rag-chat operator (= future-self, secondary)** | 한 tool BC가 망가졌을 때 chat 표면 전체가 죽지 않게 격리하고 싶음 + token 비용을 통제하고 싶음 | per-tool Resilience4j circuit breaker가 5xx burst를 격리 (breaker OPEN → 즉시 `tool_error` SSE event로 LLM round-trip skip — 추가 비용 발생 없음). max tool-call depth 5 cap이 LLM이 같은 tool을 무한 호출하는 runaway loop를 막는다 (cap exceed → `tool_error` code `MAX_DEPTH`). depth cap은 env-var (`PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH`)로 override 가능 — 정확한 var 이름은 ADR-17. |
| **End user (architect, indirect — M4 invariant 유지)** | chat 답변이 자연어가 아닌 tool action으로 분기될 때 "지금 뭘 하고 있는지" 알고 싶음 (transparency) | LLM이 tool을 호출하기로 결정하면 SSE stream에 `tool_call` 이벤트가 emit돼서 frontend가 "invoking generate_massing…" 같은 인디케이터를 그릴 수 있다 (정확한 인디케이터 visual은 **M8 Stage-2 design의 책임** — M7은 event grammar만 보장). tool 결과가 도착하면 `tool_result` 이벤트로 카드 렌더링이 가능 (역시 visual은 per-tool design). tool이 실패하면 `tool_error` 이벤트로 error 코드 enum (`TIMEOUT` / `CIRCUIT_OPEN` / `MAX_DEPTH` / `UPSTREAM_4XX` / `UPSTREAM_5XX` / `SCHEMA_INVALID` / `INTERNAL`)을 받아 정확한 copy로 표시 가능. |

## User stories with acceptance criteria

> 본 마일스톤은 사용자-가시 기능보다 **developer / operator 가시 infra**가 본질이다. Story 1–5는 tool BC author 관점 (M8이 첫 소비자), Story 6–8은 operator 관점, Story 9는 end-user transparency, Story 10은 M4 regression invariant.

### Tool BC author — registration + invocation

#### Story 1 — `ToolCatalog`에 descriptor 하나 추가만으로 등록됨
> As a tool BC author landing on M7 infra, I want to register my tool by adding a single `ToolDescriptor` constant to `rag-chat-domain.ToolCatalog` so that the chat surface picks it up via classpath without any wiring code change.

- [ ] `rag-chat-domain`에 `ToolCatalog` 클래스가 존재한다. `public static List<ToolDescriptor> descriptors()` 또는 동등한 노출 API를 제공한다.
- [ ] `ToolDescriptor`는 `rag-chat-domain`의 immutable record / value object. 필드: `name (String)`, `description (String)`, `parameterSchema (JsonSchema or 동등 표현)`, `endpoint (URI)`, `timeout (Duration)`. 정확한 시그니처는 ADR-17.
- [ ] `ToolCatalog` 는 Spring annotation을 사용하지 않는다 (`-domain` Spring-free invariant 유지 — ADR-02).
- [ ] M7 P0 출시 시점에 `ToolCatalog.descriptors()`는 **0 entries**를 반환할 수 있다 — 등록된 tool이 없는 경우 chat 흐름은 M4 대비 변화 없이 자연어 응답만 흐른다 (regression invariant).
- [ ] M8 시점에 `MassingTool` descriptor가 추가되면 별도 `ChatTurnUseCase` 수정 없이 classpath load 만으로 LLM에 노출된다 — M7의 verification fixture는 합성 `echo` tool descriptor로 이 path를 확인한다 (Story 10 참조).

#### Story 2 — LLM이 tool을 호출하기로 결정하면 ToolDispatcher가 HTTP로 dispatching
> As a tool BC author, I want my `/internal/tools/<name>` POST endpoint to be invoked when the LLM emits a `tool_call` with my tool's name so that I don't need to integrate with Spring AI directly.

- [ ] `rag-chat-infra`에 `ToolDispatcher` adapter가 존재한다. `invoke(name, args) → ToolResult` 또는 동등 API.
- [ ] `ToolDispatcher.invoke(name, args)`는 `ToolCatalog`에서 descriptor를 lookup하고 → descriptor의 `endpoint`로 WebClient HTTP POST → 응답 body를 `ToolResult`로 deserialize한다.
- [ ] HTTP body shape: 요청은 LLM이 produce한 `args` JSON (descriptor의 `parameterSchema`를 따른다), 응답은 tool BC가 제공하는 임의 JSON shape (구조는 tool BC 자유). rag-chat은 응답 JSON을 Spring AI의 tool-result message로 wrap해서 다음 LLM turn에 주입한다.
- [ ] WebClient는 per-tool 설정 (timeout = descriptor's `timeout` 값). 정확한 WebClient instance lifecycle (per-descriptor vs shared with per-call config)은 ADR-17.
- [ ] WebClient는 `application/json` Content-Type / Accept를 사용한다.

#### Story 3 — `X-User-Id` 헤더가 tool BC로 forwarding됨
> As a tool BC author that needs to do tenant-scoped reads (e.g., `massing-gen` reading the brief doc via M2's `/internal/docs/{id}/body` per ADR-08 Exception 1), I want the originating user's `X-User-Id` to be forwarded automatically so that I can pass the same identity downstream.

- [ ] `ToolDispatcher`는 진행 중인 chat session의 `X-User-Id`를 tool BC HTTP 요청의 `X-User-Id` 헤더로 forwarding한다.
- [ ] `X-User-Sub`도 함께 forwarding된다 (M4 ADR-14 §15의 audit logging invariant 일관성 유지 — operator가 어느 Google account가 tool을 호출했는지 추적 가능).
- [ ] 통합 테스트가 WireMock 스텁으로 forwarding 동작을 확인한다 (요청 capture 후 헤더 assertion).
- [ ] `Authorization` 또는 다른 인증 헤더는 forwarding하지 않는다 — chat session은 gateway에서 OAuth로 이미 검증된 상태이고, `/internal/**` 경로는 backend-to-backend trusted channel (ADR-08 invariant).

### Tool BC author — multi-turn flow

#### Story 4 — 한 user turn 안에서 여러 tool이 순차적으로 호출됨
> As a tool BC author whose tool's result triggers the LLM to call another tool, I want the full chain (tool A → tool B → final text) to complete in one user turn without the user re-sending the message.

- [ ] `ChatTurnUseCase`는 LLM이 `tool_call`로 응답하면 dispatching → tool-result feedback → 다음 LLM 호출의 cycle을 LLM이 더 이상 tool을 호출하지 않을 때까지 반복한다 (P0 = serial; parallel은 out of scope).
- [ ] 한 user turn 안에서 최대 **5번**의 tool 호출까지 허용된다 (depth cap, 작업 default).
- [ ] depth cap이 env-var `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH`로 override 가능하다. 정확한 var 이름과 default 위치(`application.yml` 노출)는 ADR-17.
- [ ] cap을 초과하면 `tool_error` SSE 이벤트가 emit된다 with `code: "MAX_DEPTH"`, chat turn은 그 시점에서 terminate된다 (assistant message는 직전까지의 partial content로 persist — 정확한 persist 정책은 ADR-17 / M4 ADR-14 §13 abort path 패턴 재사용 권고).
- [ ] WireMock 통합 테스트가 6번째 tool 호출 시 `MAX_DEPTH` 발동을 확인한다.

### Tool BC author — error semantics

#### Story 5 — tool BC가 실패하면 `tool_error` 이벤트가 enum 코드와 함께 발행됨
> As a tool BC author, I want failures from my BC to surface to the frontend as `tool_error` events with a stable error-code enum so that the UI can render the correct copy and so I can debug without combing through chat logs.

- [ ] `tool_error` SSE 이벤트의 `data.code` 필드는 다음 enum 값 중 하나다 — frontend가 이 enum으로 user-facing copy를 결정:
  - `TIMEOUT` — descriptor의 `timeout`을 초과
  - `CIRCUIT_OPEN` — per-tool circuit breaker가 OPEN 상태라 호출 자체가 거부됨 (LLM round-trip 자체를 skip — 비용 절약)
  - `MAX_DEPTH` — depth cap 초과 (Story 4)
  - `UPSTREAM_4XX` — tool BC가 4xx 응답 (tool BC의 contract 어김 — 보통 LLM args가 schema 위반)
  - `UPSTREAM_5XX` — tool BC가 5xx 응답 (tool BC의 internal failure)
  - `SCHEMA_INVALID` — LLM이 produce한 args가 descriptor의 `parameterSchema`를 위반 (Spring AI / 자체 validator가 검출)
  - `INTERNAL` — `ToolDispatcher` 자체 내부 오류 (deserialization 실패 등)
- [ ] enum 값 추가 / 변경은 ADR-17이 핀 — 본 PRD는 위 7개를 P0 set으로 정의한다.
- [ ] `tool_error` 이벤트 emit 후의 LLM 동작은 ADR-17이 결정 — working assumption: (a) breaker OPEN / MAX_DEPTH / SCHEMA_INVALID → 즉시 terminate (추가 LLM round-trip 없음), (b) TIMEOUT / UPSTREAM_5XX → 한 번 LLM에 error-as-tool-result로 다시 fed → LLM이 사과 메시지 자연어로 생성. 정확한 정책은 ADR-17 / Spring AI 1.0 retry 정책 평가.

### rag-chat operator — resilience

#### Story 6 — per-tool circuit breaker가 한 tool의 5xx burst를 격리
> As the operator, I want each tool to have its own Resilience4j circuit breaker so that one sick tool BC doesn't degrade chat overall.

- [ ] 각 `ToolDescriptor`마다 별도의 Resilience4j `CircuitBreaker` 인스턴스가 등록된다. 브레이커 이름은 descriptor name 기반 (예: `tool-generate_massing`). 정확한 명명 규칙은 ADR-17.
- [ ] 브레이커 임계치는 ADR-14 §4의 `spark-gateway` 브레이커와 동일한 형태 (failure rate threshold + sliding window + minimum calls + OPEN duration + half-open permitted calls) — 정확한 수치 핀은 ADR-17. 작업 default: failure-rate ≥50% over 60s window, minimum 10 calls, OPEN duration 30s, half-open 1 probe.
- [ ] 브레이커가 OPEN 상태일 때 `ToolDispatcher.invoke(...)` 호출은 **upstream HTTP 호출 없이 즉시** `tool_error` SSE event with `code: "CIRCUIT_OPEN"`를 emit한다 — LLM round-trip은 발생하지 않는다 (operator의 cost-protection 의도).
- [ ] WireMock 통합 테스트: tool BC stub이 5xx를 반복 응답 → minimum-calls 임계 도달 후 브레이커 OPEN → 다음 호출이 `CIRCUIT_OPEN`으로 즉시 실패함을 확인.
- [ ] half-open probe 후 success → CLOSED 복귀도 통합 테스트로 확인.

#### Story 7 — tool 호출 timeout이 descriptor 단위로 적용됨
> As the operator, I want each tool's timeout to be pinned in its descriptor so that a slow tool can't tie up a chat thread indefinitely.

- [ ] WebClient 호출이 descriptor의 `timeout` 값을 적용한다. (descriptor가 30s를 선언했으면 30s.)
- [ ] timeout 초과 시 `tool_error` SSE event with `code: "TIMEOUT"`가 emit된다.
- [ ] timeout이 만료된 upstream 호출은 (a) Reactor cancellation으로 cancel되어 (b) 가능하면 tool BC의 Netty connection도 closed 된다 (M4 ADR-14 §14의 vLLM disconnect 패턴 재사용). 정확한 verification 단계는 ADR-17.
- [ ] descriptor가 timeout을 누락하면 빌드 시점 또는 컨테이너 시작 시점에 fail-fast — 정확한 강제 메커니즘은 ADR-17 (record validation vs `@PostConstruct` check).

#### Story 8 — depth cap이 runaway loop를 차단
> As the operator, I want a hard cap on tool-call depth per user turn so that an LLM that keeps calling the same tool can't burn unbounded GPU minutes or tokens.

- Story 4와 동일한 acceptance criteria (depth=5 default + env-var override + `MAX_DEPTH` enum). 본 항목은 operator framing으로 명시.

### End user — transparency

#### Story 9 — tool 호출/결과가 SSE로 투명하게 전파됨 (visual은 M8 design 책임)
> As an end user whose LLM decides to call a tool, I want a transparent `tool_call` SSE event so that the chat UI can show "invoking generate_massing…" while the tool runs, and a `tool_result` event so that the artifact is renderable inline.

- [ ] SSE event grammar가 3개의 신규 이벤트로 확장된다. 정확한 표현 (새 event name vs 기존 `phase` 이벤트의 `step` discriminator 확장)은 **ADR-17 open question** — Open questions 섹션 참조. 본 PRD는 wire-shape를 두 표현 중 어느 쪽이든 carry할 수 있도록 wire-shape contract만 핀.
- [ ] LLM이 tool 호출을 결정한 직후 `tool_call` event가 emit되고, tool 응답 도착 후 `tool_result` (성공) 또는 `tool_error` (실패) 중 하나가 emit된다.
- [ ] event interleaving: 한 user turn 안에서 `phase` (retrieval / generating) + `token` (자연어 streaming) + `tool_call` + `tool_result` 가 시간순으로 섞여서 emit되며, terminal은 항상 `done` 또는 `error` 1개 (M4 ADR-14 invariant 유지).
- [ ] M7 P0의 visual 책임 범위는 **event emission + wire-shape 보장까지**. `tool_call` / `tool_result` / `tool_error` 의 카드 / 인디케이터 visual은 **각 tool BC의 Stage-2 design 책임** (spec §5의 명시) — M7 PR set은 frontend에서 이 이벤트를 dispatch loop에 인식만 시키되, 실제 카드 렌더링은 M8 frame이 land된 이후 plug-in 한다.

### Cross-milestone — M4 regression invariant

#### Story 10 — `ToolCatalog`가 비어있어도 chat 흐름은 M4 그대로
> As an end user using the chat surface when no tool BCs are registered (M7 ships before M8), I want chat to behave exactly as M4 ships — natural-language responses, no SSE event grammar surprises in the frontend.

- [ ] `ToolCatalog.descriptors()`가 0 entries를 반환하는 deployment에서 `POST /api/rag/chat` 호출이 M4 ADR-14 §1의 happy-path SSE event sequence (`phase` × N → `token` × N → `done`)를 그대로 emit한다 — `tool_call` / `tool_result` / `tool_error` 이벤트는 한 건도 emit되지 않는다.
- [ ] M4 통합 테스트 슈트가 본 PRD cycle PR set에서 한 줄도 변경되지 않고 모두 통과한다 — invariant.
- [ ] M7 cycle 안에서 합성 `echo` tool descriptor (test fixture only — main classpath에는 없음)를 등록한 별도 end-to-end 통합 테스트가 `tool_call` → `tool_result` → `done` 흐름을 확인한다 — 이 테스트는 M7의 verification에만 사용되고 운영 deployment 의 `ToolCatalog`에는 영향이 없다.

## UX surfaces

Per spec §5: M7 자체는 user-visible UI를 ship하지 않는다. SSE event grammar가 frontend에 새 event type 3개를 dispatch 가능하게 만들 뿐, 실제 카드 / 인디케이터 렌더링은 각 tool BC (M8이 첫 사례)의 Stage-2 design이 책임진다.

| Route | Auth | M7 변경 |
|---|---|---|
| `/chat` | required (M4 invariant) | frontend SSE consumer가 3개의 신규 event name (`tool_call` / `tool_result` / `tool_error`)을 인식하도록 dispatch loop만 확장 — 카드 / 인디케이터의 visual rendering은 **M7에서 ship하지 않는다** (no-op handler 등록). M8 Stage-2 design에서 `generate_massing` 카드 visual을 pin할 때 실제 컴포넌트가 추가된다. |
| `/chat/sessions/{id}/messages` | required (M4 invariant) | message history payload에 `tool_call` / `tool_result` 흔적이 포함되는지 여부는 **open question** — 작업 default: history payload는 M4의 user / assistant 메시지 한 쌍만 carry하고, intermediate tool 호출은 영구 저장하지 않는다 (M4 ADR-14 §13의 abort path와 같은 "in-memory-only" 패턴). 정확한 persist 정책은 ADR-17. |

| Endpoint | Auth | M7 변경 |
|---|---|---|
| `POST /api/rag/chat` | required (M4 invariant) | SSE stream이 3개의 신규 event를 추가로 emit할 수 있게 됨. 요청 body shape은 M4와 동일 (`sessionId` + `message`). 응답 stream의 termination semantic (`done` / `error` 한 개)는 M4 invariant 유지. |

신규 라우트 없음 — M7은 M4의 기존 surface 위에 SSE event grammar 확장과 ToolDispatcher 백엔드 dispatch 분기만 더한다.

## Bounded Context: RAG-Chat (M7 amendment)

M4의 RAG-Chat BC를 그대로 사용한다 — 새 BC를 만들지 않는다. M7은 그 안에 다음 컴포넌트를 추가한다.

- **책임 (Responsibility, M4와 동일 + 1줄 추가):** chat 표면 + retrieval + generation의 owning context. M7은 여기에 **"tool dispatcher — chat이 call하는 외부 tool BC의 catalog와 호출 메커니즘"** 책임을 추가한다. tool 자체의 logic은 owning하지 않는다 (그건 tool BC들의 책임 — M8이 첫 사례).
- **새 도메인 컴포넌트 (in `rag-chat-domain`, Spring-free):**
  - `ToolCatalog` — `static List<ToolDescriptor> descriptors()` 노출 (P0는 hardcoded; 동적 등록은 P2). 정확한 노출 형태 (static method vs static field vs interface)은 ADR-17.
  - `ToolDescriptor` — immutable record. 필드 `(name, description, parameterSchema, endpoint, timeout)`. JsonSchema 표현은 ADR-17 (e.g., Jackson `JsonNode` 직접 vs everit-json-schema 객체 vs raw string).
  - `ToolResult` — tool BC 응답의 wrap 표현 (success body + tool name + correlation id). 정확한 시그니처는 ADR-17.
  - `ToolError` — tool 실패 표현 (code enum + message). enum은 위 Story 5의 7개 set.
  - `ToolCallEnvelope` — LLM이 produce한 `(toolName, argsJson)`의 immutable 표현. 정확한 형태는 Spring AI 1.0의 tool-call message shape에 의존 — ADR-17.
- **새 인프라 adapter (in `rag-chat-infra`):**
  - `WebClientToolDispatcher implements ToolDispatcherPort` — WebClient per-descriptor (timeout 적용). Resilience4j `CircuitBreaker` per descriptor.
  - 각 `ToolDescriptor`마다 Resilience4j `CircuitBreaker` 인스턴스가 `CircuitBreakerRegistry`에 등록된다 — 명명: `tool-<descriptor-name>` (working — ADR-17 확정).
- **app-layer 변경 (in `rag-chat-app`):**
  - `StreamChatTurnUseCase`가 multi-turn function-calling 분기를 갖는다 — LLM 응답이 `tool_call` 필드를 carry하면 dispatching → 결과를 tool-result message로 wrap → 같은 turn 안에서 LLM 재호출. 정확한 Spring AI 1.0 API 호출 시그니처 (`ChatClient.tools(...)` 의 정확한 form)는 ADR-17.
  - depth cap enforcement: turn 내 tool 호출 횟수를 카운트 → cap 도달 시 `tool_error` emit + terminate.
  - `ToolDispatcherPort` (port interface, `-app` 또는 `-domain` 결정은 ADR-17).
- **위치 invariant (M4 invariant 재확인):** Spring AI imports는 `rag-chat-infra`에만 존재. Resilience4j도 마찬가지. `-domain`은 Spring-free 유지 — `ToolCatalog`, `ToolDescriptor`, `ToolResult`, `ToolError`는 모두 Spring annotation 사용 금지.
- **소유 데이터 (M4와 동일, 변경 없음):** `chat.sessions` / `chat.messages` / `chat.message_citations` 스키마 변경 **없음**. M7 P0는 tool 호출 trace를 영구 저장하지 않는다 (in-memory only — M4의 abort path 패턴과 동일). 영구 저장 여부는 Open questions로 raise 됨 (운영 telemetry 필요성 평가 후 M7.1).
- **이벤트 (Kafka 표면 = 0, M4 invariant 유지):** rag-chat BC는 여전히 Kafka producer / consumer 가 아니다. tool 호출은 동기 HTTP — ADR-08 Exception 4의 sanctioning이 본 cycle의 ADR amendment.
- **새 에러 enum (in `rag-chat-domain`):**
  - `ToolErrorCode` enum: `TIMEOUT`, `CIRCUIT_OPEN`, `MAX_DEPTH`, `UPSTREAM_4XX`, `UPSTREAM_5XX`, `SCHEMA_INVALID`, `INTERNAL` (Story 5). 위 7개로 P0 set이 닫힌다 — 추가는 ADR-17 또는 M7.1.
- **외부 의존성 (신규):** 없음 (Resilience4j는 M4 ADR-14 §4에서 이미 도입됨 — 재사용. Spring AI는 M4 ADR-14에서 이미 도입됨 — `.tools(...)` API만 새로 사용). 신규 컨테이너 / 신규 라이브러리 의존성은 추가되지 않는다.

## Wire-shape contracts

SSE event 의 `data` payload JSON shape. **본 PRD가 wire-shape를 pin** — frontend / ADR-17이 이 shape을 깨면 안 된다. 단, 3 신규 event가 "독립된 event name (`event: tool_call` …)" 형태로 land될지 "기존 `phase` event의 `step` discriminator 확장" 형태로 land될지는 Open Question (Q-A 참조). 어느 표현이든 아래 payload shape은 동일하게 carry된다.

### `tool_call` payload

```json
{
  "name": "generate_massing",
  "args": {
    "briefDocId": "11111111-2222-3333-4444-555555555555",
    "siteWidth": 30.0,
    "siteDepth": 40.0
  }
}
```

- `name` — `ToolDescriptor.name` (LLM이 ChatClient.tools가 제공한 catalog 안에서 고른 것).
- `args` — LLM이 produce한 임의 JSON. descriptor의 `parameterSchema` 와 일치한다 (Spring AI 가 validation 후 dispatch — schema 위반 시 `tool_error` `SCHEMA_INVALID` 분기).
- (선택) `id` — correlation id. Open Question Q-B 참조. P0에서 포함 여부는 ADR-17.

### `tool_result` payload

```json
{
  "name": "generate_massing",
  "result": {
    "fileUrl": "/api/arch/outputs/abcdef…",
    "summary": "12 rooms, 3 floors, 480 m² total",
    "programJson": { "rooms": [ {"name":"강의실A","area":80}, … ] }
  }
}
```

- `name` — 대응하는 `tool_call.name`.
- `result` — tool BC가 반환한 응답 body JSON 그대로. shape은 tool BC 자유 — `rag-chat` 은 통과시킨다 (frontend는 per-tool 로 카드 렌더링).
- (선택) `id` — correlation id. Q-B 참조.

### `tool_error` payload

```json
{
  "name": "generate_massing",
  "code": "TIMEOUT",
  "message": "Tool 'generate_massing' did not respond within 30s"
}
```

- `name` — 대응하는 `tool_call.name`.
- `code` — `ToolErrorCode` enum 값 중 하나 (Story 5의 7개 set 중).
- `message` — human-readable. 정확한 i18n / ko-en wording은 ADR-17 / implementer.
- (선택) `id` — correlation id. Q-B 참조.

### terminal events (M4 invariant 유지)

- `done` 이벤트는 M7에서 변경되지 않는다 — assistant 메시지가 정상 완료될 때 emit (multi-turn tool 호출의 마지막 LLM 자연어 응답 다음).
- `error` 이벤트는 M4 ADR-14 §6.5의 enum (`GATEWAY_5XX` / `RATE_LIMIT` / `RETRIEVAL_EMPTY` / `ABORTED` / `INTERNAL`)을 carry한다 — chat-level fatal error. tool-level error는 `tool_error`로 분리되어 있어서 `error`로는 fall back하지 않는다 (단, breaker가 chat 전체를 trip하는 경계 케이스의 정확한 분기는 ADR-17).

## Non-functional requirements

- **`rag-chat-domain`은 Spring-free 유지** (M4 ADR-14 invariant). `ToolCatalog` / `ToolDescriptor` / `ToolResult` / `ToolError` / `ToolErrorCode` 모두 Spring annotation 사용 금지. Spring AI imports는 `rag-chat-infra`에만.
- **M4 regression invariant (Story 10).** `ToolCatalog`가 비어있을 때 chat 흐름은 M4 그대로. M4 통합 테스트 슈트 본 cycle PR set에서 0줄 변경.
- **ADR-14 §5.2 SSE grammar amendment**: 본 PRD가 wire-shape를 pin (위 §Wire-shape contracts). ADR-17이 정확한 표현 형식 (event name vs phase step)을 finalize하고 ADR-14에 amendment block을 append.
- **ADR-08 Exception 4 추가**: ADR-17의 책임 (본 PRD는 ADR-08을 직접 변경하지 않는다). M8 시점에 `massing-gen-api`가 첫 sub-row로 추가된다.
- **per-tool circuit breaker isolation**: tool A의 5xx burst가 tool B의 호출에 영향을 주지 않아야 한다 — 통합 테스트가 두 WireMock 인스턴스로 isolation을 단언.
- **depth cap default 5** (env-var override). 정확한 var 이름과 default가 어디에 위치하는지 (`application.yml` 의 노출 위치)는 ADR-17.
- **per-tool timeout enforcement**: descriptor의 `timeout` 이 WebClient 호출에 적용되어야 한다. timeout 초과 시 upstream connection 도 close 시도 (best-effort, M4 ADR-14 §14 패턴 재사용).
- **`X-User-Id` + `X-User-Sub` forwarding** (Story 3). audit log shape (M4 ADR-14 §15)도 tool 호출 event를 추가로 carry해야 한다 — 정확한 필드 (`toolName`, `toolDepth`, `toolDurationMs` 등) 는 ADR-17.
- **token cost accounting**: tool 호출이 LLM context를 inflate하므로 tokens-in 카운트가 증가한다 — M4 ADR-14 §5의 token bucket은 tool-inflated turn도 그대로 카운트하는 것이 working assumption. per-tool 별도 quota 도입은 M7.1 검토.
- **observability**: tool 호출당 (a) tool name, (b) 호출 duration ms, (c) circuit breaker state transition, (d) `tool_error` 발생 시 code 가 INFO / WARN 구조화 로그로 기록된다. 정확한 필드는 ADR-17. Micrometer 메트릭으로도 노출되어야 함 (resilience4j 자동 binding).
- **Spring AI 1.0 GA function-calling API 안정성**: spec §5의 첫 open question — 본 PRD는 working assumption `ChatClient.prompt().tools(...)` 패턴을 사용하지만, 실제 stable API shape (synchronous `.call()` 과 streaming `.stream()` 사이의 차이 포함)은 ADR-17이 평가하고 핀.
- **Spring AI retry policy**: tool 5xx 응답을 Spring AI가 자체적으로 retry하는지, 그게 우리 circuit breaker와 충돌하는지 — ADR-17 평가 영역.

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 사용자 시나리오와 기술 검증 둘 다 포함.

### Tool author / dispatcher scenarios

- [ ] **`ToolCatalog` 노출**: `rag-chat-domain.ToolCatalog`가 존재하며 `descriptors()` API 또는 동등 노출을 제공. P0 출시 시 0 entries 가능 (Story 1).
- [ ] **`ToolDispatcher` invocation**: `ToolDispatcher.invoke(name, args)`가 descriptor lookup → WebClient HTTP POST → tool BC `/internal/tools/<name>` 호출. WireMock 통합 테스트로 happy path 확인 (Story 2).
- [ ] **`X-User-Id` / `X-User-Sub` forwarding**: tool BC 호출 요청에 두 헤더가 포함됨. WireMock 가 헤더를 capture해서 assertion (Story 3).
- [ ] **Multi-turn tool chain**: LLM이 한 user turn 안에서 tool A → tool B → 자연어 마무리 흐름. WireMock 통합 테스트가 2-step chain을 확인 (Story 4).
- [ ] **Max depth cap**: 6번째 tool 호출 시 `tool_error` SSE event with `code: "MAX_DEPTH"` emit + turn terminate. env-var override 동작도 확인 (Story 4 / 8).
- [ ] **합성 echo tool end-to-end**: M7 test fixture에 등록된 합성 `echo` tool descriptor로 `tool_call` → tool_result` → `done` 흐름이 SSE stream에 정확히 emit (Story 9 / 10).

### Resilience scenarios

- [ ] **Circuit breaker OPEN**: WireMock stub이 5xx를 minimum-calls(working 10) 회 이상 + failure-rate ≥50% 발생시 → 브레이커 OPEN. 다음 호출이 `tool_error` `CIRCUIT_OPEN`으로 즉시 실패 (LLM round-trip 없음 — WireMock 호출 count 0회 증가) (Story 6).
- [ ] **Circuit breaker HALF_OPEN → CLOSED**: OPEN 후 30s (working OPEN duration) 경과 → half-open probe 1회 → success → CLOSED 복귀 (Story 6).
- [ ] **Per-tool isolation**: tool A에 5xx burst를 가해 OPEN으로 보내도 tool B의 happy-path 호출이 영향받지 않음 (Story 6).
- [ ] **Timeout enforcement**: WireMock stub이 descriptor `timeout` 보다 긴 응답 지연 → `tool_error` `TIMEOUT` emit. upstream connection close 시도 (Story 7).
- [ ] **UPSTREAM_4XX vs UPSTREAM_5XX 구분**: WireMock 가 400 응답 → `UPSTREAM_4XX`. 500 응답 → `UPSTREAM_5XX`. 4xx는 circuit breaker failure 로 카운트되지 않음 (M4 ADR-14 §4 invariant 재사용 — code/data 버그는 breaker trip의 신호가 아님) (Story 5).
- [ ] **SCHEMA_INVALID**: LLM이 descriptor `parameterSchema`를 위반하는 args를 produce → Spring AI / 자체 validator가 검출 → `tool_error` `SCHEMA_INVALID` (Story 5).

### Technical validation

- [ ] `rag-chat-domain`에 `ToolCatalog`, `ToolDescriptor`, `ToolResult`, `ToolError`, `ToolErrorCode` 가 존재하고 Spring annotation을 사용하지 않는다.
- [ ] `rag-chat-infra`에 `WebClientToolDispatcher`(또는 동등 명명) 가 존재하고 Resilience4j `CircuitBreaker` per-descriptor 가 등록된다.
- [ ] `rag-chat-app.StreamChatTurnUseCase`가 LLM 응답의 `tool_call` 필드를 감지하면 dispatch → tool-result → 다음 LLM 호출의 cycle을 반복한다 (P0 = serial).
- [ ] `ToolErrorCode` enum이 정확히 7개 값 (`TIMEOUT`, `CIRCUIT_OPEN`, `MAX_DEPTH`, `UPSTREAM_4XX`, `UPSTREAM_5XX`, `SCHEMA_INVALID`, `INTERNAL`).
- [ ] depth cap default = 5. env-var `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH` (정확한 이름 — ADR-17) override 동작 검증.
- [ ] SSE stream이 `tool_call` / `tool_result` / `tool_error` 페이로드를 §Wire-shape contracts의 JSON shape 으로 정확히 emit.
- [ ] M4 통합 테스트 슈트 0줄 변경 + 모두 통과 (regression invariant).
- [ ] `rag-chat-api` Docker 컨테이너에 신규 dependency 추가 없음 (Resilience4j + Spring AI는 재사용).
- [ ] `chat` Postgres schema 변경 없음 (M7 P0는 tool 호출 trace 영구 저장 안 함).

### Cross-doc invariants

- [ ] **ADR-14 SSE grammar amendment**: ADR-17 작성 시 ADR-14 §5.2에 amendment block 추가 — 정확한 표현은 ADR-17 책임 (본 PRD는 wire-shape만 핀).
- [ ] **ADR-08 Exception 4 추가**: ADR-17이 ADR-08 amendment block 작성. **본 PRD는 ADR-08을 변경하지 않는다.**
- [ ] **ADR-00 (index) 행 추가**: ADR-17이 land될 때 ADR-00 index에 row 추가 — 본 PRD cycle은 ADR-00을 변경하지 않는다.
- [ ] **roadmap.md §M7**: 본 PRD의 acceptance bullet 들과 `docs/roadmap.md` §M7의 acceptance bullet 사이 충돌이 없다. 충돌이 있다면 본 PRD가 우선 (PRD가 spec §5의 in-scope 리스트를 명문화한 후속 문서).
- [ ] **GitHub issue #163 body refresh**: 본 PRD가 land된 후 issue #163 의 placeholder body가 본 PRD에 대한 link + 5–6개의 구체적 acceptance item 으로 refresh.

## Out of scope

### M7.1 (same milestone bucket, ship if cycle has slack)

- **Parallel tool 호출.** 한 user turn 안에서 LLM이 여러 tool을 한꺼번에 호출 (Spring AI 1.0이 지원하면). P0 = serial — multi-turn은 가능하지만 한 시점에 하나의 tool 호출만 진행.
- **Tool 호출 trace 영구 저장.** P0는 in-memory only (M4 abort path 패턴 재사용). audit / debugging / 사용자 history 표시 요구가 강하면 M7.1에서 `chat.tool_calls` 테이블 도입 검토.
- **Tool progress streaming from tool BC → rag-chat → frontend.** P0는 tool BC가 단일 JSON으로 응답할 때까지 block. tool BC가 SSE로 progress emit하는 path (예: "1/10 페이지 처리 중") 는 M7.1.
- **Tool result Redis 캐싱.** 동일 (name, args)에 대한 deterministic tool의 결과를 Redis로 캐시 (idempotency / cost-saving). P0 = 매 호출 실행.
- **Tool ACL beyond auth-only.** P0 = any signed-in user 가 any registered tool 호출 가능. tool descriptor에 `allowedUserIds` / role-based gate 추가는 M7.1.
- **Per-tool rate-limiting.** P0 = M4 chat-level token bucket 만 적용. tool-specific quota는 M7.1.
- **Streaming abort 와 tool 호출의 정확한 상호작용.** Stop 버튼이 tool 호출 도중 눌리면 어떻게 cancel 전파되는지의 edge case는 working assumption만 P0 (Reactor cancellation이 dispatcher 까지 propagate). 정밀한 cancellation semantic은 ADR-17 + M7.1.

### P2 (별도 후속 마일스톤)

- **External MCP server connection (Anthropic MCP, OpenAI plugins).** P0의 hardcoded `ToolCatalog`로는 외부 MCP 서버의 동적 tool 노출을 cover하지 않는다.
- **User-defined tools (UI for tool registration).** chat 사용자가 자신만의 tool descriptor를 UI에서 등록하는 path는 별도 마일스톤. Tool descriptor governance 가 personal-scale 을 넘어서면 도입.
- **Dynamic tool catalog (config-file / DB-backed registry).** P0는 classpath constants. 운영 중 hot-add 는 P2.
- **Tool descriptor versioning.** descriptor가 evolve할 때의 schema migration / LLM cache invalidation 은 P2 (P0 = constants 라서 빌드 deploy 시점에 atomic 교체).
- **Cross-session tool result sharing / caching.** "user A가 호출한 generate_massing 결과를 user B가 재사용" 같은 path 는 사용자 격리 invariant 와 충돌 — 검토 자체가 P2.

## Dependencies

- **요구:** M0 — compose stack (Postgres + Redis + Kafka [unused by rag-chat] + gateway), spark-inference-gateway가 host process로 동작 중 (`host.docker.internal:10080`, ADR-04).
- **요구:** M1 (Identity) — `X-User-Id` / `X-User-Sub` 헤더 인젝션 (M4 invariant 유지 + tool BC forwarding 의존).
- **요구:** M2 + M6 (Docs + PDF) — **간접 의존**. M7 자체는 docs BC를 직접 호출하지 않지만, 첫 tool 소비자 M8이 docs body fetch를 사용한다. M7 단독 ship에는 M2 / M6 출시가 필수는 아님.
- **요구:** M3 (RAG-Ingestion / M6.1 후 docs로 consolidated). **간접 의존 only.** M7 자체는 ingestion을 호출하지 않지만 M4가 retrieval을 위해 의존.
- **요구:** M4 (RAG-Chat) — **직접 출시 완료**. M7은 M4의 `rag-chat-{api,app,domain,infra}` 위에 add-on. M4의 SSE controller, `StreamChatTurnUseCase`, Resilience4j `spark-gateway` 브레이커, Spring AI `ChatClient` adapter가 모두 land된 상태여야 한다.
- **요구:** ADR-17 (`docs/adr/17-m7-tool-calling.md` — 후속) — Spring AI 1.0 `ChatClient.tools(...)` API 정확한 호출 시그니처, ADR-08 Exception 4 amendment의 정확한 wording, ADR-14 §5.2 SSE grammar amendment 의 정확한 표현(event name vs `phase` step 결정), Resilience4j 임계치 핀, depth cap env-var 이름, tool error 분류 정책, tool 호출 trace persist 결정, SSE event correlation id 결정.
- **신규 외부 의존성 (이번 마일스톤에서 도입):** **없음**. Resilience4j 와 Spring AI 는 M4 ADR-14에서 이미 도입. M7은 기존 라이브러리의 새 API 만 사용.
- **소비자 (M7 close blocker 아님):** M8 (`massing-gen` BC, 후속). M8 cycle에서 `ToolCatalog`에 `generate_massing` descriptor를 추가하고 `massing-gen-api`의 `/internal/tools/generate-massing` 엔드포인트를 구현한다. M7 자체의 close 는 합성 `echo` tool descriptor (test fixture)로 end-to-end 검증.

## Open questions for ADR-17

ADR-17 (architect)이 해소할 사항. 본 PRD는 wire-shape contract + scope boundary + 7개 error code enum 을 닫았고, 나머지 라이브러리 / 알고리즘 / 정책 핀은 ADR-17.

1. **Spring AI 1.0 GA `ChatClient.tools(...)` API 안정성** (spec §5의 첫 open question). 정확한 호출 시그니처 (`prompt().tools(catalog)` vs builder-level `.defaultTools(...)`), streaming pipeline (`.stream()`) 과의 통합, multi-turn function-calling 의 native 지원 여부 vs 자체 구현 필요성. M4의 streaming wiring (ADR-14 §1) 과의 정합성도 평가.
2. **Spring AI tool error handling** (spec §5). tool 이 5xx 또는 timeout 으로 실패하면 Spring AI 가 자체적으로 retry 하는가? retry 가 있다면 우리 Resilience4j 브레이커와 어떻게 상호작용? retry 횟수 / backoff 가 configurable 한가? 우리 retry 정책으로 override 가능한가?
3. **Max tool-call depth default 와 env-var 이름** (spec §5). 본 PRD working = 5 + `PLAYGROUND_RAG_CHAT_TOOL_MAX_DEPTH`. 5가 정말 합리적 default 인지 (M8 의 단일-tool 시나리오에서 3이면 충분할 수도), env-var 이름 컨벤션 (M4 의 `PLAYGROUND_RAG_CHAT_RETRIEVAL_K` 패턴 따름) 확정.
4. **Multi-turn SSE event ordering 보장** (spec §5). 한 user turn 안에서 `phase` × N + `tool_call` × N + `tool_result` × N + `token` × N + `done` 이 어떤 순서로 emit되는지의 invariant. 정확한 ordering rule (e.g., `tool_call` 은 항상 직전 `phase: generating` 다음, `tool_result` 는 dispatching 직후 emit) 명문화.
5. **Tool result max size** (spec §5). tool BC 가 거대한 JSON (예: 100 KB program JSON + 200 KB excerpt) 을 반환하면 LLM context 가 폭주. M4 의 token budget (ADR-14 §8) 안에서 tool result 의 max payload bytes / token cap. 초과 시 (a) `tool_error` `INTERNAL` vs (b) truncate-and-warn vs (c) drop result 정책.
6. **Cost accounting for tool-inflated prompts** (spec §5). tool 호출이 prompt size 를 inflate 하므로 M4 의 hourly / daily token bucket 이 더 빨리 소진된다. P0 = 그대로 둠 (작업 default), but M5 metrics dashboard 에 tool-driven token 비용 분리 노출 필요한지 평가.
7. **(Q-A) SSE event 표현: 새 event name 인가 `phase` step 인가?** (본 PRD가 추가한 open question.) M4 ADR-14 §5.2 의 revised SSE grammar (2026-05-19 PR B) 는 progress 정보를 `phase` event 의 `step` discriminator 로 표현하며, 이미 `step: "tool_call"` / `step: "tool_result"` 예시를 carry한다 (PR C 표시). M7 spec §5는 별개의 새 event name (`event: tool_call` …) 으로 기술. 이 둘은 wire-level 에서 다른 형식 — 어느 쪽으로 land 할지 ADR-17 결정. 둘 다 §Wire-shape contracts 의 payload 를 carry할 수 있지만 frontend dispatch loop 코드가 다르다.
8. **(Q-B) `tool_call.id` correlation ID** (본 PRD가 추가한 open question — spec §5에는 명시 없음). multi-turn 안에서 `tool_call` 이 여러 번 emit될 때 frontend 가 어떤 `tool_result` 가 어떤 `tool_call` 에 대응하는지를 pairing 하려면 correlation id 가 필요하다 (예: `id: "call_01"`). P0 P0 working: Spring AI 가 tool-call 마다 생성하는 id 를 그대로 carry. 정확한 id 형식 / generation 책임 (Spring AI vs 우리 `ToolDispatcher`) 은 ADR-17.

---

> **PRD vs ADR:** 이 문서는 사용자 (= tool BC author 미래의 자신 / operator 미래의 자신 / end user) 와 리뷰어가 읽는 표면이다. Spring AI 1.0 정확한 API, Resilience4j 임계치 수치, SSE event 표현 형식 (event name vs phase step), tool 호출 trace persist 결정, env-var 이름, retry 정책 같은 기술 컨트랙트는 ADR-17 이 우선한다. PRD 가 ADR-17 과 어긋나 보이면 ADR-17 을 따른다.
>
> **PRD vs spec:** spec (`docs/superpowers/specs/2026-05-19-post-m5-roadmap.md`) §5 의 in-scope 리스트 5개 / out-of-scope (M7.1 + P2) / 6개 open question 을 본 PRD 가 verbatim 으로 carry 한다 — 닫지 않는다. spec 의 SSE event 예시는 새 event name 형태로 적혀 있고, M4 ADR-14 §5.2 의 revised grammar 는 `phase` step 형태로 적혀 있다 — 본 PRD는 이 모순을 Open Question Q-A 로 catalogue 한다.
>
> **PRD vs design doc:** M7 은 user-visible UI 가 없고, frontend 영향은 SSE dispatch loop 가 신규 event 를 인식할 수 있게 되는 것 뿐이다. 카드 / 인디케이터 visual 은 각 tool BC 의 Stage-2 design 책임 (M8 이 첫 사례) — M7 PRD cycle 은 design doc 을 작성하지 않는다.
