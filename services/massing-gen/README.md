# massing-gen — M8 brief-to-massing tool BC

First polyglot BC in the playground project. Python 3.12 + FastAPI per
ADR-18 §A18.1 — flipped from the originally-planned Java/Spring quadruplet
because `.3dm` serialization requires the rhino3dm library, which has a
first-class Python binding (`pip install rhino3dm`) and no Java binding.

## Layout

```
services/massing-gen/
├── Dockerfile          — multi-stage Python 3.12-slim + pip install
├── pyproject.toml      — deps + tooling pins
├── schema.sql          — arch.outputs DDL (hand-rolled, P0)
├── README.md           — this file
└── app/
    ├── main.py         — FastAPI app + lifespan + MassingError handler
    ├── config.py       — env-driven Settings (pydantic-settings)
    ├── deps.py         — UserContext (X-User-Id / X-User-Sub headers)
    ├── models.py       — Pydantic wire shapes + SQLAlchemy ArchOutput
    ├── errors.py       — MassingErrorCode + MassingError exception
    ├── database.py     — SQLAlchemy engine + session_scope context
    ├── algorithm.py    — rectangular first-fit massing (ADR-18 §8)
    ├── brief_extractor.py — LLM-driven Korean brief → ProgramJson
    ├── llm_client.py   — httpx → spark-inference-gateway (OpenAI-compat)
    ├── docs_client.py  — httpx → docs-api (ADR-08 Exception 5)
    ├── serializer.py   — rhino3dm.py .3dm serialization
    ├── slug.py         — Content-Disposition filename helper
    ├── summary.py      — Korean fixed-format summary string
    ├── workflow.py     — 8-step orchestrator
    └── routers/
        ├── tools.py    — POST /internal/tools/generate-massing
        └── outputs.py  — GET /outputs/{id} (gateway /api/arch/outputs/{id})
```

## Endpoints

- `POST /internal/tools/generate-massing` — M7 ToolDispatcher target
  (compose-internal only). Request body validated by Pydantic per
  ADR-18 §9; response shape per §10.
- `GET /outputs/{id}` — owner-only `.3dm` download. Gateway routes
  `/api/arch/**` → here with `StripPrefix=2`.
- `GET /actuator/health` — Docker healthcheck target.
- `GET /actuator/prometheus` — M5 metrics scrape target.

## Tests

```bash
cd services/massing-gen
pip install -e ".[test]"
pytest
```

Unit tests cover the algorithm, brief extractor (with stub LLM),
summary formatter, slug helper, and error enum. The workflow's HTTP
+ DB orchestration is verified by the E2E integration test that the
M8 PR's infra slice spins up via docker-compose.

## Notes

- Single uvicorn worker per ADR-18 §A18.3 — sync SQLAlchemy is fine.
- Resilience4j circuit breakers retired in P0 per ADR-18 §A18.6;
  httpx `timeout=` is the only failure boundary.
- LLM call goes to `SPRING_AI_OPENAI_BASE_URL` (env var name preserved
  across Java + Python BCs per ADR-18 §A18.5 §19).
