"""매싱 파이프라인 진행 스테이지 맵 (tool-streaming spec D1).

stage = LangGraph 노드명(서브그래프 내부 포함), label = FE가 verbatim
렌더하는 한국어 텍스트 (ADR-18 §5 summary 전례). 맵에 없는 노드
(respond, resolve_program 래퍼 등)는 progress를 발신하지 않는다.
"""

from __future__ import annotations

# 순서가 의미를 가진다 — workflow._build_graph + program_resolution 서브그래프의
# 위상 순서와 일치해야 stageIndex가 단조 증가한다. 노드를 추가/개명하면 여기와
# test_stream_yields_progress_sequence_then_result(실제 그래프 실행 핀)를 갱신할 것.
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

# Refine pipeline (spec D4) — skips fetch_brief/locate/extract/reconcile; the
# LLM extraction is replaced by load_recipe + apply_edits, then classify/derive
# re-run deterministically.
REFINE_STAGES: tuple[tuple[str, str], ...] = (
    ("load_recipe", "기존 매싱 불러오기"),
    ("apply_edits", "수정 반영"),
    ("classify", "공간 분류"),
    ("derive", "층수·풋프린트 재산정"),
    ("compute", "매싱 재계산"),
    ("serialize", "3D 모델 생성"),
    ("store_3dm", "파일 저장"),
    ("store_glb", "미리보기 생성"),
)
REFINE_STAGE_COUNT = len(REFINE_STAGES)


def _make_progress_event(stages: tuple[tuple[str, str], ...]):
    index = {name: i + 1 for i, (name, _) in enumerate(stages)}
    label = dict(stages)
    count = len(stages)

    def progress_event(node: str, attempt: int | None = None) -> dict | None:
        """노드-시작 → progress 이벤트 dict. 맵 밖 노드는 None.

        `attempt`는 2 이상일 때만 필드로 포함 (spec W1)."""
        if node not in index:
            return None
        ev: dict = {
            "event": "progress",
            "stage": node,
            "label": label[node],
            "stageIndex": index[node],
            "stageCount": count,
        }
        if attempt is not None and attempt >= 2:
            ev["attempt"] = attempt
        return ev

    return progress_event


progress_event = _make_progress_event(STAGES)
refine_progress_event = _make_progress_event(REFINE_STAGES)
