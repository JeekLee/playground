# Bootstrap guide (M0)

Stand up the empty playground platform skeleton. After this guide:

- `./gradlew build` passes (gateway + shared-kernel compile cleanly).
- `docker compose up -d` boots gateway + Postgres (pgvector) + Redis + Kafka (KRaft).
- `curl http://localhost:18080/actuator/health` returns `{"status":"UP"}`.

M0 ships zero application features. The four BC quadruplets (`identity`, `docs`, `rag-ingestion`, `rag-chat`, `metrics`) come online per their milestones — see `docs/roadmap.md`.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21 LTS** (Eclipse Temurin) | Required for `./gradlew build`. The Dockerfile bundles its own JDK — host JDK is only needed for local `bootRun` / IDE. |
| Docker | 24+ with Compose v2 | All infra runs in Compose. |
| `spark-inference-gateway` | reachable at `host.docker.internal:10080` | **Not consumed by M0**; the gateway wires the env var so M3+ doesn't need a re-bootstrap. |

## Steps

1. **Clone and enter the repo.**

   ```bash
   git clone git@github.com:JeekLee/playground.git
   cd playground
   ```

2. **Bootstrap the Gradle wrapper** (only needed once, if it isn't checked in):

   ```bash
   docker run --rm -v "$PWD":/workspace -w /workspace gradle:8.10.2-jdk21 \
     gradle wrapper --gradle-version 8.10.2
   ```

   This drops `gradlew`, `gradlew.bat`, and `gradle/wrapper/` into the repo. Commit them.

3. **Build the modules:**

   ```bash
   ./gradlew build
   ```

   Compiles `:api:shared-kernel` and `:api:gateway`. No tests yet — that lands per BC.

4. **Configure environment:**

   ```bash
   cp infra/.env.example infra/.env
   # Edit POSTGRES_PASSWORD at minimum. KAFKA_CLUSTER_ID can stay on the template
   # for local dev; regenerate per environment using:
   #   docker run --rm apache/kafka:3.8.0 /opt/kafka/bin/kafka-storage.sh random-uuid
   ```

5. **Boot infra + gateway:**

   ```bash
   cd infra
   docker compose --env-file .env up -d
   ```

   First boot takes 1-3 minutes (Postgres init, Kafka KRaft cluster formation, gateway Gradle build inside its container).

6. **Verify health:**

   ```bash
   curl http://localhost:18080/actuator/health
   # → {"status":"UP",...}
   ```

   Also reachable:
   - Postgres: `psql -h localhost -p 10232 -U playground -d playground` (`CREATE EXTENSION vector;` should already be applied).
   - Redis: `redis-cli -h localhost -p 10279 ping` → `PONG`.
   - Kafka: `nc -z localhost 19092` (host-exposed PLAINTEXT listener).

## Tearing down

```bash
cd infra
docker compose --env-file .env down       # stop containers, keep volumes
docker compose --env-file .env down -v    # also drop volumes (loses Postgres + Kafka state)
```

## Troubleshooting

- **`./gradlew: Permission denied`** — first time after wrapper bootstrap: `chmod +x gradlew`.
- **Port already in use (`10232`, `10279`, `18080`, `19092`)** — `docker ps` to find the offender; either stop it or rebind the port in `infra/docker-compose.yml`.
- **`pgvector` extension missing** — the init SQL only runs on first volume creation. Reset with `docker compose down -v` and bring up again.
- **Gateway healthcheck failing** — `docker compose logs gateway`. Most common cause is BOM resolution; retry with `./gradlew --refresh-dependencies build` (host) or `docker compose build --no-cache gateway`.
- **Kafka broker won't elect a controller** — host clock skew between WSL/macOS Docker host and the container. Restart Docker Desktop or check `date` on the host.

## What M0 deliberately leaves out

- **No application code.** M1 adds the `identity` BC (Google OAuth, `/me`, `X-User-Id` header injection).
- **No OpenSearch / MinIO.** Those ports (10292, 10293) are reserved in ADR-05 for M2+ and not booted yet.
- **No tests.** Test conventions land per BC starting M1 (see ADR-02 v2).
- **No CI.** The `./gradlew build` + `docker compose up` health check is the only verification gate for M0.
