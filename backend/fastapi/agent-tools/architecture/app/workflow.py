"""Brief-to-massing orchestrator — LangGraph `StateGraph` (ADR-19 §A19.8).

Top-level control flow only; composes nodes + subgraphs (ADR-19 Phase 3a-2):

  START → fetch_brief → resolve_program(subgraph) → compute
        → serialize → store_3dm → store_glb → respond → END

- fetch_brief     — docs-api read (X-User-Id-filtered) + readiness check (node).
- resolve_program — the 5-stage interpretation sub-flow with the bounded
                    re-prompt loop (locate → extract → reconcile → classify →
                    derive), compiled subgraph composed as one node.
- compute         — deterministic massing (MassingInputs → boxes) node.
- serialize       — rhino3dm → .3dm bytes (node → infra/serializer).
- store_3dm       — upload .3dm bytes to MinIO, set storage_key in state (ADR-20 §D3 revised).
- store_glb       — best-effort preview .glb upload at the same prefix (extension swapped).
- respond         — build the {result, artifact} envelope where artifact carries
                    metadata only (no base64); rag-chat records the storageKey.

The pipeline (7 interpretation stages + fetch/respond plumbing) replaces the
M8 `ceil(GFA/lot)` massing; floors are footprint-driven by the largest single
space (Phase 3a-2). MassingError raised in any node propagates out of
`graph.invoke()` unchanged so the FastAPI handler maps it as before.

`stream()` yields per-node progress events then a terminal result|error event
(tool-streaming spec D1); `run()` is unchanged for backward compatibility.
"""

from __future__ import annotations

import logging
from typing import Iterator
from uuid import UUID

from langchain_core.runnables import Runnable
from langgraph.graph import END, START, StateGraph

from shared_kernel.config import Settings
from shared_kernel.docs_client import DocsClient
from shared_kernel.errors import MassingError, MassingErrorCode

from architecture.api.dtos import GenerateMassingRequest, GenerateMassingResponse
from architecture.app.graphs.program_resolution import make_resolve_program_node
from architecture.app.nodes.compute import compute
from architecture.app.nodes.fetch_brief import make_fetch_brief_node
from architecture.app.nodes.respond import respond
from architecture.app.nodes.serialize import serialize
from architecture.app.nodes.store_3dm import store_3dm
from architecture.app.nodes.store_glb import store_glb
from architecture.app.stages import progress_event
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
        g.add_node(
            "resolve_program",
            make_resolve_program_node(self._settings, chain=self._extraction_chain),
        )
        g.add_node("compute", compute)
        g.add_node("serialize", serialize)
        g.add_node("store_3dm", store_3dm)
        g.add_node("store_glb", store_glb)
        g.add_node("respond", respond)
        g.add_edge(START, "fetch_brief")
        g.add_edge("fetch_brief", "resolve_program")
        g.add_edge("resolve_program", "compute")
        g.add_edge("compute", "serialize")
        g.add_edge("serialize", "store_3dm")
        g.add_edge("store_3dm", "store_glb")
        g.add_edge("store_glb", "respond")
        g.add_edge("respond", END)
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

    def stream(
        self,
        req: GenerateMassingRequest,
        *,
        user_id: UUID,
        user_sub: str | None,
    ) -> Iterator[dict]:
        """진행 + 터미널 이벤트 제너레이터 (tool-streaming spec D1/W1).

        graph.stream(stream_mode=["debug","values"], subgraphs=True):
        debug `task` 페이로드가 노드-시작 신호 (updates 모드는 완료-후라
        "진행 중" 의미 불일치), 외부 그래프의 마지막 `values`가 최종
        state. 이벤트 튜플은 (namespace, mode, payload) — 2026-06-06
        LangGraph 0.2.60 컨테이너 실측으로 검증.

        모든 종료는 정확히 1개의 터미널 이벤트(result | error)로 끝난다.
        heartbeat는 여기 없음 — async 브리지(router)가 주입.
        """
        extract_attempts = 0
        last_values: MassingState | None = None
        try:
            for ns, mode, payload in self._graph.stream(
                {"req": req, "user_id": user_id, "user_sub": user_sub},
                stream_mode=["debug", "values"],
                subgraphs=True,
            ):
                if mode == "values":
                    if not ns:  # 외부 그래프 state만 (서브그래프 values 제외)
                        last_values = payload
                    continue
                if payload.get("type") != "task":
                    continue
                node = payload.get("payload", {}).get("name", "")
                attempt = None
                if node == "extract":
                    extract_attempts += 1
                    attempt = extract_attempts
                ev = progress_event(node, attempt)
                if ev is not None:
                    yield ev

            if last_values is None or "response" not in last_values:
                raise MassingError(
                    MassingErrorCode.INTERNAL, "graph finished without a response"
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
        except Exception as exc:  # noqa: BLE001 — 스트림은 항상 터미널 이벤트로 끝난다
            logger.exception("massing stream failed")
            yield {
                "event": "error",
                "code": MassingErrorCode.INTERNAL.value,
                "message": str(exc),
                "status": 500,
            }
