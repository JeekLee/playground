"""Brief → BriefAnalysis extraction as an LCEL chain (ADR-19 §A19.8 / §A19.10).

Phase 3a retargets the chain from the old `ExtractedProgram` to the OPEN,
fact-only `BriefAnalysis`. The model extracts only what the brief STATES; the
deterministic resolve step (`app/nodes/resolve.py`) decides defaults and
builds the tight `MassingInputs`.

`ChatPromptTemplate | model.with_structured_output(BriefAnalysis, method="json_mode")`.
`method="json_mode"` is kept (ADR-19 §A19.10 — reproduces the M8 extraction
mechanism; the gateway serves json_object today).

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

from architecture.domain.models import BriefAnalysis

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """당신은 한국 건축 설계공모 brief 문서에서 사실(fact)만 추출하는 전문가입니다.

입력: 한국어 brief 마크다운 본문.
목표: brief가 **명시한** 사실만 다음 구조로 추출합니다. 추측·창작 금지.

추출 항목:
1. program — 면적 프로그램(실/공간)의 배열. 각 항목:
   - name: 실/공간 이름(한국어 또는 영문)
   - area_m2: 면적(㎡, 숫자). 명시된 ㎡/m² 값을 그대로 사용. 면적이 없으면 그 항목은 제외.
   - grade: brief가 지하/B1/basement 를 가리키면 "below", 지상/상부를 가리키면 "above",
     불명확하면 "unknown".
   - kind: 용도 분류(예: 업무, 시험, 주차, 연구, 전시 등). 모르면 null.
2. site_area_m2 — 대지면적(㎡). brief에 명시된 경우만. 없으면 null. 가로/세로 치수를
   대지면적으로 임의 변환하지 마세요(치수가 명시돼 있고 면적이 없으면 면적은 null).
3. coverage_ratio_max — 건폐율. **0..1 비율로** 저장(brief가 80%라 하면 0.8). 없으면 null.
4. floor_area_ratio_max — 용적률(예: 3.5 또는 350%면 3.5). 없으면 null.
5. total_gfa_m2 — 연면적(㎡). 없으면 null.
6. floor_limit — 층수 제한(정수). "층수 제한없음"이면 **반드시 null**. 임의 층수 창작 금지.
7. height_limit_m — 높이 제한(m). 없으면 null.
8. parking — 주차 대수 {{below: 지하, above: 지상, total: 합계}}. 없으면 null.
9. constraints — 그 밖의 관련 요구사항(용도지역/일조/조경/공개공지/분동/증축/단지맥락 등)을
   {{category, text, value?, unit?}} 객체로. 해당 없으면 빈 배열.
10. notes — 위에 담기 어려운 보충 메모. 없으면 null.

규칙:
- brief가 명시한 것만 추출. 대지 치수·층수 등 **수치를 창작하지 마세요**.
- 불명확하면 grade="unknown", 모르는 수치는 null.
- program 배열은 최소 1개 이상.
- 학습 데이터의 일반 예시(샘플 도서관/카페/예제 사무실 등) 생성 금지 — brief의 실만.
- 출력은 JSON 객체만. 코드 펜스/설명/주석 금지."""


USER_TEMPLATE = (
    "다음 brief 본문에서 명시된 사실만 위 구조에 맞게 추출하세요.\n\n"
    "---\n"
    "{brief_body}\n"
    "---"
)


# Optional extra instruction appended on a re-prompt when a hard-required input
# (대지면적) was missing on the first pass — see app/graphs/program_resolution.py.
REPROMPT_SITE_AREA_INSTRUCTION = (
    "\n\n[재확인 요청] 이전 추출에서 대지면적(site_area_m2)을 찾지 못했습니다. "
    "brief를 다시 살펴 대지면적이 명시되어 있으면 site_area_m2 에 ㎡ 숫자로 채우세요. "
    "치수(가로×세로)가 명시돼 있으면 곱해서 면적을 계산해도 됩니다. "
    "정말 명시되어 있지 않으면 null 로 두세요(창작 금지)."
)


def build_brief_extraction_chain(
    model: BaseChatModel,
    *,
    extra_instruction: str = "",
) -> Runnable:
    """Compose the LCEL extraction chain.

    Input: ``{"brief_body": <str>}``. Output: ``BriefAnalysis``.

    ``model`` is a parameter so tests can inject a fake. ``extra_instruction``
    appends a re-prompt hint to the system prompt (used by the resolution
    subgraph's re-extraction loop). ``method="json_mode"`` per §A19.10.
    """
    system = SYSTEM_PROMPT + extra_instruction
    prompt = ChatPromptTemplate.from_messages(
        [("system", system), ("user", USER_TEMPLATE)]
    )
    structured = model.with_structured_output(BriefAnalysis, method="json_mode")
    return prompt | structured


def extract_brief_analysis(chain: Runnable, brief_body: str) -> BriefAnalysis:
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

    if result is None or not isinstance(result, BriefAnalysis):
        raise MassingError(
            MassingErrorCode.BRIEF_EXTRACTION_FAILED,
            "LLM returned no structured output",
        )
    return result
