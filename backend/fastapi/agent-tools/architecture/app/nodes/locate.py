"""locate node (ADR-19 Phase 3a-2) — reduce the brief body to the
massing-governing content.

Single job: `state["detail"].body` -> `state["excerpt"]`.

This is a LIGHT, rule/heuristic section selector — **no LLM call**. The
extract node owns the single heavy LLM call, and the 60s tool breaker must
hold; a second heavyweight model invocation here would threaten it.

Heuristic:
- Split the body into blocks (markdown sections / paragraphs).
- Keep any block containing a massing keyword (면적, 규모, 대지, 건폐율,
  용적률, 연면적, 주차, 층, 영역, Lab, ㎡, m²) plus a one-block margin on
  each side, so context around a hit survives.
- Drop blocks that are obvious procedural boilerplate (심사/일정/제출서식/
  자격/문의/접수) UNLESS they also carry a massing keyword.
- If nothing matches, pass the whole body through (never starve extract).
- Cap the output size so a pathological brief can't blow the LLM context.

The result is a strict subset of the original body text (order preserved),
so extract sees only real brief content, never invented text.
"""

from __future__ import annotations

import re

from architecture.app.state import MassingState

# Keywords whose presence marks a block as massing-relevant. Case-insensitive
# for the latin tokens (Lab, m²); Korean matched verbatim.
_KEEP_KEYWORDS = (
    "면적",
    "규모",
    "대지",
    "건폐율",
    "용적률",
    "연면적",
    "주차",
    "층",
    "영역",
    "전용",
    "공용",
    "합계",
    "lab",
    "㎡",
    "m²",
    "m2",
)

# Procedural boilerplate markers — dropped unless the block also has a keyword.
_DROP_KEYWORDS = (
    "심사",
    "일정",
    "제출서식",
    "제출 서식",
    "자격",
    "문의",
    "접수",
    "유의사항",
    "시상",
    "상금",
    "저작권",
    "공모일정",
)

# Output safety cap (chars). Generous — a real brief excerpt is well under this;
# the cap only guards against a pathological document.
_MAX_EXCERPT_CHARS = 16000


def _split_blocks(body: str) -> list[str]:
    """Split into blocks on blank lines, keeping markdown headings attached to
    the following content where possible. A block is a run of non-blank lines."""
    # Normalize newlines, split on one-or-more blank lines.
    parts = re.split(r"\n\s*\n", body.strip())
    return [p for p in (part.strip() for part in parts) if p]


def _has_keyword(block: str, keywords: tuple[str, ...]) -> bool:
    low = block.lower()
    return any(kw in low for kw in keywords)


def locate(state: MassingState) -> dict:
    body = state["detail"].body or ""
    blocks = _split_blocks(body)
    if not blocks:
        return {"excerpt": body.strip()}

    keep_idx: set[int] = set()
    for i, block in enumerate(blocks):
        keep = _has_keyword(block, _KEEP_KEYWORDS)
        if keep and _has_keyword(block, _DROP_KEYWORDS):
            # Boilerplate that nonetheless mentions a massing fact: keep it,
            # the fact wins over the boilerplate marker.
            pass
        if keep:
            keep_idx.add(i)
            # one-block margin on each side preserves surrounding context
            if i > 0:
                keep_idx.add(i - 1)
            if i + 1 < len(blocks):
                keep_idx.add(i + 1)

    if not keep_idx:
        # Nothing matched — pass the whole body through (bounded).
        return {"excerpt": body.strip()[:_MAX_EXCERPT_CHARS]}

    selected = [blocks[i] for i in sorted(keep_idx)]
    excerpt = "\n\n".join(selected)
    return {"excerpt": excerpt[:_MAX_EXCERPT_CHARS]}
