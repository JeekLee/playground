package com.playground.metrics.api.controller;

import com.playground.metrics.app.BuildDashboardUseCase;
import com.playground.metrics.app.dto.DashboardResponse;
import com.playground.metrics.app.port.IpRateLimitPort;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive controller for {@code GET /api/metrics/dashboard} per spec §5.1.
 * Gateway routes {@code /api/metrics/**} with {@code StripPrefix=2}, so this
 * controller listens on {@code /dashboard}.
 *
 * <p>Slice 1 hits the no-op {@link IpRateLimitPort} (always allowed); slice
 * 2 (Task 8) wires the Redisson per-IP bucket per ADR-15 §C.
 */
@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final BuildDashboardUseCase useCase;
    private final IpRateLimitPort ipRateLimit;

    @GetMapping(value = {"/dashboard", "/api/metrics/dashboard"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DashboardResponse> dashboard(
            @RequestParam(value = "range", required = false) String rangeToken,
            ServerWebExchange exchange) {
        Range range;
        try {
            range = Range.parseOrDefault(rangeToken);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(MetricsErrorCode.INVALID_RANGE, rangeToken).build();
        }
        String clientIp = clientIp(exchange);
        return ipRateLimit.tryAcquire(clientIp)
                .flatMap(allowed -> {
                    if (!Boolean.TRUE.equals(allowed)) {
                        return Mono.error(ExceptionCreator.of(MetricsErrorCode.RATE_LIMITED).build());
                    }
                    return useCase.execute(range);
                });
    }

    static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        // InetSocketAddress can be unresolved (hostname-only) when the request
        // arrives through a reverse proxy that hasn't set X-Forwarded-For —
        // guard the inner getAddress() too. Without this the dashboard 500s
        // with NPE on every gateway-proxied call.
        var addr = remote.getAddress();
        return addr == null ? remote.getHostString() : addr.getHostAddress();
    }
}
