"""Korean fixed-format summary string per ADR-18 §5 + ADR-19 Phase 3a.

Format reports above-grade floors and (when present) basement levels:
- no basement:  `"{n}실 · 지상 {a}층 · 총 {area} m²"`
- with basement: `"{n}실 · 지상 {a}층 + 지하 {b}층 · 총 {area} m²"`

The FE renders this verbatim; no localization in the FE.
"""

from __future__ import annotations


def format_summary(
    *,
    room_count: int,
    floors_above: int,
    basement_levels: int,
    total_area_m2: float,
) -> str:
    floors = f"지상 {floors_above}층"
    if basement_levels:
        floors += f" + 지하 {basement_levels}층"
    return f"{room_count}실 · {floors} · 총 {total_area_m2:.0f} m²"
