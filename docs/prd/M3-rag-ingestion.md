# PRD: M3 — RAG-Ingestion

> **Source of truth (downstream-facing contract):** roadmap.md §M3, `docs/adr/08-inter-service-comms.md` §"Exception 1: rag-ingestion → docs body-fetch" + §"Exception 2: rag-ingestion → redis-playground", `docs/adr/09-public-route-policy.md` §"Public retrieval scoping", `docs/adr/12-m2-docs.md` §2, and the M2 PRD/spec for the exact event names (`docs.document.uploaded`, `docs.document.visibility-changed`, `docs.document.deleted` — confirmed in `docs/superpowers/specs/2026-05-16-m2-docs-bc-design.md` §5).
>
> **Parallel work:** the per-milestone ADR for M3 (provisionally **ADR-13**) is the architect's artifact and will pin libraries, retry curves, chunk parameters, DLQ topic names, the ingestion-complete signal mechanism, and the module quadruplet wiring. Stage-2 design doc does **not** exist for M3 — the BC has no user-facing UI.
>
> **Forward contract this PRD enables:** M4's retrieval surface is being amended in this same cycle from "two endpoints" (`/api/rag/chat/public` + `/api/rag/chat/private`) to **a single `/api/rag/chat` endpoint** that switches retrieval corpus by header presence:
> - **Anonymous (no `X-User-Id`):** `WHERE visibility = 'public'` — community-wide.
> - **Authenticated (`X-User-Id` present):** `WHERE visibility = 'public' OR (user_id = X-User-Id AND visibility = 'private')`.
>
> M3 has no API role in that switch — M4 reads pgvector directly — but **M3 must guarantee that every chunk row carries an accurate `(user_id, visibility)` pair at all times**, including after visibility flips. This PRD pins that invariant.

## 한 줄 설명
업로드되거나 편집된 모든 문서를 사용자 없는(UI-less) Kafka 파이프라인으로 자동 청크·임베딩·재태깅하여 pgvector에 보관함으로써, M4가 단일 retrieval 쿼리만으로 익명/인증 두 corpus를 정확히 분리해 검색할 수 있도록 만든다.

## Summary

M3은 playground의 **첫 번째 사용자 UI 없는 백엔드 BC**다. 작성자가 M2에서 문서를 업로드하거나 본문을 편집하면 `docs-api`는 outbox로 `docs.document.uploaded`를 발행하고, 같은 작성자가 가시성을 토글하면 `docs.document.visibility-changed`를, 문서를 삭제하면 `docs.document.deleted`를 발행한다. `rag-ingestion`은 이 세 토픽을 소비해 pgvector(`rag.document_chunks`)를 source-of-truth로 유지한다:

- `uploaded` → 본문 fetch(`GET docs-api/internal/docs/public/{id}/body`, ADR-08 §"Exception 1") → 청크 분할 → BGE-M3 임베딩(`spark-inference-gateway` at `host.docker.internal:10080`) → upsert into pgvector with `(document_id, chunk_index, user_id, visibility, embedding, text)`.
- `visibility-changed` → 같은 `document_id`의 모든 chunk 행에 대해 **본문을 다시 fetch하지 않고** `visibility` 컬럼만 UPDATE.
- `deleted` → 같은 `document_id`의 모든 chunk 행을 purge.

이 BC가 존재하는 이유는 단 하나다: **M4가 retrieval할 때 두 corpus(공개 community + 인증된 작성자의 비공개)를 한 번의 SQL `WHERE` 절로 분리할 수 있어야 한다.** 이 SQL 조건의 정합성은 곧 M3가 `(user_id, visibility)` 메타데이터를 얼마나 신뢰성 있게 유지하느냐에 달려 있다.

