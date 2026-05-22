package com.playground.metrics.domain;

import java.time.Instant;

/**
 * Single log line returned by {@code GET /api/metrics/logs}. Shape per spec
 * §5.4.
 *
 * @param ts log line timestamp (UTC instant; serialized as ISO-8601)
 * @param service originating service container name
 * @param level log level extracted from the JSON pipeline (DEBUG / INFO / WARN / ERROR)
 * @param message free-text log message body
 */
public record LogEntry(Instant ts, String service, String level, String message) {
}
