"""매싱 파이프라인 진행 스테이지 맵 (tool-streaming spec D1).

stage = LangGraph 노드명(서브그래프 내부 포함), label = FE가 verbatim
렌더하는 한국어 텍스트 (ADR-18 §5 summary 전례). 맵에 없는 노드
(respond, resolve_program 래퍼 등)는 progress를 발신하지 않는다.
"""

from __future__ import annotations

STAGES: tuple[tuple[str, str], ...] = (
    ("fetch_brief", "브리프 조회"),
    ("locate", "면적 정보 탐색"),
    ("extract", "공간 프로그램 추출 중"),
    ("reconcile", "프로그램 정합"),
    ("classify", "공간 분류"),
    ("derive", "층수·풋프린트 산정"),
    ("compute", "매싱 계산"),
    ("serialize", "3D 모델 생성"),
    ("store_3dm", "파일 저장"),
    ("store_glb", "미리보기 생성"),
)
STAGE_COUNT = len(STAGES)
_INDEX = {name: i + 1 for i, (name, _) in enumerate(STAGES)}
_LABEL = dict(STAGES)


def progress_event(node: str, attempt: int | None = None) -> dict | None:
    """노드-시작 → progress 이벤트 dict. 맵 밖 노드는 None.

    `attempt`는 2 이상일 때만 필드로 포함 (spec W1)."""
    if node not in _INDEX:
        return None
    ev: dict = {
        "event": "progress",
        "stage": node,
        "label": _LABEL[node],
        "stageIndex": _INDEX[node],
        "stageCount": STAGE_COUNT,
    }
    if attempt is not None and attempt >= 2:
        ev["attempt"] = attempt
    return ev
