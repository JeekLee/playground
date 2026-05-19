# PRD: M5 — Metrics

> **Source of truth:** `docs/superpowers/specs/2026-05-19-m5-metrics-design.md` (v1, 2026-05-19). 이 PRD는 사용자/리뷰어가 읽는 표면이고, 정확한 PromQL 템플릿, Alloy River config, retention 환경 변수 이름, Recharts vs Visx 라이브러리 선택, 컨테이너 이미지 버전 핀 같은 기술 컨트랙트는 spec(그리고 후속 ADR-15)이 우선한다. Section references like "spec §7.2"는 그 spec 안의 위치를 가리킨다.
>
> **Parallel work:** 이 PRD와 함께 묶이는 per-milestone ADR(provisionally **ADR-15** at `docs/adr/15-m5-metrics.md`)이 architect의 별도 세션에서 작성된다 — 4개 observability 컨테이너의 이미지/버전 핀, 포트 핀(`metrics-api` 18085 후보), 모듈 quadruplet wiring, PromQL/LogQL 전체 매핑 테이블, 차트 라이브러리 선택, 그리고 spec §13의 4개 cross-doc amendment(roadmap.md §M5 / ADR-09 / ADR-00 / `docs/infra-requirements/be.md`)는 모두 ADR-15가 책임진다. Stage-2 design(`docs/design/M5-metrics.md`) + Figma 프레임도 별도 in flight.
>
> **Important supersession note:** roadmap.md §M5의 현재 acceptance 4번 항목("Dashboard is only reachable behind login")과 5번 항목("No new external dependency is introduced — polling-only, no Prometheus/Grafana")은 spec §2 + §13에 의해 **공식 폐기된다**. 본 PRD는 spec을 canonical로 취급한다 — `/metrics`는 public이고, Prometheus + Loki + Alloy + cAdvisor 4개 컨테이너를 새 외부 의존성으로 도입한다. roadmap.md §M5의 acceptance 블록은 ADR-15 PR이 일괄 재amend한다.

## 한 줄 설명

playground 운영자(와 익명 방문자)가 5초 cadence로 스크랩된 stack health, container resource, host CPU/mem/disk, JVM heap, HTTP request rate, `spark-inference-gateway` 상태를 15초 폴링으로 보는 **public read-only single-page dashboard**를 출시한다 — Prometheus + Loki + Alloy + cAdvisor 4개 observability 컨테이너 위에 stateless `metrics` BC가 PromQL/LogQL을 wrap하는 형태로.

## Summary

M5는 playground의 **첫 번째 observability surface**다. M0–M4가 5개 backend BC(`gateway`, `identity-api`, `docs-api`, `rag-ingestion`, `rag-chat-api`)를 stack에 올려놓았고, 각 BC가 ADR-13/14에서 이미 `/actuator/prometheus`를 export하고 있으며, `spark-inference-gateway`는 host process로 `127.0.0.1:10080`에 살아 있다. M5는 이 분산된 metric surface를 한 페이지 dashboard로 묶어, 운영자가 "지금 stack이 살아 있는가, GPU 게이트웨이는 응답하는가, 어떤 컨테이너가 메모리를 먹고 있는가"를 한눈에 확인할 수 있게 한다.

표면은 단일 페이지(`/metrics`) + 4개 backend route + 4개 observability 컨테이너로 구성된다.

- **`/metrics` 페이지**는 익명도 접근 가능한 public 페이지로, 19개 위젯(service health grid × 1, host × 4, JVM heap × 4, HTTP rate × 3, spark × 2, container resource table × 1, 그리고 시간순 차트들)을 단일 viewport(데스크톱 ≥720 px) 안에 렌더한다. Range는 15m / 1h / 6h / 24h / 7d 5개 preset에서 선택되며 `?range=Xh` URL 파라미터로 공유 가능하다.
- **`metrics` BC**는 새 Spring Boot quadruplet(`metrics-{api,app,domain,infra}`)으로, Postgres schema도 Kafka 토픽도 가지지 않는 stateless request-response BC다 — Prometheus의 `/api/v1/query`와 Loki의 `/loki/api/v1/query_range`를 WebClient로 호출하고, PromQL/LogQL 템플릿 + 화이트리스트를 owner로 가진다.
- **Observability 컨테이너 4개**: `prometheus-playground`(TSDB + scrape engine, 7일 retention), `loki-playground`(log TSDB, 3일 retention), `alloy-playground`(Grafana Alloy — scrape orchestrator + log shipper + host metric exporter, Promtail + node_exporter를 한 agent로 대체), `cadvisor-playground`(per-container CPU/mem/IO). Alloy가 백엔드 BC를 5초 cadence로, host를 10초 cadence로 scrape한다.

비용 보호는 personal scale에 맞춰 가벼운 두 layer로 구성된다: (1) `/api/metrics/dashboard`에 IP당 30 req/min Redis token-bucket(M3/M4의 Redisson 패턴 재사용), (2) 19개 PromQL 쿼리에 10초 per-request 예산 — 초과한 widget만 `"degraded": true`로 partial response. `/api/metrics/logs`(P0 UI 없음, ad-hoc CLI/M5.1 logs UI용)는 인증된 호출자에게 user당 60 req/min.

지금 출시하는 이유: stack이 M4까지 늘어나면서 "어디서 OOM이 났나, 어떤 컨테이너가 restart했나, spark gateway P95가 튀었나" 같은 질문이 docker logs를 일일이 뒤지지 않으면 답하기 어려운 임계점에 도달했다. M5는 운영자의 일상 운영 비용을 한 자릿수 분 단위로 줄이는 것이 목적이다. P0는 데스크톱 + 영어/한국어 mix + alerts 없음(폴링-only)이며, 모바일 / 도메인 메트릭 위젯 / 로그 UI / 알림은 M5.1 또는 P2로 미룬다.

**Out of scope re-revision:** 직전 사이클 roadmap §M5의 두 가지 acceptance("behind login" + "no new external dependency")는 본 사이클에서 **공식 뒤집힌다** — `/metrics`는 public(ADR-09 §"Public retrieval scoping" 유지)이고, Prometheus + Loki + Alloy + cAdvisor 4개 컨테이너가 도입된다. 자세한 이유는 spec §2 + §13.

## User personas

M5의 표면은 dashboard 한 페이지가 전부이고 그것은 **public**이다. 다만 logs API(`/api/metrics/logs`)는 인증된 호출자에게만 노출된다 — P0에는 그 endpoint를 소비하는 UI가 없고, M5.1의 logs 탭과 ad-hoc CLI(curl) 호출자만 이를 사용한다.

