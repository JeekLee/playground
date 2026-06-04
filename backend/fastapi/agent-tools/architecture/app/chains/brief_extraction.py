"""Brief → BriefAnalysis extraction as an LCEL chain (ADR-19 §A19.8 / §A19.10).

Phase 3a-2 retargets the chain to the OPEN, fact-only `BriefAnalysis`, now
capturing BOTH the per-zone GROSS totals (`zones_gross`) AND the named
sub-spaces (`program`, with `parent_zone` / `is_net`). The deterministic
reconcile→classify→derive nodes decide defaults and build the tight
`MassingInputs`; the model extracts only what the brief STATES.

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

⚠️ 가장 중요 — 면적 표를 빠짐없이 분해하세요:
brief에는 보통 "시설 면적 개요" 같은 면적 표가 있습니다. 그 표에는 (a) 큰 **영역(zone)**
별 합계와 (b) 그 안의 **개별 공간(sub-space)** 면적이 함께 있습니다. 이 둘을 **반드시 구분**해
서로 다른 필드에 넣으세요:
  - 개별 공간(사무공간, 지원공간, 복지공간, 민원공간, Light Lab, Middle Lab, Heavy Lab,
    대강당 등) → **program** 배열의 항목으로 (각각 자기 면적과 함께).
  - 영역 합계(업무영역, 시험영역, 연구영역, 지하영역 등 "…영역/…동"의 계·합계) → **zones_gross**.
중요: **영역(예: 업무영역 11,525 / 시험영역 14,975)은 program 에 넣지 말고 zones_gross 에만**
넣으세요. program 에는 그 하위 개별 공간(사무 3,045 / Light Lab 3,000 / Middle Lab 5,680 …)을
넣습니다. 표의 모든 면적 행을 누락 없이 추출하세요.
OCR 주의: 본문은 스캔 PDF의 OCR 결과라 오타가 있을 수 있습니다(예: "Middie"=Middle, "시험설"=
시험실, 각주기호 `$^1)$`·빈 셀 `|||`는 무시). 숫자(면적)는 그대로 신뢰하세요.

추출 항목:
1. program — **개별 명칭이 있는 실/공간(sub-space)**의 배열. 영역(zone) 합계가 아니라 그 하위
   개별 공간만. 각 항목:
   - name: 실/공간 이름(한국어 또는 영문, 예: Middle Lab, 대회의실).
   - area_m2: 면적(㎡, 숫자). 명시된 ㎡/m² 값을 그대로 사용. 면적이 없으면 그 항목은 제외.
   - grade: brief가 지하/B1/basement 를 가리키면 "below", 지상/상부를 가리키면 "above",
     불명확하면 "unknown".
   - kind: 용도 분류(예: 업무, 시험, 주차, 연구, 전시 등). 모르면 null.
   - parent_zone: 이 공간이 속한 영역(zone)의 이름. brief가 영역 구분을 명시하면 그 영역명을
     넣고, 아니면 null.
   - is_net: 이 면적이 **전용(net)** 면적이면 true, **합계/계(gross, 공용 포함)** 면적이면
     false, 불명확하면 null. 보통 개별 실 면적은 전용(true).
2. zones_gross — **영역(zone)별 GROSS 합계** 배열. brief가 영역(예: 업무영역, 연구영역,
   지하영역)별 "계/합계" 면적(전용+공용 포함)을 제시하면 그 합계를 여기에 넣습니다. 각 항목:
   - name: 영역 이름.
   - area_m2: 그 영역의 GROSS 합계 면적(㎡).
   - grade: "above"/"below"/"unknown".
   - net_ratio: 그 영역의 전용비율(전용/합계). brief가 전용/공용 비율을 명시하면 0..1로,
     아니면 null. (예: 전용 75%면 0.75)
   영역별 합계가 없으면 빈 배열.
3. site_area_m2 — 대지면적(㎡). brief에 명시된 경우만. 없으면 null. 가로/세로 치수를
   대지면적으로 임의 변환하지 마세요(치수가 명시돼 있고 면적이 없으면 면적은 null).
4. coverage_ratio_max — 건폐율. **0..1 비율로** 저장(brief가 80%라 하면 0.8). 없으면 null.
5. floor_area_ratio_max — 용적률(예: 3.5 또는 350%면 3.5). 없으면 null.
6. total_gfa_m2 — 연면적(㎡). 없으면 null.
7. floor_limit — 층수 제한(정수). "층수 제한없음"이면 **반드시 null**. 임의 층수 창작 금지.
8. height_limit_m — 높이 제한(m). 없으면 null.
9. parking — 주차 대수 {{below: 지하, above: 지상, total: 합계}}. 없으면 null.
10. constraints — 그 밖의 관련 요구사항(용도지역/일조/조경/공개공지/분동/증축/단지맥락 등)을
   {{category, text, value?, unit?}} 객체로. 해당 없으면 빈 배열.
11. notes — 위에 담기 어려운 보충 메모. 없으면 null.

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
