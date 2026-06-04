"""Program-resolution subgraph (ADR-19 §A19.8 / Phase 3a).

Owns the extract → resolve sub-flow *with the re-prompt loop*:

    START → extract → resolve → (END | back to extract, bounded)

- extract: brief body → BriefAnalysis via the LCEL extraction chain.
- resolve: BriefAnalysis + request → MassingInputs (rule-first defaults).

If resolve finds a hard-required input missing (대지면적 → MassingError
BRIEF_NOT_READY), the conditional edge loops back to a re-extraction with an
added instruction, up to a bounded retry count. After the budget is spent the
BRIEF_NOT_READY error propagates so the FastAPI handler maps it (422). Any
other MassingError (e.g. the coverage gate) propagates immediately — only the
missing-site-area case loops.

This is composed as a single node ("resolve_program") in the orchestrator.
"""

from __future__ import annotations

import logging
from typing import Callable

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable
from langgraph.graph import END, START, StateGraph

from architecture.app.chains.brief_extraction import (
    REPROMPT_SITE_AREA_INSTRUCTION,
    build_brief_extraction_chain,
    extract_brief_analysis,
)
from architecture.app.nodes.resolve import resolve_inputs
from architecture.app.state import MassingState
from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode
from shared_kernel.llm import get_chat_model

logger = logging.getLogger(__name__)

# Bounded re-prompt budget: first pass + this many re-extractions.
MAX_EXTRACT_RETRIES = 2


def build_program_resolution_subgraph(
    settings: Settings,
    *,
    chain: Runnable | None = None,
    model: BaseChatModel | None = None,
):
    """Compile the extract → resolve subgraph with the re-prompt loop.

    - ``chain`` (tests): a fixed injected extraction chain; re-prompts reuse it
      verbatim (no prompt rebuild possible / needed).
    - ``model``: a BaseChatModel; the base chain is built from it, and a
      re-prompt rebuilds the chain with an added site-area instruction.
    - neither: the default ChatOpenAI factory is used.
    """
    base_model = model if (chain is None) else None
    if chain is None and base_model is None:
        base_model = get_chat_model(settings)

    base_chain = chain or build_brief_extraction_chain(base_model)  # type: ignore[arg-type]

    def _reprompt_chain() -> Runnable:
        if chain is not None or base_model is None:
            # Injected chain can't be rebuilt with a new prompt; reuse it.
            return base_chain
        return build_brief_extraction_chain(
            base_model, extra_instruction=REPROMPT_SITE_AREA_INSTRUCTION
        )

    def extract(state: MassingState) -> dict:
        attempts = state.get("extract_attempts", 0)
        active = base_chain if attempts == 0 else _reprompt_chain()
        analysis = extract_brief_analysis(active, state["detail"].body)
        return {"analysis": analysis, "extract_attempts": attempts + 1}

    def resolve(state: MassingState) -> dict:
        try:
            inputs = resolve_inputs(state["analysis"], state["req"], settings)
        except MassingError as exc:
            if exc.code == MassingErrorCode.BRIEF_NOT_READY:
                # Signal the conditional edge; do not write `inputs`.
                logger.info(
                    "resolve missing site area (attempt %d): %s",
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
    g.add_node("extract", extract)
    g.add_node("resolve", resolve)
    g.add_edge(START, "extract")
    g.add_edge("extract", "resolve")
    g.add_conditional_edges(
        "resolve",
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
