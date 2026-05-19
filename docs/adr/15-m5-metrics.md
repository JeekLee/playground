# ADR-15: M5 Metrics — Implementation Decisions

## Status
Accepted

## Context

The M5 spec (`docs/superpowers/specs/2026-05-19-m5-metrics-design.md` v1)
pins the Metrics BC's bounded context, the 4-container observability stack
(Prometheus + Loki + Alloy + cAdvisor), the 4-route HTTP surface, the
PromQL/LogQL mapping sketch, the public-dashboard / authenticated-logs
split, and the supersession set that walks back the immediately-prior
roadmap framing ("behind login" + "no new external dependency"). It
deliberately defers **20 implementation-shape questions** to this
per-milestone ADR (spec §11) plus **four concrete cross-doc amendments**
enumerated in spec §13.

ADR-14 did the same job for M4: pin library versions, port number,
module quadruplet wiring, the cross-schema SELECT exception, the
Resilience4j/Redisson primitives, the rate-limit numbers, and the six
cross-doc amendments. ADR-13 covered M3 with a Kafka-only BC. ADR-12
covered M2 with the OpenSearch container introduction pattern — the
closest precedent for adding multiple new compose containers in a single
per-milestone ADR.

The M5 BC is, in shape, **the smallest BC in the project**:

- **No Postgres schema** (PRD §"Bounded Context" + spec §3). Prometheus
  TSDB + Loki TSDB are the only persistent data stores; the BC is a
  stateless WebClient proxy. No Flyway migration. ADR-05 is **not**
  amended.
- **No Kafka surface** (spec §3). The BC neither produces nor consumes
  events. ADR-03 is **not** amended.
- **No new ADR-08 HTTP exception** — the BC talks to Prometheus + Loki
  + spark-inference-gateway, all of which sit on the
  "non-BC infra" side of ADR-08's "no BC-to-BC HTTP" rule. They are
  external dependencies of the BC, not sibling BCs. ADR-08 is **not**
  amended.

What M5 does change is **the operator-facing surface**: a public
`/metrics` dashboard becomes the second public read surface after the
M2 docs surface, and an authenticated `/api/metrics/logs/**` row is
added to ADR-09's allowlist beside the existing public
`/api/metrics/**` row. The "most-specific-match wins" rule applies —
the new logs row narrows the existing public row without removing it.

Like ADR-12/13/14, none of the decisions below supersede a transverse
ADR's invariants; they fill in implementation details inside the
envelopes ADR-01 v2, ADR-02, ADR-04, ADR-07, ADR-08, and ADR-09
defined. Four explicit amendments — to `docs/roadmap.md` §M5
(acceptance bullets 4+5 retire + bullet refresh), `docs/adr/09-public-route-policy.md`
(logs row addition), `docs/adr/00-overview.md` (index row +
module-count bump for the metrics quadruplet), and `docs/infra-requirements/be.md`
(new M5 section on docker socket mounts, disk budgets, memory
footprints, named volume conventions) — are captured at the bottom of
this ADR and appended inline to each affected file in the same PR.

## Decision

### 1. Port assignment — 18085 for `metrics-api`, gateway-routable

**Decision:** `metrics-api` binds **port 18085** on
`localhost`/compose-internal. Reserved by ADR-01 v2's port table and
mentioned forward-looking by ADR-14 §2's port table (which listed
"18086 metrics-api (planned)" — that listing is **superseded by this
ADR**; the canonical M5 port is **18085**, the lowest unused number in
the BC range, with 18086 now reserved for the next BC).

| Port | Service | Gateway-routable? | Notes |
|---|---|---|---|
| 18080 | gateway | n/a | OAuth ingress per ADR-07 |
| 18081 | identity-api | yes | M1 |
| 18082 | docs-api | yes | M2 |
| 18083 | rag-ingestion-api | **no** | M3 — actuator-only, host-not-exposed |
| 18084 | rag-chat-api | yes | M4 |
| **18085** | **metrics-api** | **yes** | **M5 — this ADR** |
| 18086 | (reserved) | — | next BC |

`server.port: 18085` in `application.yml`. **No host port mapping** in
`infra/docker-compose.yml` — `metrics-api` is compose-internal per
ADR-08's "backends must not be host-exposed" rule; all browser traffic
flows through the gateway.

**Considered alternative (spec §11 Q1):** assign port 18086 per
ADR-14's earlier reservation. Rejected — the BC quadruplet ordering
follows ship order (M1 → 18081, M2 → 18082, …, M5 → 18085). ADR-14's
"18086 planned" note was forward-looking, not a binding pin; the lower
port keeps the sequence intact. ADR-14's port-table row is superseded
by §G.5 below; the canonical table is the one above.

### 2. Module quadruplet wiring — WebFlux end-to-end, no JPA, no Kafka

**Decision (matches ADR-01 v2's quadruplet pattern; M5-specific shape
= reactive WebFlux throughout with no JPA / no Postgres / no Kafka):**

