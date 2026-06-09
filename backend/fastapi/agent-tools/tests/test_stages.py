from architecture.app.stages import (
    REFINE_STAGE_COUNT,
    progress_event,
    refine_progress_event,
)


def test_generate_progress_unchanged():
    ev = progress_event("extract")
    assert ev["stageIndex"] == 3 and ev["stageCount"] == 10
    assert progress_event("respond") is None


def test_refine_progress_sequence():
    assert REFINE_STAGE_COUNT == 8
    assert refine_progress_event("load_recipe")["stageIndex"] == 1
    assert refine_progress_event("store_glb")["stageIndex"] == 8
    assert refine_progress_event("store_glb")["stageCount"] == 8
    assert refine_progress_event("extract") is None  # not a refine stage
    assert refine_progress_event("respond") is None
