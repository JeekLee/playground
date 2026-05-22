# M8 massing-gen BC — Python 3.12 + FastAPI + rhino3dm.py per ADR-18 §A18.3.
# Build context: backend/fastapi/massing-gen/. Invoked via infra/docker-compose.yml.

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

COPY app ./app
COPY schema.sql ./schema.sql

# Non-root user (security baseline)
RUN useradd -m -u 1000 massing && chown -R massing:massing /app
USER massing

EXPOSE 18083

# Uvicorn workers default to 1 — single LLM-bound process is sufficient at
# personal-scale. Operators can override via UVICORN_WORKERS env if needed.
ENV PYTHONUNBUFFERED=1
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:18083/actuator/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18083"]
