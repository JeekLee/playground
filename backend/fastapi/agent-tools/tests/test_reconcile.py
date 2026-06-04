"""reconcile node tests (ADR-19 Phase 3a-2) — BriefAnalysis -> NormalizedBrief."""

from __future__ import annotations

from architecture.app.nodes.reconcile import reconcile_analysis
from architecture.domain.models import BriefAnalysis, ProgramItem, ZoneGross


def test_prefers_gross_zones_when_present() -> None:
    analysis = BriefAnalysis(
        program=[
            ProgramItem(name="Middle Lab", area_m2=5680.0, grade="above",
                        parent_zone="연구영역", is_net=True),
        ],
        zones_gross=[
            ZoneGross(name="연구영역", area_m2=26500.0, grade="above", net_ratio=0.75),
            ZoneGross(name="지하영역", area_m2=4500.0, grade="below"),
        ],
        site_area_m2=12000.0,
    )
    norm = reconcile_analysis(analysis)
    zone_names = {z.name for z in norm.zones}
    assert zone_names == {"연구영역", "지하영역"}
    # named sub-spaces carried separately for the footprint driver.
    assert [s.name for s in norm.sub_spaces] == ["Middle Lab"]
    research = next(z for z in norm.zones if z.name == "연구영역")
    assert research.net_ratio == 0.75


def test_falls_back_to_program_as_zones() -> None:
    analysis = BriefAnalysis(
        program=[
            ProgramItem(name="업무", area_m2=20000.0, grade="above"),
            ProgramItem(name="지하주차", area_m2=4500.0, grade="below"),
        ],
        site_area_m2=12000.0,
    )
    norm = reconcile_analysis(analysis)
    assert {z.name for z in norm.zones} == {"업무", "지하주차"}
    # program also doubles as sub-spaces when no gross breakdown exists.
    assert {s.name for s in norm.sub_spaces} == {"업무", "지하주차"}


def test_ratio_normalized_from_percent() -> None:
    analysis = BriefAnalysis(
        program=[ProgramItem(name="업무", area_m2=1000.0, grade="above")],
        site_area_m2=5000.0,
        coverage_ratio_max=80.0,  # given as a percent
    )
    norm = reconcile_analysis(analysis)
    assert norm.coverage_ratio_max == 0.8


def test_ratio_already_fraction_kept() -> None:
    analysis = BriefAnalysis(
        program=[ProgramItem(name="업무", area_m2=1000.0, grade="above")],
        site_area_m2=5000.0,
        coverage_ratio_max=0.6,
    )
    norm = reconcile_analysis(analysis)
    assert norm.coverage_ratio_max == 0.6


def test_consistency_note_when_gross_deviates() -> None:
    analysis = BriefAnalysis(
        program=[ProgramItem(name="업무", area_m2=1000.0, grade="above")],
        zones_gross=[ZoneGross(name="업무영역", area_m2=10000.0, grade="above")],
        site_area_m2=5000.0,
        total_gfa_m2=20000.0,  # 10k vs 20k -> 50% off
    )
    norm = reconcile_analysis(analysis)
    assert norm.consistency_note is not None


def test_no_consistency_note_within_tolerance() -> None:
    analysis = BriefAnalysis(
        program=[ProgramItem(name="업무", area_m2=1000.0, grade="above")],
        zones_gross=[ZoneGross(name="업무영역", area_m2=20100.0, grade="above")],
        site_area_m2=5000.0,
        total_gfa_m2=20000.0,  # 0.5% off -> within ±5%
    )
    norm = reconcile_analysis(analysis)
    assert norm.consistency_note is None
