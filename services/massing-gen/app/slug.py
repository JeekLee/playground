"""Brief slug helper per ADR-18 §21.

Drives the Content-Disposition filename on the .3dm download:
`massing-<briefSlug>-<timestamp>.3dm`. The slug is derived from the brief
title — Korean Hangul + ASCII alphanumerics preserved, everything else
collapsed to '-'. Capped at 40 chars.
"""

from __future__ import annotations

import re

_NON_SLUG_CHAR = re.compile(r"[^\w가-힣]+", flags=re.UNICODE)
_MAX_SLUG_LEN = 40


def briefslug(title: str) -> str:
    """Convert a brief title to a filesystem-safe slug.

    Preserves:
    - ASCII alphanumerics + underscore (\\w covers them)
    - Hangul syllables (U+AC00..U+D7A3)
    Collapses everything else to '-'. Strips leading/trailing '-'.
    Empty input → 'brief'.
    """
    if title is None or not title.strip():
        return "brief"
    cleaned = _NON_SLUG_CHAR.sub("-", title.strip())
    cleaned = cleaned.strip("-")
    if not cleaned:
        return "brief"
    return cleaned[:_MAX_SLUG_LEN]
