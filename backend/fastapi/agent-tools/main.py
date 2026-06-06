"""FastAPI application entry point for the M8 massing-gen BC.

Wires:
- /actuator/health (compose healthcheck target)
- /internal/tools/generate-massing (M7 tool dispatch target)
- MassingError -> JSON response with the {code, message} shape FE consumes
  via M7's tool_error.message <CODE>: <message> prefix grammar
- prometheus-fastapi-instrumentator for the M5 metrics dashboard

ADR-20 §D4: the `architecture` BC is now a stateless generator. The
`arch.outputs` store + `/outputs/{id}` download are retired — no Postgres
bootstrap, no DB session. The `.3dm` bytes flow out in the tool-result
`artifact`; chat owns persistence + download.

Single uvicorn worker per ADR-18 §A18.3.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator

from shared_kernel.config import get_settings
from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError
from architecture.api.routers import tools
from architecture.app.workflow import MassingWorkflow

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    docs_client = DocsClient(settings)
    workflow = MassingWorkflow(settings, docs_client)
    app.state.settings = settings
    app.state.docs_client = docs_client
    app.state.workflow = workflow
    logger.info(
        "massing-gen started",
        extra={
            "docs_api": settings.docs_api_base_url,
            "llm_gateway": settings.spring_ai_openai_base_url,
            "model": settings.llm_model,
        },
    )
    try:
        yield
    finally:
        docs_client.close()


app = FastAPI(
    title="massing-gen",
    version="0.1.0",
    description="M8 — brief-to-massing tool BC (Python/FastAPI per ADR-18 §A18.1)",
    lifespan=lifespan,
)

Instrumentator().instrument(app).expose(app, endpoint="/actuator/prometheus")


@app.get("/actuator/health", tags=["actuator"])
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.exception_handler(MassingError)
async def massing_error_handler(request: Request, exc: MassingError) -> JSONResponse:
    """Map MassingError → HTTP response.

    Body shape: {code: "<CODE>", message: "<human>"}. M7's ToolDispatcher
    on the chat side will pack this into tool_error.message as
    `<CODE>: <message>` per ADR-18 §A18.5 §7.
    """
    # NOTE: Python's logging module reserves `message` as a LogRecord
    # attribute — passing it via `extra={"message": ...}` raises KeyError
    # ("Attempt to overwrite 'message' in LogRecord") and crashes the
    # exception handler, returning a generic 500 instead of the intended
    # {code, message} body. Rename the key to `error_message` here.
    logger.warning(
        "MassingError handled code=%s error_message=%s path=%s",
        exc.code.value,
        exc.message,
        request.url.path,
    )
    return JSONResponse(
        status_code=int(exc.code.http_status),
        content={"code": exc.code.value, "message": exc.message},
    )


app.include_router(tools.router)
