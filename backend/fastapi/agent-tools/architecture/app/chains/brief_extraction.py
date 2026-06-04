"""Brief → room program extraction as an LCEL chain (ADR-19 §A19.8 / §A19.10).

Replaces the hand-rolled httpx extractor (`brief_extractor.extract_program`)
with `ChatPromptTemplate | model.with_structured_output(ExtractedProgram)`.

The Korean SYSTEM_PROMPT + user template are carried over verbatim from the
M8 `brief_extractor.py`. `method="json_mode"` is the default for
`with_structured_output` (closest to the prior `response_format: json_object`
behavior — the gateway already serves json_object today).

Error mapping mirrors the old extractor: LLM/parse failure →
`BRIEF_EXTRACTION_FAILED`, gateway timeout → `SIDECAR_TIMEOUT`.
"""

from __future__ import annotations

import logging

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import Runnable
from pydantic import ValidationError

from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.domain.models import ExtractedProgram

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """당신은 한국 건축 설계공모 brief 문서에서 실(room) 프로그램과 대지 정보를 추출하는 전문가입니다.

입력: 한국어 brief 마크다운 본문.
출력: 다음 JSON Schema를 정확히 따르는 JSON 객체:

{{
  "site": {{"width": <대지 가로 m, 숫자>, "depth": <대지 세로 m, 숫자>}} | null,
  "rooms": [
    {{"name": "<실 이름(한국어 또는 영문)>", "areaM2": <면적 m², 숫자>}}
    ...
  ]
}}

규칙:
1. site 정보가 brief에 명시되지 않았으면 site 필드를 null로 출력하세요. 추측 금지.
2. 실 면적은 brief에 명시된 ㎡ / m² 값을 그대로 사용. 없으면 해당 실은 제외.
3. rooms 배열은 최소 1개 이상의 실을 포함해야 합니다. 추출 실패 시 빈 응답 대신 가장 가까운 1실이라도 출력.
4. 출력은 JSON 객체만. 마크다운 코드 펜스, 설명, 주석 모두 금지.
5. 학습 데이터의 일반적인 예시 데이터(샘플 도서관, 카페, 예제 사무실 등) 생성 금지 — brief에 명시된 실만 추출."""


USER_TEMPLATE = (
    "다음 brief 본문에서 실 프로그램과 대지 정보를 추출하세요.\n"
    "추출 결과는 위 JSON Schema 형식을 그대로 따라야 합니다.\n\n"
    "---\n"
    "{brief_body}\n"
    "---"
)


def build_brief_extraction_chain(model: BaseChatModel) -> Runnable:
    """Compose the LCEL extraction chain.

    Input: ``{"brief_body": <str>}``. Output: ``ExtractedProgram``.

    ``model`` is a parameter so tests can inject a fake. `method="json_mode"`
    matches the prior in-prompt-schema + json_object behavior; see §A19.10
    (the M7 gateway proved function_calling too, but json_mode reproduces M8).
    """
    prompt = ChatPromptTemplate.from_messages(
        [("system", SYSTEM_PROMPT), ("user", USER_TEMPLATE)]
    )
    structured = model.with_structured_output(ExtractedProgram, method="json_mode")
    return prompt | structured


def extract_program(chain: Runnable, brief_body: str) -> ExtractedProgram:
    """Invoke the extraction chain with the M8 error mapping.

    Raises MassingError(BRIEF_EXTRACTION_FAILED) on LLM failure, malformed
    output, or schema validation failure. Raises MassingError(SIDECAR_TIMEOUT)
    on LLM gateway timeout.
    """
    if not brief_body or not brief_body.strip():
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "brief body is empty",
        )

    try:
        result = chain.invoke({"brief_body": brief_body})
    except httpx.TimeoutException as exc:
        raise MassingError(
            MassingErrorCode.SIDECAR_TIMEOUT,
            "LLM gateway timeout",
            cause=exc,
        ) from exc
    except ValidationError as exc:
        first = exc.errors()[0] if exc.errors() else {"msg": "validation failed"}
        logger.warning("brief extraction validation failed: %s", exc.errors())
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            f"LLM output schema invalid: {first.get('msg', 'invalid')}",
            cause=exc,
        ) from exc
    except MassingError:
        raise
    except Exception as exc:  # noqa: BLE001 — wrap any LLM/parse failure
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            f"LLM extraction failed: {exc}",
            cause=exc,
        ) from exc

    if result is None or not isinstance(result, ExtractedProgram):
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "LLM returned no structured output",
        )
    return result
