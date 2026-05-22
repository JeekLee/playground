package com.playground.metrics.app.dto;

import java.util.List;

/**
 * Standalone service health grid payload per spec §5.1 — same
 * {@code services[]} shape as {@link DashboardResponse#services()}.
 */
public record ServicesResponse(List<DashboardResponse.ServiceCell> services) {
}