| 페르소나 | 핵심 동기 | M5에서 가능한 것 |
|---|---|---|
| **Operator (= 프로젝트 오너 / dev, primary)** | stack의 건강 상태(컨테이너 / 호스트 / JVM / HTTP / spark gateway)를 한 페이지에서 5초 cadence로 보고, 장애 발생 시 어떤 컴포넌트가 빠졌는지 즉시 식별하고, 로그를 CLI로 tail하고 싶음 | `/metrics` 페이지를 사이드바 "System status" 또는 직접 URL로 진입; range preset 변경; `⟳`로 즉시 refresh; "Updated Ns ago" 카운터로 신선도 확인; ad-hoc `curl --cookie ... /api/metrics/logs?service=rag-chat&since=15m`로 로그 tail; 19개 위젯에 표시되는 service health / container resource / host / JVM / HTTP / spark 상태를 polling-only로 관측 |
| **Anonymous visitor (secondary)** | playground가 "live"한 personal project임을 확인하고 싶음 — workshop 신호로서 stack 상태가 공개되어 있다는 것 자체가 의도된 surface | `/metrics`에 로그인 없이 접근; 19개 dashboard 위젯 전체를 동일하게 관측; 단, `/api/metrics/logs`는 401(서버 로그가 PII나 사용자 컨텍스트를 노출할 가능성 때문) |

오너(JeekLee)는 M5에서 anonymous visitor와 dashboard 표면에서는 구분되지 않는다 — 둘 다 같은 페이지를 본다. 차이는 logs API 접근 권한뿐이다 (per spec §8.1, ADR-09 amendment). M5에는 "쿠키만 가진 일반 인증 사용자가 chat을 쓰는" 류의 페르소나가 없다 — 그것은 M4의 영역이다.

**No new authenticated-user UI surface in P0.** 인증된 호출자가 차별적으로 보는 UI는 없다 — 단지 logs API CLI에 대한 접근권만 있다. M5.1이 logs 탭을 추가하면 그때 인증된 페르소나가 dashboard 안에서 첫 번째 UI 차별을 보게 된다.

## User stories with acceptance criteria

User story는 spec §12의 5개 acceptance 서브섹션(Observability stack / `metrics` BC routes / Frontend / Cost protection / Cross-milestone traceability)을 페르소나 voice로 풀어낸 것이다.

### Observability stack

#### Story 1 — 4개 새 컨테이너가 깨끗하게 부트
> As the operator, I want all 4 observability containers to boot cleanly via `docker compose up` and respond on their internal health endpoints so that I can rely on the stack as base infra rather than fight container plumbing.

- [ ] `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground` 4개가 `infra/docker-compose.yml`에 추가되고 `docker compose up`이 error 없이 부트한다 (per spec §4.1).
- [ ] 4개 컨테이너 각각이 자체 `/metrics` 또는 health-equivalent endpoint(`/-/ready` / `/ready` / `/healthy` 등 — 정확한 path는 ADR-15 핀)에 200을 반환한다 (per spec §12 "Observability stack").
- [ ] 4개 컨테이너는 기존 `playground` compose network에 join하며 host port로 노출되지 **않는다** — 외부에서 직접 PromQL을 던지지 못한다 (per spec §4.4).
- [ ] (선택) 운영자가 ad-hoc CLI에서 PromQL을 던질 수 있도록 Prometheus :9090만 env-var 게이트로 host bind 가능 — 기본 off (per spec §4.4).

#### Story 2 — Alloy가 backend BC를 5초 cadence로 scrape
> As the operator, I want Alloy to scrape every backend BC's `/actuator/prometheus` endpoint at 5-second cadence so that the dashboard's range presets (down to 15m) have usable resolution.

- [ ] Alloy의 River config(파일 구조는 ADR-15 핀)에 5개 backend job(`gateway`, `identity-api`, `docs-api`, `rag-ingestion`, `rag-chat-api`)이 5초 scrape interval로 등록된다 (per spec §4.2).
- [ ] cAdvisor도 같은 5초 cadence로 scrape된다 (per spec §4.2 block 2).
- [ ] Host metrics(`prometheus.exporter.unix` Alloy component, node_exporter를 대체)는 10초 cadence로 scrape된다 (per spec §4.2 block 3, §10).
- [ ] Prometheus의 web UI(또는 `/api/v1/targets` API)가 5개 backend job + cAdvisor + host job을 모두 `UP`으로 보고한다 (per spec §12 "Observability stack" — "the 5 BC jobs as `UP`").

#### Story 3 — Loki가 모든 컨테이너의 docker logs를 수집
> As the operator, I want Loki to receive docker logs for every playground container so that M5.1's logs UI and ad-hoc CLI queries have a complete corpus, and so that I never lose a container's log to docker's default log rotation.

- [ ] Alloy의 `discovery.docker` + `loki.source.docker` 컴포넌트가 `/var/run/docker.sock`(read-only mount)을 통해 모든 playground 컨테이너의 stdout/stderr을 tail한다 (per spec §4.2 block 5).
- [ ] Loki는 `/loki/api/v1/push`로 들어온 entry를 받아 3일 retention 안에 저장한다 (per spec §4.3, §10).
- [ ] `loki.source.docker` component가 0건의 error를 보고한다 — boot 후 첫 5분 관측 (per spec §12 "Observability stack").
- [ ] 임의 backend BC에 대해 `{container="<svc>-playground"}` LogQL 쿼리가 최근 15분 분의 line을 반환한다 (per spec §6 LogQL 매핑).

#### Story 4 — Retention 환경 변수가 작동
> As the operator, I want Prometheus 7-day and Loki 3-day retention to be overridable via env vars so that I can adjust budgets on the host without rebuilding images.

- [ ] Prometheus는 `--storage.tsdb.retention.time=7d` 기본 + `METRICS_PROM_RETENTION_DAYS` env var로 override (per spec §4.3 — env var 이름은 ADR-15가 final 핀).
- [ ] Loki는 3일 기본 + `METRICS_LOKI_RETENTION_DAYS` env var로 override (per spec §4.3).
- [ ] 통합 테스트(또는 manual verification)가 두 env var override가 실제로 effective함을 확인한다 (per spec §12 "Observability stack" — "verified via env-var override test").
- [ ] 총 disk usage budget은 ~5 GB(Prometheus ~3 GB + Loki ~2 GB)로 설정 — `docs/infra-requirements/be.md`의 M5 section에 명시 (per spec §10, §13).

