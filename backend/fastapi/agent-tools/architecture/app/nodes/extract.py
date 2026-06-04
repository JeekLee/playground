"""extract node (ADR-19 §A19.9) — invokes the brief-extraction LCEL chain.

Single step, no branching. Builds/holds the chain (model from
shared_kernel.llm) and invokes it on ``state["detail"].body``.
"""

from __future__ import annotations

from typing import Callable

from langchain_core.runnables import Runnable

from architecture.app.chains.brief_extraction import (
    build_brief_extraction_chain,
    extract_program,
)
from architecture.app.state import MassingState
from shared_kernel.config import Settings
from shared_kernel.llm import get_chat_model


def make_extract_node(
    settings: Settings,
    *,
    chain: Runnable | None = None,
) -> Callable[[MassingState], dict]:
    """Build the extract node.

    ``chain`` may be injected (tests inject a fake / RunnableLambda); otherwise
    a default chain is built from the shared_kernel ChatOpenAI factory.
    """
    extraction_chain = chain or build_brief_extraction_chain(get_chat_model(settings))

    def extract(state: MassingState) -> dict:
        return {"extracted": extract_program(extraction_chain, state["detail"].body)}

    return extract
