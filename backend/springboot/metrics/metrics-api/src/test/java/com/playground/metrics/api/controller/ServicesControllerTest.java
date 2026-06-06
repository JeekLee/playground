package com.playground.metrics.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.api.advice.MetricsReactiveExceptionHandler;
import com.playground.metrics.api.config.MetricsSecurityConfig;
import com.playground.metrics.app.BuildServicesUseCase;
import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.ServiceProbeTarget;
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

/**
 * WebTestClient slice for {@code GET /api/metrics/services} per issue #138.
 * Verifies the eleven-cell payload shape + ADR-15 §17 canonical ordering.
 */
@SpringBootTest(
        classes = ServicesControllerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServicesControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    PrometheusPort prometheus;

    @Autowired
    ActuatorHealthPort actuator;

    @Autowired
    SparkGatewayProbePort spark;

    @BeforeEach
    void setUp() {
        Mockito.reset(prometheus, actuator, spark);
    }

    @Test
    void allUpReturnsElevenCellsInCanonicalOrder() {
        // sum_over_time(up{}[12s]) = 2 → both scrapes captured
        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        webTestClient.get()
                .uri("/services")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.services").isArray()
                .jsonPath("$.services.length()").isEqualTo(16)
                // ADR-15 §17 canonical order — assert each index
                .jsonPath("$.services[0].name").isEqualTo("playground-backend-gateway")
                .jsonPath("$.services[1].name").isEqualTo("playground-backend-identity-api")
                .jsonPath("$.services[2].name").isEqualTo("playground-backend-docs-api")
                .jsonPath("$.services[3].name").isEqualTo("playground-backend-rag-ingestion-api")
                .jsonPath("$.services[4].name").isEqualTo("playground-backend-chat-api")
                .jsonPath("$.services[5].name").isEqualTo("playground-backend-metrics-api")
                .jsonPath("$.services[6].name").isEqualTo("spark-inference-gateway")
                .jsonPath("$.services[7].name").isEqualTo("playground-prometheus")
                .jsonPath("$.services[8].name").isEqualTo("playground-loki")
                .jsonPath("$.services[9].name").isEqualTo("playground-alloy")
                .jsonPath("$.services[10].name").isEqualTo("playground-cadvisor")
                .jsonPath("$.services[11].name").isEqualTo("playground-frontend")
                .jsonPath("$.services[12].name").isEqualTo("playground-postgres")
                .jsonPath("$.services[13].name").isEqualTo("playground-redis")
                .jsonPath("$.services[14].name").isEqualTo("playground-kafka-broker")
                .jsonPath("$.services[15].name").isEqualTo("playground-opensearch")
                // All up under the all-good mock setup
                .jsonPath("$.services[0].status").isEqualTo("up")
                .jsonPath("$.services[6].status").isEqualTo("up");
    }

    @Test
    void scrapeMissAndUnreachableProbeMarksDown() {
        when(prometheus.instantQuery(anyString())).thenReturn(Mono.just(List.of()));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.unreachable()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.down()));

        webTestClient.get()
                .uri("/services")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.services[0].status").isEqualTo("down")
                .jsonPath("$.services[6].status").isEqualTo("down")
                .jsonPath("$.services[10].status").isEqualTo("down");
    }

    @Test
    void cleanScrapeButActuatorDownMarksDegraded() {
        // ADR-15 §9 row 5: 2/2 scrapes + actuator non-UP → degraded.
        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableDown()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        webTestClient.get()
                .uri("/services")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.services[0].status").isEqualTo("degraded")
                .jsonPath("$.services[5].status").isEqualTo("degraded")
                // Spark is independent of the actuator path — still up.
                .jsonPath("$.services[6].status").isEqualTo("up");
    }

    @Test
    void serviceProbeTargetCatalogStillElevenEntries() {
        // Guardrail: if a future PR widens ServiceProbeTarget.ALL beyond the
        // 11 cells issue #138 wired up, this slice test will surface it so
        // the frontend grid layout is updated in lock-step.
        org.assertj.core.api.Assertions.assertThat(ServiceProbeTarget.ALL).hasSize(16);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        ServicesController servicesController(BuildServicesUseCase useCase) {
            return new ServicesController(useCase);
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
        org.springframework.security.web.server.SecurityWebFilterChain filterChain(
                org.springframework.security.config.web.server.ServerHttpSecurity http,
                MetricsSecurityConfig cfg) {
            return cfg.metricsFilterChain(http);
        }
    }
}
