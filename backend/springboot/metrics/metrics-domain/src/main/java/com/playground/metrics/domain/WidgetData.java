package com.playground.metrics.domain;

import java.util.List;

/**
 * Generic widget payload used by {@code BuildDashboardUseCase} to ship
 * timeseries data alongside the {@code degraded} flag per ADR-15 §C +
 * spec §8.2. The frontend renders {@code "degraded": true} as the
 * "⚠ Failed to refresh" overlay (spec §7.3 Story 15).
 *
 * <p>Slice 1 doesn't emit {@code degraded=true} (the {@code PromQlBudgetEnforcer}
 * stub is no-op); slice 2 (Task 8) flips this bit when a per-widget query
 * exceeds the 10s budget.
 */
public record WidgetData(String metric, List<TimeseriesPoint> points, boolean degraded) {
}