#### Story 5 — Docker socket 마운트가 read-only이고 명시적으로 문서화됨
> As the operator, I want the docker socket mounts (cAdvisor + Alloy) to be explicitly documented as a deliberate trade-off so that I'm not surprised later by the privilege escalation surface they introduce.

- [ ] `cadvisor-playground`와 `alloy-playground` 둘 다 `/var/run/docker.sock`을 **read-only**로만 마운트한다 (per spec §4.4, §8.3).
- [ ] cAdvisor와 Alloy 둘 다 host network에 노출되지 않는다 — compose-internal만 (per spec §8.3).
- [ ] `docs/infra-requirements/be.md`의 M5 section이 두 socket 마운트의 privilege 의미를 explicit trade-off로 기술한다 — personal-scale 정당화 + rootless docker alternative의 복잡성 언급 (per spec §8.3, §13).

### `metrics` BC routes

#### Story 6 — Dashboard payload 하나로 19개 위젯 데이터 묶음 반환
> As the operator, I want `GET /api/metrics/dashboard?range=1h` to return all 19 widgets' data in one JSON so that the frontend can render the page in one round-trip rather than 19.

- [ ] `GET /api/metrics/dashboard?range=15m|1h|6h|24h|7d`는 spec §5.2의 JSON shape(`fetchedAt`, `range`, `services[]`, `containers[]`, `host`, `sparkGateway`, `jvm[]`, `httpRate[]`)을 반환한다 (per spec §5.2, §12 "`metrics` BC routes").
- [ ] P95 latency ≤ 400 ms over 1 minute of polling (19개 PromQL 쿼리 parallel composition 포함) (per spec §10, §12 "`metrics` BC routes").
- [ ] Payload 크기 ≤ 8 KB (working assumption; chart 시계열은 별도 endpoint로 분리되어 있음) (per spec §5.2).
- [ ] 4개의 backend route 모두 게이트웨이를 통해 노출되며 `/api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries`는 public, `/api/metrics/logs`만 authenticated (per spec §5.1, §8.1).

#### Story 7 — Service health grid는 가벼운 별도 endpoint
> As the operator, I want `GET /api/metrics/services` to return just the service health grid so that the sidebar's "System status" row can refresh more aggressively without pulling the full dashboard payload.

