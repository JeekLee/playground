"""Massing subgraph (ADR-19 §A19.8 / Phase 3a) — deterministic compute.

The Phase-2c `resolve_site` step is superseded by the rule-first resolve
(now part of the program-resolution subgraph, which produces the validated
`MassingInputs`). This subgraph is the deterministic compute stage: it reads
`state["inputs"]` and writes `state["boxes"]`. The coverage gate is already
enforced by `MassingInputs` validation upstream.

Kept as a compiled subgraph-as-node so the orchestrator vocabulary (graphs/
nodes) stays stable and a future converge/optimization loop can land here.
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from architecture.app.state import MassingState
from architecture.domain.algorithm import compute_massing
from shared_kernel.config import Settings


def build_massing_subgraph(settings: Settings):  # noqa: ARG001 — settings reserved for future converge loop
    """Compile the deterministic compute subgraph (inputs -> boxes)."""

    def compute(state: MassingState) -> dict:
        return {"boxes": compute_massing(state["inputs"])}

    g = StateGraph(MassingState)
    g.add_node("compute", compute)
    g.add_edge(START, "compute")
    g.add_edge("compute", END)
    return g.compile()
