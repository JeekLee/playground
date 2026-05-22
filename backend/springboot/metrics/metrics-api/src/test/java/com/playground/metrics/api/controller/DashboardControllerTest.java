package com.playground.metrics.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.api.advice.MetricsReactiveExceptionHandler;
import com.playground.metrics.api.config.MetricsSecurityConfig;
import com.playground.metrics.app.BuildDashboardUseCase;
import com.playground.metrics.app.BuildServicesUseCase;
import com.playground.metrics.app.PromQlBudgetEnforcer;
import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.app.port.IpRateLimitPort;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
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
        classes = DashboardControllerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    PrometheusPort prometheus;

    @Autowired
    SparkGatewayProbePort spark;

    @Autowired
    ActuatorHealthPort actuator;

    @BeforeEach
    void setUp() {
        Mockito.reset(prometheus, spark, actuator);
        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(
                        Map.of("service", "rag-chat-api"), 1_700_000_000L, 1.0))));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));
        when(spark.listModels()).thenReturn(Mono.just(List.of("BGE-M3", "Qwen3-32B")));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
    }

    @Test
    void dashboardReturnsSpecShape() {
        webTestClient.get()
                .uri("/dashboard?range=1h")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fetchedAt").isNotEmpty()
                .jsonPath("$.range").isEqualTo("1h")
                .jsonPath("$.services").isArray()
                .jsonPath("$.containers").isArray()
                .jsonPath("$.host.loadAvg").isArray()
                .jsonPath("$.sparkGateway.url").isEqualTo("host.docker.internal:10080")
                .jsonPath("$.sparkGateway.modelsLoaded").isArray()
                .jsonPath("$.jvm").isArray()
                .jsonPath("$.httpRate").isArray();
    }

    @Test
    void invalidRangeReturns400() {
        webTestClient.get()
                .uri("/dashboard?range=99x")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("METRICS-VALIDATION-003");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        DashboardController dashboardController(
                BuildDashboardUseCase useCase, IpRateLimitPort ipRateLimit) {
            return new DashboardController(useCase, ipRateLimit);
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
        SparkGatewayProbePort sparkGatewayProbePort() {
            return Mockito.mock(SparkGatewayProbePort.class);
        }

        @Bean
        ActuatorHealthPort actuatorHealthPort() {
            return Mockito.mock(ActuatorHealthPort.class);
        }

        @Bean
        BuildServicesUseCase buildServicesUseCase(
                PrometheusPort p, ActuatorHealthPort a, SparkGatewayProbePort s) {
            return new BuildServicesUseCase(p, a, s);
        }

        @Bean
        BuildDashboardUseCase buildDashboardUseCase(
                BuildServicesUseCase services, PrometheusPort p, SparkGatewayProbePort s) {
            // Generous budget so unit tests never trip the degraded path
            // accidentally; per-test enforcer-targeted coverage lives in
            // PromQlBudgetEnforcerTest.
            return new BuildDashboardUseCase(
                    services, p, s,
                    new PromQlBudgetEnforcer(30),
                    15);
        }

        @Bean
        IpRateLimitPort ipRateLimitPort() {
            return ip -> Mono.just(true);
        }

        @Bean
        org.springframework.security.web.server.SecurityWebFilterChain filterChain(
                org.springframework.security.config.web.server.ServerHttpSecurity http,
                MetricsSecurityConfig cfg) {
            return cfg.metricsFilterChain(http);
        }
    }
}
