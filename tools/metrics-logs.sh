#!/usr/bin/env bash
# tools/metrics-logs.sh — operator log tail via /api/metrics/logs
# Per ADR-15 §19 + PRD M5-metrics.md §"Acceptance criteria — Backend" + issue #145.
#
# Usage:
#   tools/metrics-logs.sh <service> [since=15m] [search=<substr>] [limit=200]
#
# Examples:
#   tools/metrics-logs.sh rag-chat-api
#   tools/metrics-logs.sh docs-api since=1h limit=500
#   tools/metrics-logs.sh identity-api since=30m 'search=OAuth' limit=200
#
# Each positional after the service name is a single `key=value` pair that is
# forwarded to /api/metrics/logs verbatim. Use `--data-urlencode` semantics —
# the script never concatenates user input into the URL.
#
# Environment:
#   PLAYGROUND_BASE     base URL of the gateway (default http://localhost:18080)
#   PLAYGROUND_COOKIES  cookie jar path (default ~/.playground/cookies.txt)
#                       — must contain a valid PLAYGROUND_SESSION cookie.
#                       Obtain via browser login + cookie export, OR by hitting
#                       the OAuth flow from curl with a cookie-jar option.
#
# Logs UI lands in M5.1 (PRD §Out of scope + ADR-15 §19); until then this
# script is the operator's interface to the endpoint.

set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
    cat <<'USAGE'
usage: tools/metrics-logs.sh <service> [since=15m] [search=<substr>] [limit=200]

Tails /api/metrics/logs for the named service through the gateway.

Required: <service> — one of the ServiceAllowlist names
  (gateway, identity-api, docs-api, rag-ingestion-api, rag-chat-api,
   metrics-api, plus the four observability containers).

Optional positionals (each is a verbatim key=value pair):
  since=15m     time window (Loki since= syntax: 15m, 1h, 24h, 7d).
  search=foo    substring filter on the message body.
  limit=200     max entries (default 200; backend caps at 1000).

Env:
  PLAYGROUND_BASE      gateway base URL (default http://localhost:18080)
  PLAYGROUND_COOKIES   cookie jar path (default ~/.playground/cookies.txt)
USAGE
    exit 0
fi

SERVICE="${1:?usage: $0 <service> [since=15m] [search=...] [limit=200]}"
SINCE="${2:-since=15m}"
SEARCH="${3:-}"
LIMIT="${4:-limit=200}"

BASE="${PLAYGROUND_BASE:-http://localhost:18080}"
COOKIES="${PLAYGROUND_COOKIES:-$HOME/.playground/cookies.txt}"

if [[ ! -r "$COOKIES" ]]; then
    echo "error: cookie jar not readable at $COOKIES" >&2
    echo "       set PLAYGROUND_COOKIES or export a session cookie there." >&2
    exit 2
fi

# Each positional is forwarded verbatim via --data-urlencode so curl percent-
# encodes the value — no string concatenation into the URL means injection-
# safe even if `search=` contains shell metacharacters or quotes.
curl --silent --fail \
    --cookie "$COOKIES" \
    --get "$BASE/api/metrics/logs" \
    --data-urlencode "service=$SERVICE" \
    --data-urlencode "$SINCE" \
    --data-urlencode "$LIMIT" \
    ${SEARCH:+--data-urlencode "$SEARCH"} \
    | jq -r '.entries[] | "\(.ts) [\(.level)] [\(.service)] \(.message)"'