- [ ] `GET /api/metrics/services`는 spec §5.2의 `services[]` 배열만 반환한다 — 6개 cell(`gateway`, `identity-api`, `docs-api`, `rag-ingestion`, `rag-chat-api`, `spark-inference`) (per spec §5.1).
- [ ] P95 latency ≤ 100 ms — dashboard보다 더 aggressively cache 가능(Prometheus `up{}` 쿼리만이라 cheap) (per spec §12 "`metrics` BC routes").
- [ ] Service "unhealthy" 판단 규칙은 ADR-15가 핀: Prometheus `up{} == 0`(scrape miss) vs Spring Actuator `/actuator/health != UP` vs 둘 다 (per spec §11 #9).

#### Story 8 — Timeseries endpoint는 차트 1개를 위한 시계열
> As the operator, I want `GET /api/metrics/timeseries?metric=<id>&range=<r>&step=<s>` to return a single chart's series so that the frontend can parallelize chart fetches and degrade per-chart on failure.

- [ ] `GET /api/metrics/timeseries?metric=jvm-heap-rag-chat-api&range=1h&step=30s` (또는 다른 화이트리스트된 metric id)는 spec §5.3의 shape(`metric`, `range`, `step`, `series[].label + points[][ts,val]`, `unit`)을 반환한다 (per spec §5.3).
- [ ] P95 latency ≤ 200 ms per single chart (per spec §10).
- [ ] `metric` 파라미터는 spec §6의 화이트리스트(jvm-heap-`<svc>`, jvm-gc-`<svc>`, http-rate-`<svc>`, http-error-`<svc>`, container-cpu-`<name>`, container-mem-`<name>`, host-cpu, host-mem, host-disk, spark-latency 등)에 대해서만 accept한다 (per spec §6).
- [ ] `<svc>` 와 `<name>` substitution은 service 허용목록을 거친 뒤 PromQL 템플릿에 binding된다 — application-layer concat이 아닌 parameterized substitution (per spec §6).

#### Story 9 — PromQL/LogQL injection 차단
> As the operator, I want PromQL injection attempts via `metric=` query params to be rejected with 400 so that no caller can craft an arbitrary `node_filesystem_free_bytes{instance=~".*"}` query through my public endpoint.

- [ ] 화이트리스트에 없는 `metric` 값은 400을 반환한다 — 5xx 아닌 명시적 client error (per spec §6, §12 "`metrics` BC routes").
- [ ] `service=` 파라미터에 대한 allowlist 검증도 동일하게 적용된다 — LogQL injection 차단 (per spec §6 LogQL 블록).
- [ ] 게이트웨이 통합 테스트가 임의의 PromQL injection 시도(예: `metric=jvm-heap-rag-chat-api"}|sum() OR ...`)에 대해 400을 단언한다.

#### Story 10 — Logs endpoint는 authenticated only
> As the operator, I want `/api/metrics/logs` to require authentication so that server logs (which may include user PII or error context) aren't exposed to anonymous visitors.

- [ ] `GET /api/metrics/logs?service=<id>&since=<duration>&search=<query>&limit=<n>`은 `X-User-Id` 헤더 부재 시 401을 반환한다 (per spec §5.1, §8.1).
- [ ] 같은 호출이 `X-User-Id` 헤더를 carry할 경우 spec §5.4의 shape(`entries[].ts/service/level/message`, `hasMore`, `nextCursor`)을 반환한다 (per spec §5.4).
- [ ] P95 latency ≤ 600 ms for a 200-line, 15-minute-range query (per spec §10).
- [ ] LogQL 쿼리는 `{container="<service>-playground"} |~ "<search>" | json` 템플릿을 따르고 line cap 200 per request (per spec §6, §9).
- [ ] ADR-09 §"Route classification"에 `GET /api/metrics/logs/**` 행이 authenticated section에 추가된다 — 기존 `GET /api/metrics/**` public 행은 dashboard/services/timeseries만 cover하도록 좁혀진다 (per spec §8.1, §13).

#### Story 11 — `metrics` BC는 Postgres / Kafka 둘 다 사용하지 않음
> As the architect / downstream maintainer, I want the `metrics` BC to be explicitly stateless so that no Postgres migration, no schema, no Kafka topic gets accidentally introduced under M5's name.

- [ ] `metrics` BC는 자체 Postgres schema를 가지지 않는다 — TSDB(Prometheus + Loki)가 유일한 source of truth (per spec §3).
- [ ] `metrics` BC는 Kafka 토픽을 publish하지 **않으며** consume하지도 **않는다** — request-response stateless BC (per spec §3).
- [ ] `metrics` BC의 4-module quadruplet wiring(`-api` WebFlux controller / `-app` PromQL+LogQL assembly + rate-limit guard / `-domain` value object + query template constants / `-infra` Prometheus + Loki WebClient adapter)은 ADR-01 v2를 따르며 정확한 entry point는 ADR-15가 핀 (per spec §11 #2).

### Frontend

#### Story 12 — `/metrics`는 익명에게도 접근 가능
> As any visitor, I want to see service health on `/metrics` without signing in so that I can verify the playground is actually live as a "workshop signal".

- [ ] `/metrics` 페이지는 익명 GET에 200을 반환하며 login redirect가 발생하지 **않는다** (per spec §12 "Frontend" — "no login redirect").
- [ ] 19개 위젯 전체가 익명에게도 동일하게 렌더된다 — dashboard payload에 user-scope 데이터가 들어가지 않는다 (per spec §5.2).
- [ ] 사이드바 "System status" 행이 M5 ship 후 `🔒 M5` 배지를 떨어뜨리고 unconditionally active 상태가 된다 — 익명도 signed-in도 동일하게 `/metrics`로 라우팅 (per spec §7.4).

#### Story 13 — Auto-refresh + "Updated Ns ago"
> As the operator, I want the dashboard to auto-refresh every 15 seconds with a visible "Updated Ns ago" indicator so that I always know the data freshness without staring at the clock.

- [ ] 15초 cadence로 `/api/metrics/dashboard`가 polled된다 — 명시적 pause/resume은 P0에는 없음 (per spec §7.2, §10).
- [ ] "Updated Ns ago" 카운터는 마지막 fetch에서부터 매초 increment된다 (per spec §7.2, §12 "Frontend").
- [ ] `⟳` manual refresh 버튼은 즉시 refetch를 trigger + 15초 timer를 리셋한다 (per spec §7.2).
- [ ] Tab이 focus를 잃을 때 polling은 브라우저 default 동작(throttling)에 맡긴다 — explicit pause 없음 (per spec §12 "Frontend").

#### Story 14 — Range preset 5개 + URL-shareable
> As the operator, I want to switch the time range between 15m / 1h / 6h / 24h / 7d and have the URL update so that I can share a link to "the last 24 hours" with someone else (or with my future self).

- [ ] 5개 preset pill(15m / 1h / 6h / 24h / 7d)이 페이지 상단에 렌더된다 — 현재 선택은 filled accent (per spec §7.2).
- [ ] Default는 `1h` — `?range` 쿼리 파라미터 없이 접근 시 `1h`가 선택됨 (per spec §7.2).
- [ ] Pill 클릭은 URL을 `?range=Xh`로 update + 4개 backend route 모두 refetch + 15초 timer 재시작 (per spec §7.2).
- [ ] 직접 URL `/metrics?range=24h`로 진입 시 24h preset이 선택된 상태로 첫 fetch가 일어난다 (per spec §12 "Frontend").
- [ ] 커스텀 date/time range picker는 P0에 없음 — M5.1 (per spec §2).

#### Story 15 — Per-widget error degrade
> As the operator, I want a single failed PromQL query to degrade only its widget and not the whole dashboard so that one stale metric doesn't blind me to the other 18.

- [ ] 단일 timeseries fetch가 5xx를 반환하면 해당 widget만 `⚠ Failed to refresh` overlay + 재시도 아이콘으로 degrade한다 — 나머지 위젯은 normal (per spec §7.3 "Stale (one widget)").
- [ ] `/api/metrics/dashboard`가 3번 연속 5xx 반환 시 상단 banner "Couldn't reach metrics service. Retrying in 30s."가 표시되고 polling이 일시 정지하며 manual `⟳`는 항상 활성 (per spec §7.3 "Stale (whole dashboard)").
- [ ] `metrics` BC가 10초 PromQL budget을 초과한 widget에 대해 `"degraded": true`로 partial response를 반환 — frontend는 해당 widget만 degrade 상태로 표시 (per spec §8.2, §12 "Cost protection").

#### Story 16 — 초기 로딩 시 skeleton
> As any visitor, I want skeleton placeholders to render for every widget on initial load so that the page doesn't look broken or empty before data arrives.

- [ ] 첫 fetch가 in-flight인 동안 19개 위젯 위치마다 skeleton placeholder가 렌더된다 (per spec §7.3 "Initial load", §12 "Frontend").
- [ ] 후속 refresh 동안에는 기존 데이터가 그대로 보이고 `⟳` 아이콘이 회전하며 "Updated Ns ago"가 멈춘다 — 깜빡임 없음 (per spec §7.3 "Subsequent refresh").

#### Story 17 — 데스크톱 only (P0)
> As any visitor on a tablet/phone, I should see the desktop dashboard degrade gracefully (single-column stack with sparklines only) rather than break, with full mobile layout deferred to M5.1.

- [ ] 뷰포트 ≥ 720 px에서 19개 위젯이 3–4 widget/row의 풀 레이아웃으로 렌더된다 (per spec §7.1).
- [ ] 뷰포트 < 720 px에서는 single-column stack + sparkline only로 degrade — 풀 차트는 숨김 (per spec §7.5).
- [ ] 풀 모바일 레이아웃은 P0에 없음 — M5.1 (per spec §2, §7.5).

#### Story 18 — Spark gateway 위젯이 host process 상태를 노출
> As the operator, I want to see the spark-inference-gateway's status (up/degraded/down), P95 latency, and loaded models in dedicated widgets so that I can correlate chat slowness with GPU host issues.

- [ ] Spark latency P95 line chart가 BGE-M3와 Qwen3-32B 2개 series를 표시한다 (per spec §2, §7.1).
- [ ] "Models loaded" card가 현재 로드된 모델 목록(`["BGE-M3", "Qwen3-32B"]`)을 표시 — dashboard payload의 `sparkGateway.modelsLoaded` 필드 (per spec §5.2).
- [ ] Spark gateway health probe 메커니즘(HEAD `/v1/models` vs scrape `/metrics` vs 둘 다)은 ADR-15가 핀 (per spec §11 #12).

### Cost protection

#### Story 19 — Dashboard endpoint에 IP당 rate limit
> As the operator, I want `/api/metrics/dashboard` rate-limited at 30 req/min/IP so that anon visitors (or a scraper) can't degrade the dashboard by hammering it from a single IP.

- [ ] Redis token-bucket(Redisson — M3/M4의 패턴 재사용)이 IP당 분당 30 request를 cap한다 (per spec §8.2, §12 "Cost protection").
- [ ] Bucket이 empty면 429 + `Retry-After` 헤더를 반환한다 (per spec §12 "Cost protection").
- [ ] `/api/metrics/services`와 `/api/metrics/timeseries`에는 추가 rate limit이 걸리지 **않는다** — 가벼움 (per spec §8.2).

#### Story 20 — PromQL 10초 예산 + degraded partial response
> As the operator, I want PromQL queries exceeding 10 seconds to return `"degraded": true` rather than block the whole dashboard so that one slow query doesn't tank the response P95.

- [ ] `metrics` BC가 dashboard PromQL 쿼리 composition 시 per-request 10초 예산을 enforce한다 (per spec §8.2).
- [ ] 예산을 초과한 widget은 자신의 데이터 자리에 `"degraded": true` 마커와 함께 partial response로 반환된다 — 전체 응답이 5xx로 떨어지지 않는다 (per spec §8.2, §12 "Cost protection").
- [ ] Frontend는 `"degraded": true`인 widget을 Story 15의 per-widget error degrade와 같은 UI로 표시한다.

#### Story 21 — Logs endpoint에 user당 rate limit
> As an authenticated operator, I want `/api/metrics/logs` rate-limited at 60 req/min/user so that even my own ad-hoc CLI loops don't hammer Loki when I forget to add `sleep`.

- [ ] Redis token-bucket이 `X-User-Id` 기준 user당 분당 60 request를 cap한다 (per spec §8.2).
- [ ] Bucket empty 시 429 + `Retry-After` 반환 (per spec §12 "Cost protection").
- [ ] Logs API의 LogQL 호출은 line cap 200 per request로 추가 protection — Loki 측에서 long-range full-text search를 거부 (per spec §9, §6).

## UX surfaces

Per spec §7 — 모든 시각 처리(spacing token, font scale, exact pill 배경, sparkline 두께 등)는 Stage-2 design doc + Figma에서 결정.

| Route | Auth | Purpose |
|---|---|---|
| `/metrics` | **public** | 단일 페이지 dashboard. 19개 위젯(service health grid × 1 + host × 4 + JVM heap × 4 + HTTP rate × 3 + spark × 2 + container resource table × 1 + 시간순 차트). Range preset 5개, default 1h, `?range=Xh` URL-shareable. 15초 auto-poll + manual `⟳`. |

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/metrics/dashboard?range=...` | public | 19개 위젯 데이터 묶음 (~8 KB JSON). |
| `GET /api/metrics/services` | public | Service health grid만 — cheap, aggressively cacheable. |
| `GET /api/metrics/timeseries?metric=<id>&range=<r>&step=<s>` | public | 단일 차트의 시계열 (`series[].points[]`). |
| `GET /api/metrics/logs?service=<id>&since=<d>&search=<q>&limit=<n>` | required | Loki LogQL 결과 (`entries[]`). P0 UI 없음 — ad-hoc CLI + M5.1 logs 탭 전용. |

사이드바 "System status" 행 처리(per spec §7.4)는 M1에서 잠금/잠금해제 메커니즘이 이미 자리잡혀 있으며, M5 ship 시점에 이 행이 잠금 해제된다 — `🔒 M5` 배지가 떨어지고 익명/signed-in 무관하게 `/metrics`로 라우팅된다. M4에서 도입된 `🔒 Sign in` auth-lock variant는 M5에는 **적용되지 않는다** — `/metrics`는 public이므로.

## Bounded Context: Metrics

- **책임 (Responsibility):** Frontend dashboard가 소비하는 HTTP 표면을 소유한다. Observability stack의 PromQL + LogQL을 composition하고, business-friendly JSON으로 wrap해 반환한다. **Metric id → query 템플릿 매핑 테이블의 sole owner** — frontend는 raw PromQL을 보지 못한다.
- **소유 데이터 (Owned data):** **없다.** `metrics` BC는 **Postgres schema를 가지지 않는다.** 모든 persistent 데이터는 Prometheus TSDB + Loki TSDB에 있다 — 이 둘이 source of truth.
- **외부 의존성 (External dependencies):**
  - `prometheus-playground` HTTP API (`/api/v1/query`, `/api/v1/query_range`) — read-only WebClient.
  - `loki-playground` HTTP API (`/loki/api/v1/query_range`) — read-only WebClient.
  - `spark-inference-gateway` (host process at `127.0.0.1:10080`, exposed via `host.docker.internal` in compose, per ADR-04) — spark widget을 위해 `/v1/models` 폴링 + 선택적으로 `/metrics`. 정확한 health probe 메커니즘은 ADR-15.
- **누가 metrics BC를 호출하나 (Inbound):** Gateway → `metrics-api` for all `/api/metrics/**` traffic. 다른 어떤 BC도 `metrics` BC를 호출하지 않는다.
- **이벤트 (Events):** **publishes none, consumes none.** Stateless request-response BC — Kafka 표면을 가지지 않는다.
- **모듈 quadruplet wiring (per ADR-01 v2; 정확한 entry point는 ADR-15):**
  - `metrics-api`: WebFlux controllers (`DashboardController`, `ServicesController`, `TimeseriesController`, `LogsController`).
  - `metrics-app`: PromQL/LogQL assembly use cases(`BuildDashboardUseCase`, `BuildTimeseriesUseCase`, `BuildServicesUseCase`, `QueryLogsUseCase`) + rate-limit guard.
  - `metrics-domain`: 값 객체(`MetricId`, `Range`, `WidgetData`, `ServiceHealth`) + query template constants (PromQL 매핑 테이블).
  - `metrics-infra`: Prometheus WebClient adapter, Loki WebClient adapter, Redis Redisson rate-limit adapter, spark-gateway probe adapter.
- **Redis 사용:** rate-limit token-bucket 카운터 — M3/M4 패턴 재사용 (per spec §8.2). 자체 namespace prefix는 ADR-15 핀.

**Forward note for the architect:** `metrics` BC는 어떤 이벤트도 publish/consume하지 **않으며** Postgres schema도 가지지 **않는다**는 사실은 의도적이다. ADR-15는 M5를 위한 새 Kafka 토픽이나 Postgres migration을 도입해서는 안 된다 — Prometheus/Loki TSDB가 source of truth고 BC는 stateless query proxy다.

## Non-functional requirements

Per spec §10에서 PRD 음성으로 재진술 — ship 직전 통과해야 함.

- **`/api/metrics/dashboard` P95 latency ≤ 400 ms** (19개 PromQL 쿼리 parallel composition 포함, 1분 polling 측정). Working target; ADR-15가 revise 가능 (per spec §10, §11 #16).
- **`/api/metrics/timeseries` P95 latency ≤ 200 ms** per single chart (per spec §10).
- **`/api/metrics/services` P95 latency ≤ 100 ms** — cheap, cacheable (per spec §12 "`metrics` BC routes").
- **`/api/metrics/logs` P95 latency ≤ 600 ms** for a 200-line, 15-minute-range query (per spec §10).
- **Frontend polling cadence: 15초** — visitor 한 명당 페이지 open 분당 ~4 request. Personal scale에 충분 (per spec §10).
- **Alloy scrape interval: 5초** for backend BC + cAdvisor; **10초** for host metrics (per spec §10).
- **Prometheus retention: 7일** (`--storage.tsdb.retention.time=7d`, override env var `METRICS_PROM_RETENTION_DAYS`); **Loki retention: 3일** (override env var `METRICS_LOKI_RETENTION_DAYS`). Disk budget: ~5 GB total (Prometheus ~3 GB + Loki ~2 GB) (per spec §4.3, §10).
- **Stateless invariant:** `metrics` BC는 Postgres schema도 Kafka 토픽도 가지지 않는다 — Prometheus + Loki가 source of truth. 어떤 form의 자체 persistent state도 introduce하지 않는다 (per spec §3).
- **Auth invariant on logs:** `/api/metrics/logs/**`는 `X-User-Id` 부재 시 401. ADR-09 allowlist에 추가되지 않으며, authenticated section에 등록된다 (per spec §8.1, §13).
- **PromQL/LogQL injection 방어:** `metric=` 와 `service=` URL 파라미터는 화이트리스트를 거친 뒤에만 template binding된다 — application-layer concat 금지. 화이트리스트 외 값은 400 (per spec §6).
- **Per-widget graceful degrade:** 단일 timeseries 실패 / 10초 예산 초과 widget은 `"degraded": true`로 표시되고 나머지 위젯은 영향 받지 않는다 (per spec §7.3, §8.2).
- **Docker socket security:** cAdvisor + Alloy는 `/var/run/docker.sock`을 **read-only**로 마운트하며 host network에 노출되지 않는다. 정당화는 `docs/infra-requirements/be.md`의 M5 section에 명시 (per spec §4.4, §8.3, §13).
- **Observability self-monitoring:** 4개 observability 컨테이너(Prometheus + Loki + Alloy + cAdvisor) 자체도 `/metrics` Prometheus endpoint를 emit. 이를 service health grid의 extra cell로 P0에 포함할지 vs M5.1로 미룰지는 ADR-15 결정 (per spec §10, §11 #17).
- **No alerting in P0:** Polling-only read surface. 알림은 P2 (per spec §10, §2).

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 전체 체크리스트, per spec §12 미러링.

### Observability stack
- [ ] `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground` 4개가 `infra/docker-compose.yml`에 정의된다.
- [ ] `docker compose up`이 error 없이 4개 모두 부트한다; actuator/health-equivalent endpoint가 200을 반환한다.
- [ ] Alloy가 5개 backend BC의 `/actuator/prometheus`를 5초 cadence로 scrape한다; Prometheus :9090의 web UI(또는 `/api/v1/targets`)가 5개 BC job + cAdvisor + host job을 `UP`으로 보고한다.
- [ ] Loki가 모든 playground 컨테이너의 docker logs를 수신한다; `loki.source.docker`는 0건 error 보고.
- [ ] Prometheus는 7일 retention, Loki는 3일 retention을 유지한다 — env var override(`METRICS_PROM_RETENTION_DAYS`, `METRICS_LOKI_RETENTION_DAYS`)이 effective함을 검증.
- [ ] cAdvisor + Alloy의 `/var/run/docker.sock` 마운트가 read-only이며 host network에 노출되지 않는다.

### `metrics` BC routes
- [ ] `GET /api/metrics/dashboard?range=1h`이 spec §5.2 JSON shape을 반환하고 P95 latency ≤ 400 ms (1분 polling 측정).
- [ ] `GET /api/metrics/timeseries?metric=jvm-heap-rag-chat-api&range=1h&step=30s`이 spec §5.3 shape을 반환하고 P95 ≤ 200 ms.
- [ ] `GET /api/metrics/services`가 6개 service health cell을 반환하고 P95 ≤ 100 ms.
- [ ] `GET /api/metrics/logs?service=rag-chat&since=15m`이 `X-User-Id` 없이 호출 시 401.
- [ ] 같은 호출이 `X-User-Id`를 carry할 때 spec §5.4 shape을 반환하고 P95 ≤ 600 ms.
- [ ] 화이트리스트 외 `metric=` / `service=` 값에 대해 400을 반환 — PromQL/LogQL injection 차단.
- [ ] `metrics` BC는 자체 Postgres schema를 가지지 않고 Kafka 토픽을 publish/consume하지 않는다.

### Frontend
- [ ] `/metrics` 경로가 익명 visitor에게 접근 가능하며 login redirect가 없다.
- [ ] 사이드바 "System status" 행이 M5 ship 후 `🔒 M5` 배지를 떨어뜨린다.
- [ ] Range preset pill(15m / 1h / 6h / 24h / 7d) 클릭이 URL을 `?range=Xh`로 update + 4개 backend route 모두 refetch.
- [ ] "Updated Ns ago" 카운터가 매초 increment; `⟳` manual refresh는 즉시 refetch + 15초 timer 리셋.
- [ ] 15초 auto-polling이 background에서 동작; tab focus loss 시 브라우저 default 동작에 위임(explicit pause 없음).
- [ ] 초기 로딩 시 19개 위젯 자리마다 skeleton placeholder가 렌더된다.
- [ ] 단일 timeseries fetch 5xx 또는 `"degraded": true` widget이 per-widget error state로 degrade한다 — 나머지 위젯은 정상.
- [ ] `/api/metrics/dashboard` 3회 연속 5xx 시 상단 banner가 표시되고 polling이 일시 정지.

### Cost protection
- [ ] `/api/metrics/dashboard` 30/min/IP rate-limit이 overrun 시 429 + `Retry-After` 반환.
- [ ] `metrics` BC가 dashboard PromQL composition에 10초 per-request budget을 enforce하고, 초과 widget은 `"degraded": true`로 partial response.
- [ ] `/api/metrics/logs` 60/min/user rate-limit이 overrun 시 429 + `Retry-After` 반환.

### Cross-milestone (traceability — non-blocking for M5 close)
- [ ] `docs/roadmap.md` §M5의 acceptance bullet 4("Dashboard is only reachable behind login") + bullet 5("No new external dependency is introduced")가 ADR-15 amendment로 retire된다 (per spec §13). Public surface block은 유지.
- [ ] `docs/adr/09-public-route-policy.md` §"Route classification"에 `GET /api/metrics/logs/**` → authenticated 행이 추가된다; 기존 `GET /api/metrics/**` public 행은 dashboard/services/timeseries만 cover하도록 좁혀진다 (per spec §8.1, §13).
- [ ] `docs/adr/00-overview.md` index에 ADR-15 행이 추가되고, 모듈 카운트 라인이 M5 quadruplet 반영되도록 update된다 (per spec §13).
- [ ] `docs/infra-requirements/be.md`에 M5 section이 추가된다 — docker socket 마운트(cAdvisor + Alloy) read-only 정당화, Prometheus/Loki disk budget(~5 GB), retention default(7d / 3d), 4개 observability 컨테이너 목록 (per spec §4.4, §8.3, §10, §13).
- [ ] 사이드바 "System status" 행 활성화가 backend M5 PR과 같은 마일스톤-close window에 land한다 — backend와 frontend가 same PR vs sequential PR인지는 ADR-15 결정 (per spec §11 #15).
- [ ] ADR-04(`spark-inference-gateway` at `host.docker.internal:10080`)와 ADR-13/14(rag-ingestion/rag-chat가 `/actuator/prometheus`를 export)는 amend되지 않는다 — M5는 consumer이지 modifier가 아니다 (per spec §13).
- [ ] `docs/adr/05-data-store.md`는 amend되지 않는다 — M5는 Postgres schema를 가지지 않는다 (per spec §13).
- [ ] Manual E2E: 운영자가 `/metrics`를 익명으로 열어 19개 위젯이 모두 데이터를 표시함을 확인; 직접 backend BC 하나를 `docker compose stop`으로 내려 service health grid가 ≤15초 안에 `down`으로 전환되는지 확인. M5 close blocker가 아니다.

## Out of scope

### M5.1 (same milestone bucket, ship if cycle has slack)
- **Mobile layout (≤719 px).** 풀 모바일 reflow + sparkline 외 차트 노출. P0는 single-column stack + sparkline only로 graceful degrade (per spec §2, §7.5).
- **Logs UI.** `/metrics`에 logs 탭을 추가해 `GET /api/metrics/logs`를 소비 — filter + search + tail. Auth-gated 탭. P0에 API endpoint는 존재하지만 UI 없음 (per spec §2, §5.4).
- **Domain metrics widgets.** rag-chat tokens/hr, rag-ingestion chunks/day, docs published today, M3 ingestion latency P95, M4 chat completion success rate — 비즈니스 지표 위젯 (per spec §2).
- **Custom range picker** (date/time picker로 5개 preset 외 임의 range 선택) (per spec §2).
- **Settings panel** — polling interval, retention override, 위젯 hide/show 같은 클라이언트 측 설정 (per spec §2).

### P2 (별도 후속 마일스톤 / 다음 사이클)
- **Alerts / notifications.** Slack / email / push 통보. P0는 polling-only read surface (per spec §2, §10).
- **Historical comparison** ("this week vs last week", "예전 베이스라인 대비 +30%" 같은 비교 위젯) (per spec §2).
- **Multi-host monitoring** — 두 번째 호스트(GPU box vs CPU box)의 metric을 같은 페이지에 합쳐 보기 (per spec §2).
- **Tracing** — Tempo / OpenTelemetry trace 파이프라인. 4개 observability 컨테이너 외에 trace stack은 도입하지 않는다 (per spec §2).
- **Grafana embed / sidecar** — Grafana 컨테이너를 같이 띄워 `/metrics` 페이지에 iframe으로 embed하는 옵션. P0는 자체 `/metrics` 페이지가 유일한 UI surface (per spec §2).
- **Logs full-text search across long ranges** — Loki의 LogQL native search를 며칠 단위 range로 노출하기. P0는 short-range만 (`since=15m` 같은) (per spec §2, §9).
- **Custom dashboard authoring / multi-page** — 사용자가 자체 dashboard를 저장/공유. P0는 고정 19-widget single-page (per spec §2).

## Dependencies

- **요구:** M0 (Bootstrap) — compose stack(Postgres + Redis + Kafka), `playground` compose network, `host.docker.internal:10080`이 `spark-inference-gateway`로 라우팅되는 `extra_hosts` 설정. Redis는 M3/M4와 같은 인스턴스를 token-bucket용으로 재사용.
- **요구:** M1 (Identity) — 게이트웨이의 `/api/metrics/**` 라우팅 + public route allowlist(`/api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries`)와 authenticated section(`/api/metrics/logs/**`) 등록. `X-User-Id` 헤더 forward 메커니즘은 logs endpoint가 사용.
- **요구:** M2 / M3 / M4 (Docs / RAG-Ingestion / RAG-Chat) 출시 — 각각의 BC가 `/actuator/prometheus`를 export하고 있어야 Alloy가 5초 cadence로 scrape할 수 있다. ADR-13/14가 이미 이 invariant를 안정화했다 — M5는 consumer이지 modifier가 아니다 (per spec §13).
- **요구:** `spark-inference-gateway` — host process로 `127.0.0.1:10080`에 살아 있고, compose 컨테이너들이 `host.docker.internal:10080`을 통해 접근 가능해야 한다 (per ADR-04). M5는 spark widget을 위해 이 게이트웨이의 `/v1/models`를 폴링하고, 선택적으로 `/metrics`를 scrape한다 — 정확한 probe 메커니즘은 ADR-15.
- **요구:** ADR-15 (M5 per-milestone ADR) — 4개 observability 컨테이너의 이미지/버전 핀, `metrics-api` 포트(18085 후보), 모듈 quadruplet wiring entry point, PromQL/LogQL 전체 매핑 테이블(spec §6의 sketch 확장), 차트 라이브러리 선택(Recharts / Visx / Tremor / raw SVG), service "unhealthy" 판단 규칙, rate-limit 정확한 수치/keys, observability self-monitoring을 P0에 포함할지 여부, env var 이름/기본값. **ADR-15는 동시에 4개의 cross-doc amendment를 운반한다** (per spec §13): `docs/roadmap.md` §M5(acceptance bullet 4 + 5 retire), `docs/adr/09-public-route-policy.md`(logs row 추가), `docs/adr/00-overview.md`(index + 모듈 카운트), `docs/infra-requirements/be.md`(M5 section 신설). 모든 amendment는 M5 ADR PR 하나로 atomic하게 land한다.
- **신규 외부 의존성 (이번 마일스톤에서 도입):** **4개 observability 컨테이너** — `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground`. 이 도입은 roadmap §M5 acceptance bullet 5("No new external dependency is introduced")를 의도적으로 폐기한다 (per spec §13).
- **소비자 (M5 close blocker 아님):** 없음. M5의 dashboard와 logs endpoint는 외부 BC가 호출하지 않는다 — frontend와 ad-hoc CLI만 소비.
- **Kafka 의존성 없음:** M5는 Kafka 토픽을 publish하지도 consume하지도 않는다. ADR-03 envelope schema는 적용되지 않는다 (per spec §3).
- **Postgres schema 없음:** M5는 자체 Postgres schema나 migration을 도입하지 않는다. `docs/adr/05-data-store.md`는 amend되지 않는다 (per spec §3, §13).

## Open questions for the implementer

ADR-15(architect) + Stage-3 implementer가 해소할 사항. PRD 리뷰어가 한 곳에서 보도록 spec §11에서 그대로 옮겨둔다. **The architect must close these in ADR-15, the per-milestone ADR.**

1. **Port assignment** — `metrics-api`의 포트(M0–M4가 18080 / 18081 / 18082 / 18083 / 18084 사용; M5는 18085 후보). 확정 필요.
2. **모듈 quadruplet wiring** — `metrics-{api, app, domain, infra}`. `-api`가 WebFlux controller(`DashboardController`, `ServicesController`, `TimeseriesController`, `LogsController`)를 host. `-app`이 PromQL/LogQL assembly use case + rate-limit guard를 host. `-domain`이 value object + query template constants를 host. `-infra`가 Prometheus WebClient + Loki WebClient adapter를 host.
3. **Prometheus 이미지 + 버전 핀** — `prom/prometheus:v2.54.x` vs latest 라인.
4. **Loki 이미지 + 버전 핀** — `grafana/loki:3.x` 라인.
5. **Alloy 이미지 + 버전 핀** + 정확한 River config 파일 구조 — single file vs include split.
6. **cAdvisor 이미지 + 버전 핀** + privileged-vs-readonly socket trade-off 정당화.
7. **HTTP client 선택** for PromQL/LogQL — Spring `WebClient`(M2/M3/M4와 일관) vs Prometheus 공식 Java client. Default: WebClient.
8. **Frontend 차트 라이브러리** — Recharts vs Visx vs Tremor vs raw SVG. Working assumption: Recharts (smallest learning curve, SSR-friendly, CSS variable 통합 가능).
9. **Service "unhealthy" 판단 규칙** — Prometheus `up{} == 0`(scrape miss) vs Spring Actuator `/actuator/health != UP` vs 둘 다. 어떤 조합으로 pick할지.
10. **PromQL 화이트리스트 + 매핑 finalized** as a constants class in `metrics-domain` — spec §6의 sketch를 넘어서는 전체 테이블.
11. **Loki 라벨 set 표준** — `container`, `service`, `level`. 확정 또는 확장.
12. **`spark-inference-gateway` health probe 메커니즘** — HEAD `/v1/models` vs scrape `/metrics` vs 둘 다.
13. **Container "unhealthy" 판단 규칙** — cAdvisor가 metric 반환을 중단 vs docker inspect status vs Alloy `loki.source.docker` 실패. 어떤 신호의 조합으로 정의할지.
14. **Auth identifier on logs endpoint** — `X-User-Id` 헤더 confirmation(M2/M4와 같은 패턴); P0에서 operator = anyone signed in (role 구분 없음).
15. **사이드바 "System status" 행 활성화 coordination** — backend M5 PR + frontend 행 활성화 PR이 same PR vs sequential PR인지.
16. **`/api/metrics/dashboard` P95 latency 목표** — 400 ms 확정 또는 revise.
17. **Observability self-monitoring** — Prometheus + Loki + Alloy + cAdvisor 자체를 service health grid의 extra cell(6 → 10)로 P0에 포함할지 vs M5.1로 미룰지.
18. **구체적 env var 이름 + 기본값** — retention(`METRICS_PROM_RETENTION_DAYS`, `METRICS_LOKI_RETENTION_DAYS`), polling cadence(`METRICS_POLL_INTERVAL_S`), scrape interval(`METRICS_SCRAPE_INTERVAL_S`) 등.
19. **운영자 CLI for log tail** — UI가 M5.1로 미뤄지므로 `tools/` 디렉토리에 helper script (예: `tools/metrics-logs.sh`) — nice-to-have.
20. **Docker socket mitigation** — `docs/infra-requirements/be.md`의 M5 section에 cAdvisor + Alloy의 socket 마운트(read-only) 정당화 + rootless docker alternative 미사용 사유의 formal 노트.

---

> **PRD vs ADR:** 이 문서는 사용자(operator + anonymous visitor)와 리뷰어가 읽는 표면이다. 정확한 라이브러리 좌표, 4개 observability 컨테이너의 이미지/버전 핀, 포트 번호, PromQL/LogQL 전체 매핑 테이블, env var 이름/기본값, 모듈 wiring entry point, rate-limit 정확한 수치/keys, service "unhealthy" 판단 조합, 차트 라이브러리 선택 같은 기술 컨트랙트는 ADR-15(architect의 per-milestone ADR)가 우선한다. PRD가 ADR과 어긋나 보이면 ADR을 따른다.
>
> **PRD vs spec:** 4개 endpoint의 정확한 JSON shape, PromQL 템플릿의 정확한 표현, Alloy River config의 component DAG, 19개 widget의 ASCII wireframe, retention 정확한 값 같은 컨트랙트는 spec(`docs/superpowers/specs/2026-05-19-m5-metrics-design.md`)이 우선한다. PRD가 spec과 어긋나 보이면 spec을 따른다.
>
> **PRD vs roadmap supersession:** roadmap.md §M5의 acceptance bullet 4("Dashboard is only reachable behind login")와 bullet 5("No new external dependency is introduced")는 본 사이클의 ADR-15 PR이 일괄 재amend한다 — `/metrics`는 public, 4개 observability 컨테이너는 새 외부 의존성으로 도입된다. 본 PRD는 spec을 canonical로 따른다.
