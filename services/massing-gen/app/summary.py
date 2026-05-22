"""Korean fixed-format summary string per ADR-18 §5 + §A18.5.

Format: `"%d실 · %d층 · 총 %.0f m²"` (e.g. `"12실 · 3층 · 총 480 m²"`).
The FE renders this verbatim; no localization in the FE.
"""

from __future__ import annotations


def format_summary(*, room_count: int, floor_count: int, total_area_m2: float) -> str:
    return f"{room_count}실 · {floor_count}층 · 총 {total_area_m2:.0f} m²"
