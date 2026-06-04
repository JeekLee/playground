"""LLM client per ADR-18 §A18.3 — direct httpx POST to the
spark-inference-gateway's OpenAI-compatible /v1/chat/completions endpoint.

Replaces the Java side's Spring AI ChatClient (which §A18.5 retired for M8).
Other Java BCs (rag-chat, docs-api Vision) keep Spring AI; M8 is the first
BC where the LLM call lives in Python.
"""

from __future__ import annotations

import json
import logging

import httpx

from .config import Settings
from .errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)


class LlmClient:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self._settings = settings
        self._owned_client = client is None
        self._client = client or httpx.Client(
            base_url=settings.spring_ai_openai_base_url,
            timeout=settings.llm_timeout_seconds,
            headers={"Authorization": f"Bearer {settings.spring_ai_openai_api_key}"},
        )

    def close(self) -> None:
        if self._owned_client:
            self._client.close()

    def complete_json(self, system_prompt: str, user_prompt: str) -> str:
        """Call /v1/chat/completions with `response_format: {type: json_object}`
        and return the raw assistant content string. Caller validates the shape.
        """
        body = {
            "model": self._settings.llm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self._settings.llm_temperature,
            "max_tokens": self._settings.llm_max_tokens,
            "response_format": {"type": "json_object"},
        }

        try:
            resp = self._client.post("/v1/chat/completions", json=body)
        except httpx.TimeoutException as exc:
            raise MassingError(
                MassingErrorCode.SIDECAR_TIMEOUT,
                "LLM gateway timeout",
                cause=exc,
            ) from exc
        except httpx.HTTPError as exc:
            raise MassingError(
                MassingErrorCode.SIDECAR_FAILED,
                f"LLM gateway transport error: {exc}",
                cause=exc,
            ) from exc

        if resp.status_code >= 500:
            raise MassingError(
                MassingErrorCode.SIDECAR_FAILED,
                f"LLM gateway {resp.status_code}: {resp.text[:200]}",
            )
        if resp.status_code >= 400:
            raise MassingError(
                MassingErrorCode.BRIEF_EXTRACTION_FAILED,
                f"LLM gateway {resp.status_code}: {resp.text[:200]}",
            )

        try:
            payload = resp.json()
            content = payload["choices"][0]["message"]["content"]
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise MassingError(
                MassingErrorCode.BRIEF_EXTRACTION_FAILED,
                f"LLM gateway response malformed: {exc}",
                cause=exc,
            ) from exc

        return content
