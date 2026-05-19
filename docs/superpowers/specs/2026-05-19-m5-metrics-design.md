# Spec: M5 — Metrics BC (design)

**Date:** 2026-05-19 (v1)
**Status:** Draft (brainstorming output)
**Audience:** the PM agent who will write `docs/prd/M5-metrics.md` when the M5 cycle starts; the architect agent who will write the per-milestone ADR `docs/adr/15-m5-metrics.md`; the product-designer agent who will produce `docs/design/M5-metrics.md` + Figma frames; the human reviewer.

**Relationship to other docs:**
- Supersedes the M5 stub in `docs/roadmap.md` (which stays as the one-paragraph summary).
- **Will amend** `docs/roadmap.md` §M5 — acceptance bullet 4 ("Dashboard is only reachable behind login") + bullet 5 ("No new external dependency") are stale and superseded by this spec (public dashboard + Prometheus/Loki/Alloy/cAdvisor stack adopted). Bullet 4 was already contradicted by the §M5 Public surface block in the same file; this spec resolves the contradiction by formally retiring bullet 4 and replacing bullet 5.
- **Will amend** `docs/adr/09-public-route-policy.md` — add one row in the authenticated section: `POST? GET /api/metrics/logs/**` → authenticated. The existing `GET /api/metrics/**` public row stays for everything except `logs/**`.
- References, does not supersede: ADR-04 (`spark-inference-gateway` at `host.docker.internal:10080`). ADR-13 + ADR-14 (the per-milestone ADRs that already set up `/actuator/prometheus` on rag-ingestion and rag-chat — M5 just consumes those endpoints).
- Will be the canonical input for the M5 PRD (Stage-1 PM run, future cycle).

## 0. Terminology

- **Observability stack** = the 4 new containers introduced by M5: Prometheus + Loki + Alloy + cAdvisor.
- **Collector** = `alloy-playground`. Single agent doing both Prometheus scrape coordination and Loki log shipping (replaces the need for separate Promtail / node_exporter processes).
- **`metrics` BC** = the new Spring Boot quadruplet (api/app/domain/infra) that queries Prometheus + Loki via their HTTP APIs and exposes a higher-level stat API to the frontend.
- **Dashboard** = the single `/metrics` page (also surfaced as sidebar "System status" row). NOT a Grafana dashboard — we don't run Grafana.
- **Service health** = a binary status (`up` / `down` / `degraded`) per playground service, derived from a combination of Prometheus's `up` metric and Spring Actuator `/actuator/health` signals.
- **Widget** = one chart, sparkline, or status card on the dashboard. There are ~19 widgets in M5 P0.
- **Range** = the lookback window for time-series widgets. Selectable from 5 presets (15m / 1h / 6h / 24h / 7d), default 1h.

## 1. Purpose

Pin the bounded context, container stack, data flow, HTTP surface, PromQL/LogQL mapping table, UX surfaces, and amendments to upstream docs needed to ship M5 — a public read-only dashboard for playground operator (and any visitor) to see system health, container resources, JVM state, HTTP request rates, host CPU/mem/disk, and `spark-inference-gateway` status, refreshed via 15-second client polling.

This spec **does not** describe per-widget visual design (Stage-2 design / Figma), library version pins (per-milestone ADR), or the M5.1 follow-ons (mobile layout, log search UI, domain metrics — see §2).

## 2. Scope summary

