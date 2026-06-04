"""extract node (ADR-19 Phase 3a-2) — the single heavy LLM call.

Single job: run the LCEL `brief_extraction` chain on `state["excerpt"]` ->
`state["analysis"]` (BriefAnalysis).

This is the only heavyweight model invocation in the pipeline (locate is a
pure heuristic). The 60s tool breaker is sized around this one call.

The chain is built from an injected model / injected chain so tests can run
hermetically. On a re-prompt (extract_attempts > 0) the chain is rebuilt with
an added site-area instruction when a real model is available; an injected
fake chain is reused verbatim. The node bumps `extract_attempts`.
"""

from __future__ import annotations

import logging

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable

from architecture.app.chains.brief_extraction import (
    REPROMPT_SITE_AREA_INSTRUCTION,
    build_brief_extraction_chain,
    extract_brief_analysis,
)
from architecture.app.state import MassingState

logger = logging.getLogger(__name__)
from shared_kernel.config import Settings
from shared_kernel.llm import get_chat_model


def make_extract_node(
    settings: Settings,
    *,
    chain: Runnable | None = None,
    model: BaseChatModel | None = None,
):
    """Build the extract node.

    - ``chain`` (tests): a fixed injected extraction chain; re-prompts reuse it
      verbatim (no prompt rebuild possible / needed).
    - ``model``: a BaseChatModel; the base chain is built from it, and a
      re-prompt rebuilds the chain with an added site-area instruction.
    - neither: the default ChatOpenAI factory (spark gateway) is used.
    """
    base_model = model if (chain is None) else None
    if chain is None and base_model is None:
        base_model = get_chat_model(settings)

    base_chain = chain or build_brief_extraction_chain(base_model)  # type: ignore[arg-type]

    def _reprompt_chain() -> Runnable:
        if chain is not None or base_model is None:
            return base_chain
        return build_brief_extraction_chain(
            base_model, extra_instruction=REPROMPT_SITE_AREA_INSTRUCTION
        )

    def extract(state: MassingState) -> dict:
        attempts = state.get("extract_attempts", 0)
        active = base_chain if attempts == 0 else _reprompt_chain()
        analysis = extract_brief_analysis(active, state["excerpt"])
        # Observability (Phase 3a-3): log extraction granularity per run so we
        # can see whether sub-spaces (e.g. Middle Lab) were captured vs only
        # zone-level — the driver of floor-count stability.
        logger.info(
            "extracted attempt=%d program=%s zones_gross=%s site_area=%s coverage=%s",
            attempts,
            [(p.name, p.area_m2, p.grade, p.parent_zone) for p in analysis.program],
            [(z.name, z.area_m2, z.grade) for z in (analysis.zones_gross or [])],
            analysis.site_area_m2,
            analysis.coverage_ratio_max,
        )
        return {"analysis": analysis, "extract_attempts": attempts + 1}

    return extract
