package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PromQlBudgetEnforcerTest {

    @Test
    void wrapPassesThroughOnHappyPath() {
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(Duration.ofSeconds(1));

        Mono<Integer> wrapped = enforcer.wrap(Mono.just(42), () -> -1);

        StepVerifier.create(wrapped)
                .expectNext(42)
                .verifyComplete();
    }

    @Test
    void wrapSubstitutesDegradedSentinelOnTimeout() {
        // Use virtual-time scheduling so the test is deterministic and fast.
        StepVerifier.withVirtualTime(() -> {
                    PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(Duration.ofSeconds(10));
                    return enforcer.wrap(Mono.<String>never(), () -> "DEGRADED");
                })
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(11))
                .expectNext("DEGRADED")
                .verifyComplete();
    }

    @Test
    void wrapPropagatesNonTimeoutErrors() {
        // The enforcer specifically catches TimeoutException; other failures
        // propagate so the per-widget onErrorReturn(...) branches in
        // BuildDashboardUseCase stay in charge of zeroing the value (vs
        // marking it degraded).
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(Duration.ofSeconds(1));

        Mono<String> wrapped = enforcer.wrap(
                Mono.error(new RuntimeException("upstream 5xx")),
                () -> "DEGRADED");

        StepVerifier.create(wrapped)
                .expectErrorMessage("upstream 5xx")
                .verify();
    }

    @Test
    void wrapRejectsNullArguments() {
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(Duration.ofSeconds(1));

        assertThatThrownBy(() -> enforcer.wrap(null, () -> "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enforcer.wrap(Mono.just("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorDefaultsToTenSecondsWhenBudgetNonPositive() {
        // The Spring-bound ctor accepts an int from configuration; verify the
        // negative / zero fallback behaviour.
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(0);
        assertThat(enforcer.budget()).isEqualTo(Duration.ofSeconds(10));

        PromQlBudgetEnforcer enforcer2 = new PromQlBudgetEnforcer(-5);
        assertThat(enforcer2.budget()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void constructorAcceptsPositiveBudget() {
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(3);
        assertThat(enforcer.budget()).isEqualTo(Duration.ofSeconds(3));
    }
}