### In scope (P0)
- **`metrics` BC** as a 4-module quadruplet (`metrics-{api,app,domain,infra}`) per ADR-01 v2.
- **Observability stack containers** added to `infra/docker-compose.yml`: `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground`.
- **Public dashboard** at `/metrics` route + sidebar "System status" row unlocks (route is public per ADR-09; the sidebar row becomes always-active after M5 ships).
- **Range presets** 15m / 1h / 6h / 24h / 7d; default 1h; URL-shareable via `?range=Xh`.
- **15-second client polling** of `GET /api/metrics/dashboard?range=Xh`. Manual `⟳` button + "Updated Ns ago" indicator.
- **4 backend routes**: `/api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries`, `/api/metrics/logs` (last is auth-only).
- **Service health grid** — 6 cells (gateway, identity-api, docs-api, rag-ingestion, rag-chat-api, spark-inference-gateway).
- **Container resource snapshot** — CPU% + memory used/limit per playground container (sourced from cAdvisor).
- **Host metrics** — CPU%, mem used/total, disk used%, 1/5/15-minute load average (sourced from Alloy's unix exporter component).
- **JVM heap charts** — 4 line charts (one per Spring Boot BC: rag-chat, docs-api, identity-api, rag-ingestion).
- **HTTP request rate charts** — 3 line charts (gateway, rag-chat, docs-api — the request-heavy ones; rag-ingestion is Kafka-driven, no HTTP request rate to chart).
- **`spark-inference-gateway` widgets** — latency P95 line chart (BGE-M3 + Qwen3-32B as 2 series) + loaded-models card.
- **Logs API endpoint** (`/api/metrics/logs`) — authenticated, returns JSON entries; **no UI for logs in P0**.
- **Loki log collection** — Alloy ships `docker logs` to Loki; retention 3 days. Logs are persisted in P0 even without a UI consuming them.

### Deferred to M5.1 (P1, ship if cycle has slack)
- Mobile layout (≤719 px).
- Logs UI — a tab on `/metrics` that exposes `GET /api/metrics/logs` with filter + search + tail. Auth-gated tab.
- Domain metrics widgets — rag-chat tokens/hr, rag-ingestion chunks/day, docs published today, M3 ingestion latency P95, M4 chat completion success rate.
- Custom range picker (date/time range) beyond the 5 presets.
- Settings panel for polling interval, retention overrides, etc.

### Out of scope (P2 — future milestones)
- Alerts / notifications.
- Historical comparison (e.g., "this week vs last week").
- Multi-host monitoring.
- Tracing (no Tempo, no OpenTelemetry trace pipeline).
- Grafana embed or sidecar.
- Logs full-text search across long time ranges (Loki's LogQL native search is exposed for short ranges only).
- Custom dashboard authoring / multi-page dashboards.

## 3. Bounded Context: metrics

- **책임 (Responsibility):** owns the HTTP surface that the frontend dashboard consumes. Composes PromQL + LogQL queries against the observability-stack APIs and returns business-friendly JSON. Owns the metric-id → query mapping table (so frontend never sees raw PromQL).
- **소유 데이터:** none. The metrics BC has **no Postgres schema**. All persistent data lives in Prometheus (TSDB) and Loki (logs TSDB) — these are the source of truth.
- **외부 의존성:**
  - `prometheus-playground` HTTP API (`/api/v1/query`, `/api/v1/query_range`) — read-only.
  - `loki-playground` HTTP API (`/loki/api/v1/query_range`) — read-only.
- **누가 metrics BC를 호출하나:** Gateway → `metrics-api` for all `/api/metrics/**` traffic. No other BC calls it.
- **이벤트:** publishes none, consumes none. Stateless request-response BC.

## 4. Observability stack

### 4.1 New containers (4)

| Container | Image | Compose-internal port | Purpose |
|---|---|---|---|
| `prometheus-playground` | `prom/prometheus:v2.54.x` (M5 ADR pins exact) | `9090` | TSDB + scrape engine. Scrapes Alloy + cAdvisor + every BC's `/actuator/prometheus`. |
| `loki-playground` | `grafana/loki:3.x` | `3100` | Log TSDB. Receives logs pushed by Alloy. |
| `alloy-playground` | `grafana/alloy:1.x` | `12345` (UI), `4317` (OTLP receiver if used) | Unified collector. Coordinates Prometheus scrape, ships docker logs to Loki, exposes host metrics via the `prometheus.exporter.unix` component. Replaces Promtail + node_exporter. |
| `cadvisor-playground` | `gcr.io/cadvisor/cadvisor:v0.49.x` | `8080` | Per-container CPU / memory / IO / network metrics. Scraped by Prometheus. Requires read-only mount of `/var/run/docker.sock`. |

### 4.2 Scrape configuration (Prometheus + Alloy)

Alloy is the orchestrator. Its config (sketch):

```hcl
// alloy config — Grafana Alloy River syntax (concrete pin in ADR-15)

// 1. Backend BC scrape
prometheus.scrape "backend_bcs" {
  job_name = "playground-bcs"
  targets = [
    { __address__ = "gateway:8080",        service = "gateway",        __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "identity-api:18081",  service = "identity-api",   __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "docs-api:18082",      service = "docs-api",       __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "rag-ingestion:18083", service = "rag-ingestion",  __metrics_path__ = "/actuator/prometheus" },
    { __address__ = "rag-chat-api:18084",  service = "rag-chat-api",   __metrics_path__ = "/actuator/prometheus" },
  ]
  scrape_interval = "5s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 2. cAdvisor scrape
prometheus.scrape "cadvisor" {
  job_name = "cadvisor"
  targets = [{ __address__ = "cadvisor-playground:8080" }]
  scrape_interval = "5s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 3. Host metrics (replaces node_exporter)
prometheus.exporter.unix "host" { }
prometheus.scrape "host" {
  targets    = prometheus.exporter.unix.host.targets
  scrape_interval = "10s"
  forward_to = [prometheus.remote_write.local.receiver]
}

// 4. Local Prometheus (push)
prometheus.remote_write "local" {
  endpoint { url = "http://prometheus-playground:9090/api/v1/write" }
}

// 5. Docker log shipping to Loki
discovery.docker "containers" { host = "unix:///var/run/docker.sock" }
loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.docker.containers.targets
  labels     = { source = "docker" }
  forward_to = [loki.write.local.receiver]
}
loki.write "local" {
  endpoint { url = "http://loki-playground:3100/loki/api/v1/push" }
}
```

Prometheus itself runs with minimal scrape config (just enough to scrape Alloy's own metrics) and accepts remote_write from Alloy. Concrete config files land via ADR-15.

### 4.3 Retention

- **Prometheus**: 7 days (`--storage.tsdb.retention.time=7d`). Env var `METRICS_PROM_RETENTION_DAYS` for override.
- **Loki**: 3 days. Env var `METRICS_LOKI_RETENTION_DAYS` for override.
- Both are personal-scale defaults. ADR-15 may revise.

### 4.4 Network + security

- All 4 new containers join the existing `playground` compose network.
- cAdvisor needs `/var/run/docker.sock` read-only mount (security-significant — flag in M5 PRD).
- Alloy needs `/var/run/docker.sock` for the `discovery.docker` component (also read-only).
- Prometheus + Loki + Alloy + cAdvisor are **not exposed to the host** — accessed only through `metrics-api` proxying. (Operator may also bind Prometheus :9090 to host for ad-hoc PromQL CLI — env-var-gated, off by default.)

## 5. HTTP surface

### 5.1 The 4 routes

```
GET /api/metrics/dashboard?range=15m|1h|6h|24h|7d
  → bundled dashboard payload (~19 widgets' worth of data in one JSON).
  Public.

GET /api/metrics/services
  → service health grid only (cheap; cached aggressively).
  Public.

GET /api/metrics/timeseries?metric=<id>&range=<r>&step=<s>
  → one chart's time series. `metric` is whitelisted (see §6).
  Public.

GET /api/metrics/logs?service=<id>&since=<duration>&search=<query>&limit=<n>
  → Loki LogQL result. Authenticated only (gateway rejects 401 if X-User-Id absent).
  No P0 UI consumes this; surface exists for ad-hoc CLI access + M5.1 logs UI.
```

### 5.2 `/api/metrics/dashboard` response shape

```json
{
  "fetchedAt": "2026-05-19T07:42:13Z",
  "range": "1h",
  "services": [
    { "name": "gateway",           "status": "up", "since": "2026-05-19T04:10:02Z", "uptimeSec": 12731, "image": "playground/gateway:0.4.1" },
    { "name": "identity-api",      "status": "up", "since": "...", "uptimeSec": ...,   "image": "..." },
    { "name": "docs-api",          "status": "up", "since": "...", "uptimeSec": ...,   "image": "..." },
    { "name": "rag-ingestion",     "status": "up", "since": "...", "uptimeSec": ...,   "image": "..." },
    { "name": "rag-chat-api",      "status": "up", "since": "...", "uptimeSec": ...,   "image": "..." },
    { "name": "spark-inference",   "status": "degraded", "latencyP95Ms": 3400, "note": "elevated latency" }
  ],
  "containers": [
    { "name": "postgres-playground", "cpuPct": 4.2, "memUsedMb": 182, "memLimitMb": 512, "restartCount": 0 },
    { "name": "redis-playground",    "cpuPct": 0.8, "memUsedMb":  42, "memLimitMb": 256, "restartCount": 0 },
    { "name": "kafka-playground",    "cpuPct": 11.5,"memUsedMb": 620, "memLimitMb": 1024,"restartCount": 0 },
    { "name": "opensearch-playground","cpuPct": 8.3,"memUsedMb": 480, "memLimitMb": 1024,"restartCount": 0 },
    { "name": "prometheus-playground","cpuPct": 1.1,"memUsedMb": 110, "memLimitMb": 512, "restartCount": 0 },
    { "name": "loki-playground",     "cpuPct": 0.9, "memUsedMb": 95,  "memLimitMb": 512, "restartCount": 0 },
    { "name": "alloy-playground",    "cpuPct": 2.4, "memUsedMb": 86,  "memLimitMb": 256, "restartCount": 0 },
    { "name": "cadvisor-playground", "cpuPct": 1.8, "memUsedMb": 64,  "memLimitMb": 256, "restartCount": 0 }
  ],
  "host": {
    "cpuPct": 18.2,
    "memUsedGb": 12.4,
    "memTotalGb": 64,
    "diskUsedPct": 42,
    "diskUsedGb": 420,
    "diskTotalGb": 1000,
    "loadAvg": [1.2, 0.8, 0.6]
  },
  "sparkGateway": {
    "url": "host.docker.internal:10080",
    "status": "up",
    "latencyP95Ms": 340,
    "modelsLoaded": ["BGE-M3", "Qwen3-32B"]
  },
  "jvm": [
    { "service": "rag-chat-api",  "heapUsedMb": 420, "heapMaxMb": 1024, "threads": 48, "gcPauseP95Ms": 12 },
    { "service": "docs-api",      "heapUsedMb": 280, "heapMaxMb": 1024, "threads": 32, "gcPauseP95Ms":  9 },
    { "service": "identity-api",  "heapUsedMb": 180, "heapMaxMb":  512, "threads": 24, "gcPauseP95Ms":  7 },
    { "service": "rag-ingestion", "heapUsedMb": 220, "heapMaxMb": 1024, "threads": 36, "gcPauseP95Ms": 10 }
  ],
  "httpRate": [
    { "service": "gateway",      "rps": 2.4, "errorRate": 0.0 },
    { "service": "rag-chat-api", "rps": 0.8, "errorRate": 0.0 },
    { "service": "docs-api",     "rps": 1.6, "errorRate": 0.01 }
  ]
}
```

Time-series data for charts (heap history, request rate history, latency history) is **not** in the dashboard payload. The frontend fetches each chart's series separately via `/api/metrics/timeseries` to parallelize and keep the dashboard payload small. Working assumption: dashboard payload ≤ 8 KB; each timeseries payload 1–10 KB depending on `step`.

### 5.3 `/api/metrics/timeseries` response shape

```json
{
  "metric": "jvm-heap-rag-chat",
  "range": "1h",
  "step": "30s",
  "series": [
    { "label": "heapUsed",  "points": [[1715763600, 380], [1715763630, 392], ...] },
    { "label": "heapMax",   "points": [[1715763600,1024], [1715763630,1024], ...] }
  ],
  "unit": "MB"
}
```

The frontend chart library renders `points` directly (timestamp → x, value → y).

### 5.4 `/api/metrics/logs` response shape (auth-only)

```json
{
  "entries": [
    { "ts": "2026-05-19T07:41:58.234Z", "service": "rag-chat-api", "level": "INFO", "message": "..." },
    { "ts": "2026-05-19T07:41:57.812Z", "service": "rag-chat-api", "level": "WARN", "message": "..." }
  ],
  "hasMore": false,
  "nextCursor": null
}
```

P0: endpoint exists, returns 401 for anon. Used by ad-hoc CLI (`curl --cookie ... http://.../api/metrics/logs?service=rag-chat&since=15m`). No P0 UI.

## 6. PromQL / LogQL mapping (metric id → query template)

The `metrics` BC owns this table. Frontend sends opaque metric ids; BC translates. P0 whitelist:

| Metric id | PromQL template (range vector) | Unit |
|---|---|---|
| `jvm-heap-<svc>` | `jvm_memory_used_bytes{area="heap",service="<svc>"}` / `1048576` | MB |
| `jvm-gc-<svc>` | `rate(jvm_gc_pause_seconds_sum{service="<svc>"}[5m]) / rate(jvm_gc_pause_seconds_count{service="<svc>"}[5m])` | s |
| `http-rate-<svc>` | `sum(rate(http_server_requests_seconds_count{service="<svc>"}[1m])) by (service)` | req/s |
| `http-error-<svc>` | `sum(rate(http_server_requests_seconds_count{service="<svc>",status=~"5.."}[1m])) / sum(rate(http_server_requests_seconds_count{service="<svc>"}[1m]))` | ratio |
| `container-cpu-<name>` | `rate(container_cpu_usage_seconds_total{name="<name>"}[1m]) * 100` | % |
| `container-mem-<name>` | `container_memory_working_set_bytes{name="<name>"}` / `1048576` | MB |
| `host-cpu` | `100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)` | % |
| `host-mem` | `(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / 1073741824` | GB |
| `host-disk` | `(1 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"})) * 100` | % |
| `spark-latency` | `histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{target="spark-inference-gateway"}[1m]))` | s |

`<svc>` and `<name>` are validated against an allowlist before substitution (defense against PromQL injection from URL params).

For service health (`up{service="..."}` boolean) the BC composes multiple queries in `BuildDashboardUseCase` and combines results into the `services` array.

Logs (LogQL):

```
{container="<service>-playground"} |~ "<search>" | json
```

`<service>` validated against the same service allowlist.

## 7. UX surfaces

### 7.1 `/metrics` page (desktop ≥720 px)

ASCII wireframe (Stage-2 design produces the polished Figma):

```
┌───┬───────────────────────────────────────────────────────────────────┐
│ ▣ │ Home / System status                          [⌘K]   [👤]         │
│   ├───────────────────────────────────────────────────────────────────┤
│ H │ Range: [15m] [1h●] [6h] [24h] [7d]          Updated 4s ago [⟳]   │
│ D ├───────────────────────────────────────────────────────────────────┤
│ C │ Service Health                                                    │
│ ▣ │ ┌──────────┬──────────┬──────────┬──────────┬──────────┬───────┐ │
│   │ │✅ gateway │✅ identity│✅ docs   │✅ rag-ing│✅ rag-cht│⚠️ spark│ │
│   │ └──────────┴──────────┴──────────┴──────────┴──────────┴───────┘ │
│   │                                                                   │
│   │ Host                                                              │
│   │ ┌─CPU 18%─┐ ┌─Mem 12/64GB─┐ ┌─Disk 42%─┐ ┌─Load 1.2 0.8 0.6─┐   │
│   │ │ sparkln │ │ sparkln      │ │ pie/bar  │ │ 3 numbers         │   │
│   │ └─────────┘ └──────────────┘ └──────────┘ └───────────────────┘   │
│   │                                                                   │
│   │ JVM heap per BC                                                   │
│   │ ┌rag-chat───┐ ┌docs-api───┐ ┌identity───┐ ┌rag-ingest─┐           │
│   │ │420/1024MB │ │280/1024MB │ │180/512MB  │ │220/1024MB │           │
│   │ │<line chart│ │<line chart│ │<line chart│ │<line chart│           │
│   │ └───────────┘ └───────────┘ └───────────┘ └───────────┘           │
│   │                                                                   │
│   │ HTTP request rate (req/s)                                         │
│   │ ┌gateway────┐ ┌rag-chat───┐ ┌docs-api───┐                        │
│   │ │2.4 rps    │ │0.8 rps    │ │1.6 rps    │                        │
│   │ │<line chart│ │<line chart│ │<line chart│                        │
│   │ └───────────┘ └───────────┘ └───────────┘                        │
│   │                                                                   │
│   │ spark-inference-gateway                                           │
│   │ ┌─Latency P95 (BGE-M3 / Qwen3-32B)──┐ ┌─Models loaded──┐         │
│   │ │ <stacked line chart, 2 series>     │ │ BGE-M3 ✅      │         │
│   │ │                                     │ │ Qwen3-32B ✅   │         │
│   │ └────────────────────────────────────┘ └────────────────┘         │
│   │                                                                   │
└───┴───────────────────────────────────────────────────────────────────┘
```

19 widgets total: 1 service grid + 4 host + 4 JVM heap + 3 HTTP rate + 2 spark + 1 container resource table (collapsible by default? — Stage-2 decision). Polish + spacing tokens are Stage-2 design's job.

### 7.2 Range + refresh UX

- **Range preset pills** at the top of the content area. 5 options; default `1h`; current selection has filled accent background. Click = update `?range=Xh` URL param + refetch all 4 routes + restart the 15s timer.
- **"Updated Ns ago" indicator** on the right side, counter-style (increments every second from last fetch). Manual `⟳` button next to it triggers an immediate refetch + resets the timer.
- **Auto-refresh** runs in background, 15s interval, no pause/resume in P0.

### 7.3 Loading / error states

| State | Trigger | Render |
|---|---|---|
| Initial load | First fetch in flight | Skeleton placeholders for every widget. |
| Subsequent refresh | Background refetch in flight | Existing widget data stays visible; the `⟳` icon spins; "Updated Ns ago" doesn't tick. |
| Stale (one widget) | Single timeseries fetch returns 5xx | That widget shows `⚠ Failed to refresh` overlay with retry icon; other widgets unaffected. |
| Stale (whole dashboard) | `/api/metrics/dashboard` returns 5xx 3 times in a row | Top banner: "Couldn't reach metrics service. Retrying in 30s." Polling pauses; manual `⟳` always available. |
| Prometheus down | `metrics` BC returns 503 from `/dashboard` | Same as "Stale (whole dashboard)" — frontend can't distinguish; just shows the banner. |
| Loki down | `/api/metrics/logs` returns 503 | Auth-only endpoint, no P0 UI — no impact on dashboard. |

### 7.4 Sidebar "System status" row state transition (M5 ship)

| Visitor state | Before M5 ship | After M5 ship |
|---|---|---|
| Anon | `🔒 M5` (milestone-lock; click no-op) | Active, no badge; click → `/metrics` (public page, no login) |
| Signed-in | `🔒 M5` (same) | Active, no badge; click → `/metrics` |

The auth-lock variant (`🔒 Sign in`) introduced for M4 is **not used here** — `/metrics` is public; the row routes unconditionally.

### 7.5 Mobile

P0 punts. The desktop dashboard at 1440 wide assumes a wide layout (3-4 widgets per row). On <720 px the page degrades to a single-column stack with sparklines only (no full charts). M5.1 produces a real mobile layout.

## 8. Auth + cost protection

### 8.1 Public route allowlist amendment (ADR-09)

ADR-09 §"Route classification" — the existing row `GET /api/metrics/**` stays public BUT a new row for `logs` lands in the authenticated section:

| Method | Path | Audience | Note |
|---|---|---|---|
| `GET /api/metrics/dashboard`, `/api/metrics/services`, `/api/metrics/timeseries` | public | Read-only system status. Polling-friendly. |
| `GET /api/metrics/logs/**` | **authenticated** | Server logs may reveal user PII or error context not appropriate for anon viewers. |

### 8.2 Cost protection

Personal scale; minimal need. P0 measures:
- **Per-IP rate limit** on `/api/metrics/dashboard`: 30 requests/min/IP. Token-bucket via Redisson (same Redis instance as M3/M4).
- **PromQL query cap**: `metrics` BC enforces a 10-second per-request budget when composing the dashboard's PromQL queries. Anything beyond that returns a partial response with `"degraded": true` on the slow widget.
- **No additional cost protection on `/api/metrics/services` or `/api/metrics/timeseries`** — they're light enough.
- **`/api/metrics/logs`**: per-user rate limit 60 requests/min (logs are heavier).

### 8.3 cAdvisor + docker socket security note

Both cAdvisor and Alloy require `/var/run/docker.sock` mounted (read-only). This is a privilege escalation surface — anyone who exploits cAdvisor or Alloy can list/inspect all containers. Mitigations in M5 PRD:
- Mount the socket **read-only**.
- Don't expose cAdvisor/Alloy to the host network.
- Document in `docs/infra-requirements/be.md` as a deliberate trade-off (personal-scale; the alternative is a rootless docker setup which has its own complications).

## 9. Markdown / chart / log feature scope

- **No Markdown rendering** on `/metrics`. The page is structured data only.
- **Chart library**: choose between Recharts / Visx / Tremor / raw SVG in ADR-15. Working assumption: **Recharts** (smallest learning curve, plays well with React Server Components for SSR, design token integration via CSS variables).
- **Sparklines**: small inline SVG without axes/labels (e.g., the host CPU widget). Same library or a tiny custom `<svg>` component.
- **Logs (P0 endpoint, no UI)**: API returns JSON; LogQL query is `{container="<svc>-playground"} |~ "<search>" | json`; line count cap 200 per request.

## 10. Non-functional requirements

- **`/api/metrics/dashboard` P95 latency ≤ 400 ms** (with 19 PromQL queries in parallel). Working target; ADR-15 may revise.
- **`/api/metrics/timeseries` P95 latency ≤ 200 ms** per single chart.
- **`/api/metrics/logs` P95 latency ≤ 600 ms** for a 200-line, 15-minute-range query.
- **15s polling cadence** generates ~4 requests/min/visitor per page open. Acceptable for personal scale.
- **Alloy scrape interval 5s** for backend BCs + cAdvisor; 10s for host metrics.
- **Prometheus retention 7d**; Loki retention 3d. Disk usage budget: ~5 GB total (Prometheus ~3 GB, Loki ~2 GB).
- **Observability self-monitoring**: Alloy + Prometheus + Loki + cAdvisor each emit their own `/metrics` Prometheus endpoint. The `metrics` BC dashboard SHOULD include these in the service health grid (P0 — extra cells beyond the 6 BC cells) OR defer to M5.1. Decision in ADR-15.
- **No alerting in P0**. Polling-only read surface.

## 11. Open questions for the per-milestone ADR (M5)

The M5 ADR (`docs/adr/15-m5-metrics.md`) must close at least these:

1. **Port assignment** for `metrics-api` (working candidate 18085 — confirm against M0–M4 port pins).
2. **Module quadruplet wiring** — `-api` (WebFlux controllers), `-app` (PromQL/LogQL assembly use cases + rate-limit guard), `-domain` (value objects + query template constants), `-infra` (Prometheus WebClient + Loki WebClient adapters).
3. **Prometheus image + version pin** (`prom/prometheus:v2.54.x` vs latest).
4. **Loki image + version pin** (`grafana/loki:3.x` line).
5. **Alloy image + version pin** + the exact River config file structure (single file vs include split).
6. **cAdvisor image + version pin** + privileged-vs-readonly socket trade-off.
7. **HTTP client choice** for PromQL/LogQL: Spring `WebClient` (consistent with M2/M3/M4) vs Prometheus official Java client. Default: WebClient.
8. **Chart library on frontend**: Recharts vs Visx vs Tremor vs raw SVG.
9. **Service "unhealthy" judgment rule**: Prometheus `up{} == 0` (scrape miss) vs Spring Actuator `/actuator/health != UP` vs both — pick combination.
10. **PromQL whitelist + mapping** finalized as a constants class in `metrics-domain` (full table beyond §6's sketch).
11. **Loki label set standard**: `container`, `service`, `level`. Confirm or extend.
12. **`spark-inference-gateway` health probe mechanism**: HEAD `/v1/models` vs scrape `/metrics` vs both.
13. **Container "unhealthy" judgment rule**: cAdvisor stops returning metrics vs docker inspect status vs Alloy `loki.source.docker` failing.
14. **Auth identifier on logs endpoint**: confirm `X-User-Id` from gateway header (same pattern as M2/M4); operator = anyone signed in (no role distinction in P0).
15. **Sidebar "System status" row unlock coordination**: backend M5 PR + frontend row-activation PR — same PR or sequential?
16. **`/api/metrics/dashboard` P95 latency target** — confirm 400 ms or revise.
17. **Observability self-monitoring** — include Prometheus + Loki + Alloy + cAdvisor in the service health grid (extra cells) or defer to M5.1.
18. **Concrete env var names + defaults** for retention (`METRICS_PROM_RETENTION_DAYS`, `METRICS_LOKI_RETENTION_DAYS`), polling cadence (`METRICS_POLL_INTERVAL_S`), scrape interval (`METRICS_SCRAPE_INTERVAL_S`).
19. **Operator CLI for log tail** (since UI is P1) — nice-to-have helper script in `tools/`?
20. **Docker socket mitigations** — formal note in `docs/infra-requirements/be.md` for the M5 cycle.

## 12. Acceptance criteria (refinement of `roadmap.md` M5)

### Observability stack
- [ ] `prometheus-playground`, `loki-playground`, `alloy-playground`, `cadvisor-playground` defined in `infra/docker-compose.yml`.
- [ ] `docker compose up` boots all 4 containers without error; their actuator/health-equivalent endpoints respond 200.
- [ ] Alloy scrapes every backend BC's `/actuator/prometheus` at 5s cadence; Prometheus's web UI at :9090 shows the 5 BC jobs as `UP`.
- [ ] Loki receives docker logs for every playground container; `loki.source.docker` reports zero errors.
- [ ] Prometheus retains 7 days; Loki retains 3 days. Verified via env-var override test.

### `metrics` BC routes
- [ ] `GET /api/metrics/dashboard?range=1h` returns the JSON shape in §5.2; P95 latency ≤ 400 ms over 1 minute of polling.
- [ ] `GET /api/metrics/timeseries?metric=jvm-heap-rag-chat-api&range=1h&step=30s` returns the shape in §5.3.
- [ ] `GET /api/metrics/services` returns the service health grid; P95 latency ≤ 100 ms.
- [ ] `GET /api/metrics/logs?service=rag-chat&since=15m` returns 401 when called without `X-User-Id`.
- [ ] Same call with `X-User-Id` returns the shape in §5.4; P95 latency ≤ 600 ms.
- [ ] PromQL injection attempts via `metric=` query param are rejected (allowlist enforced).

### Frontend
- [ ] `/metrics` route accessible to anon visitors (no login redirect).
- [ ] Sidebar "System status" row drops the `🔒 M5` badge after M5 ships.
- [ ] Range preset pills change the URL via `?range=Xh` and refetch.
- [ ] "Updated Ns ago" indicator ticks every second; `⟳` manual refresh resets.
- [ ] 15s auto-polling runs in background; pauses when tab loses focus (browser default behavior, no explicit pause).
- [ ] Initial-load skeleton placeholders render for every widget.
- [ ] Per-widget error states render correctly when a single timeseries call fails.

### Cost protection
- [ ] `/api/metrics/dashboard` 30/min/IP rate-limit returns 429 on overrun.
- [ ] `metrics` BC enforces a 10s per-request PromQL budget; widgets that exceed are flagged `"degraded": true`.
- [ ] `/api/metrics/logs` per-user rate-limit 60/min returns 429 on overrun.

### Cross-milestone (traceability — non-blocking for M5 close)
- [ ] roadmap §M5 acceptance bullets 4 + 5 retired via ADR-15 amendment.
- [ ] ADR-09 amendment adds `GET /api/metrics/logs/**` row in the authenticated section.
- [ ] `docs/infra-requirements/be.md` updated with the docker socket security note.
- [ ] Sidebar "System status" row activation lands in the same milestone-close window as the backend.

## 13. Supersession + downstream amendments

The M5 ADR PR set must apply:

| File | Section | Change |
|---|---|---|
| `docs/roadmap.md` | §M5 | Retire acceptance bullet 4 ("behind login" — was already contradicted) + bullet 5 ("no new external dependency" — superseded by the 4-container observability stack). Public surface block stays. |
| `docs/adr/09-public-route-policy.md` | "Route classification" | Add `GET /api/metrics/logs/**` → authenticated row; keep `GET /api/metrics/**` public row (covers dashboard/services/timeseries). |
| `docs/adr/00-overview.md` | Index | Add row for ADR-15. Update module-count line if M5 quadruplet bumps it (ADR-01 v2 may already include). |
| `docs/adr/05-data-store.md` | (no change) | M5 has no Postgres schema; nothing to amend. |
| `docs/infra-requirements/be.md` | (new section) | Document the docker socket mounts (cAdvisor + Alloy), Prometheus/Loki disk budgets, retention defaults. |

ADR-04 and ADR-13/14 are untouched — M5 is a consumer of their `/actuator/prometheus` endpoints, not a modifier.
