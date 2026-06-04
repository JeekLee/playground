# agent-tools — Python 3.12 + FastAPI host for LLM-tool BCs (ADR-19);
# first BC `architecture` uses rhino3dm.py in-process (ADR-18 §A18.3).
# Build context: backend/fastapi/agent-tools/. Invoked via infra/docker-compose.yml.
# Phase 1 (ADR-19) is rename-only: the cohesive `app` package is preserved;
# the host/BC package split (app + architecture) is Phase 2.

FROM python:3.12-slim AS base
WORKDIR /app

# System deps — psycopg binary, build-essential for any wheels needing compile.
# Keep slim: only what the runtime + pip-install need.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir .

COPY main.py ./main.py
COPY shared_kernel ./shared_kernel
COPY architecture ./architecture
COPY schema.sql ./schema.sql

# Non-root user (security baseline)
RUN useradd -m -u 1000 agent && chown -R agent:agent /app
USER agent

EXPOSE 18083

# Uvicorn workers default to 1 — single LLM-bound process is sufficient at
# personal-scale. Operators can override via UVICORN_WORKERS env if needed.
ENV PYTHONUNBUFFERED=1
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:18083/actuator/health || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "18083"]
