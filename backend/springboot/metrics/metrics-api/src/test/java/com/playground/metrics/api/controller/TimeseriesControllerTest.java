package com.playground.metrics.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.api.advice.MetricsReactiveExceptionHandler;
import com.playground.metrics.api.config.MetricsSecurityConfig;
import com.playground.metrics.app.BuildTimeseriesUseCase;
import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.domain.TimeseriesPoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
        classes = TimeseriesControllerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeseriesControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    PrometheusPort prometheus;

    @BeforeEach
    void setUp() {
        Mockito.reset(prometheus);
    }

    @Test
    void timeseriesShapeForKnownMetric() {
        when(prometheus.rangeQuery(anyString(), any(Range.class), any(Step.class)))
                .thenReturn(Mono.just(List.of(new PrometheusSeries(
                        Map.of("service", "chat-api"),
                        List.of(new TimeseriesPoint(1715763600, 380.0),
                                new TimeseriesPoint(1715763630, 392.0))))));

        webTestClient.get()
                .uri("/timeseries?metric=jvm-heap-chat-api&range=1h&step=30s")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.metric").isEqualTo("jvm-heap-chat-api")
                .jsonPath("$.range").isEqualTo("1h")
                .jsonPath("$.step").isEqualTo("30s")
                .jsonPath("$.unit").isEqualTo("MB")
                .jsonPath("$.series[0].label").isEqualTo("chat-api")
                .jsonPath("$.series[0].points[0][0]").isEqualTo(1715763600)
                .jsonPath("$.series[0].points[0][1]").isEqualTo(380.0);
    }

    @Test
    void unknownMetricReturns400() {
        webTestClient.get()
                .uri("/timeseries?metric=foo-bar-not-allowlisted&range=1h&step=30s")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("METRICS-VALIDATION-001");
    }

    @Test
    void allowlistBypassAttemptReturns400() {
        webTestClient.get()
                .uri("/timeseries?metric=jvm-heap-evil-svc&range=1h&step=30s")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("METRICS-VALIDATION-001");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        TimeseriesController timeseriesController(BuildTimeseriesUseCase useCase) {
            return new TimeseriesController(useCase);
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
        PrometheusPort prometheusPort() {
            return Mockito.mock(PrometheusPort.class);
        }

        @Bean
        BuildTimeseriesUseCase buildTimeseriesUseCase(PrometheusPort port) {
            return new BuildTimeseriesUseCase(port);
        }

        @Bean
        org.springframework.security.web.server.SecurityWebFilterChain filterChain(
                org.springframework.security.config.web.server.ServerHttpSecurity http,
                MetricsSecurityConfig cfg) {
            return cfg.metricsFilterChain(http);
        }
    }
}