| Module | Type | Port | What it hosts |
|---|---|---|---|
| `metrics-api` | runnable Spring Boot app | **18085** (gateway-routable per ADR-07) | Spring Boot bootstrap, `application.yml`, `@SpringBootApplication`, `/actuator/health` + `/actuator/prometheus`, the **4 WebFlux controllers** (`DashboardController`, `ServicesController`, `TimeseriesController`, `LogsController`), the security config (`MetricsSecurityConfig` — bears on the logs endpoint's `X-User-Id` requirement), auth-header binding (`X-User-Id` for logs only). |
| `metrics-app` | Java library | n/a | Use-case orchestrators (`BuildDashboardUseCase`, `BuildServicesUseCase`, `BuildTimeseriesUseCase`, `QueryLogsUseCase`), port interfaces (`PrometheusPort`, `LokiPort`, `SparkGatewayProbePort`, `IpRateLimitPort`, `UserRateLimitPort`), the **`RateLimitGuard`** composite (per-IP + per-user bucket checks composed in front of the use cases), the **`PromQlBudgetEnforcer`** (10s per-request budget per spec §8.2). |
| `metrics-domain` | Java library | n/a | `MetricId`, `Range`, `Step`, `WidgetData`, `ServiceHealth`, `LogEntry`, `LokiLabelSet` value objects (all Spring-free); the **`PromQlTemplate`** constants class (the full PromQL whitelist + mapping, see §10 below); the **`LogQlTemplate`** constants class; the **`ServiceAllowlist`** + **`ContainerAllowlist`** sets used for `<svc>` / `<name>` substitution validation; the `HealthVerdict` value object that composes Prometheus `up{}` + Spring Actuator `/actuator/health` signals into a single `up`/`degraded`/`down`. **No Spring imports.** |
| `metrics-infra` | Java library | n/a | `PrometheusAdapter` (Spring `WebClient` → `/api/v1/query` + `/api/v1/query_range`), `LokiAdapter` (Spring `WebClient` → `/loki/api/v1/query_range`), `SparkGatewayProbeAdapter` (Spring `WebClient` → HEAD `/v1/models`), `RedissonIpRateLimiterAdapter` + `RedissonUserRateLimiterAdapter` (both via `RRateLimiter`). **No JPA, no Postgres, no Kafka clients.** |

**Why WebFlux end-to-end (no servlet stack imported):**

Every M5 I/O path is HTTP-bound (Prometheus, Loki, spark-inference-gateway,
Redisson — Redisson is non-blocking on the Lettuce client). There is no
blocking JDBC adapter to wrap with `Schedulers.boundedElastic()`. The
reactive pipeline composes 19 PromQL queries in parallel via `Mono.zip`
+ `Flux.merge` inside `BuildDashboardUseCase` to keep the dashboard
payload under the 400 ms P95 budget (§16). A servlet stack would force
the 19-query fan-out onto a Tomcat worker pool, defeating the
parallelization.

**Why no `spring-boot-starter-data-jpa` on `metrics-infra`'s classpath
(spec §E):**

The `metrics` BC has no Postgres schema. Pulling JPA in would
auto-configure a `DataSource` bean, which would fail at startup unless
a stub URL is provided. The exclusion is enforced at the Gradle level:

```kotlin
// backend/metrics/metrics-infra/build.gradle.kts
dependencies {
  // explicitly NOT spring-boot-starter-data-jpa or spring-boot-starter-jdbc
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  // ... other deps
}
```

If a future M5.1 introduces a Postgres rollup (e.g., `metrics.usage_daily`
mirroring the M4 PRD §"Out of scope re-revision" hint), this exclusion
is lifted in a follow-up ADR. M5 P0 keeps the BC stateless.

**Component placement summary (resolves spec §11 Q2):**

| Component | Module | Notes |
|---|---|---|
| `@GetMapping("/api/metrics/dashboard")` returning `Mono<DashboardResponse>` | `metrics-api` (`DashboardController`) | Binds optional `range` query param. Delegates to `BuildDashboardUseCase`. |
| `@GetMapping("/api/metrics/services")` returning `Mono<ServicesResponse>` | `metrics-api` (`ServicesController`) | Cheap; cacheable. |
| `@GetMapping("/api/metrics/timeseries")` returning `Mono<TimeseriesResponse>` | `metrics-api` (`TimeseriesController`) | Binds `metric`, `range`, `step` query params. `metric` is validated against `ServiceAllowlist` + `ContainerAllowlist` before template lookup. |
| `@GetMapping("/api/metrics/logs")` returning `Mono<LogsResponse>` | `metrics-api` (`LogsController`) | Requires `X-User-Id` header (enforced via `MetricsSecurityConfig` per §C below); validates `service` against `ServiceAllowlist`. |
| `MetricsSecurityConfig` (WebFlux `SecurityWebFilterChain`) | `metrics-api` | Permits `/api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries`, `/actuator/**`. Requires authentication (via `X-User-Id` presence rule, mirrors ADR-12/ADR-14 patterns) for `/api/metrics/logs/**`. |
| `BuildDashboardUseCase` (orchestrates 19 PromQL queries in parallel) | `metrics-app` | Uses `Mono.zip` over `PrometheusPort.instantQuery(...)` calls. Applies `PromQlBudgetEnforcer` per query. |
| `BuildServicesUseCase` | `metrics-app` | Composes Prometheus `up{}` queries + the `HealthVerdict` rule per §9. |
| `BuildTimeseriesUseCase` | `metrics-app` | Looks up `PromQlTemplate` by `metric` id, calls `PrometheusPort.rangeQuery(...)`. |
| `QueryLogsUseCase` | `metrics-app` | Validates `service`, composes LogQL via `LogQlTemplate`, calls `LokiPort.queryRange(...)`. |
| `RateLimitGuard` | `metrics-app` | Composite. For `/dashboard`: IP-bucket check via `IpRateLimitPort`. For `/logs`: user-bucket check via `UserRateLimitPort`. For `/services` + `/timeseries`: no rate limit (spec §8.2). |
| `PromQlBudgetEnforcer` | `metrics-app` | Wraps each individual PromQL `Mono` with `.timeout(Duration.ofSeconds(10))`; on timeout returns a `DegradedWidget` sentinel rather than failing the whole `Mono.zip`. Result feeds into the `"degraded": true` widget marker (spec §7.3 + §8.2). |
| `PromQlTemplate` (PromQL whitelist constants) | `metrics-domain` | Full table in §10 below. Pure-Java enum + map. No Spring. |
| `LogQlTemplate` (LogQL whitelist constants) | `metrics-domain` | Single template per spec §6. Pure-Java. |
| `ServiceAllowlist` / `ContainerAllowlist` | `metrics-domain` | `Set<String>` constants. The `<svc>` and `<name>` substitution paths in `PromQlTemplate` consult these before template binding. |
| `HealthVerdict` (combines `up{}` + `/actuator/health` into one verdict) | `metrics-domain` | Pure logic per §9. |
| `PrometheusAdapter` implements `PrometheusPort` | `metrics-infra` | `WebClient` → `http://prometheus-playground:9090/api/v1/query` (instant) + `/api/v1/query_range` (range). Response decoded as `application/json`. |
| `LokiAdapter` implements `LokiPort` | `metrics-infra` | `WebClient` → `http://loki-playground:3100/loki/api/v1/query_range`. |
| `SparkGatewayProbeAdapter` implements `SparkGatewayProbePort` | `metrics-infra` | `WebClient` → HEAD `http://host.docker.internal:10080/v1/models`. 15s cache (Caffeine in-process) per §12. |
| `RedissonIpRateLimiterAdapter` / `RedissonUserRateLimiterAdapter` | `metrics-infra` | Redisson `RRateLimiter` (same pattern as M3/M4). Keys per §C below. |

### 3. Prometheus image + version pin — `prom/prometheus:v2.54.1`

**Decision:** `prom/prometheus:v2.54.1` (the latest 2.54.x patch on
Docker Hub at the time of this ADR; 2.54 is the current LTS-equivalent
stable line). The `v2.54.x` placeholder in the spec is resolved to
`v2.54.1`.

| Concern | Pin |
|---|---|
| Image | `prom/prometheus:v2.54.1` |
| Compose service name | `prometheus-playground` |
| Compose-internal port | `9090` (HTTP API + web UI) |
| Host port binding | **none** (compose-internal only; spec §4.4). Operator may opt into a host bind via env-var `PROMETHEUS_HOST_BIND=9090` (default unset). |
| Retention | **7 days** (`--storage.tsdb.retention.time=7d`) — overridable via env-var `METRICS_PROM_RETENTION_DAYS` (per §18). |
| Storage backend | Local TSDB at `/prometheus` inside the container; persisted via named volume **`prometheus-playground-data`** (per `docs/infra-requirements/be.md` amendment §G.4). |
| Scrape config | **Minimal** — only scrapes Alloy's own `:12345/metrics` endpoint. All other scrape jobs are pushed to Prometheus by Alloy via `remote_write` per spec §4.2 block 4. |
| Web UI auth | Disabled in dev (compose-internal). |

**Why 2.54.x and not 3.x:** Prometheus 3.0 GA'd late 2024 with breaking
changes to the OTLP receiver and the experimental UTF-8 metric name
support. The metrics BC uses only the stable `/api/v1/query` +
`/api/v1/query_range` paths and the basic remote-write path, which are
identical across 2.54.x and 3.x. Pinning 2.54.x keeps M5 P0 on the
mature line; a 3.x bump is a single-image-tag follow-up if needed.

**Considered alternative (spec §11 Q3):** `prom/prometheus:latest`.
Rejected — `latest` violates ADR-01's "no `latest` tags" rule (mirrored
from M0's roadmap acceptance criterion).

### 4. Loki image + version pin — `grafana/loki:3.2.1`

**Decision:** `grafana/loki:3.2.1` (the latest 3.x patch at time of
this ADR; the 3.x line is current stable and supports the
`tsdb` schema head + `--target=all` single-binary mode appropriate for
personal scale).

| Concern | Pin |
|---|---|
| Image | `grafana/loki:3.2.1` |
| Compose service name | `loki-playground` |
| Compose-internal port | `3100` (HTTP API + push receiver) |
| Host port binding | **none** (compose-internal only). |
| Retention | **3 days** (Loki config `limits_config.retention_period: 72h`) — overridable via env-var `METRICS_LOKI_RETENTION_DAYS` (per §18). |
| Storage backend | Local filesystem (single-binary mode); persisted via named volume **`loki-playground-data`**. |
| Schema | `tsdb` head from day one (Loki 3.x default; no `boltdb-shipper` legacy concerns). |
| Push receiver | `/loki/api/v1/push` open to compose-internal traffic (Alloy is the only producer). |
| Auth | Disabled in dev (compose-internal). `auth_enabled: false` in Loki config. |

**Loki config file** (`infra/observability/loki-config.yml`, infra-implementer
transcribes verbatim in Stage 3):

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    instance_addr: 127.0.0.1
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2026-05-19
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  retention_period: 72h
  reject_old_samples: true
  reject_old_samples_max_age: 168h
  max_query_length: 168h

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem
```

**Considered alternative (spec §11 Q4):** `grafana/loki:2.9.x`
(previous stable). Rejected — Loki 2.x's `boltdb-shipper` is being
retired; starting on `tsdb`-from-3.x avoids a future schema migration.

### 5. Alloy image + version pin + single `config.alloy` file (no include split)

**Decision:** `grafana/alloy:v1.3.1` (the latest 1.x patch at time of
this ADR). **Single-file config** — `infra/observability/config.alloy`
— no `include` split. Rationale: ~30 lines of River, four
discrete components (`prometheus.scrape "backend_bcs"`,
`prometheus.scrape "cadvisor"`, `prometheus.exporter.unix "host"` +
`prometheus.scrape "host"`, `discovery.docker` + `loki.source.docker` +
`loki.write`, `prometheus.remote_write "local"`). Splitting into
includes triples the file count without making any single component
easier to find.

| Concern | Pin |
|---|---|
| Image | `grafana/alloy:v1.3.1` |
| Compose service name | `alloy-playground` |
| Compose-internal port | `12345` (Alloy web UI + own `/metrics`) |
| OTLP receiver | **disabled in P0** — the spec §4.1 lists `4317` as "if used"; M5 P0 does not consume OTLP. Re-enable in a follow-up ADR when tracing lands (P2). |
| Host port binding | **none** (compose-internal only). |
| Docker socket mount | `/var/run/docker.sock:/var/run/docker.sock:ro` — **read-only** (per §6 + spec §4.4 + `docs/infra-requirements/be.md` amendment §G.4). |
| Config file | `infra/observability/config.alloy` mounted at `/etc/alloy/config.alloy:ro`. |
| State volume | named volume **`alloy-playground-data`** mounted at `/var/lib/alloy/data` (positional state for `loki.source.docker` cursors + scrape failure counters). |
| Run mode | `run /etc/alloy/config.alloy --storage.path=/var/lib/alloy/data --server.http.listen-addr=0.0.0.0:12345` |

**Final `config.alloy` content** (River syntax — infra-implementer
transcribes verbatim in Stage 3, modulo any compose-name typos
caught in smoke):

```hcl
// =====================================================================
// infra/observability/config.alloy
// Grafana Alloy v1.3.1 — playground unified collector
// Pinned by ADR-15.
// =====================================================================

// 1. Backend BC scrape — 5 jobs at 5s cadence
prometheus.scrape "backend_bcs" {
  job_name = "playground-bcs"
  targets = [
    { __address__ = "gateway:18080",        service = "gateway",        __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "identity-api:18081",   service = "identity-api",   __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "docs-api:18082",       service = "docs-api",       __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "rag-ingestion-api:18083", service = "rag-ingestion-api", __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "rag-chat-api:18084",   service = "rag-chat-api",   __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "metrics-api:18085",    service = "metrics-api",    __metrics_path__ = "/actuator/prometheus" },
  ]
  scrape_interval = "5s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 2. cAdvisor scrape — per-container CPU/mem
prometheus.scrape "cadvisor" {
  job_name = "cadvisor"
  targets = [{ __address__ = "cadvisor-playground:8080" }]
  scrape_interval = "5s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 3. Host metrics — replaces node_exporter
prometheus.exporter.unix "host" { }
prometheus.scrape "host" {
  targets    = prometheus.exporter.unix.host.targets
  scrape_interval = "10s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 4. Observability self-monitoring (Prometheus, Loki, Alloy itself, cAdvisor)
prometheus.scrape "observability_self" {
  job_name = "observability-self"
  targets = [
    { __address__ = "prometheus-playground:9090", service = "prometheus-playground", __metrics_path__ = "/metrics" },
    { __address__ = "loki-playground:3100",       service = "loki-playground",       __metrics_path__ = "/metrics" },
    { __address__ = "alloy-playground:12345",     service = "alloy-playground",      __metrics_path__ = "/metrics" },
    { __address__ = "cadvisor-playground:8080",   service = "cadvisor-playground",   __metrics_path__ = "/metrics" },
  ]
  scrape_interval = "10s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 5. Local Prometheus (push)
prometheus.remote_write "local" {
  endpoint { url = "http://prometheus-playground:9090/api/v1/write" }
}

// 6. Docker log discovery + shipping to Loki
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets
  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }
  rule {
    source_labels = ["__meta_docker_container_label_service"]
    target_label  = "service"
  }
}

loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.containers.output
  labels     = { source = "docker" }
  forward_to = [loki.write.local.receiver]
}

loki.write "local" {
  endpoint { url = "http://loki-playground:3100/loki/api/v1/push" }
}
```

**Note on the `backend_bcs` job and the `metrics-api` entry:** Alloy
scrapes its own scraper-target (`metrics-api:18085/actuator/prometheus`).
This is intentional — `metrics-api` exposes its own request-rate /
latency / circuit-breaker metrics (Micrometer auto-binding via
`spring-boot-starter-actuator`), and the operator wants to see those
on the dashboard alongside the other 5 BCs. No infinite loop —
Prometheus stores; the BC reads at query time only.

**Considered alternative (spec §11 Q5):** split into
`config.alloy` + `targets-bcs.river` + `targets-host.river` includes.
Rejected — premature factoring. M5.1's logs UI may add a small
`loki.process` stage for parsing, at which point a `processors.river`
include is reasonable; not yet.

### 6. cAdvisor image + version pin + read-only socket mount

**Decision:** `gcr.io/cadvisor/cadvisor:v0.49.1` (the latest 0.49.x
patch at time of this ADR; the 0.49 line ships the v2 stat collector
and the Docker 25+ socket compatibility). **Read-only** socket mount
(`/var/run/docker.sock:/var/run/docker.sock:ro`) — no `privileged: true`
flag — with the residual privilege-escalation surface explicitly
documented in `docs/infra-requirements/be.md` amendment §G.4.

| Concern | Pin |
|---|---|
| Image | `gcr.io/cadvisor/cadvisor:v0.49.1` |
| Compose service name | `cadvisor-playground` |
| Compose-internal port | `8080` (HTTP `/metrics` endpoint) |
| Host port binding | **none** (compose-internal only). |
| Privileged | **no** (`privileged: false`, default). |
| Socket mount | `/var/run/docker.sock:/var/run/docker.sock:ro` (read-only). |
| Additional mounts | `/:/rootfs:ro`, `/sys:/sys:ro`, `/var/lib/docker:/var/lib/docker:ro` (cAdvisor needs these for cgroup + image disk usage; all `:ro`). |
| Command | `--docker_only=true --store_container_labels=false --whitelisted_container_labels=service` |

**Why read-only socket and not privileged:**

The cAdvisor maintainers ship two operational stances — privileged
(the historical default, recommended for "full feature set") and
unprivileged with selective mounts. Personal-scale playground does not
need the privileged extras (CPU thermal stats, hardware counters); the
metrics dashboard's needs (per-container CPU/mem/IO) are covered by
the v2 stat collector against cgroup v2 readable through `/sys:ro`.
The **residual privilege-escalation surface** of the read-only socket
is: anyone who compromises cAdvisor can `docker inspect` every
container (revealing env vars, mount paths, exposed ports). They
**cannot** `docker exec` or `docker run` — the socket is `:ro` and
docker's API enforces verbs against the mode of the socket connection
when the kernel supports it (Linux 5.x+ which playground's host runs).

This is documented as a deliberate trade-off in
`docs/infra-requirements/be.md` per §G.4 + spec §8.3.

**Considered alternative (spec §11 Q6):** run cAdvisor with
`privileged: true`. Rejected — gains no metric the dashboard
consumes, and the elevated mode opens the kernel module load path
which is a meaningfully worse blast radius than the read-only socket.

**Considered alternative (deferred to P2):** rootless docker setup
where the socket is unprivileged-by-construction. Rejected for M5 P0
— rootless docker changes the storage driver, breaks the existing
`postgres-playground` volume mount semantics, and is operational work
disproportionate to a personal-scale dashboard.

### 7. HTTP client choice — Spring `WebClient` (consistent with M2/M3/M4)

**Decision:** Spring `WebClient` for both PromQL and LogQL adapters.
Not the Prometheus official Java client; not Apache HttpClient5.

| Concern | Pin |
|---|---|
| Adapter library | `spring-boot-starter-webflux` (already pulled in for the controller layer) |
| Reactor support | native — `WebClient` returns `Mono` / `Flux` natively |
| JSON decoder | Jackson (Spring Boot default) |
| Connection pool | Reactor Netty default (one shared `HttpClient` bean) |
| Timeout | 8 seconds per individual PromQL query (covers the `PromQlBudgetEnforcer` 10 s budget per §B); 15 seconds for Loki range queries (logs are larger payloads). |

**Why WebClient and not the Prometheus official Java client (spec §11 Q7):**

- **Consistency.** M2/M3/M4 all use `WebClient` (docs body-fetch in
  M2 per ADR-12 §2; embedding/chat calls in M3/M4 are Spring AI which
  wraps `WebClient`). Adding a second HTTP-client abstraction (Apache
  HttpClient5 or Prometheus's own) bifurcates the codebase for no
  feature gain.
- **Reactive composition.** `BuildDashboardUseCase` fans out 19 PromQL
  queries via `Mono.zip`; this is `WebClient`'s native shape. The
  Prometheus Java client returns synchronous values that would have to
  be wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`,
  defeating the parallel-fan-out purpose.
- **The Prometheus HTTP API is a thin JSON-over-REST surface** — there
  is no protocol nuance that a dedicated client handles better than
  `WebClient` + Jackson DTOs.

**LogQL client choice:** same `WebClient`. Loki ships no official Java
client; the `/loki/api/v1/query_range` endpoint is JSON-over-REST,
identical in shape to Prometheus's range query.

**WebClient bean configuration** (in `metrics-infra`):

```yaml
# metrics-api/src/main/resources/application.yml (excerpt)
playground:
  metrics:
    prometheus:
      base-url: ${METRICS_PROMETHEUS_BASE_URL:http://prometheus-playground:9090}
      timeout-ms: 8000
    loki:
      base-url: ${METRICS_LOKI_BASE_URL:http://loki-playground:3100}
      timeout-ms: 15000
    spark-gateway:
      base-url: ${METRICS_SPARK_GATEWAY_BASE_URL:http://host.docker.internal:10080}
      probe-cache-ttl-s: 15
```

A `MetricsHttpProperties` `@ConfigurationProperties` POJO in
`metrics-app` binds these. Adapter classes in `metrics-infra` construct
their `WebClient`s via `WebClient.Builder` from a single shared
`HttpClient` (Reactor Netty), with per-adapter `baseUrl` injected.

### 8. Frontend chart library — Recharts

**Decision:** **Recharts** (current stable line, ^2.13.x) for all
dashboard charts and sparklines.

| Concern | Pin |
|---|---|
| Library | `recharts` ^2.13.x |
| Bundle hit estimate | ~80–100 KB gzipped for the chart route's chunk (acceptable; the public `/metrics` chunk does not collide with the BlockNote bundle in M2's `/docs/*` chunk per ADR-12 §3) |
| SSR posture | Charts are rendered client-side; the dashboard page is a `"use client"` component imported by the route handler. SSR skeleton (spec §7.3 initial-load state) is server-rendered as static placeholder boxes (CSS dimensions matching the chart container) — no Recharts on the server. |
| Design-token integration | Recharts SVG primitives accept inline `fill` / `stroke` / `strokeWidth` props bound to CSS variables (`var(--color-chart-primary)`) — same design-token mechanism the M2 docs surface uses per ADR-12 §3 + the design system spec. |
| Composition | Use `LineChart`, `AreaChart`, `BarChart`, `PieChart` directly; do not wrap into a custom abstraction in M5 P0. M5.1 may introduce a `<DashboardChart>` wrapper if reuse pressure justifies it. |

**Considered alternatives (spec §11 Q8):**

- **Visx** (Airbnb's d3-on-React). Rejected — closer to "build your
  own chart library on top of low-level primitives"; M5's 19 widgets
  are all stock chart shapes (line / area / bar / pie / sparkline)
  and don't justify the Visx ceremony. Reach for Visx in M5.1 if a
  custom visualization (e.g., a service-dependency arc diagram) is
  added.
- **Tremor** (React component library for dashboards). Rejected —
  opinionated styling that fights the playground's design tokens;
  fits "ship a dashboard fast" use cases but not "dashboard inside a
  brand-tuned site with shared color tokens".
- **Raw SVG with d3-scale**. Rejected — small bundle (~20 KB), but
  the operator-task of writing 19 widgets from scratch trades engineer
  time for bundle size and the trade is wrong at personal scale.

**Frontend module placement (per ADR-06's FSD layering):**

| Surface | Layer | Path |
|---|---|---|
| `/metrics` page (route handler) | `app/` | `client/src/app/metrics/page.tsx` |
| 19-widget composite + range pills + auto-refresh logic | `widgets/` | `client/src/widgets/metrics-dashboard/` |
| Individual chart components (one per widget kind: heap-line, http-rate-line, host-cpu-sparkline, …) | `features/` | `client/src/features/metrics-chart/{ui,model}/` |
| Service health card | `entities/` | `client/src/entities/service-health/ui/ServiceHealthCard.tsx` |
| Recharts wrapper helpers (`<TokenizedLineChart>`, `<Sparkline>`) | `shared/ui/` | `client/src/shared/ui/charts/` |

### 9. Service "unhealthy" judgment — combine Prometheus `up{}` + `/actuator/health`

**Decision:** the verdict is a function of **both** signals,
short-circuit-favoring `down`:

| Prometheus `up{service="<svc>"}` (last 2 scrape cycles, i.e., ~10 s) | Spring `/actuator/health` reachable? | `/actuator/health` body status | Verdict |
|---|---|---|---|
| All 2 of last 2 → `0` (scrape miss) | n/a | n/a | **`down`** (service likely not running) |
| Last 1 of 2 → `0` (one miss, one hit) | yes | `UP` | **`degraded`** (intermittent reachability) |
| Last 1 of 2 → `0` | yes | `DOWN` / `OUT_OF_SERVICE` | **`down`** |
| All 2 of last 2 → `1` | yes | `UP` | **`up`** |
| All 2 of last 2 → `1` | yes | `DOWN` / `OUT_OF_SERVICE` / non-`UP` | **`degraded`** |
| All 2 of last 2 → `1` | no (5xx / timeout) | n/a | **`degraded`** |

`spark-inference-gateway` is a special case — it has no Spring
Actuator endpoint. Its verdict is composed of:
- `HEAD /v1/models` reachable + 200 → `up`.
- Reachable + non-200 → `degraded`.
- Unreachable / timeout → `down`.
- See §12 for the probe mechanism.

**Implementation:** `HealthVerdict.from(promUp, actuatorBody)` in
`metrics-domain` (pure logic, no Spring). `BuildServicesUseCase`
parallelizes the two probes per service via `Mono.zip` and feeds the
results into `HealthVerdict.from(...)`. For services that route
`/actuator/health` through the gateway (gateway itself, identity-api,
docs-api, rag-chat-api, metrics-api), the call goes to the
service's compose-internal address (`http://identity-api:18081/actuator/health`,
etc.) — **not** via the gateway, to avoid a self-referential loop on
gateway health.

**Why both signals (spec §11 Q9):**

- Prometheus `up{}` alone misses the case where the service is
  reachable on its port but its internal health (DB, Kafka producer)
  has failed — `/actuator/health` catches that.
- `/actuator/health` alone misses the case where the service is
  running but unreachable from the metrics BC (network partition,
  DNS hiccup) — Prometheus `up{}`'s scrape-miss catches that.
- "Both up" is the safest definition of `up`; "either signals trouble"
  surfaces as `degraded`; "both miss" is `down`. The dashboard treats
  `degraded` as a non-blocking warning (yellow), `down` as a blocker
  (red).

**Considered alternative:** treat `up{}` as authoritative and ignore
`/actuator/health`. Rejected — the M2/M3/M4 services' actuator health
indicators (DB connectivity, Kafka producer state) are exactly the
signal the operator wants on the dashboard; ignoring them collapses
the verdict to a TCP-port reachability check.

### 10. PromQL whitelist + mapping — full table in `metrics-domain`

**Decision:** the `PromQlTemplate` constants class in
`metrics-domain` owns the **single source of truth** for the
metric-id → PromQL mapping. The frontend never sees raw PromQL; the
`TimeseriesController` accepts a `metric` query parameter validated
against this table.

**Full table (P0 whitelist):**

| Metric id | PromQL template | `<svc>` substitution? | `<name>` substitution? | Unit | Notes |
|---|---|---|---|---|---|
| `jvm-heap-<svc>` | `jvm_memory_used_bytes{area="heap",service="<svc>"} / 1048576` | yes (ServiceAllowlist) | no | MB | Per-BC JVM heap. |
| `jvm-nonheap-<svc>` | `jvm_memory_used_bytes{area="nonheap",service="<svc>"} / 1048576` | yes | no | MB | Auxiliary; not on dashboard P0 but exposed via `/timeseries`. |
| `jvm-gc-pause-<svc>` | `rate(jvm_gc_pause_seconds_sum{service="<svc>"}[5m]) / rate(jvm_gc_pause_seconds_count{service="<svc>"}[5m])` | yes | no | s | Average GC pause. |
| `jvm-threads-<svc>` | `jvm_threads_live_threads{service="<svc>"}` | yes | no | count | |
| `http-rate-<svc>` | `sum(rate(http_server_requests_seconds_count{service="<svc>"}[1m])) by (service)` | yes | no | req/s | |
| `http-error-rate-<svc>` | `sum(rate(http_server_requests_seconds_count{service="<svc>",status=~"5.."}[1m])) / sum(rate(http_server_requests_seconds_count{service="<svc>"}[1m]))` | yes | no | ratio | |
| `http-latency-p95-<svc>` | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service="<svc>"}[1m])) by (le))` | yes | no | s | |
| `container-cpu-<name>` | `rate(container_cpu_usage_seconds_total{name="<name>"}[1m]) * 100` | no | yes (ContainerAllowlist) | % | |
| `container-mem-<name>` | `container_memory_working_set_bytes{name="<name>"} / 1048576` | no | yes | MB | |
| `container-restart-<name>` | `container_start_time_seconds{name="<name>"}` | no | yes | s | Used to derive restart count via deltas. |
| `host-cpu` | `100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)` | no | no | % | |
| `host-mem-used` | `(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / 1073741824` | no | no | GB | |
| `host-mem-total` | `node_memory_MemTotal_bytes / 1073741824` | no | no | GB | |
| `host-disk-used-pct` | `(1 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"})) * 100` | no | no | % | |
| `host-load-1m` | `node_load1` | no | no | n | |
| `host-load-5m` | `node_load5` | no | no | n | |
| `host-load-15m` | `node_load15` | no | no | n | |
| `spark-latency-p95` | `histogram_quantile(0.95, sum(rate(http_client_requests_seconds_bucket{target="spark-inference-gateway"}[1m])) by (le, uri))` | no | no | s | Per-model series separation is by `uri` label (e.g., `/v1/embeddings` vs `/v1/chat/completions`). |
| `service-up-<svc>` | `up{service="<svc>"}` | yes | no | 0/1 | Used internally by `BuildServicesUseCase`; not exposed on `/timeseries`. |

**`ServiceAllowlist`** (M5 P0 set — `metrics-domain`):

```
gateway, identity-api, docs-api, rag-ingestion-api, rag-chat-api, metrics-api,
prometheus-playground, loki-playground, alloy-playground, cadvisor-playground
```

The 6 BCs + the 4 observability containers (per §17 below — self-monitoring
included in P0). Adding a future BC requires this set to be extended in
the same PR; otherwise a `metric=jvm-heap-foo-api` request returns 400
(per spec §6 + Story 9 in the PRD).

**`ContainerAllowlist`** (M5 P0 set):

```
postgres-playground, redis-playground, kafka-playground, opensearch-playground,
prometheus-playground, loki-playground, alloy-playground, cadvisor-playground,
gateway, identity-api, docs-api, rag-ingestion-api, rag-chat-api, metrics-api
```

All infra + all BC service container names. Adding a new container in a
future milestone requires this set to be extended in that milestone's
PR; otherwise the corresponding `container-cpu-foo` widget returns 400.

**Substitution validation flow** (in `metrics-app`):

```text
1. TimeseriesController receives `metric=container-cpu-postgres-playground`
2. PromQlTemplate.parse(metricId) splits at the last hyphen:
     template = "container-cpu-<name>"
     name     = "postgres-playground"
3. Validates: name ∈ ContainerAllowlist (true → ok).
4. Substitution: PromQL string becomes
     rate(container_cpu_usage_seconds_total{name="postgres-playground"}[1m]) * 100
   — parameterization is **string interpolation after allowlist check only.**
   The raw `metric` query param NEVER reaches the PromQL string.
5. WebClient sends the resolved PromQL to Prometheus.
```

**PromQL injection defense (spec Story 9):** the only point where
caller-supplied data enters the PromQL string is the `<svc>` / `<name>`
substitution. Both are gated by the allowlist sets above. Caller-supplied
`metric` ids that don't match a known template return 400 before any
PromQL is composed. Caller-supplied `range` and `step` are parsed into
typed value objects (`Range`, `Step`) before being formatted into the
range-query URL — no string interpolation of caller-supplied range
values into PromQL strings.

**Considered alternative (spec §11 Q10):** expose raw PromQL via a
power-user query parameter (e.g., `?promql=<raw>`). Rejected
permanently — leaks the entire metric surface (including labels for
other tenants if multi-tenancy ever lands) and creates a query-cost
DoS vector. The whitelist is the contract.

### 11. Loki label set standard — `container`, `service`, `level` (extended with `source`)

**Decision:** Alloy applies the following label set to every log line
shipped to Loki:

| Label | Source | Cardinality | Example value |
|---|---|---|---|
| `container` | `__meta_docker_container_name` (stripped leading `/`) | ~15 (BCs + infra) | `rag-chat-api` |
| `service` | Docker label `service=...` on the container | ~15 | `rag-chat-api` |
| `source` | Static = `docker` (set in `loki.source.docker.labels`) | 1 | `docker` |
| `level` | **NOT a Loki label.** Set at query time via `| json | level=~"WARN|ERROR"` LogQL filter expression. | n/a (high cardinality if labeled) | n/a |

**Why `level` is a LogQL filter, not a Loki label (spec §11 Q11):**

Loki's labels are stored in the inverted index; high-cardinality labels
explode the index size. `level` has ~4 distinct values
(`DEBUG`, `INFO`, `WARN`, `ERROR`) per log line, but multiplied across
~15 containers and ~5 minutes of activity it becomes a large cross-product.
Loki's official advice is "label by stream, filter by content" — `level`
is content, queried via `| json | level=~"WARN|ERROR"`. The M1
ADR-10 JSON logback encoder (inherited by M2-M5) emits `level` as a
JSON field, which `| json` parses for free.

**`container` and `service` are both labels because they're often
identical** — the docker container name is the same as the
`service` Docker label for BC services per ADR-12 §4 + ADR-14 §A. Having
both costs nothing (Loki dedupes identical label pairs) and supports
the rare future case where they diverge (e.g., a sidecar container
whose `service` label points to its parent).

**Considered extension:** add `env` (`dev`/`prod`) and `host`
(physical-machine hostname). Deferred to multi-host M5.x+; M5 P0 is
single-host so both labels are constants and add no query power.

### 12. `spark-inference-gateway` health probe — HEAD `/v1/models` every 15 s + 15 s in-process cache

**Decision:** the probe is **HEAD `http://host.docker.internal:10080/v1/models`**.
Successful 200 (or 204) → `up`. Non-200 → `degraded`. Timeout / connection
refused → `down`. The result is cached in-process via Caffeine for 15
seconds; the cache key is the constant `spark-gateway` (single key —
the BC has one upstream gateway).

| Concern | Pin |
|---|---|
| Mechanism | HEAD `/v1/models` (vLLM's OpenAI-compatible models list — supports HEAD per OpenAPI spec; HEAD returns the same headers as GET but no body, ~5 ms RTT on host loopback) |
| Cadence | Probe on cache miss; cache TTL **15 seconds** (matches the dashboard polling interval — at most one upstream probe per dashboard refresh window per `metrics-api` instance). |
| Cache library | Caffeine `Cache<String, ProbeResult>` (already on the classpath via Spring Boot's caching auto-config) |
| Timeout | 2 seconds (host loopback; anything longer is failure) |
| Latency P95 model is also probed | `/metrics` scrape via Prometheus (Alloy doesn't scrape spark-inference-gateway — that's a host-process outside the compose network). The latency P95 widget instead reads `http_client_requests_seconds_bucket{target="spark-inference-gateway"}` series **from the BC-side `rag-chat-api` Micrometer** (per spec §6 `spark-latency` row in §10 above). This is the indirect path — spark-inference-gateway's own latency is observed via the client-side timing. |
| `modelsLoaded` field on dashboard payload | Derived from GET `/v1/models` (body) — second adapter call, executed only when the dashboard payload is built (~15 s cadence per polling visitor), separate from the HEAD probe. |

**Why HEAD `/v1/models` and not scrape `/metrics` (spec §11 Q12):**

- HEAD is ~5 ms RTT; the `/metrics` Prometheus scrape is ~30 ms RTT
  for vLLM's full metric set. HEAD's 6× speed buys real budget inside
  the dashboard's 400 ms P95.
- HEAD's reachability signal is exactly what the dashboard cell needs
  ("is the gateway answering at all?"). `/metrics` would surface more
  detail but the dashboard doesn't render that detail at P0.
- `/v1/models` is a **stable** OpenAI-compatible endpoint that vLLM
  honors across versions; the `/metrics` endpoint is vLLM-specific and
  could change.

**Two-probe design** (HEAD for liveness + GET for model list)
prevents the every-15s dashboard refresh from incurring a body parse
on every poll. The GET fires lazily inside `BuildDashboardUseCase`
when the spark widget needs the `modelsLoaded` field, and its result
is also cached for 15 s.

**Considered alternative (spec §11 Q12):** also scrape vLLM's
`/metrics` to compose a richer spark widget (queue depth, GPU mem).
Deferred to M5.1 — `spark-inference-gateway` is a host process per
ADR-04, and a Prometheus scrape config that reaches `host.docker.internal`
from Alloy is feasible but ties the dashboard to vLLM's internal
metric names (which churn). The M5 P0 spark widget shows up/degraded/down
+ latency P95 (from client-side timing) + models loaded; that's enough
for the operator to answer "is GPU host healthy".

### 13. Container "unhealthy" judgment — cAdvisor scrape miss + name-not-present

**Decision:** a container is `unhealthy` on the dashboard's
`containers[]` array if **either**:

- cAdvisor stopped emitting metrics for that container name for the
  last 30 seconds (i.e., `container_last_seen{name="<name>"}` is more
  than 30 s old) — indicates the container is no longer running.
- The metrics for the container show `container_tasks_state{name="<name>",state="stopped"} > 0`
  for the last 30 seconds — indicates the container is paused/stopped
  but still tracked.

If the container name is absent from cAdvisor's metric set entirely
(never appeared, or appeared and was removed), it is rendered with
status `down` and a `lastSeen` timestamp of `null`. The dashboard
`containers[]` array's source-of-truth membership is the
`ContainerAllowlist` from §10 — fixed at compile time. A container
that should exist but doesn't (cAdvisor never saw it) is a `down`
verdict.

| Container signal | Verdict |
|---|---|
| cAdvisor reports `container_last_seen` ≤ 30s old + `container_tasks_state{state="running"} > 0` | `up` |
| cAdvisor reports the container but `container_tasks_state{state="running"} == 0` for ≥30s | `down` |
| cAdvisor metrics older than 30 s for this name | `unhealthy` (likely stopped or restarting) |
| cAdvisor has no series for the name at all | `down` |

**Why not `docker inspect`** (the spec §11 Q13 alternative): the BC
doesn't have docker socket access (only cAdvisor and Alloy do, per
§5–§6). Adding a docker-API client to the BC would replicate the
socket-mount privilege surface for no benefit cAdvisor doesn't already
provide.

**Why not Alloy `loki.source.docker` failure signals:** Alloy's log
shipper failures are noisy (a single restart can blip a container's
log cursor temporarily) and aren't a clean container-state signal.
cAdvisor's `container_tasks_state` and `container_last_seen` are
purpose-built for the question.

**Considered alternative (spec §11 Q13):** combine all three signals
(cAdvisor + docker inspect + Alloy). Rejected — adds complexity
without precision; cAdvisor's two metrics fully cover the verdict
space the dashboard renders.

### 14. Auth identifier on logs endpoint — `X-User-Id` from gateway header

**Decision:** confirms the M2/M4 pattern. The logs endpoint requires
the `X-User-Id` header to be present; absence → 401 at the gateway
(the gateway enforces the authentication boundary per ADR-07; the BC
treats `X-User-Id` presence as sufficient — same trust contract as
ADR-09 + ADR-14).

| Concern | Pin |
|---|---|
| Required header | `X-User-Id` (UUID, gateway-injected per ADR-07) |
| Absent → response | 401 at the gateway (rejected before reaching `metrics-api`). The gateway's `SecurityWebFilterChain` allowlist gains a row for `/api/metrics/logs/**` per §G.2 below. |
| Header optionally consumed | `X-User-Sub` (for audit log only — same shape as ADR-14 §15's audit log; the M5 logs endpoint logs at INFO `userId + userSub + serviceFilter + since + matchCount + latencyMs`). |
| Role distinction | **none in P0.** Any signed-in user is an "operator" for the purposes of `/api/metrics/logs`. There is no admin-only role. |
| Future role distinction (P2) | when JeekLee's playground gains real multi-tenant or partial-visibility logs (e.g., `service=docs-api` is anyone-signed-in but `service=identity-api` is admin-only), a role check would land in `MetricsSecurityConfig`. Out of scope for M5 P0. |

**Why no role distinction in P0 (spec §11 Q14):**

The playground has a single operator (JeekLee) and visitors. The
"authenticated" tier is uniformly "anyone who got past Google OAuth".
The logs endpoint's PII concern (server logs may include user emails
in error messages) is addressed by the auth gate alone — anonymous
visitors can't reach the endpoint, and any signed-in visitor is
trusted with the same logs. M5.1's logs UI inherits this default;
when the audience grows, role gating is a follow-up ADR amendment.

### 15. Sidebar "System status" row unlock — same PR as backend M5

**Decision:** the sidebar row activation lands **in the same PR** as
the backend M5 close (or, more precisely, the same milestone-close
window: backend + frontend may be separate PRs against the milestone
branch, but the unlock is **not** delayed past the backend ship).
The row's `🔒 M5` badge drops as soon as the backend dashboard route
is reachable.

| Step | Owner | Sequencing |
|---|---|---|
| 1. Backend `metrics-api` deployed; `/api/metrics/dashboard` returns 200 | backend-implementer (Stage 3) | First — the route must exist before the sidebar can route to it. |
| 2. Frontend `/metrics` page deployed (the widgets render + auto-poll) | frontend-implementer (Stage 3) | Second — depends on step 1's payload shape. |
| 3. Sidebar row's `🔒 M5` badge dropped + click navigates to `/metrics` | frontend-implementer (Stage 3, T8 polish) | Bundled into the same frontend PR as step 2. |

The three steps don't have to be one PR, but they DO have to land in
the same milestone-close window — the sidebar row going active before
the backend is reachable produces 502s for clicks; the backend being
reachable while the sidebar still shows `🔒 M5` is the boring side
of the timing imbalance (operators can still type `/metrics` into the
URL bar).

**Considered alternative (spec §11 Q15):** sequential — ship the
backend in one PR, the frontend in a follow-up PR a day later.
Rejected as the operational default — the milestone-close ritual
("M5 is done") should match the user-visible "the dashboard works"
event; a multi-day split fragments the close.

### 16. `/api/metrics/dashboard` P95 latency target — 400 ms (confirmed)

**Decision:** confirm spec §10's 400 ms target. Rationale:

| Factor | Time budget |
|---|---|
| 19 PromQL queries fan-out via `Mono.zip` | parallel — the slowest single query is the gating factor, not the sum. |
| Per-query upper-bound (PromQL execution + WebClient RTT) | ~80–120 ms typical, ~250 ms tail (the host-disk query is the heaviest, scanning a `node_filesystem_*` series over the range). |
| Reactive composition overhead | ~5 ms |
| JSON serialization of dashboard payload (~8 KB) | ~5 ms |
| Gateway pass-through | ~3 ms |
| **Total P95 budget** | ~250 + 20 = **~270 ms** typical; **400 ms** target with headroom for occasional Prometheus GC pause or HNSW-style internal index sweep. |

**`PromQlBudgetEnforcer` 10 s per-query budget** is a separate
fail-safe — it prevents a single mis-tuned PromQL from blocking the
dashboard, not a P95 target. Widgets that exceed 10 s return
`"degraded": true` in the partial response (per spec §8.2). The P95
target above assumes no widget is in the degraded path.

**Considered alternative (spec §11 Q16):** revise to 600 ms to give
more headroom for Prometheus cold starts. Rejected — at personal
scale Prometheus's working set fits comfortably in memory, cold
starts are sub-second, and the 400 ms target is achievable on the
median path. If implementation reveals it isn't, this ADR is amended
with the looser target rather than letting the spec drift.

### 17. Observability self-monitoring — included in P0 (4 extra cells)

**Decision:** Prometheus + Loki + Alloy + cAdvisor are **4 extra
cells** on the service health grid alongside the 6 BC + 1
spark-inference-gateway cells, for **11 cells total**.

| Cell | Source | Verdict rule |
|---|---|---|
| 1. gateway | Prom `up{service="gateway"}` + `gateway:18080/actuator/health` | §9 |
| 2. identity-api | Prom `up{service="identity-api"}` + `identity-api:18081/actuator/health` | §9 |
| 3. docs-api | Prom `up{service="docs-api"}` + `docs-api:18082/actuator/health` | §9 |
| 4. rag-ingestion-api | Prom `up{service="rag-ingestion-api"}` + `rag-ingestion-api:18083/actuator/health` | §9 |
| 5. rag-chat-api | Prom `up{service="rag-chat-api"}` + `rag-chat-api:18084/actuator/health` | §9 |
| 6. metrics-api | Prom `up{service="metrics-api"}` + `metrics-api:18085/actuator/health` | §9 |
| 7. spark-inference-gateway | HEAD `host.docker.internal:10080/v1/models` | §12 |
| 8. prometheus-playground | `up{service="prometheus-playground"}` (self-scrape via Alloy's observability_self job) + GET `prometheus-playground:9090/-/healthy` 200 | combined rule |
| 9. loki-playground | `up{service="loki-playground"}` + GET `loki-playground:3100/ready` 200 | combined rule |
| 10. alloy-playground | `up{service="alloy-playground"}` + GET `alloy-playground:12345/-/ready` 200 | combined rule |
| 11. cadvisor-playground | `up{service="cadvisor-playground"}` + GET `cadvisor-playground:8080/healthz` 200 | combined rule |

The observability containers have no Spring Actuator — they're not
Spring services. Their secondary signal is each tool's native
readiness endpoint (`-/healthy` for Prometheus, `/ready` for Loki,
`-/ready` for Alloy, `/healthz` for cAdvisor). `BuildServicesUseCase`
dispatches the appropriate readiness probe per service type.

**Why P0 (spec §11 Q17):**

- **The operator needs meta-monitoring.** If the observability stack
  itself dies, all 6 BC cells go `down` (Prometheus stopped scraping
  → `up{}` returns no data → §9 reads "scrape miss" → `down`). The
  operator looking at the dashboard would conclude "every service is
  down" when in fact only the observability stack is. The 4 self-cells
  break that ambiguity — "10 of 11 down, prometheus-playground is
  down" is a useful diagnostic; "all 6 down" without the self-cells
  is misleading.
- **Marginal implementation cost.** The 4 extra probes are 4 extra
  rows in the `BuildServicesUseCase` parallel zip — no new adapter, no
  new schema, no new container. The dashboard's grid layout already
  handles a variable cell count.

**Considered alternative (spec §11 Q17):** defer to M5.1. Rejected
— the cost is small enough that bundling avoids a separate cycle for
a feature that materially improves the dashboard's diagnostic value.

### 18. Env var names + defaults

**Decision:** consolidated table; `metrics-api`'s
`@ConfigurationProperties` binds these under the
`playground.metrics.*` namespace; compose env vars below override.

| Env var | Default | Consumed by | Purpose |
|---|---|---|---|
| `METRICS_API_PORT` | `18085` | `metrics-api` | Server port. Bound to `server.port`. |
| `METRICS_POLL_INTERVAL_S` | `15` | client (frontend, via the dashboard payload's `pollIntervalSeconds` echo field) | Dashboard auto-refresh interval. The backend echoes this so the frontend doesn't hardcode it. |
| `METRICS_SCRAPE_INTERVAL_S` | `5` | Alloy config (templating) | Backend BC + cAdvisor scrape cadence. Host scrape stays at 10 s (hard-coded in `config.alloy` block 3). |
| `METRICS_PROM_RETENTION_DAYS` | `7` | `prometheus-playground` container (`--storage.tsdb.retention.time=${METRICS_PROM_RETENTION_DAYS}d`) | Prometheus retention. |
| `METRICS_LOKI_RETENTION_DAYS` | `3` | `loki-playground` container (`limits_config.retention_period: ${METRICS_LOKI_RETENTION_DAYS * 24}h` — templated at compose start via an entrypoint script, since Loki YAML doesn't directly accept env-var-templated durations) | Loki retention. |
| `METRICS_PROMETHEUS_BASE_URL` | `http://prometheus-playground:9090` | `metrics-api` `PrometheusAdapter` | Prometheus HTTP API base URL. |
| `METRICS_LOKI_BASE_URL` | `http://loki-playground:3100` | `metrics-api` `LokiAdapter` | Loki HTTP API base URL. |
| `METRICS_SPARK_GATEWAY_BASE_URL` | `http://host.docker.internal:10080` | `metrics-api` `SparkGatewayProbeAdapter` | Spark-inference-gateway base URL (per ADR-04). |
| `METRICS_DASHBOARD_RATE_LIMIT_PER_MIN` | `30` | `metrics-api` `RedissonIpRateLimiterAdapter` | Per-IP rate limit on `/api/metrics/dashboard` (spec §8.2). |
| `METRICS_LOGS_RATE_LIMIT_PER_MIN` | `60` | `metrics-api` `RedissonUserRateLimiterAdapter` | Per-user rate limit on `/api/metrics/logs` (spec §8.2). |
| `METRICS_PROMQL_BUDGET_S` | `10` | `metrics-api` `PromQlBudgetEnforcer` | Per-PromQL-query timeout / budget (spec §8.2). |
| `PROMETHEUS_HOST_BIND` | _(unset)_ | `prometheus-playground` compose block | Optional. If set to a port number (e.g., `9090`), the compose binds Prometheus's :9090 to that host port for ad-hoc PromQL CLI access (per spec §4.4). Off by default. |

Default values land in `application.yml`; the compose `environment:`
block on each container references the env-var with the same default
fallback (`${METRICS_API_PORT:-18085}` style). `.env.example` enumerates
every variable per the M0 acceptance criterion.

**Considered alternative (spec §11 Q18):** use a single
`METRICS_RETENTION_DAYS` for both Prometheus and Loki. Rejected — the
two tools have different retention costs and the operator may want to
tune them independently (3 days of Loki logs is plenty for
short-incident triage; 7 days of Prometheus is needed for the 7d range
preset).

### 19. Operator CLI for log tail — `tools/metrics-logs.sh` shipped

**Decision:** ship a minimal shell helper at
`tools/metrics-logs.sh`. The script is a `curl` wrapper that takes
operator-friendly arguments (service name, since duration, search
substring, limit) and emits JSON-formatted log entries or a
human-readable tail.

**Script shape** (infra-implementer transcribes verbatim in Stage 3,
modulo cookie-jar path tuning):

```bash
#!/usr/bin/env bash
# tools/metrics-logs.sh — operator log tail via /api/metrics/logs
# Per ADR-15 §19.
#
# Usage:
#   tools/metrics-logs.sh <service> [since=15m] [search=...] [limit=200]
#
# Requires: ~/.playground/cookies.txt with a valid PLAYGROUND_SESSION cookie
# (obtain via browser login + cookie export).

set -euo pipefail

SERVICE="${1:?usage: $0 <service> [since=15m] [search=...] [limit=200]}"
SINCE="${2:-since=15m}"
SEARCH="${3:-}"
LIMIT="${4:-limit=200}"

BASE="${PLAYGROUND_BASE:-http://localhost:18080}"
COOKIES="${PLAYGROUND_COOKIES:-$HOME/.playground/cookies.txt}"

curl --silent \
  --cookie "$COOKIES" \
  --get "$BASE/api/metrics/logs" \
  --data-urlencode "service=$SERVICE" \
  --data-urlencode "$SINCE" \
  --data-urlencode "$LIMIT" \
  ${SEARCH:+--data-urlencode "$SEARCH"} \
  | jq -r '.entries[] | "\(.ts) [\(.level)] [\(.service)] \(.message)"'
```

**Why ship it (spec §11 Q19):**

- The logs UI is M5.1 (P1). Until it lands, the operator's only way
  to consume `/api/metrics/logs` is `curl + jq`. The shell helper
  removes the boilerplate.
- The cost is one ~30-line script in `tools/`. No CI burden, no
  language runtime, no compile step.
- The pattern matches the project's existing `tools/` ergonomic norm
  (small bash helpers for operator chores).

**Considered alternative:** wait for M5.1's logs UI. Rejected — the
operator already has the use case ("tail rag-chat logs while
debugging a streaming issue"); the script unblocks today's work for
a 30-line cost.

### 20. Docker socket mitigations — formal note in `docs/infra-requirements/be.md`

**Decision:** add a new "M5 Observability Stack" section to
`docs/infra-requirements/be.md` documenting:

- cAdvisor + Alloy both mount `/var/run/docker.sock:ro` (read-only).
- The residual privilege-escalation surface is `docker inspect`-class:
  an attacker who compromises either container can enumerate
  containers, read env vars, and inspect mount paths. They **cannot**
  `docker exec`, `docker run`, or otherwise create/destroy containers
  because the socket is `:ro` (the docker API enforces verb-vs-mode
  on supporting kernels).
- Mitigations applied: read-only mount, no `privileged: true`, no
  host-network binding (cAdvisor + Alloy are compose-internal only).
- Acceptable trade-off for personal-scale single-tenant. Revisit if
  the audience scope grows (rootless docker becomes the next step).
- Disk budget: Prometheus ~3 GB @ 7d, Loki ~2 GB @ 3d, ~5 GB total.
- Memory footprint table: ~600 MB across all 4 observability
  containers.
- Named volume conventions:
  `prometheus-playground-data`, `loki-playground-data`,
  `alloy-playground-data`.

Full text in §G.4 amendment block below.

## Additional decisions (not in spec's question list but architect-owned)

### A. Compose service container names — `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground`, `metrics-api`

**Decision:** the M5 milestone introduces **5 new compose services**.
The 4 observability containers follow the `-playground` suffix
convention used for shared infrastructure (`postgres-playground`,
`redis-playground`, `kafka-playground`, `opensearch-playground`); the
BC service follows the BC-quadruplet naming convention (`metrics-api`,
matching `identity-api`, `docs-api`, `rag-ingestion-api`,
`rag-chat-api`). This is the discipline ADR-12 §A and ADR-13 §B and
ADR-14 §A established.

| Container | Image | Suffix convention | Purpose |
|---|---|---|---|
| `prometheus-playground` | `prom/prometheus:v2.54.1` | shared infra | TSDB + scrape engine |
| `loki-playground` | `grafana/loki:3.2.1` | shared infra | Log TSDB |
| `alloy-playground` | `grafana/alloy:v1.3.1` | shared infra | Unified collector |
| `cadvisor-playground` | `gcr.io/cadvisor/cadvisor:v0.49.1` | shared infra | Per-container metrics |
| `metrics-api` | `playground/metrics-api:<git-sha>` (built from `backend/metrics/metrics-api/Dockerfile`) | BC quadruplet runnable | M5 BC HTTP surface |

**Compose service blocks** (specification — infra-implementer
transcribes verbatim into `infra/docker-compose.yml` in Stage 3;
shown in ship order):

```yaml
  # --- M5: cadvisor-playground (per-container metrics) ---
  cadvisor-playground:
    image: gcr.io/cadvisor/cadvisor:v0.49.1
    container_name: cadvisor-playground
    command:
      - --docker_only=true
      - --store_container_labels=false
      - --whitelisted_container_labels=service
    volumes:
      - /:/rootfs:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /sys:/sys:ro
      - /var/lib/docker:/var/lib/docker:ro
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/healthz || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
    # NO `ports:` — compose-internal only.

  # --- M5: prometheus-playground (TSDB + scrape engine) ---
  prometheus-playground:
    image: prom/prometheus:v2.54.1
    container_name: prometheus-playground
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=${METRICS_PROM_RETENTION_DAYS:-7}d
      - --web.enable-remote-write-receiver
    volumes:
      - ../infra/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-playground-data:/prometheus
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
    # Optional host bind via env (spec §4.4 opt-in CLI access)
    ${PROMETHEUS_HOST_BIND:+ports:}
    ${PROMETHEUS_HOST_BIND:+  - "${PROMETHEUS_HOST_BIND}:9090"}

  # --- M5: loki-playground (log TSDB) ---
  loki-playground:
    image: grafana/loki:3.2.1
    container_name: loki-playground
    command: ["-config.file=/etc/loki/loki-config.yml"]
    volumes:
      - ../infra/observability/loki-config.yml:/etc/loki/loki-config.yml:ro
      - loki-playground-data:/loki
    environment:
      METRICS_LOKI_RETENTION_DAYS: ${METRICS_LOKI_RETENTION_DAYS:-3}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3100/ready || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
    # NO `ports:` — compose-internal only.

  # --- M5: alloy-playground (collector — scrape + log shipping + host exporter) ---
  alloy-playground:
    image: grafana/alloy:v1.3.1
    container_name: alloy-playground
    command:
      - run
      - /etc/alloy/config.alloy
      - --storage.path=/var/lib/alloy/data
      - --server.http.listen-addr=0.0.0.0:12345
    volumes:
      - ../infra/observability/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - alloy-playground-data:/var/lib/alloy/data
    depends_on:
      prometheus-playground:
        condition: service_healthy
      loki-playground:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:12345/-/ready || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
    # NO `ports:` — compose-internal only.

  # --- M5: metrics-api (BC quadruplet runnable per ADR-01 v2 + ADR-15) ---
  metrics-api:
    build:
      context: ../backend
      dockerfile: metrics/metrics-api/Dockerfile
    container_name: metrics-api
    depends_on:
      redis-playground:
        condition: service_healthy
      prometheus-playground:
        condition: service_healthy
      loki-playground:
        condition: service_healthy
      gateway:
        condition: service_started
    extra_hosts:
      - "host.docker.internal:host-gateway"   # per ADR-04 — reach spark-inference-gateway
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-default}
      REDIS_HOST: ${REDIS_HOST:-redis-playground}
      REDIS_PORT: ${REDIS_PORT:-6379}
      METRICS_API_PORT: ${METRICS_API_PORT:-18085}
      METRICS_PROMETHEUS_BASE_URL: ${METRICS_PROMETHEUS_BASE_URL:-http://prometheus-playground:9090}
      METRICS_LOKI_BASE_URL: ${METRICS_LOKI_BASE_URL:-http://loki-playground:3100}
      METRICS_SPARK_GATEWAY_BASE_URL: ${METRICS_SPARK_GATEWAY_BASE_URL:-http://host.docker.internal:10080}
      METRICS_DASHBOARD_RATE_LIMIT_PER_MIN: ${METRICS_DASHBOARD_RATE_LIMIT_PER_MIN:-30}
      METRICS_LOGS_RATE_LIMIT_PER_MIN: ${METRICS_LOGS_RATE_LIMIT_PER_MIN:-60}
      METRICS_PROMQL_BUDGET_S: ${METRICS_PROMQL_BUDGET_S:-10}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:18085/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
    # NO `ports:` — compose-internal only.

volumes:
  prometheus-playground-data:
  loki-playground-data:
  alloy-playground-data:
```

**Notable absences from `metrics-api` `depends_on`:** no
`postgres-playground` (BC has no Postgres schema), no
`kafka-playground` (no Kafka surface), no `docs-api` /
`rag-ingestion-api` / `rag-chat-api` / `identity-api` (the BC reads
their metrics indirectly via Prometheus, not directly via HTTP). The
dependency graph is the shallowest of any M5 service — only
Prometheus, Loki, Redis, gateway, and the host inference gateway.

### B. Library versions

Inherited from M1/M2/M3/M4 where applicable; M5-specific additions
listed:

| Coordinate | Version source / pin | Why |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-webflux` | Spring Boot **3.3.x BOM** (inherited) | Reactive controllers + `WebClient` for Prometheus/Loki adapters |
| `org.springframework.boot:spring-boot-starter-security` | Spring Boot 3.3.x BOM | `SecurityWebFilterChain` for the logs-endpoint auth gate (`X-User-Id` presence rule) |
| `org.springframework.boot:spring-boot-starter-actuator` | Spring Boot 3.3.x BOM | `/actuator/health` + `/actuator/prometheus` exposure (Alloy scrapes this) |
| `io.micrometer:micrometer-registry-prometheus` | inherited via actuator | Prometheus-format metrics scrape target |
| `org.redisson:redisson-spring-boot-starter` | **3.34.x** (same as M3/M4 per ADR-13 §C and ADR-14 §B) | Per-IP + per-user `RRateLimiter` token buckets |
| `com.github.ben-manes.caffeine:caffeine` | Spring Boot 3.3.x BOM | In-process cache for the spark-gateway probe (§12) |
| `com.fasterxml.jackson.module:jackson-module-kotlin` (or `jackson-module-parameter-names` for plain Java) | Spring Boot 3.3.x BOM | DTO deserialization for Prometheus/Loki JSON responses |
| `com.github.tomakehurst:wiremock-standalone` | **3.x** (same as M4) | Test scope — stub Prometheus + Loki HTTP APIs |
| `org.testcontainers:testcontainers` | **1.20.x** (same as M2-M4) | Test scope — boot real Prometheus + Loki + Alloy + cAdvisor for integration |
| **NOT used:** `org.springframework.boot:spring-boot-starter-data-jpa` | n/a | M5 has no Postgres schema |
| **NOT used:** `org.springframework.boot:spring-boot-starter-jdbc` | n/a | Same |
| **NOT used:** `org.springframework.kafka:spring-kafka` | n/a | M5 has no Kafka surface |
| **NOT used:** `org.springframework.modulith:spring-modulith-events-*` | n/a | Same |
| **NOT used:** `io.github.resilience4j:resilience4j-*` | n/a | M5 has no upstream service to circuit-break — Prometheus/Loki are local-network reads; spark-gateway is health-probed (not flooded with traffic); failures degrade per-widget via `PromQlBudgetEnforcer` rather than via breaker. (Reconsider if observability stack 5xx becomes a chronic issue.) |

### C. HTTP error response shape

Per spec §5 + ADR-11. All errors return the unified envelope:

| HTTP status | When | Body (ADR-11 unified shape) |
|---|---|---|
| 400 | `metric=` query param not in `PromQlTemplate` table, OR `<svc>`/`<name>` substitution value not in allowlist | `{"errorCode":"METRICS-VALIDATION-001","message":"Unknown metric id","timestamp":"...","path":"...","traceId":"..."}` |
| 400 | `service=` query param on logs route not in `ServiceAllowlist` | `{"errorCode":"METRICS-VALIDATION-002","message":"Unknown service","timestamp":"...","path":"...","traceId":"..."}` |
| 400 | `range=` not in `{15m,1h,6h,24h,7d}` | `{"errorCode":"METRICS-VALIDATION-003","message":"Invalid range","timestamp":"...","path":"...","traceId":"..."}` |
| 401 | `X-User-Id` absent on `/api/metrics/logs/**` | `{"errorCode":"AUTH-401-001","message":"Authentication required","timestamp":"...","path":"...","traceId":"..."}` (gateway returns this before reaching `metrics-api`) |
| 429 | IP-bucket empty on `/dashboard`, OR user-bucket empty on `/logs` | `{"errorCode":"METRICS-RATE-LIMIT-001","message":"Rate limit exceeded","retryAfterSeconds":<n>,"timestamp":"...","path":"...","traceId":"..."}` + `Retry-After: <n>` header |
| 503 | Prometheus or Loki unreachable for `/dashboard` (all 19 PromQL queries failed) | `{"errorCode":"METRICS-UPSTREAM-DOWN-001","message":"Metrics backend unavailable","retryAfterSeconds":30,"timestamp":"...","path":"...","traceId":"..."}` |
| 200 + `"degraded": true` widgets | Per-widget PromQL exceeded 10 s budget (§B) | The dashboard payload's per-widget object carries `"degraded": true` and a null `value` (per spec §8.2). |

The `200 + degraded` path is deliberately **not** a 207 multi-status
— the dashboard is a single resource, and the per-widget degradation
is a payload concern, not an HTTP-status concern. This matches the
frontend's per-widget error-state UI (spec §7.3 Story 15).

### D. Concurrent request handling — no special locking

**Decision:** M5 has no per-user concurrent-stream cap (unlike M4's
`RLock` per user). The endpoints are plain request-response — Spring
WebFlux's Reactor pipeline handles parallelism naturally, and there
is no expensive per-user resource to protect (the per-IP and per-user
rate limits in §C provide cost protection).

A single visitor opening 10 dashboard tabs creates 10 polling streams
at 15 s each → ~40 requests/min, well above the 30/min/IP rate limit
→ they get 429s after the 30th request. The rate limit is the
denominator; no lock is needed.

### E. Database connection pool — none

**Decision:** the BC has no `DataSource` bean. Spring Boot's `DataSource`
auto-configuration would attempt to construct one if
`spring-boot-starter-data-jpa` or `spring-boot-starter-jdbc` were on
the classpath — neither is. As a defensive `application.yml` entry
(belt and suspenders):

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
```

This makes any future accidental dependency pull-in fail at startup
with a clear "no DataSource configured" message rather than silently
auto-construct an embedded H2 (Spring Boot's well-known footgun).

### F. Reactor scheduler policy — default WebFlux

**Decision:** default WebFlux behavior. No custom `Schedulers.parallel()`
or `Schedulers.boundedElastic()` wrapping. All M5 I/O is HTTP-bound
via `WebClient` and Redisson-on-Lettuce, both of which are
non-blocking by default. The Reactor pipeline composes naturally on
the WebFlux event-loop threads.

The one exception is the WebClient body decode for large Loki
responses (200 log lines × ~500 bytes each ≈ 100 KB JSON). Jackson
decoding on a 100 KB body takes <10 ms — staying on the event loop is
fine. If profiling shows this becoming an event-loop blocker, a
`.publishOn(Schedulers.boundedElastic())` wrap on the Loki adapter is
the localized fix; not a P0 concern.

### G. Self `/actuator/health` reachable by Alloy

**Decision:** `metrics-api` exposes `/actuator/health` +
`/actuator/prometheus` on its compose-internal port 18085. Alloy's
`backend_bcs` scrape job (per §5 above) targets
`metrics-api:18085/actuator/prometheus` — making the metrics BC visible
in its own dashboard via the §9 service-health-grid combined rule
(cell #6 in §17's table).

This is a meta-monitoring property: if `metrics-api` itself crashes,
the dashboard goes 503 (no one to answer `/api/metrics/dashboard`)
but the operator can still see in Prometheus's web UI (when
`PROMETHEUS_HOST_BIND` env-var is set, per §3) that
`up{service="metrics-api"} == 0`. The 4 observability cells from §17
+ this cell give the operator a near-complete picture even when the
BC itself is down.

### H. Test fixture strategy — WireMock + Testcontainers (mirrors ADR-13 §13 / ADR-14 §16)

**Decision:** same shape as the M3/M4 ADRs.

| Test layer | Stack | What it covers |
|---|---|---|
| `metrics-domain` unit tests | Pure JUnit 5 | `PromQlTemplate.parse(metricId)` correctness on every entry in §10's table, `ServiceAllowlist` / `ContainerAllowlist` membership checks, `HealthVerdict.from(...)` combinatoric correctness (the 7 rows in §9's table). No Spring, no I/O. |
| `metrics-app` slice tests | `@SpringBootTest(classes = {...})` + Mockito for all ports | Use-case behavior — given a mocked `PrometheusPort` returning known JSON, asserts the `DashboardResponse` shape and the `"degraded": true` propagation. `RateLimitGuard` tests use Mockito on the rate-limiter ports. |
| `metrics-infra` integration tests | **Testcontainers** for: `prom/prometheus:v2.54.1` (with a pre-seeded WAL fixture), `grafana/loki:3.2.1` (with pre-pushed log lines), `redis:7-alpine` (Redisson rate-limit). **WireMock** for `spark-inference-gateway`. | Real `WebClient` against real Prometheus/Loki; asserts the response decoding for each PromQL query shape; asserts the spark-gateway HEAD probe cache TTL. |
| `metrics-api` end-to-end tests | Full `@SpringBootTest` with all Testcontainers + WireMock | Hits the 4 actual routes via `WebTestClient`; asserts response shape, P95 latency (loose — CI environment variability), the 401 / 400 / 429 paths, and the per-widget degraded path (force one PromQL to exceed `METRICS_PROMQL_BUDGET_S=1` to trigger). |

**WireMock stub files:**
- `metrics-infra/src/test/resources/wiremock/prometheus/query-up.json` — golden response for `up{}` queries.
- `metrics-infra/src/test/resources/wiremock/prometheus/query-range-jvm-heap.json` — golden response for a JVM heap range query.
- `metrics-infra/src/test/resources/wiremock/loki/query-range-rag-chat.json` — golden response for a 200-line, 15-minute Loki query.
- `metrics-infra/src/test/resources/wiremock/spark-inference-gateway/v1-models-head-200.json` — 204 HEAD response.

**Why not Spring Cloud Contract:** same rationale as ADR-13 §13 +
ADR-14 §16 — Prometheus/Loki are external (their HTTP APIs are stable
per their respective project versioning policies). WireMock with
response bodies copied from real Prometheus samples is the
proportionate fixture strategy.

### I. Amendments to transverse ADRs + sibling docs

Four amendments. Inline notes appended to each affected file so
readers see the M5 change in context. (Spec §13 counts ADR-15 itself
as item 0; the count of "4 amendments" refers to the four files
modified beyond ADR-15.)

#### G.1. Amendment to `docs/roadmap.md` §M5

The §M5 block is rewritten to retire acceptance bullets 4 + 5 and
freshen the bullet list to reflect the spec's actual scope (public
dashboard + 4-container observability stack).

> **Goal:** Give the operator (the user) a single public dashboard
> for the health of the playground stack, container resources, JVM
> state, HTTP traffic, host metrics, and the spark-inference-gateway.
>
> **Acceptance:**
> - [ ] `metrics` BC queries Prometheus + Loki for stack health
>   (PromQL via HTTP API; LogQL via HTTP API).
> - [ ] Frontend dashboard at `/metrics` renders ~19 widgets covering
>   service health, host metrics, JVM heap, HTTP request rate, and
>   spark-inference-gateway state — 15-second polling.
> - [ ] Dashboard is **public** (per ADR-09); the logs API endpoint
>   (`/api/metrics/logs/**`) is authenticated.
> - [ ] **Observability stack added** — Prometheus + Loki + Alloy +
>   cAdvisor (4 new compose containers); concrete image pins in
>   ADR-15 §3–§6.
> - [ ] Sidebar "System status" row unlocks on M5 ship.
>
> **Dependencies:** M0, M1, M2, M3, M4.
>
> **Notes:** Infra-heavy: needs the docker socket mounted (read-only)
> into both `cadvisor-playground` and `alloy-playground`; that
> constraint goes into `docs/infra-requirements/be.md` for the M5
> cycle (per ADR-15 §G.4 below). Polling interval, retention periods,
> and per-IP / per-user rate limits are tunable env vars (per ADR-15
> §18).
>
> **Public surface (per ADR-09 + ADR-15 amendment):** The metrics
> dashboard is a **public** page. We accept that this exposes
> container / cluster health to anyone — it's an intentional "live
> workshop" signal, not a leak. `/api/metrics/dashboard`,
> `/api/metrics/services`, `/api/metrics/timeseries` are public read
> endpoints; `/api/metrics/logs/**` is authenticated (logs may carry
> PII / error context not appropriate for anon viewers — per ADR-09
> + ADR-15 §G.2). Per-IP rate limit 30/min on `/api/metrics/dashboard`;
> per-user rate limit 60/min on `/api/metrics/logs`.

The interim "Dashboard is only reachable behind login" framing
(bullet 4) and "No new external dependency is introduced" framing
(bullet 5) are **superseded permanently** — the dashboard is public
per ADR-09 + design system spec §11, and the 4-container
observability stack is the deliberate external-dependency addition
this milestone carries.

#### G.2. Amendment to ADR-09 (Public Route Policy)

ADR-09's route classification table gains **one row** in the
authenticated section. The existing `GET /api/metrics/**` public row
stays — it covers `/dashboard`, `/services`, `/timeseries`. The new
row narrows it via the most-specific-match-wins rule.

> Amendment (2026-05-19, ADR-15): the M5 Metrics BC ships with a
> mixed public / authenticated surface — the dashboard / services /
> timeseries endpoints are public per ADR-09's original
> `GET /api/metrics/**` row, **and** a new authenticated row is added
> for `GET /api/metrics/logs/**`. The most-specific-match wins —
> the new row takes precedence for the `logs` subtree without
> displacing the public row that still covers everything else under
> `/api/metrics/**`.
>
> ### Route classification table — new row (post-ADR-15)
>
> The authenticated section gains:
>
> | Pattern | Class | Reason |
> |---|---|---|
> | `GET /api/metrics/logs/**` | **authenticated** | Logs may surface user PII, error stack traces, or content not appropriate for anon viewers. Per-user rate-limit 60/min via Redisson `RRateLimiter`. |
>
> The existing public `GET /api/metrics/**` row is **unchanged in
> wording**; its scope is now interpreted as
> "`/api/metrics/dashboard`, `/api/metrics/services`,
> `/api/metrics/timeseries` — anything under `/api/metrics/**` that
> isn't matched by a more-specific authenticated row" per ADR-09's
> "most-specific-match wins" rule.
>
> ### Anonymous identity contract — unchanged
>
> ADR-09's original §"Anonymous identity contract" remains in force.
> `/api/metrics/dashboard`, `/api/metrics/services`,
> `/api/metrics/timeseries` are anonymous-OK — `X-User-Id` may be
> absent, and the metrics BC does not consult it for those routes
> (the dashboard's data has no user-scope component). The `PLAYGROUND_ANON`
> cookie isn't consulted by the metrics BC at all — per-IP rate
> limiting on `/dashboard` uses `X-Forwarded-For` directly (per ADR-09
> + ADR-12 §7's anonymous-read pattern).
>
> ### Auth-lock badge — does NOT apply to `/metrics`
>
> ADR-14's auth-lock badge convention (`🔒 Sign in` on the sidebar
> for shipped-but-anon-locked routes) does **not** apply to the
> `System status` row. `/metrics` is public; the sidebar row goes
> from `🔒 M5` (milestone-lock, pre-ship) to no-badge (active,
> unconditional click → `/metrics`) when M5 ships. There is no
> middle state. This matches ADR-14's forward note "M5 Metrics is
> unaffected".
>
> ### Per-user rate limit on logs endpoint
>
> The `/api/metrics/logs/**` row carries a per-user rate limit of
> **60 requests/min/user**, enforced by `metrics-api` (not the
> gateway — the gateway only sees the auth header; the bucket is
> per-user). Backing: Redisson `RRateLimiter`, key
> `metrics:bucket:user:{userId}:logs` (per ADR-15 §C + §18).
>
> See `docs/adr/15-m5-metrics.md` §14 + §18 + §C for the full
> specification.

(Note: ADR-09's existing 2026-05-17 ADR-12 amendment block and
2026-05-18 ADR-14 amendment block remain in force. ADR-15's
amendment composes additively with both — the docs allowlist rows,
the chat auth-only row, and the auth-lock badge convention are all
untouched. M5 only adds one new row to the authenticated section.)

#### G.3. Amendment to ADR-00 (Overview)

Two changes:

1. Add a new row to the index table:

> | 15 | `15-m5-metrics.md` | **(per-milestone, M5)** Metrics BC implementation — Spring WebFlux 4-route HTTP surface (`/api/metrics/{dashboard,services,timeseries,logs}`) on port 18085 (gateway-routable), 4-container observability stack (Prometheus v2.54.1 + Loki 3.2.1 + Alloy v1.3.1 + cAdvisor v0.49.1) added to compose, PromQL whitelist constants in `metrics-domain` (no raw PromQL exposed), Recharts as the dashboard chart library, public dashboard + authenticated logs endpoint (one new row in ADR-09's authenticated section), no Postgres schema (stateless BC), no Kafka surface, docker sockets mounted read-only on cAdvisor + Alloy with the privilege-escalation surface documented in `docs/infra-requirements/be.md`. Amends ADR-09 (logs auth row) + `docs/roadmap.md` §M5 (retire bullets 4+5) + ADR-00 (index + module count) + `docs/infra-requirements/be.md` (new M5 section). |

2. Bump the "Module count" line. ADR-00 currently reads:

> Total at M5: gateway + shared-kernel + (5 BCs × 4 modules) = **22 production modules** + `buildSrc` convention plugins.

M5 introduces the 6th BC quadruplet (`metrics-api`, `metrics-app`,
`metrics-domain`, `metrics-infra`) — 4 more modules. The bumped
line reads:

> Total at M5: gateway + shared-kernel + (6 BCs × 4 modules) = **26 production modules** + `buildSrc` convention plugins. Only the **six** `*-api` modules (plus gateway) bind a JVM port (gateway 18080; identity-api 18081; docs-api 18082; rag-ingestion-api 18083 — actuator-only; rag-chat-api 18084; metrics-api 18085); the rest are Java libraries linked into the BC's `*-api` fat jar.

The Topic-to-BC matrix's `metrics.snapshot.captured` row in ADR-00
is **removed** — that was a forward-looking placeholder under the
old "polling + Redis cache" framing for M5. The current spec (§3 +
this ADR) confirms M5 publishes no events; the row is stale.

The module dependency graph ASCII diagram in ADR-00 already shows
`metrics-api` at port `18086` — the diagram is updated to port
**18085** per §1 above. (One character change in the ASCII art.)

3. Add a new row to the "Topic-to-BC matrix" table noting that
M5 contributes nothing:

> *(M5's `metrics` BC publishes no events and consumes none — stateless request-response BC. No row added to this table by ADR-15.)*

#### G.4. Amendment to `docs/infra-requirements/be.md`

A new section "M5 Observability Stack (2026-05-19, ADR-15)" is added
covering docker socket mounts, disk budget, memory footprint, named
volume conventions, and the host metrics rationale.

> ## M5 Observability Stack
>
> ADR-15 introduces 4 new compose containers (Prometheus + Loki +
> Alloy + cAdvisor) to support the system-status dashboard. Operator
> considerations:
>
> ### Docker socket access
>
> - `cadvisor-playground` and `alloy-playground` both require
>   `/var/run/docker.sock` mounted **read-only**.
> - This is a residual privilege-escalation surface: an attacker who
>   compromises either container can list containers, read env vars,
>   and inspect mount paths via `docker inspect`-class API verbs.
>   They **cannot** `docker exec`, `docker run`, or otherwise mutate
>   container state — the socket is `:ro` and docker's API enforces
>   verb-vs-mode on supporting kernels (Linux 5.x+).
> - Mitigations applied: read-only mount only, no `privileged: true`
>   flag, no host-network binding (both containers are
>   compose-internal only).
> - Acceptable trade-off for personal-scale single-tenant.
>   Revisit if the audience scope grows; rootless docker is the
>   next-step alternative (current cost: breaks the
>   `postgres-playground` volume mount semantics; deferred to P2).
>
> ### Disk budget
>
> - Prometheus 7-day retention: **~3 GB** on average (the Spark
>   host's typical metric volume; tail of ~5 GB during heavy chat
>   activity).
> - Loki 3-day retention: **~2 GB** on average.
> - Total observability disk allocation: **~5 GB**.
> - Named volumes:
>   - `prometheus-playground-data` (mounted at `/prometheus` in the
>     prometheus container)
>   - `loki-playground-data` (mounted at `/loki` in the loki
>     container)
>   - `alloy-playground-data` (mounted at `/var/lib/alloy/data` for
>     log-cursor state)
> - cAdvisor needs no named volume (stateless; in-memory metrics
>   only).
> - Retention is env-var-tunable: `METRICS_PROM_RETENTION_DAYS`
>   (default `7`), `METRICS_LOKI_RETENTION_DAYS` (default `3`).
>
> ### Memory footprint
>
> | Container | Approximate RSS | Notes |
> |---|---|---|
> | `prometheus-playground` | ~150 MB | Single-binary; TSDB working set scales with active series — ~50k series at personal scale. |
> | `loki-playground` | ~100 MB | Single-binary `target=all` mode. |
> | `alloy-playground` | ~200 MB | Hosts the scrape DAG + log-shipping pipeline + host exporter; largest of the four. |
> | `cadvisor-playground` | ~150 MB | v2 stat collector working set. |
> | **Total** | **~600 MB** | Personal-scale; fits comfortably on the Spark host alongside the existing BC stack. |
>
> ### Host metrics
>
> `alloy-playground` runs the `prometheus.exporter.unix` component,
> exposing host CPU / memory / disk / load average to Prometheus
> via Alloy's local scrape. This replaces the need for a separate
> `node_exporter` container — one fewer process, one fewer image to
> pin, one less attack surface. The host metrics scrape interval is
> 10 s (per `config.alloy` block 3 in ADR-15 §5); the BC scrapes
> are 5 s.
>
> ### Network exposure
>
> All 4 observability containers join the existing `playground`
> compose network and **none** binds to a host port by default.
> Direct access from the host is via `docker exec` only. An
> exception is the optional `PROMETHEUS_HOST_BIND` env-var (per
> ADR-15 §3) which conditionally binds Prometheus's :9090 to the
> host for ad-hoc PromQL CLI use.
>
> ### Operational reset
>
> To wipe observability state without affecting BC state:
>
> ```bash
> docker compose down prometheus-playground loki-playground alloy-playground
> docker volume rm playground_prometheus-playground-data playground_loki-playground-data playground_alloy-playground-data
> docker compose up -d prometheus-playground loki-playground alloy-playground
> ```
>
> Postgres / Redis / Kafka volumes are unaffected. The next
> dashboard load shows a 7-day window's worth of fresh-collected
> data only (gap for the wiped retention window).
>
> See `docs/adr/15-m5-metrics.md` §3 + §4 + §5 + §6 + §A + §20 for
> the full specification.

## Open questions deferred beyond M5

These are explicitly out of scope for M5 P0 and noted here so the
next milestone's architect doesn't re-litigate them:

- **Mobile layout (M5.1)** — spec §2 deferred. P0 punts with a
  single-column sparkline-only degrade for <720 px.
- **Logs UI (M5.1)** — a `/metrics` tab consuming
  `/api/metrics/logs`. P0 ships the endpoint and the
  `tools/metrics-logs.sh` helper (§19) only.
- **Domain metrics widgets (M5.1)** — rag-chat tokens/hr,
  rag-ingestion chunks/day, docs published today, M3 ingestion
  latency P95, M4 chat completion success rate. P0 ships
  infrastructure metrics only.
- **Custom range picker (M5.1)** — date/time range beyond the 5
  presets.
- **Settings panel (M5.1)** — polling interval, retention override,
  widget hide/show.
- **Alerts / notifications (P2)** — Slack/email/push. P0 is
  polling-only read surface.
- **Historical comparison (P2)** — week-over-week deltas.
- **Multi-host monitoring (P2)** — second host (GPU box vs CPU box).
- **Tracing (P2)** — Tempo / OpenTelemetry trace pipeline. M5 P0
  does not enable Alloy's OTLP receiver (`4317` is documented as
  "disabled in P0" per §5).
- **Grafana embed / sidecar (P2)** — Grafana container with iframe
  embed.
- **Logs full-text search across long ranges (P2)** — LogQL native
  search exposed for short ranges only (`since=15m` etc.) in M5
  P0/M5.1.
- **Custom dashboard authoring / multi-page (P2)** — fixed 19-widget
  single-page surface in M5 P0/M5.1.
- **Postgres rollup for metrics history (M5.x+)** — if the operator
  wants a per-user / per-day Prometheus-independent rollup (the M4
  PRD §"Out of scope re-revision" hint), this introduces the
  `metrics.usage_daily` schema and an ADR-05 amendment. Out of scope
  for M5 P0; the Prometheus TSDB is the source of truth.
- **`spark-inference-gateway` `/metrics` scrape (M5.1)** — Alloy
  scrape config pointed at `host.docker.internal:10080/metrics` for
  vLLM-internal metrics (queue depth, GPU mem). P0 uses HEAD probe +
  client-side latency only (§12).
- **Role-gated logs (P2)** — admin-only vs operator-only log
  visibility splits. P0 treats any signed-in caller as operator
  (§14).
- **Multi-instance `metrics-api`** — single-instance assumption is
  baked into the in-process Caffeine cache for the spark-gateway
  probe (§12). Multi-instance fan-out moves the cache to Redis or
  switches to a stale-while-revalidate Caffeine policy; P2+ concern.

## Diagrams

### M5 dashboard request flow (HTTP read-only, parallel PromQL composition)

```
Browser (anon OR authenticated, /metrics page)
   │
   │  GET /api/metrics/dashboard?range=1h     (15s auto-poll)
   ▼
gateway (18080)  ──── (no auth required; rate-limit by X-Forwarded-For)
   │
   ▼
metrics-api (18085)  WebFlux DashboardController
   │
   ▼
┌──────────────────────────────────────────────────────────┐
│  BuildDashboardUseCase (metrics-app)                     │
│                                                          │
│   1. ip-rate-limit check (IpRateLimitPort, 30/min/IP)    │
│   2. parallel fan-out via Mono.zip:                      │
│      ┌─ PromQL: up{service="gateway"}                    │
│      ├─ PromQL: up{service="identity-api"}               │
│      ├─ ... (11 service-health probes)                   │
│      ├─ PromQL: container_cpu_usage_seconds_total{...}   │
│      ├─ PromQL: container_memory_working_set_bytes{...}  │
│      ├─ ... (8 container resource probes)                │
│      ├─ PromQL: node_cpu_seconds_total{...}              │
│      ├─ PromQL: node_memory_*                            │
│      ├─ ... (4 host probes)                              │
│      ├─ PromQL: jvm_memory_used_bytes{area="heap",...}   │
│      ├─ ... (4 JVM heap probes)                          │
│      ├─ PromQL: rate(http_server_requests_*)             │
│      ├─ ... (3 HTTP rate probes)                         │
│      ├─ HEAD spark-inference-gateway/v1/models  (cached) │
│      └─ GET  spark-inference-gateway/v1/models  (cached) │
│   3. apply PromQlBudgetEnforcer (10s per query)          │
│   4. compose DashboardResponse                           │
└──────────────────────────────────────────────────────────┘
   │           │            │                │
   ▼           ▼            ▼                ▼
prometheus  redis      loki (logs route only)  spark-inference-gateway
(playground)            (host.docker.internal:10080)
   │            │
   │            │  rate-limit token bucket
   │            │  key: metrics:bucket:ip:{ip}:dashboard
   │
   │  19 PromQL queries via /api/v1/query
   │  Aggregated TSDB views: up{}, jvm_*, http_*, container_*, node_*

Failure paths:
  - Pre-stream:  400 / 401 (logs only) / 429 / 503 → standard ADR-11 JSON body
  - Per-widget timeout: budget enforcer marks widget "degraded": true
                        in the partial response (status 200)
```

### M5 observability stack data flow (push-based)

```
                                                +-- /actuator/prometheus
gateway:18080 ──────────────────────────────────┤
identity-api:18081 ─────────────────────────────┤
docs-api:18082 ─────────────────────────────────┤  Alloy
rag-ingestion-api:18083 ────────────────────────┤  scrapes
rag-chat-api:18084 ─────────────────────────────┤  every
metrics-api:18085 ──────────────────────────────┘  5s

cadvisor-playground:8080 ───────────────────────── Alloy scrapes every 5s

host (/proc, /sys via prometheus.exporter.unix) ── Alloy scrapes every 10s

prometheus-playground:9090, loki-playground:3100,  Alloy scrapes every 10s
alloy-playground:12345, cadvisor-playground:8080   (self-monitoring)

                          ▼
                          Alloy
                          ▼
                          prometheus.remote_write
                          ▼
                          prometheus-playground:9090 (TSDB, 7d retention)
                          ▲
                          │  PromQL: /api/v1/query, /api/v1/query_range
                          │
                          metrics-api (read-only WebClient)

docker logs (every container)
                          ▼
                          Alloy (discovery.docker + loki.source.docker)
                          ▼
                          loki.write
                          ▼
                          loki-playground:3100 (TSDB, 3d retention)
                          ▲
                          │  LogQL: /loki/api/v1/query_range
                          │
                          metrics-api (read-only WebClient, auth-gated)
```

## Consequences

- Positive: M5 ships with the same module-quadruplet shape every
  prior BC uses. backend-implementer has one mental model across BCs;
  code-reviewer has one shape to check. The metrics BC is the
  shallowest of any quadruplet (no Postgres, no Kafka, no
  shared-kernel events) — easy to audit.
- Positive: The 4-container observability stack is the
  conventional industry shape (Prometheus + Loki + collector +
  cAdvisor) and the operator's transferable skill — anyone familiar
  with Grafana Cloud / on-prem Grafana stacks recognizes the
  pieces. No bespoke metric system.
- Positive: All caller-supplied PromQL inputs are gated by allowlist
  constants in `metrics-domain` — no string-interpolated raw PromQL
  ever touches the network. The injection defense is structural, not
  filter-based.
- Positive: The dashboard is public — anonymous visitors get the
  same view as the operator, reinforcing the "live workshop signal"
  framing of ADR-09. The one authenticated row (`/logs/**`) keeps
  the PII-bearing surface gated.
- Positive: Recharts is the smallest reasonable bundle hit
  (~80–100 KB) for a 19-widget dashboard. The public `/metrics`
  route's chunk is independent of the M2 BlockNote chunk, so the
  dashboard does not pay the editor bundle cost.
- Positive: The single-file `config.alloy` keeps the observability
  config in one readable place — operator can grep for "where is
  cAdvisor scraped" and get an answer in one file.
- Positive: Observability self-monitoring (4 extra cells per §17) is
  meta-diagnostic — the operator can distinguish "all services
  down" from "Prometheus is down so I see no scrape data".
- Negative: Two new docker socket mounts (cAdvisor + Alloy) — even
  read-only, this is two new escalation surfaces. The mitigation is
  per `docs/infra-requirements/be.md` (read-only + no privileged +
  compose-internal only); the residual risk is documented and
  accepted.
- Negative: 4 new containers + ~600 MB additional RSS on the Spark
  host. Personal scale fits but is no longer trivial. Disk budget
  ~5 GB at retention defaults. ADR-15 §G.4 documents both as
  explicit budget choices.
- Negative: M5 is the first BC where backend availability does NOT
  imply dashboard availability — if Prometheus or Loki is down, the
  dashboard returns 503 even though every BC is reachable. The
  meta-monitoring cells (§17) surface this distinction.
- Negative: `PromQlBudgetEnforcer`'s 10s per-query budget is a
  best-effort cap — a wedge between the user's "I want it now" and
  Prometheus's "give me a moment for this aggregation". Wedged via
  partial-response `"degraded": true` UI per spec §7.3, not by
  failing the dashboard.
- Negative: The single-instance `metrics-api` assumption (in-process
  Caffeine cache for spark-gateway probe) limits horizontal scale.
  Fine at personal scale; multi-instance is P2+ work that moves the
  cache to Redis.
- Negative: WebFlux + WebClient parallel composition is a different
  shape than the M1/M2/M3 servlet stacks. backend-implementer needs
  the Reactor mental model for the dashboard use case, even though
  the BC otherwise has the simplest surface in the project.

## Related

- ADR-00 (amended below by this ADR — index row + module count bump)
- ADR-01 v2 — quadruplet module layout, `metrics-api` port 18085
- ADR-02 — DDD layering rules the metrics quadruplet inherits
- ADR-03 — Kafka envelope / topic conventions (M5 makes **NO** topic
  changes; no amendment — the BC neither produces nor consumes)
- ADR-04 — Spring AI + spark-inference-gateway at
  `host.docker.internal:10080` (M5 consumes the gateway's health
  probe + the BC-side Micrometer histograms; no Spring AI starter
  needed since M5 makes no inference calls itself)
- ADR-05 — Postgres + pgvector (M5 makes **NO** schema changes; no
  amendment — the BC is stateless)
- ADR-07 — gateway routing for `/api/metrics/**` → `metrics-api:18085`
- ADR-08 — inter-service comms (M5 makes **NO** new HTTP exception;
  the BC reads Prometheus + Loki + spark-inference-gateway, all
  non-BC external dependencies of the BC)
- ADR-09 (amended below by this ADR) — public route policy: one new
  authenticated row for `/api/metrics/logs/**` added; existing public
  `/api/metrics/**` row stays for dashboard/services/timeseries
- ADR-10 §11 — Logback JSON encoder (inherited by M2-M5; the
  `level`-as-content rationale in §11 above relies on the JSON
  log shape this ADR pins)
- ADR-11 — shared exception hierarchy used in all metrics error
  responses
- ADR-12 — M2 Docs ADR (OpenSearch container introduction precedent
  for adding multiple new compose containers in one ADR)
- ADR-13 §F + ADR-14 §F — DDL precedents (NOT applied to M5; no
  schema)
- ADR-14 §2 — port table; this ADR supersedes the "18086 metrics-api
  planned" forward-looking entry, canonicalizing port **18085**
- `docs/prd/M5-metrics.md` — M5 PRD (the user stories this ADR
  resolves §"Open questions for the implementer" against)
- `docs/superpowers/specs/2026-05-19-m5-metrics-design.md` — M5 spec
  (the 20 open questions this ADR closes; the 4 supersession
  amendments this ADR carries)
- `docs/roadmap.md` §M5 (amended below by this ADR — acceptance
  bullets 4+5 retired, bullet list refreshed)
- `docs/infra-requirements/be.md` (amended below by this ADR — new
  M5 Observability Stack section)
