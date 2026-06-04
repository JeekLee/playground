"""Program-resolution subgraph (ADR-19 §A19.8 / Phase 3a-2).

Owns the 5-stage interpretation sub-flow *with the re-prompt loop*:

    START → locate → extract → reconcile → classify → derive
            → (END | back to extract, bounded)

Each stage is its own node file (single responsibility, ADR-19 Phase 3a-2):
- locate    — body → massing-governing excerpt (heuristic, no LLM).
- extract   — excerpt → BriefAnalysis via the LCEL chain (the heavy LLM call).
- reconcile — BriefAnalysis → NormalizedBrief (gross zones + sub-spaces).
- classify  — NormalizedBrief → ClassifiedBrief (graded zones + footprint driver).
- derive    — ClassifiedBrief + request → validated MassingInputs.

If derive finds a hard-required input missing (대지면적 → MassingError
BRIEF_NOT_READY), the conditional edge loops back to a re-extraction (locate
output is reused — it's deterministic), up to a bounded retry count. After the
budget is spent the BRIEF_NOT_READY error propagates so the FastAPI handler
maps it (422). Any other MassingError (e.g. the coverage gate) propagates
immediately — only the missing-site-area case loops.

This is composed as a single node ("resolve_program") in the orchestrator.
"""

from __future__ import annotations

import logging
from typing import Callable

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable
from langgraph.graph import END, START, StateGraph

from architecture.app.nodes.classify import classify
from architecture.app.nodes.derive import derive_inputs
from architecture.app.nodes.extract import make_extract_node
from architecture.app.nodes.locate import locate
from architecture.app.nodes.reconcile import reconcile
from architecture.app.state import MassingState
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

logger = logging.getLogger(__name__)

# Bounded re-prompt budget: first pass + this many re-extractions.
MAX_EXTRACT_RETRIES = 2


def build_program_resolution_subgraph(
    settings: Settings,
    *,
    chain: Runnable | None = None,
    model: BaseChatModel | None = None,
):
    """Compile the locate→extract→reconcile→classify→derive subgraph.

    The re-prompt loop is anchored at ``derive``: a missing-site-area
    BRIEF_NOT_READY loops back to ``extract`` (not ``locate`` — the excerpt is
    already computed and deterministic).

    Injection mirrors the extract node:
    - ``chain`` (tests): a fixed injected extraction chain reused on re-prompt.
    - ``model``: a BaseChatModel; re-prompts rebuild the chain with a hint.
    - neither: the default ChatOpenAI factory (spark gateway).
    """
    extract = make_extract_node(settings, chain=chain, model=model)

    def derive(state: MassingState) -> dict:
        try:
            inputs = derive_inputs(state["classified"], state["req"], settings)
        except MassingError as exc:
            if exc.code == MassingErrorCode.BRIEF_NOT_READY:
                # Signal the conditional edge; do not write `inputs`.
                logger.info(
                    "derive missing site area (attempt %d): %s",
                    state.get("extract_attempts", 0),
                    exc.message,
                )
                return {}
            raise
        return {"inputs": inputs}

    def _route(state: MassingState) -> str:
        if "inputs" in state:
            return "done"
        if state.get("extract_attempts", 0) <= MAX_EXTRACT_RETRIES:
            return "retry"
        # Budget exhausted — surface the missing-input error.
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_READY,
            "site area (대지면적) not found after "
            f"{state.get('extract_attempts', 0)} extraction attempts",
        )

    g = StateGraph(MassingState)
    g.add_node("locate", locate)
    g.add_node("extract", extract)
    g.add_node("reconcile", reconcile)
    g.add_node("classify", classify)
    g.add_node("derive", derive)
    g.add_edge(START, "locate")
    g.add_edge("locate", "extract")
    g.add_edge("extract", "reconcile")
    g.add_edge("reconcile", "classify")
    g.add_edge("classify", "derive")
    g.add_conditional_edges(
        "derive",
        _route,
        {"done": END, "retry": "extract"},
    )
    return g.compile()


def make_resolve_program_node(
    settings: Settings,
    *,
    chain: Runnable | None = None,
    model: BaseChatModel | None = None,
) -> Callable[[MassingState], dict]:
    """Wrap the subgraph so the orchestrator composes it as one node."""
    sub = build_program_resolution_subgraph(settings, chain=chain, model=model)

    def resolve_program(state: MassingState) -> dict:
        out = sub.invoke(state)
        return {"analysis": out["analysis"], "inputs": out["inputs"]}

    return resolve_program
