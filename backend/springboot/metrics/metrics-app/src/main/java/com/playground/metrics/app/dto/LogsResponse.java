package com.playground.metrics.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playground.metrics.domain.LogEntry;
import java.util.List;

/**
 * Auth-only logs payload per spec §5.4. Field names match the canonical
 * wire shape verbatim — the frontend (M5.1 logs UI) reads the same spec.
 *
 * <p>Slice 1 always returns {@code hasMore=false} and {@code nextCursor=null}
 * (cursor pagination is M5.1 territory; P0 caps the line count at 200).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogsResponse(
        List<LogEntry> entries,
        boolean hasMore,
        String nextCursor) {
}