지금 출시하는 이유: M2가 작성자별 본문 + 가시성 + 세 이벤트 컨트랙트를 freeze했고(PR #50 머지 직후 시점), `spark-inference-gateway`의 BGE-M3 엔드포인트도 인프라 측에서 준비되어 있다. M3는 M4(chat UI + 생성) 가 의미 있는 답변을 내기 위한 **유일한 prerequisite**이다.

## User personas

M3에는 **사용자 UI가 없다.** 페르소나는 운영자 + 간접 사용자 + 다운스트림 컨슈머의 세 카테고리로 한정된다.

| 페르소나 | 핵심 동기 | M3에서 가능한 것 / 보장받는 것 |
|---|---|---|
| **Operator (= 프로젝트 오너 / dev)** | 인제스트 파이프라인이 **신뢰 가능**하고 **재실행 가능**하고 **관측 가능**하기를 원함 | Kafka redelivery에 대한 idempotency, DLQ로 격리된 poison event, 임베딩 게이트웨이 장애에 대한 retry/backoff, 구조화된 로그 + 메트릭 surface |
| **End user (간접)** | 자신이 방금 올린 문서가 곧 chat에서 검색되기를 원함 | 업로드 후 **bounded latency** 내에 chunk가 pgvector에 가시화됨 (정확한 N초 값은 architect ADR pin) |
| **Authenticated author (간접)** | 자신이 private 문서를 public으로 toggle하면 그 문서가 즉시 익명 chat에도 노출되기를 원함 (역도 동일) | `visibility-changed` 이벤트 도착 후 **bounded latency** 내에 chunk row의 `visibility` 컬럼이 동기화됨 |
| **Downstream M4 consumer** | pgvector 한 군데만 봐도 `(user_id, visibility)` 필터로 retrieval corpus가 정확히 분리되기를 원함 | 모든 chunk 행이 `(user_id, visibility)` 쌍을 정확하게 carry. M3 측 추가 API 호출 없이 단일 SELECT로 retrieval 가능 |

오너(JeekLee)는 M3에서 일반 작성자와 구분되지 않는다 — 작성자별 chunk 격리는 `user_id` 기반이며 오너 역시 동일 칼럼으로 식별된다. 오너 특별 처리는 M4의 anonymous chat retrieval(공개 community 전체)에서만 일어난다.

## User stories with acceptance criteria

M3는 UI가 없으므로 user story는 **운영자 + 다운스트림** 관점에서 작성한다.

### Operator

#### Story 1 — Exactly-once on Kafka redelivery
> As an operator, I want every published or edited document to be ingested **exactly once** even if Kafka redelivers `docs.document.uploaded`, so that pgvector doesn't fill with duplicate chunks.

- [ ] 동일 `(documentId, bodyChecksum)`을 가진 `docs.document.uploaded` 이벤트가 N번 도착해도 `rag.document_chunks`의 row 수는 동일하게 유지된다 (per spec §5, idempotency key `documentId + bodyChecksum`).
- [ ] Idempotency는 **Redisson `@GlobalLock`** (per ADR-08 §"Exception 2", namespace `rag-ingestion:lock:document:{id}`)으로 구현되어 동시 처리 경합 시 한쪽만 진입한다 — TTL ≤ 5분 cap.
- [ ] 본문이 실제로 변경된 경우(`bodyChecksum`이 다른 `uploaded`가 도착) 기존 청크는 삭제되고 새 청크로 **재임베딩**된다. 부분 실패 시 절반의 chunk만 남는 상태는 발생하지 않는다 (단일 트랜잭션 또는 명시적 두 단계 + tombstone).
- [ ] 통합 테스트가 같은 이벤트를 3회 전송하고 chunk 수가 일정함을 단언한다.

#### Story 2 — Retry + DLQ on embedding-gateway failure
> As an operator, I want failed embedding calls (gateway down, timeout, 5xx) to retry with backoff and **eventually land in a DLQ or trigger an alert**, not silently drop.

- [ ] `spark-inference-gateway` 호출은 명시된 timeout + 지수 백오프 + jitter로 retry된다. 정확한 횟수/곡선은 ADR-13.
- [ ] **permanent failure** (예: 최종 retry까지 5xx, 또는 본문 fetch 영구 실패)는 in-flight 이벤트를 **DLQ 토픽** (예: `docs.document.uploaded.dlq`, ADR-03 컨벤션 + ADR-08 §"Exception 1")으로 라우팅한다.
- [ ] DLQ로 라우팅된 이벤트는 **그 이벤트 단독**으로 영향을 받으며 컨슈머 그룹은 다음 메시지로 진행한다 (poison message가 컨슈머 그룹을 막지 않는다).
- [ ] DLQ 진입 시 WARN/ERROR 구조화 로그가 발생하고 (선택) 메트릭 카운터가 증가한다 — 운영자가 후속 조치를 trigger할 수 있도록.
- [ ] DLQ에 재처리 도구(operator CLI 혹은 manual re-publish 절차)가 존재하는지는 ADR-13에서 결정.

#### Story 3 — Body fetch는 docs-api의 sanctioned route만 사용
> As an operator, I want body fetch to go through the **sanctioned `/internal/**` route on docs-api** so that ADR-08's schema-isolation invariant is preserved.

- [ ] 본문은 `GET http://docs-api:18082/internal/docs/public/{id}/body`로만 fetch한다 (per ADR-08 §"Exception 1", ADR-12 §2). 어떤 형태의 cross-schema DB SELECT도 발생하지 않는다.
- [ ] 호출은 read-only (`GET` only)이며 `X-User-*` 헤더는 forward되지 않는다 — service-to-service 트래픽.
- [ ] WebClient timeout과 retry 정책은 ADR-08 §"Exception 1" + ADR-12 §2의 가이드라인(5s timeout, 3 retry, exponential backoff + jitter, 영구 실패 → DLQ)을 따른다. 정확한 값은 ADR-13에서 핀.
- [ ] 메타데이터가 추가로 필요할 때 (e.g. 이벤트가 stale, 현재 visibility 재확인) `GET /internal/docs/public/{id}` 보조 route를 사용한다.

#### Story 4 — Body size cap 준수
> As an operator, I want the body-fetch caller to **respect M2's 1 MB body cap** so that an oversized body doesn't cause infinite retry or memory pressure.

- [ ] 본문이 M2의 1 MB cap(per ADR-12 §4)을 초과하는 응답은 즉시 영구 실패로 분류되어 DLQ로 보내진다 (retry 무한 loop 금지).
- [ ] 본문 fetch 응답이 413 / 400 / 404를 반환하면 retry하지 않고 DLQ로 라우팅한다 (transient 5xx만 retry 대상).
- [ ] 메모리 안전: 본문은 streaming 또는 max-bytes 가드 하에 읽어 OOM 위험을 피한다 (ADR-13 결정 사항).

#### Story 5 — Observability
> As an operator, I want every ingestion run to emit **structured logs** I can correlate with the `documentId` + `bodyChecksum`.

- [ ] 각 상태 전이(이벤트 수신, lock 획득, body fetched, embeddings requested, embeddings received, chunks written, lock released, DLQ enqueued)는 INFO/WARN/ERROR 수준의 구조화 로그로 기록되며 `documentId`, `userId`, `bodyChecksum`, `chunkCount`, `eventType`을 포함한다.
- [ ] 임베딩 게이트웨이 latency, retry 횟수, DLQ 카운트는 **메트릭 surface** (Spring Actuator + Micrometer)로 노출된다 — 구체적 메트릭 이름은 ADR-13.
- [ ] M5 (Metrics) 대시보드는 M3 메트릭을 곧바로 폴링할 수 있다 (메트릭 노출만 책임; 대시보드 위젯은 M5의 일).

### End user (indirect)

#### Story 6 — New doc becomes searchable shortly after upload
> As an end user, I want my newly-uploaded document to be **queryable in chat within a bounded latency** of upload, so that the loop feels responsive.

- [ ] 업로드 → chunk가 pgvector에 가시화되기까지의 P95 latency 목표는 ADR-13에서 핀 (working default: **≤ 30초** for typical bodies up to ~100 KB).
- [ ] M4 retrieval이 이 latency 이전에 호출되면 결과 없음(또는 부분 결과)이 정상이며, 사용자에게는 "곧 검색 가능합니다" 류 안내가 가능한지는 M4의 일.
- [ ] Latency가 cap을 넘으면 WARN 로그를 emit한다 (per spec §10의 search projection lag tolerance 패턴과 동일한 discipline).

#### Story 7 — Visibility flip is reflected without re-ingest
> As an authenticated author, I want toggling my doc public → private (or vice versa) to **stop / start that doc appearing in anonymous chat results** within a bounded latency, **without re-embedding the body**.

- [ ] `docs.document.visibility-changed` 도착 → 해당 `document_id`의 모든 `rag.document_chunks` 행의 `visibility` 컬럼만 UPDATE된다. 본문 fetch / 임베딩 호출은 **발생하지 않는다**.
- [ ] 재태깅 P95 latency 목표는 ADR-13에서 핀 (working default: **≤ 5초**).
- [ ] private → public flip 후 익명 chat 쿼리(`WHERE visibility = 'public'`)에서 즉시 해당 문서가 검색 corpus에 포함된다.
- [ ] public → private flip 후 익명 chat 쿼리에서 해당 문서의 chunk가 더 이상 hit되지 않는다 — 통합 테스트가 명시적으로 단언.

#### Story 8 — Deletion purges chunks
> As an authenticated author, I want deleting my document to **remove all chunks** from the index so it can never be retrieved again.

- [ ] `docs.document.deleted` 도착 → 해당 `document_id`의 모든 `rag.document_chunks` 행이 purge된다.
- [ ] purge 후 어떤 retrieval 쿼리에서도 해당 문서의 chunk가 hit되지 않는다 — 통합 테스트 필수.
- [ ] M4 chat history에 인용된 chunk가 사후 삭제되어도 M4의 응답 생성에는 영향이 없다 (M3는 purge 책임만; M4의 stale citation 처리 여부는 M4의 일).

### Downstream M4 consumer

#### Story 9 — Retrieval works with a single SQL filter
> As M4, I want to read chunks filtered by `(user_id, visibility)` directly from pgvector **with no M3-specific knowledge or API call**, so that retrieval is a single SQL query.

- [ ] M4의 retrieval 쿼리는 다음 두 형태 중 하나의 `WHERE` 절을 사용해 corpus를 분리할 수 있다:
  - 익명 (`X-User-Id` 없음): `WHERE visibility = 'public'`
  - 인증 (`X-User-Id = ?`): `WHERE visibility = 'public' OR (user_id = ? AND visibility = 'private')`
- [ ] 두 절은 cosine similarity ORDER BY + LIMIT K 와 결합해 단일 SQL로 표현 가능하다. M3는 어떤 RPC / 별도 API도 노출하지 않는다.
- [ ] 모든 chunk 행은 항상 `(user_id, visibility)` 두 컬럼을 **non-null**로 가진다 — partial-write 상태는 invariant violation으로 분류된다.

#### Story 10 — Ingestion-complete signal for M4 readiness checks
> As M4, I want to know "this document is queryable now" so that I can render an "indexing…" hint on a just-uploaded doc until ingestion completes.

- [ ] 인제스트 완료 시그널은 다음 셋 중 하나로 ADR-13에서 결정된다:
  - (a) **새 Kafka 이벤트** `rag.document.ingested` (envelope ADR-03, payload `{documentId, userId, chunkCount, embeddedAt, bodyChecksum}`) — clean한 옵션 (운영자의 working preference).
  - (b) **DB flag**: `rag.document_chunks`에 추가 컬럼(예: `ingestion_complete_at`) 또는 별도 sentinel row.
  - (c) **신호 없음**: M4는 pgvector를 단순 SELECT하여 "chunk 0개" → "아직 준비 안 됨"으로 해석.
- [ ] M3 P0에서 어떤 옵션이 채택되든, M4가 "ready" 상태를 판별할 수 있어야 한다. ADR-13이 정한 mechanism은 M4의 PRD/ADR도 그대로 받는다.

## UX surfaces

**없다.** M3는 BC-internal Kafka consumer + Redis-locked worker + pgvector writer로만 구성된다. 게이트웨이의 라우트 테이블에 M3 항목이 추가되지 않는다 (per ADR-07 + ADR-09). 프론트엔드 변경 없음.

## Bounded Context: RAG-Ingestion

- **책임 (Responsibility):** docs BC에서 발행한 세 종류의 문서 이벤트를 소비해 **검색 가능한 vector chunk corpus**를 pgvector에 유지한다. 사용자 인증, 게이트웨이 라우팅, 외부 HTTP 표면 없음.
- **핵심 엔티티 (Core entity):** `DocumentChunk` (per spec §M3 / 이 PRD §"Acceptance criteria")
  - `documentId` (UUID, FK는 아니지만 `docs.documents.id`를 참조하는 logical key)
  - `chunkIndex` (int, 0-based, 문서 내 순서)
  - `userId` (UUID, `docs.documents.user_id` 복사본)
  - `visibility` (`'public' | 'private'`, `docs.documents.visibility` 복사본)
  - `embedding` (`vector(N)`, N = BGE-M3 차원 — 정확한 값 ADR-13)
  - `text` (string, 청크의 raw 텍스트)
  - `bodyChecksum` (string, 이 청크가 생성된 시점의 본문 SHA-256, 선택적 추가 컬럼 — ADR-13 결정 사항)
  - `createdAt` (timestamp)
  - PK: `(documentId, chunkIndex)`
- **소비 이벤트 (Consumed events) — M2가 발행자, M3가 컨슈머:**
  - `docs.document.uploaded` — 생성 또는 본문 편집. 본문 fetch + 청크 + 임베딩 → upsert chunks.
  - `docs.document.visibility-changed` — publish/unpublish. 해당 `documentId`의 모든 chunk의 `visibility` UPDATE only (본문 재페치 없음).
  - `docs.document.deleted` — hard delete. 해당 `documentId`의 모든 chunk DELETE.
  - (참고) `docs.document.moved` — M2.1에서 추가될 예정 (path 이동). M3 P0에서는 chunk가 `path`를 carry하지 않으므로 consume 대상 아님. M2.1 이후 path 메타데이터를 carry할지는 ADR-13에서 결정.
- **발행 이벤트 (Published events) — 선택적, ADR-13 결정:**
  - (옵션 a) `rag.document.ingested` — 인제스트 완료 시그널. payload `{documentId, userId, chunkCount, embeddedAt, bodyChecksum}`, key = `documentId`. envelope은 ADR-03.
- **외부 인터페이스 (Public HTTP):** **없다.** 게이트웨이는 M3로 라우팅하지 않는다.
- **내부 인터페이스 (compose-internal):**
  - **outbound HTTP:** `docs-api:18082/internal/docs/public/{id}/body` (per ADR-08 §"Exception 1") + `docs-api:18082/internal/docs/public/{id}` (보조 메타데이터).
  - **outbound HTTP:** `spark-inference-gateway` at `host.docker.internal:10080` (BGE-M3 embedding). Spring AI 또는 native WebClient, ADR-04 + ADR-13.
  - **Redis:** `redis-playground` (namespace `rag-ingestion:lock:*`, per ADR-08 §"Exception 2"). Redisson `RLock`.
  - **Postgres (own schema `rag`):** `rag.document_chunks` 테이블. pgvector 확장 사용. 다른 BC schema 접근 금지 (ADR-05).
  - **Kafka:** consume `docs.document.*`; (선택) produce `rag.document.ingested` + DLQ topic.

## Non-functional requirements

- **Idempotency invariant:** 동일 `(documentId, bodyChecksum)`의 `uploaded` 이벤트가 임의 횟수 도착해도 `rag.document_chunks`의 행 집합은 동일하다. Redisson lock + checksum-aware upsert가 결합되어 보장.
- **Schema-per-BC invariant (ADR-05):** M3는 자신의 schema `rag`에만 write한다. `docs.documents.body`는 HTTP `/internal/**` route를 통해서만 read한다. cross-schema DB SELECT 금지.
- **Sanctioned BC-to-BC HTTP (ADR-08 §"Exception 1"):** body fetch 호출은 read-only, no `X-User-*` headers, 5s timeout, 3 retries with backoff, 영구 실패 시 DLQ. 다른 어떤 docs-api endpoint도 호출하지 않는다.
- **Sanctioned Redis usage (ADR-08 §"Exception 2"):** lock key namespace `rag-ingestion:lock:document:{id}`, TTL ≤ 5분 cap. 다른 BC의 Redis namespace에 read/write 금지.
- **Embedding gateway 의존성 isolation:** `spark-inference-gateway` 5xx / timeout이 컨슈머 그룹 전체를 stall시키지 않는다. 영구 실패는 단일 이벤트 단위로 DLQ로 격리된다.
- **Visibility tagging correctness (ADR-09 invariant):** 모든 chunk 행은 항상 부모 문서의 현재 `visibility`와 동기 상태로 유지된다. `visibility-changed` 이벤트 도착 P95 ≤ 5초(working default) 내에 모든 chunk가 재태깅된다. 통합 테스트 필수.
- **Re-tag is body-fetch-free:** `visibility-changed` 처리는 본문 fetch / 임베딩 호출을 **유발하지 않는다** — UPDATE-only. 본문 fetch가 발생하면 invariant violation.
- **Deletion completeness (ADR-09 invariant):** `deleted` 이벤트 도착 후 해당 `documentId`의 chunk는 단 하나도 남지 않는다. 통합 테스트 필수.
- **M4 retrieval contract:** 단일 SELECT (cosine ORDER BY + LIMIT K) + `WHERE visibility = 'public' [OR (user_id = ? AND visibility = 'private')]`로 M4가 검색할 수 있도록 `(user_id, visibility)` 컬럼이 chunk 행마다 정확하게 carry된다.
- **Observability:** 모든 상태 전이 → INFO 구조화 로그(`documentId`, `userId`, `eventType`, `bodyChecksum`, `chunkCount`). 임베딩 latency / retry / DLQ 카운트 → Micrometer 메트릭.
- **DLQ isolation:** poison event는 컨슈머 그룹의 다음 메시지 처리를 막지 않는다 — Spring Kafka error handler로 격리.
- **Cosine index:** `rag.document_chunks.embedding`에 cosine similarity-suitable index (예: pgvector의 `ivfflat` 또는 `hnsw`, 인덱스 파라미터는 ADR-13).

## Acceptance criteria (end-to-end)

roadmap.md §M3의 5 bullet을 그대로 미러링한 뒤 추가 invariant를 명시한다.

### Roadmap.md §M3의 5 acceptance bullet (verbatim mirror)
- [ ] `rag-ingestion` 서비스는 Kafka로부터 `docs.document.uploaded`를 소비하며 idempotent하게 ack한다 (재전송이 중복 insert를 유발하지 않음).
- [ ] 문서는 임베딩 전에 청크로 분할된다 (size + overlap configurable; 구체적 값 ADR-13).
- [ ] 각 청크는 `spark-inference-gateway`(BGE-M3 at `host.docker.internal:10080`)로 임베딩된다.
- [ ] 청크 + vector는 pgvector에 `(document_id, chunk_index, user_id, embedding, text)` schema로 저장되며 cosine similarity-suitable vector index가 존재한다.
- [ ] M4가 어떤 문서가 queryable한지 알 수 있도록 ingestion-complete 신호(이벤트 또는 DB flag)가 emit된다 — 구체적 mechanism ADR-13.

### Schema (M3 PRD pins minimum content)
- [ ] `rag.document_chunks`의 컬럼은 최소한 다음을 포함한다: `document_id`, `chunk_index`, `user_id`, `visibility`, `embedding`, `text`, `created_at`. PK는 `(document_id, chunk_index)`.
- [ ] `visibility` 컬럼이 추가됨에 따라 roadmap.md §M3의 schema bullet은 **`(document_id, chunk_index, user_id, visibility, embedding, text)`**로 해석된다 (ADR-09 §"Public retrieval scoping"의 invariant를 M3 stage에서 충족하기 위한 필수 컬럼).
- [ ] cosine similarity index가 `embedding` 컬럼에 생성되어 있다 — top-K retrieval에 적합한 파라미터로(ivfflat lists / hnsw m, ef_construction 등은 ADR-13).

### Event handling
- [ ] `docs.document.uploaded` 도착 시: body fetch → chunk → embed → upsert chunks(+ optional ingestion-complete signal).
- [ ] `docs.document.visibility-changed` 도착 시: 해당 `documentId`의 모든 chunk에 대해 `visibility` 컬럼 UPDATE only. **body fetch / embedding 호출 없음.**
- [ ] `docs.document.deleted` 도착 시: 해당 `documentId`의 모든 chunk DELETE.
- [ ] 세 이벤트 모두 shared-kernel envelope(ADR-03)을 가정하고 파싱한다. payload는 M2 spec §5의 정의를 따른다.

### Idempotency + locking
- [ ] 동일 `(documentId, bodyChecksum)`의 `uploaded`가 N회 도착해도 chunk 행 집합은 변하지 않는다. 통합 테스트 필수.
- [ ] 동시에 같은 `documentId`에 대해 두 처리가 진행되지 않는다 — Redisson lock(`rag-ingestion:lock:document:{id}`)로 직렬화. TTL ≤ 5분.
- [ ] body가 실제로 변경된 경우(`bodyChecksum` 변화) 기존 chunk는 atomic하게 교체된다 — partial 상태가 외부에 가시화되지 않는다.

### Body-fetch HTTP 컨트랙트 (ADR-08 §"Exception 1" + ADR-12 §2)
- [ ] body fetch는 `GET http://docs-api:18082/internal/docs/public/{id}/body`로만 호출된다.
- [ ] `X-User-*` 헤더는 forward되지 않는다.
- [ ] WebClient timeout = 5s, retry = up to 3 with exponential backoff + jitter (정확한 값 ADR-13). 영구 실패 시 DLQ.
- [ ] 응답이 4xx (특히 404 → 문서가 이미 삭제됨, 413 → body too large)이면 **retry하지 않고** DLQ로 라우팅 (또는 4xx 종류별로 별도 처리; ADR-13).
- [ ] body가 1 MB cap(ADR-12 §4)을 초과하면 단발성으로 DLQ; 무한 retry 금지.

### Embedding gateway 컨트랙트 (ADR-04)
- [ ] 임베딩 호출은 `spark-inference-gateway`의 BGE-M3 endpoint를 사용한다. compose 측 `host.docker.internal:10080` (per roadmap.md §M3).
- [ ] timeout / retry / backoff 정책은 ADR-13에서 명시. 모든 retry 소진 후 5xx → DLQ.
- [ ] 임베딩 차원은 BGE-M3의 모델 사양을 따른다 (정확한 정수 값 ADR-13).

### DLQ + observability
- [ ] poison event(영구 실패한 `uploaded`)는 `docs.document.uploaded.dlq` 토픽으로 라우팅된다 (정확한 토픽명 ADR-13 / ADR-03 컨벤션).
- [ ] DLQ 진입 시 WARN/ERROR 로그 + 메트릭 카운터 증가.
- [ ] 모든 상태 전이가 INFO 구조화 로그(`documentId`, `userId`, `eventType`, `chunkCount`, `bodyChecksum`)로 기록된다.

### Visibility re-tagging
- [ ] `visibility-changed` 도착 P95 ≤ 5초(working default; 정확한 SLO ADR-13) 내에 모든 chunk의 `visibility`가 새 값으로 UPDATE된다.
- [ ] private → public flip 후 익명 retrieval 쿼리(`WHERE visibility='public'`)에 해당 문서의 chunk가 나타난다. 통합 테스트 필수.
- [ ] public → private flip 후 익명 retrieval 쿼리에서 해당 문서의 chunk가 사라진다. 통합 테스트 필수.
- [ ] 재태깅 처리는 본문 fetch / 임베딩 호출을 유발하지 않는다 — 통합 테스트에서 docs-api `/internal/**` 호출 횟수와 spark-inference-gateway 호출 횟수가 0임을 단언.

### Deletion
- [ ] `deleted` 도착 후 해당 `documentId`의 chunk가 모두 사라진다 (count = 0). 통합 테스트 필수.
- [ ] 삭제 후 retrieval 쿼리에서 해당 문서의 chunk가 hit되지 않는다.

### M4 retrieval contract (forward invariant — M3 enables, M4 consumes)
- [ ] M4가 다음 단일 SQL 형태로 corpus를 분리할 수 있다 (M3가 만들어주는 invariant):
  - 인증: `SELECT ... FROM rag.document_chunks WHERE visibility = 'public' OR (user_id = :uid AND visibility = 'private') ORDER BY embedding <=> :q LIMIT :k`
- [ ] M3 측에서 retrieval API / RPC를 노출하지 않는다 — M4가 pgvector를 직접 SELECT한다 (per ADR-05의 "Postgres는 source of truth"; M4 BC는 자신의 retrieval port를 통해 호출하되 쿼리 자체는 pgvector 단일 SELECT).
- [ ] 모든 chunk 행이 `(user_id, visibility)`를 non-null로 가짐 — 통합 테스트로 invariant 단언.

> **Anon row dropped (2026-05-18, ADR-14 amendment):** earlier draft listed an anonymous corpus case (`WHERE visibility = 'public'` only). M4 is auth-only per the ADR-09 amendment in ADR-14; the anonymous SQL form is no longer a target. M3's chunk schema invariant `(user_id, visibility)` non-null is unchanged — M3's write side is unaffected by the M4 surface narrowing.

### Cross-milestone (traceability — non-blocking for M3 close)
- [ ] Manual E2E: M2에서 비공개 문서 업로드 → M3가 chunk를 pgvector에 기록 → M4(미래)의 인증 chat에서 그 문서가 인용된 답변. M3 close blocker 아님.
- [ ] Manual E2E: M2에서 공개 문서 업로드 → M3 chunk가 `visibility='public'`으로 저장됨 → M4(미래)의 인증 chat에서 다른 사용자가 그 공개 문서를 인용해서 답변 받음. M3 close blocker 아님. (이전 draft의 "익명 chat" 표현은 ADR-14 amendment에서 auth-only로 narrowing되었음.)

## Out of scope

### M3 P0에서 명시적으로 제외
- **공개 chat UI / endpoint surface** — M4의 일. M3는 게이트웨이 라우팅을 보유하지 않는다.
- **Chunking 파라미터 변경에 따른 재임베딩** — operator가 one-shot rebuild를 실행. M3 P0에서 자동 재임베딩 없음 (explicit non-goal).
- **문서 간 중복 제거 / fingerprinting** — 동일/유사 본문이 여러 문서에 있어도 별개 chunk로 저장. dedup은 M3 P0 미지원.
- **다중 모달 임베딩** — M3는 text-only (BGE-M3). 이미지/PDF 본문 임베딩은 M2.1 + M3 follow-up에서 결정.
- **이미지 추출 / OCR** — 본문에 외부 URL 이미지가 있어도 텍스트로만 임베딩 (alt text 등). OCR은 M2.1 + M3 follow-up.
- **Path / folder 메타데이터 carry** — M3 P0의 chunk 컬럼에 `path`는 포함되지 않는다. M2.1의 `docs.document.moved` 이벤트가 추가되면 path를 carry할지 ADR-13에서 결정.
- **Backfill of pre-M3 documents** — M3 deploy 이전에 M2가 생성한 문서가 있다면, 자동 backfill하지 않는다 (forward-only consumption). backfill은 별도 one-shot operator CLI로 구현 (M3 P0 미포함; ADR-13에서 backfill mechanism 정의).
- **Retrieval API / RPC** — M4가 pgvector를 직접 SELECT. M3 측 retrieval API 없음.
- **Cross-user retrieval** — chunk의 `user_id`가 carry되며 M4가 retrieval 시 필터링; M3 자체는 cross-user 격리 enforcement를 하지 않는다 (downstream의 일).

### M3.1 (same milestone bucket, cycle slack 있으면 ship)
- Path metadata가 chunk에 추가됨 (M2.1의 `docs.document.moved` 컨슈머와 동반).
- One-shot operator CLI: 누락된 문서를 강제 backfill (예: `./rag-ingestion-cli backfill --user=...` 또는 `--all`).
- Re-embedding job: 청킹 파라미터 또는 임베딩 모델이 바뀌었을 때 전체 corpus 재임베딩.

### P2 (별도 후속 마일스톤 / 다음 사이클)
- Multi-modal embedding (이미지, PDF 본문).
- 문서 간 dedup / fingerprint based clustering.
- 다국어 별도 임베딩 모델 (현재 BGE-M3가 multilingual을 cover).
- Adaptive chunk size (semantic chunking, layout-aware).

## Dependencies

- **요구:** M0 (Bootstrap) — compose stack(Postgres + pgvector + Kafka + Redis), `host.docker.internal:10080`이 `spark-inference-gateway`로 라우팅되는 `extra_hosts` 설정 (roadmap.md §M0).
- **요구:** M2 (Docs) — 세 Kafka 이벤트의 컨트랙트(`docs.document.uploaded`, `docs.document.visibility-changed`, `docs.document.deleted`)와 outbox 일관성(per ADR-12 §1), 그리고 `GET /internal/docs/public/{id}/body` + `GET /internal/docs/public/{id}` 두 internal route(per ADR-12 §2). 1 MB body cap(per ADR-12 §4).
- **요구:** `spark-inference-gateway` (외부) — BGE-M3 임베딩 endpoint at `host.docker.internal:10080`. Spring AI 통합(ADR-04). 모델 사양(차원, max input token 등)이 ADR-13의 입력.
- **요구:** ADR-13 (M3 per-milestone) — 라이브러리/버전 핀, chunk 파라미터, retry 곡선, DLQ 토픽명, ingestion-complete 시그널 mechanism, embedding 차원, pgvector index 종류 + 파라미터, 모듈 quadruplet 구조, latency SLO.
- **소비자(M3 close blocker 아님):** M4 (RAG-Chat) — pgvector를 직접 SELECT하여 retrieval. M3의 `(user_id, visibility)` invariant에 의존.
- **소비자(M3 close blocker 아님):** M5 (Metrics) — M3의 메트릭 surface를 폴링하여 대시보드에 표시.
- **Frontend:** 없음 (M3는 UI를 가지지 않는다).

## Open questions for the implementer

ADR-13(architect) + Stage-3 implementer가 해소할 사항. PRD 리뷰어가 한 곳에서 보도록 여기 모아둔다.

1. **Chunk size + overlap** — default 제안값 + 허용 범위. 일반적 출발점은 512–1024 token chunks + 64–128 token overlap이지만, BGE-M3의 max input + Korean 문서의 평균 길이를 고려해 ADR-13에서 핀.
2. **Embedding retry 정책** — max attempts, backoff 곡선(initial delay, multiplier, jitter, max delay), retry 분류(어떤 4xx는 retry하지 않는가, 어떤 5xx만 retry하는가), retry 소진 → DLQ vs 알림 일원화 여부.
3. **Ingestion-complete signal mechanism** — (a) 새 Kafka 이벤트 `rag.document.ingested` (운영자 선호; 이벤트로 일관성 유지), (b) DB flag (sentinel row 또는 별도 컬럼 `ingestion_complete_at`), (c) 신호 없음(M4가 chunk 0개를 "not ready"로 해석). **운영자 working preference: (a) 이벤트** but ADR-13이 정식 결정.
4. **모듈 quadruplet 구조 (ADR-01 v2)** — `rag-ingestion-api` / `-app` / `-domain` / `-infra`. API 모듈은 **public HTTP surface가 없다** — 게이트웨이 라우팅 대상 아님. `-api` 모듈이 (a) Kafka consumer entry point + Redisson `@GlobalLock` hook만 host하는 형태인지, 또는 (b) `-api` 자체를 없애고 `-infra`가 runnable이 되는 형태인지는 ADR-13에서 결정. 기본 working direction: ADR-01 v2 quadruplet을 유지하되 `-api`가 HTTP가 아닌 "외부 진입점"(Kafka listener)을 host한다.
5. **Race condition: `visibility-changed` arriving before `uploaded` finishes** — 작성자가 업로드 직후 즉시 publish/unpublish하면 `uploaded`가 처리 중인 동안 `visibility-changed`가 도착할 수 있다. 처리 옵션:
   - (a) `visibility-changed`를 buffer/wait — `uploaded`의 lock이 풀릴 때까지 대기, 풀리면 처리.
   - (b) `visibility-changed`가 빨리 처리되어 chunk가 아직 없으면 no-op, `uploaded`가 끝나면 그때 `docs.documents`에서 현재 visibility를 다시 fetch해 정합 (보조 메타데이터 endpoint 사용).
   - (c) drop — eventually consistent 가정 하에 무시. (M4 retrieval lag 동안 false positive 가능성).
   ADR-13에서 결정.
6. **Latency SLO** — 업로드 → queryable의 P95 (working default ≤ 30초), visibility flip → 재태깅 완료 P95 (working default ≤ 5초)의 구체적 수치. ADR-13에서 핀.
7. **Backfill story** — M3 deploy 이전에 M2가 생성한 문서를 어떻게 처리할지. M3 P0 default는 **forward-only** (deploy 후 신규 이벤트만 처리). backfill은 one-shot operator CLI로 별도 (구체적 형태 — Kafka replay vs DB scan + manual replay — ADR-13).
8. **DLQ 토픽명 + 재처리 도구** — `docs.document.uploaded.dlq` (ADR-03 컨벤션) 사용 여부, 다른 두 이벤트의 DLQ는 어떻게 명명되는지, DLQ에 쌓인 메시지를 다시 main 토픽으로 흘려보내는 operator CLI 혹은 절차의 존재 여부.
9. **pgvector index 종류 + 파라미터** — `ivfflat` (lists 파라미터, list 크기 권장) vs `hnsw` (m, ef_construction, ef_search). 코퍼스 규모 가정과 build/query cost trade-off 기반으로 ADR-13.
10. **임베딩 차원 + 모델 정확한 이름** — BGE-M3의 default 1024-dim dense embedding 가정이 working direction이지만 (multi-vector / colbert 변형 미사용), `spark-inference-gateway`가 노출하는 endpoint의 정확한 사양에 따라 ADR-13에서 핀.
11. **본문 streaming** — 1 MB body cap이 있지만 본문 fetch 응답을 buffered read vs streaming read 어느 쪽으로 처리할지. 메모리 안전성 + chunking 알고리즘이 streaming-friendly한지에 따라 결정.
12. **`bodyChecksum` 컬럼을 chunk 행에 carry할지** — checksum-aware idempotency를 chunk 행에서 직접 enforce할지 (PK 강화), 또는 별도 `rag.document_ingestion_state` 테이블에 보관할지. 운영 단순성 vs 컬럼 추가의 trade-off.
13. **Test fixture / contract test 전략** — docs-api `/internal/**` route와 spark-inference-gateway 둘 다 외부 의존성. 통합 테스트 시 mocking 전략 (WireMock vs Testcontainers + stub server), 그리고 M2 PR과의 contract test 어떻게 운영할지.

---

> **PRD vs ADR:** 이 문서는 사용자(operator + downstream) / 리뷰어가 읽는 표면이다. 정확한 라이브러리 좌표, 차원, lists/ef 파라미터, retry 곡선의 ms 단위 값, DLQ 토픽의 정확한 이름, 모듈 wiring 같은 기술 컨트랙트는 ADR-13(architect의 per-milestone ADR)이 우선한다. PRD가 ADR과 어긋나 보이면 ADR을 따른다.
>
> **PRD vs M4 forward contract:** M4의 retrieval endpoint는 단일 `/api/rag/chat`으로 amend되며, anonymous는 `WHERE visibility='public'`, authenticated는 `WHERE visibility='public' OR (user_id=? AND visibility='private')`을 사용한다. M3는 이 컨트랙트가 SQL 단일 절로 표현되도록 chunk row의 `(user_id, visibility)` invariant를 유지하는 것이 본질적 책임이다. roadmap.md §M4 + M4 spec amendment는 별도 작업.
