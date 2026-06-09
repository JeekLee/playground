"""Brief-edit orchestrator — the refine_massing LangGraph (spec D4).

  START → load_recipe → apply_edits → classify → derive → compute
        → serialize → store_3dm → store_glb → respond → END

Reuses classify/derive/compute/serialize/store_3dm/store_glb/respond unchanged;
the only new nodes are load_recipe (fetch + parse the embedded recipe) and
apply_edits (apply the typed ops). The slow LLM extraction (fetch_brief →
classify path of generate) is skipped — refine re-enters at classify with the
edited NormalizedBrief, so the 건폐율/용적률/footprint/slot-fit gates all re-run.
"""

from __future__ import annotations

import logging
from typing import Iterator
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from shared_kernel.config import Settings
from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.api.dtos import GenerateMassingResponse, RefineMassingRequest
from architecture.app.nodes.apply_edits import apply_edits
from architecture.app.nodes.classify import classify
from architecture.app.nodes.compute import compute
from architecture.app.nodes.derive import make_derive_node
from architecture.app.nodes.load_recipe import make_load_recipe_node
from architecture.app.nodes.respond import respond
from architecture.app.nodes.serialize import serialize
from architecture.app.nodes.store_3dm import store_3dm
from architecture.app.nodes.store_glb import store_glb
from architecture.app.stages import refine_progress_event
from architecture.app.state import MassingState

logger = logging.getLogger(__name__)


class RefineMassingWorkflow:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._graph = self._build_graph()

    def _build_graph(self):
        g = StateGraph(MassingState)
        g.add_node("load_recipe", make_load_recipe_node(self._settings))
        g.add_node("apply_edits", apply_edits)
        g.add_node("classify", classify)
        g.add_node("derive", make_derive_node(self._settings))
        g.add_node("compute", compute)
        g.add_node("serialize", serialize)
        g.add_node("store_3dm", store_3dm)
        g.add_node("store_glb", store_glb)
        g.add_node("respond", respond)
        g.add_edge(START, "load_recipe")
        g.add_edge("load_recipe", "apply_edits")
        g.add_edge("apply_edits", "classify")
        g.add_edge("classify", "derive")
        g.add_edge("derive", "compute")
        g.add_edge("compute", "serialize")
        g.add_edge("serialize", "store_3dm")
        g.add_edge("store_3dm", "store_glb")
        g.add_edge("store_glb", "respond")
        g.add_edge("respond", END)
        return g.compile()

    def _seed(self, req: RefineMassingRequest, user_id: UUID, user_sub: str | None) -> dict:
        return {
            "base_storage_key": req.base_storage_key,
            "edits": req.edits,
            "user_id": user_id,
            "user_sub": user_sub,
        }

    def run(
        self, req: RefineMassingRequest, *, user_id: UUID, user_sub: str | None
    ) -> GenerateMassingResponse:
        final: MassingState = self._graph.invoke(self._seed(req, user_id, user_sub))
        return final["response"]

    def stream(
        self, req: RefineMassingRequest, *, user_id: UUID, user_sub: str | None
    ) -> Iterator[dict]:
        """Progress + terminal events, mirroring MassingWorkflow.stream but with
        the refine stage map and no extract-attempt special case."""
        last_values: MassingState | None = None
        try:
            for ns, mode, payload in self._graph.stream(
                self._seed(req, user_id, user_sub),
                stream_mode=["debug", "values"],
                subgraphs=True,
            ):
                if mode == "values":
                    if not ns:
                        last_values = payload
                    continue
                if payload.get("type") != "task":
                    continue
                node = payload.get("payload", {}).get("name", "")
                ev = refine_progress_event(node)
                if ev is not None:
                    yield ev
            if last_values is None or "response" not in last_values:
                raise MassingError(
                    MassingErrorCode.INTERNAL, "refine graph finished without a response"
                )
            response: GenerateMassingResponse = last_values["response"]
            yield {"event": "result", **response.model_dump(by_alias=True, mode="json")}
        except MassingError as exc:
            yield {
                "event": "error",
                "code": exc.code.value,
                "message": exc.message,
                "status": int(exc.code.http_status),
            }
        except Exception as exc:  # noqa: BLE001 — always terminate with one event
            logger.exception("refine stream failed")
            yield {
                "event": "error",
                "code": MassingErrorCode.INTERNAL.value,
                "message": str(exc),
                "status": 500,
            }
