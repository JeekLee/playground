package com.playground.metrics.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.domain.ServiceProbeTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WireMock-backed unit test for {@link ActuatorHealthAdapter} per ADR-15
 * §9 + §17. Covers both probe-shape variants: Spring Actuator JSON body
 * (BC kind) and the 2xx-only readiness path (OBSERVABILITY kind).
 */
class ActuatorHealthAdapterTest {

    private WireMockServer wm;
    private ActuatorHealthAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        adapter = new ActuatorHealthAdapter(WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void bcKindReturnsReachableUpWhenStatusFieldIsUp() {
        wm.stubFor(get(urlPathEqualTo("/actuator/health"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"UP\"}")));

        ServiceProbeTarget target = new ServiceProbeTarget(
                "docs-api", ServiceProbeTarget.Kind.BC,
                wm.baseUrl() + "/actuator/health", true);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result).isNotNull();
        assertThat(result.reachable()).isTrue();
        assertThat(result.up()).isTrue();
    }

    @Test
    void bcKindReturnsReachableDownWhenStatusFieldIsDown() {
        // Spring emits 503 + {"status":"DOWN"} when a health indicator fails.
        wm.stubFor(get(urlPathEqualTo("/actuator/health"))
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"DOWN\"}")));

        ServiceProbeTarget target = new ServiceProbeTarget(
                "docs-api", ServiceProbeTarget.Kind.BC,
                wm.baseUrl() + "/actuator/health", true);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result).isNotNull();
        // 503 is "reachable but down" per the adapter — feeds §9 row 5 (clean
        // scrape, actuator unreachable=true, up=false → degraded).
        assertThat(result.reachable()).isTrue();
        assertThat(result.up()).isFalse();
    }

    @Test
    void bcKindReturnsReachableDownWhenStatusFieldIsOutOfService() {
        wm.stubFor(get(urlPathEqualTo("/actuator/health"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"OUT_OF_SERVICE\"}")));

        ServiceProbeTarget target = new ServiceProbeTarget(
                "docs-api", ServiceProbeTarget.Kind.BC,
                wm.baseUrl() + "/actuator/health", true);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result.reachable()).isTrue();
        assertThat(result.up()).isFalse();
    }

    @Test
    void bcKindReturnsUnreachableOnConnectionRefused() {
        ServiceProbeTarget target = new ServiceProbeTarget(
                "docs-api", ServiceProbeTarget.Kind.BC,
                wm.baseUrl() + "/actuator/health", true);
        wm.stop(); // simulate refused connection

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result).isNotNull();
        assertThat(result.reachable()).isFalse();
        assertThat(result.up()).isFalse();
    }

    @Test
    void observabilityKindReturnsReachableUpOn2xx() {
        wm.stubFor(get(urlPathEqualTo("/-/healthy"))
                .willReturn(aResponse().withStatus(200).withBody("Prometheus Server is Healthy.")));

        ServiceProbeTarget target = new ServiceProbeTarget(
                "prometheus-playground", ServiceProbeTarget.Kind.OBSERVABILITY,
                wm.baseUrl() + "/-/healthy", true);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result.reachable()).isTrue();
        assertThat(result.up()).isTrue();
    }

    @Test
    void observabilityKindReturnsReachableDownOn503() {
        // Loki returns 503 + a JSON body when ingesters aren't ready.
        wm.stubFor(get(urlPathEqualTo("/ready"))
                .willReturn(aResponse().withStatus(503).withBody("Ingester not ready: still in initial state")));

        ServiceProbeTarget target = new ServiceProbeTarget(
                "loki-playground", ServiceProbeTarget.Kind.OBSERVABILITY,
                wm.baseUrl() + "/ready", true);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result.reachable()).isTrue();
        assertThat(result.up()).isFalse();
    }

    @Test
    void observabilityKindReturnsUnreachableOnConnectionRefused() {
        ServiceProbeTarget target = new ServiceProbeTarget(
                "cadvisor-playground", ServiceProbeTarget.Kind.OBSERVABILITY,
                wm.baseUrl() + "/healthz", true);
        wm.stop();

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result.reachable()).isFalse();
        assertThat(result.up()).isFalse();
    }

    @Test
    void sparkKindReturnsUnreachableSentinel() {
        // Defensive: callers should not route SPARK targets here, but if they
        // do the adapter must not crash.
        ServiceProbeTarget target = new ServiceProbeTarget(
                "spark-inference-gateway", ServiceProbeTarget.Kind.SPARK, null, false);

        ActuatorProbeResult result = adapter.probe(target).block();
        assertThat(result).isEqualTo(ActuatorProbeResult.unreachable());
    }
}
