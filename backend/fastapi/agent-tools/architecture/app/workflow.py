"""Brief-to-massing orchestrator — LangGraph `StateGraph` (ADR-19 §A19.8).

Top-level control flow only; composes nodes + the massing subgraph:

  START → fetch_brief → extract → massing(subgraph) → serialize → persist → END

- fetch_brief — docs-api read (X-User-Id-filtered) + readiness check (node).
- extract     — brief → ExtractedProgram via the LCEL extraction chain (node).
- massing     — resolve_site → compute (compiled subgraph added as a node).
- serialize   — rhino3dm → .3dm bytes (node → infra/serializer).
- persist     — write arch.outputs row + build GenerateMassingResponse (node).

Behavior preserved at the result level vs. the pre-Phase-2c flow; the only
intended functional change is the LLM call mechanism (httpx json_object →
ChatOpenAI.with_structured_output). MassingError raised in any node propagates
out of `graph.invoke()` unchanged so the FastAPI handler maps it as before.
"""

from __future__ import annotations

import logging
from uuid import UUID

from langchain_core.runnables import Runnable
from langgraph.graph import END, START, StateGraph

from shared_kernel.config import Settings
from shared_kernel.docs_client import DocsClient

from architecture.api.dtos import GenerateMassingRequest, GenerateMassingResponse
from architecture.app.graphs.massing import build_massing_subgraph
from architecture.app.nodes.extract import make_extract_node
from architecture.app.nodes.fetch_brief import make_fetch_brief_node
from architecture.app.nodes.persist import persist
from architecture.app.nodes.serialize import serialize
from architecture.app.state import MassingState

logger = logging.getLogger(__name__)


class MassingWorkflow:
    def __init__(
        self,
        settings: Settings,
        docs_client: DocsClient,
        *,
        extraction_chain: Runnable | None = None,
    ):
        self._settings = settings
        self._docs = docs_client
        self._extraction_chain = extraction_chain
        self._graph = self._build_graph()
        try:  # one-time visualization hook so Phase-3's richer graph is observable
            logger.info("architecture massing graph:\n%s", self._graph.get_graph().draw_ascii())
        except Exception:  # noqa: BLE001 — visualization is best-effort, never fatal
            logger.debug("graph ascii render unavailable")

    def _build_graph(self):
        g = StateGraph(MassingState)
        g.add_node("fetch_brief", make_fetch_brief_node(self._docs))
        g.add_node("extract", make_extract_node(self._settings, chain=self._extraction_chain))
        g.add_node("massing", build_massing_subgraph(self._settings))
        g.add_node("serialize", serialize)
        g.add_node("persist", persist)
        g.add_edge(START, "fetch_brief")
        g.add_edge("fetch_brief", "extract")
        g.add_edge("extract", "massing")
        g.add_edge("massing", "serialize")
        g.add_edge("serialize", "persist")
        g.add_edge("persist", END)
        return g.compile()

    def run(
        self,
        req: GenerateMassingRequest,
        *,
        user_id: UUID,
        user_sub: str | None,
    ) -> GenerateMassingResponse:
        final: MassingState = self._graph.invoke(
            {"req": req, "user_id": user_id, "user_sub": user_sub}
        )
        return final["response"]
