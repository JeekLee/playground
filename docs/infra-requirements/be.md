# Backend Infra Requirements

This document captures the operator-facing infra requirements that the
backend stack imposes on the host environment — docker socket access,
disk / memory budgets, named volume conventions, and any privilege /
security trade-offs that are not derivable from looking at the compose
file alone.

Per-milestone additions are appended chronologically. Each section
references the ADR that introduced the requirement.

## M5 Observability Stack (2026-05-19, ADR-15)

ADR-15 introduces 4 new compose containers (Prometheus + Loki + Alloy +
cAdvisor) to support the system-status dashboard at `/metrics`.
Operator considerations:

### Docker socket access

- `cadvisor-playground` and `alloy-playground` both require
  `/var/run/docker.sock` mounted **read-only**.
- This is a residual privilege-escalation surface: an attacker who
  compromises either container can list containers, read env vars,
  and inspect mount paths via `docker inspect`-class API verbs.
  They **cannot** `docker exec`, `docker run`, or otherwise mutate
  container state — the socket is `:ro` and docker's API enforces
  verb-vs-mode on supporting kernels (Linux 5.x+).
- Mitigations applied:
  - read-only mount only (`:/var/run/docker.sock:ro`)
  - no `privileged: true` flag on either container
  - no host-network binding (both containers are compose-internal only)
  - no host port exposed for cAdvisor or Alloy
- Acceptable trade-off for personal-scale single-tenant. Revisit if
  the audience scope grows; rootless docker is the next-step
  alternative (current cost: breaks the `postgres-playground` volume
  mount semantics; deferred to P2).

### Disk budget

- Prometheus 7-day retention: **~3 GB** on average (the Spark host's
  typical metric volume; tail of ~5 GB during heavy chat activity).
- Loki 3-day retention: **~2 GB** on average.
- Total observability disk allocation: **~5 GB**.
- Named volumes:

| Volume name | Mounted at | Container | Purpose |
|---|---|---|---|
| `prometheus-playground-data` | `/prometheus` | `prometheus-playground` | TSDB data + WAL |
| `loki-playground-data` | `/loki` | `loki-playground` | Log TSDB + indexes |
| `alloy-playground-data` | `/var/lib/alloy/data` | `alloy-playground` | Log-cursor state + scrape failure counters |

- cAdvisor needs no named volume (stateless; in-memory metrics only).
- Retention is env-var-tunable:
  - `METRICS_PROM_RETENTION_DAYS` (default `7`)
  - `METRICS_LOKI_RETENTION_DAYS` (default `3`)

### Memory footprint

| Container | Approximate RSS | Notes |
|---|---|---|
| `prometheus-playground` | ~150 MB | Single-binary; TSDB working set scales with active series — ~50k series at personal scale. |
| `loki-playground` | ~100 MB | Single-binary `target=all` mode. |
| `alloy-playground` | ~200 MB | Hosts the scrape DAG + log-shipping pipeline + host exporter; largest of the four. |
| `cadvisor-playground` | ~150 MB | v2 stat collector working set. |
| **Total** | **~600 MB** | Personal-scale; fits comfortably on the Spark host alongside the existing BC stack. |

### Host metrics

`alloy-playground` runs the `prometheus.exporter.unix` component,
exposing host CPU / memory / disk / load average to Prometheus via
Alloy's local scrape. This replaces the need for a separate
`node_exporter` container — one fewer process, one fewer image to pin,
one less attack surface. The host metrics scrape interval is 10 s (per
`config.alloy` block 3 in ADR-15 §5); the BC scrapes are 5 s.

### Network exposure

All 4 observability containers join the existing `playground` compose
network and **none** binds to a host port by default. Direct access
from the host is via `docker exec` only. An exception is the optional
`PROMETHEUS_HOST_BIND` env-var (per ADR-15 §3) which conditionally
binds Prometheus's :9090 to the host for ad-hoc PromQL CLI use.

### Operational reset

To wipe observability state without affecting BC state:

```bash
docker compose down prometheus-playground loki-playground alloy-playground
docker volume rm \
  playground_prometheus-playground-data \
  playground_loki-playground-data \
  playground_alloy-playground-data
docker compose up -d prometheus-playground loki-playground alloy-playground
```

Postgres / Redis / Kafka volumes are unaffected. The next dashboard
load shows a 7-day window's worth of fresh-collected data only (gap
for the wiped retention window).

See `docs/adr/15-m5-metrics.md` §3 + §4 + §5 + §6 + §A + §20 for the
full specification.
