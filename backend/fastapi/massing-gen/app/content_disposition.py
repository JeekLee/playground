"""RFC 6266 Content-Disposition builder per ADR-18 §21.

HTTP header values are latin-1 encoded by ASGI servers, so a filename
that preserves Hangul (slug.py keeps U+AC00..U+D7A3) cannot go into a
bare ``filename="..."``. RFC 6266 §4.1 + RFC 5987 solve this: emit an
ASCII ``filename=`` fallback plus a percent-encoded ``filename*=UTF-8''``
form that modern browsers prefer.
"""

from __future__ import annotations

from urllib.parse import quote


def content_disposition_attachment(filename: str) -> str:
    """Build a latin-1-safe ``attachment`` Content-Disposition value.

    Non-ASCII characters are dropped from the ASCII fallback and carried
    losslessly in the percent-encoded ``filename*`` form.
    """
    ascii_fallback = filename.encode("ascii", "ignore").decode("ascii")
    if not ascii_fallback:
        ascii_fallback = "massing.3dm"
    # Guard the quoted-string fallback against embedded quotes/backslashes.
    ascii_fallback = ascii_fallback.replace("\\", "_").replace('"', "_")
    encoded = quote(filename, safe="")
    return f"attachment; filename=\"{ascii_fallback}\"; filename*=UTF-8''{encoded}"
