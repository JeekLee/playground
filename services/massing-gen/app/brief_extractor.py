"""Brief → ProgramJson extractor per ADR-18 §10 (prompt template owned by
M8.1 tuning) + §A18.3 (Pydantic replaces networknt JSON Schema validator).
"""

from __future__ import annotations

import json
import logging

from pydantic import ValidationError

from .errors import MassingError, MassingErrorCode
from .llm_client import LlmClient
from .models import ExtractedProgram

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """당신은 한국 건축 설계공모 brief 문서에서 실(room) 프로그램과 대지 정보를 추출하는 전문가입니다.

입력: 한국어 brief 마크다운 본문.
출력: 다음 JSON Schema를 정확히 따르는 JSON 객체:

{
  "site": {"width": <대지 가로 m, 숫자>, "depth": <대지 세로 m, 숫자>} | null,
  "rooms": [
    {"name": "<실 이름(한국어 또는 영문)>", "areaM2": <면적 m², 숫자>}
    ...
  ]
}

규칙:
1. site 정보가 brief에 명시되지 않았으면 site 필드를 null로 출력하세요. 추측 금지.
2. 실 면적은 brief에 명시된 ㎡ / m² 값을 그대로 사용. 없으면 해당 실은 제외.
3. rooms 배열은 최소 1개 이상의 실을 포함해야 합니다. 추출 실패 시 빈 응답 대신 가장 가까운 1실이라도 출력.
4. 출력은 JSON 객체만. 마크다운 코드 펜스, 설명, 주석 모두 금지.
5. 학습 데이터의 일반적인 예시 데이터(샘플 도서관, 카페, 예제 사무실 등) 생성 금지 — brief에 명시된 실만 추출."""


def _build_user_prompt(brief_body: str) -> str:
    return (
        "다음 brief 본문에서 실 프로그램과 대지 정보를 추출하세요.\n"
        "추출 결과는 위 JSON Schema 형식을 그대로 따라야 합니다.\n\n"
        "---\n"
        f"{brief_body}\n"
        "---"
    )


def extract_program(brief_body: str, llm: LlmClient) -> ExtractedProgram:
    """Call the LLM + validate the response via Pydantic.

    Raises MassingError(BRIEF_EXTRACTION_FAILED) on LLM failure, malformed
    JSON, or schema validation failure. Raises MassingError(SIDECAR_TIMEOUT)
    on LLM gateway timeout (propagated from LlmClient).
    """
    if not brief_body or not brief_body.strip():
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "brief body is empty",
        )

    raw = llm.complete_json(SYSTEM_PROMPT, _build_user_prompt(brief_body))

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            f"LLM returned non-JSON: {exc}",
            cause=exc,
        ) from exc

    try:
        return ExtractedProgram.model_validate(parsed)
    except ValidationError as exc:
        # First error suffices for the wire message; full detail in the log.
        first = exc.errors()[0] if exc.errors() else {"msg": "validation failed"}
        logger.warning("brief extraction validation failed: %s", exc.errors())
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            f"LLM output schema invalid: {first.get('msg', 'invalid')}",
            cause=exc,
        ) from exc
