package com.playground.metrics.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.api.advice.MetricsReactiveExceptionHandler;
import com.playground.metrics.api.config.MetricsSecurityConfig;
import com.playground.metrics.app.QueryLogsUseCase;
import com.playground.metrics.app.dto.LogsResponse;
import com.playground.metrics.app.port.LokiPort;
import com.playground.metrics.app.port.UserRateLimitPort;
import com.playground.metrics.domain.LogEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Verifies the M5 P0 logs endpoint requires {@code X-User-Id} per ADR-15 §14
 * + §C. With the header → 200 + spec §5.4 shape; without → 401.
 *
 * <p>This is a focused slice — it imports only the LogsController, its use
 * case, the security filter chain, and the reactive exception advice. The
 * other three controllers (Dashboard / Services / Timeseries) are NOT on the
 * classpath of this test.
 */
@SpringBootTest(
        classes = LogsControllerSecurityTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogsControllerSecurityTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    LokiPort lokiPort;

    @BeforeEach
    void setUp() {
        Mockito.reset(lokiPort);
    }

    @Test
    void logsWithoutUserIdReturns401() {
        webTestClient.get()
                .uri("/logs?service=chat-api&since=15m")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void logsWithUserIdReturns200WithSpecShape() {
        Instant ts = Instant.parse("2026-05-19T07:41:58.234Z");
        when(lokiPort.queryRange(anyString(), any(Duration.class), anyInt()))
                .thenReturn(Mono.just(List.of(
                        new LogEntry(ts, "playground-backend-chat-api", "INFO", "hello"),
                        new LogEntry(ts.minusSeconds(1), "playground-backend-chat-api", "WARN", "slow"))));

        webTestClient.get()
                .uri("/logs?service=playground-backend-chat-api&since=15m")
                .header("X-User-Id", "11111111-2222-3333-4444-555555555555")
                .exchange()
                .expectStatus().isOk()
                .expectBody(LogsResponse.class)
                .consumeWith(result -> {
                    LogsResponse body = result.getResponseBody();
                    assert body != null;
                    assert body.entries().size() == 2;
                    assert !body.hasMore();
                    assert body.nextCursor() == null;
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        LogsController logsController(QueryLogsUseCase useCase, UserRateLimitPort port) {
            return new LogsController(useCase, port);
        }

        @Bean
        MetricsSecurityConfig metricsSecurityConfig() {
            return new MetricsSecurityConfig();
        }

        @Bean
        MetricsReactiveExceptionHandler metricsReactiveExceptionHandler() {
            return new MetricsReactiveExceptionHandler();
        }

        @Bean
        LokiPort lokiPort() {
            return Mockito.mock(LokiPort.class);
        }

        @Bean
        QueryLogsUseCase queryLogsUseCase(LokiPort port) {
            return new QueryLogsUseCase(port);
        }

        @Bean
        UserRateLimitPort userRateLimitPort() {
            return userId -> Mono.just(true);
        }

        @Bean
        org.springframework.security.web.server.SecurityWebFilterChain filterChain(
                org.springframework.security.config.web.server.ServerHttpSecurity http,
                MetricsSecurityConfig cfg) {
            return cfg.metricsFilterChain(http);
        }
    }
}
