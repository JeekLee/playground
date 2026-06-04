"""Content-Disposition header tests per ADR-18 §21.

The .3dm download filename is `massing-<briefSlug>-<ts>.3dm`, and the
brief slug preserves Hangul (slug.py). HTTP header values are encoded
latin-1 by ASGI servers, so a raw Korean filename in the header crashes
the response with UnicodeEncodeError (observed as a 500 on every
Korean-titled brief). The header must follow RFC 6266: an ASCII
`filename=` fallback plus a percent-encoded `filename*=UTF-8''` form.
"""

from __future__ import annotations

from architecture.content_disposition import content_disposition_attachment


def test_latin1_safe_for_korean_filename() -> None:
    header = content_disposition_attachment("massing-설계공모지침서-20260601-093210.3dm")
    # The actual bug: ASGI servers encode header values as latin-1.
    header.encode("latin-1")  # must not raise


def test_uses_rfc6266_extended_form_with_percent_encoded_utf8() -> None:
    header = content_disposition_attachment("massing-설계공모지침서-20260601-093210.3dm")
    assert header.startswith("attachment;")
    assert "filename*=UTF-8''" in header
    # '설' → UTF-8 EC 84 A4 → percent-encoded
    assert "%EC%84%A4" in header


def test_ascii_filename_unchanged_in_fallback() -> None:
    header = content_disposition_attachment("massing-brief-20260601-093210.3dm")
    header.encode("latin-1")
    assert 'filename="massing-brief-20260601-093210.3dm"' in header
