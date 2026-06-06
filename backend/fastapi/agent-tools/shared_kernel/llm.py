"""langchain-openai ChatOpenAI factory (ADR-19 §A19.10).

Points a `ChatOpenAI` at the spark-inference-gateway (OpenAI-compatible;
`base_url`, `api_key`, model `qwen3-vl-30b-a3b`). Chains compose
`prompt | model | with_structured_output(...)`. This is the langchain-openai
adoption that retires the hand-rolled httpx `llm_client` for the extraction
path.

BC-neutral: lives in shared_kernel so future BCs reuse the same gateway
client (shared_kernel MUST NOT import from a BC).
"""

from __future__ import annotations

from langchain_openai import ChatOpenAI

from .config import Settings


def get_chat_model(settings: Settings) -> ChatOpenAI:
    """Build a ChatOpenAI bound to the spark-inference-gateway.

    The gateway exposes an OpenAI-compatible API; langchain-openai appends
    `/chat/completions` to `base_url`, so `base_url` is the gateway root + `/v1`
    (matching the hand-rolled client's `POST /v1/chat/completions`).
    """
    kwargs = dict(
        base_url=settings.spring_ai_openai_base_url + "/v1",
        api_key=settings.spring_ai_openai_api_key,
        model=settings.llm_model,
        temperature=settings.llm_temperature,
        timeout=settings.llm_timeout_seconds,
        # 호출당 상한 = timeout × (1 + max_retries) = 120s × 2 — chat의
        # total cap(600s) 안에서 예측 가능 (tool-streaming spec D4).
        max_retries=1,
    )
    if settings.llm_max_tokens is not None:
        kwargs["max_tokens"] = settings.llm_max_tokens
    return ChatOpenAI(**kwargs)
