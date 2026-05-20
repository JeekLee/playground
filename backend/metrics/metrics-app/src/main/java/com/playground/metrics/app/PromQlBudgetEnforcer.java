package com.playground.metrics.app;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Per-PromQL-query budget enforcer per ADR-15 §2 + §16 + spec §8.2.
 *
 * <p>The {@link BuildDashboardUseCase} composes ~19 PromQL {@code Mono}s in
 * parallel via {@link Mono#zip}; the slowest single query gates the response.
 * Without a per-query cap, a misbehaving Prometheus instance or a runaway
 * aggregation would block the whole dashboard's P95. This enforcer wraps each
 * widget's {@code Mono} with a {@link Duration} timeout and substitutes a
 * caller-supplied degraded sentinel (e.g.,
 * {@link com.playground.metrics.app.dto.DashboardResponse.JvmCell#degraded}
 * with {@code degraded=true}) when the budget is exceeded.
 *
 * <p>Default budget is {@value #DEFAULT_BUDGET_SECONDS} seconds (ADR-15 §18 —
 * {@code METRICS_PROMQL_BUDGET_S=10}). The budget is per-PromQL-query, not
 * per-request — multiple parallel queries each get the full budget, and the
 * overall dashboard P95 target (400 ms per ADR-15 §16) sits well inside it.
 * The budget is a fail-safe, not a SLA: most queries return in <100 ms.
 *
 * <p>Logs API ({@code /api/metrics/logs}) does NOT participate in this budget
 * (ADR-15 §7 — the Loki adapter has its own 15s {@code WebClient} timeout).
 * The {@link com.playground.metrics.app.QueryLogsUseCase} does not call this
 * enforcer; only the dashboard composition does.
 */
@Component
public class PromQlBudgetEnforcer {

    static final int DEFAULT_BUDGET_SECONDS = 10;

    private final Duration budget;

    public PromQlBudgetEnforcer(
            @Value("${playground.metrics.promql-budget-seconds:${METRICS_PROMQL_BUDGET_S:" + DEFAULT_BUDGET_SECONDS + "}}")
                    int budgetSeconds) {
        if (budgetSeconds <= 0) {
            budgetSeconds = DEFAULT_BUDGET_SECONDS;
        }
        this.budget = Duration.ofSeconds(budgetSeconds);
    }

    /** Test-only constructor — lets unit tests inject a short timeout. */
    PromQlBudgetEnforcer(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
    }

    /** Returns the configured budget — exposed for diagnostics + tests. */
    public Duration budget() {
        return budget;
    }

    /**
     * Wraps {@code upstream} with the configured timeout. On
     * {@link TimeoutException}, emits {@code degradedSentinel.get()} instead of
     * propagating the failure — this is the {@code "degraded": true} per-widget
     * partial-response path.
     *
     * <p>Any other error from {@code upstream} (non-timeout) propagates
     * unchanged so the caller's own {@code .onErrorReturn(...)} branches stay
     * in charge — typically the per-widget code zeroes the value rather than
     * degrades it. This separation lets the dashboard distinguish "Prometheus
     * 5xx" (zeroed value) from "PromQL exceeded budget" (degraded marker).
     */
    public <T> Mono<T> wrap(Mono<T> upstream, Supplier<T> degradedSentinel) {
        if (upstream == null) {
            throw new IllegalArgumentException("upstream must not be null");
        }
        if (degradedSentinel == null) {
            throw new IllegalArgumentException("degradedSentinel must not be null");
        }
        return upstream
                .timeout(budget)
                .onErrorResume(TimeoutException.class,
                        err -> Mono.fromSupplier(degradedSentinel));
    }
}
