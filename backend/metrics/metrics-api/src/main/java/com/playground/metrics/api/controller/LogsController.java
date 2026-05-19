package com.playground.metrics.api.controller;

import com.playground.metrics.app.QueryLogsUseCase;
import com.playground.metrics.app.dto.LogsResponse;
import com.playground.metrics.app.port.UserRateLimitPort;
import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive controller for {@code GET /api/metrics/logs} per spec §5.1 +
 * §5.4. Authenticated-only — {@link com.playground.metrics.api.config.MetricsSecurityConfig}
 * rejects requests without {@code X-User-Id}. The header is also consumed
 * here for the per-user rate-limit key.
 *
 * <p>Gateway routes {@code /api/metrics/**} with {@code StripPrefix=2}, so
 * this controller listens on {@code /logs}.
 */
@RestController
public class LogsController {

    private final QueryLogsUseCase useCase;
    private final UserRateLimitPort userRateLimit;

    public LogsController(QueryLogsUseCase useCase, UserRateLimitPort userRateLimit) {
        this.useCase = useCase;
        this.userRateLimit = userRateLimit;
    }

    @GetMapping(value = {"/logs", "/api/metrics/logs"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LogsResponse> logs(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam("service") String service,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "limit", required = false) Integer limit) {
        if (userId == null || userId.isBlank()) {
            throw ExceptionCreator.of(MetricsErrorCode.AUTH_REQUIRED).build();
        }
        return userRateLimit.tryAcquire(userId)
                .flatMap(allowed -> {
                    if (!Boolean.TRUE.equals(allowed)) {
                        return Mono.error(ExceptionCreator.of(MetricsErrorCode.RATE_LIMITED).build());
                    }
                    return useCase.execute(service, since, search, limit);
                });
    }
}
