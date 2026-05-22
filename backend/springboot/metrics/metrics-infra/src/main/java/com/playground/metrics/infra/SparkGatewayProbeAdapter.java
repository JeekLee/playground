package com.playground.metrics.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * HEAD + GET {@code /v1/models} adapter for spark-inference-gateway per
 * ADR-15 §12. Results are cached in-process via Caffeine for 15 seconds
 * (matches the dashboard polling interval).
 */
@Component
public class SparkGatewayProbeAdapter implements SparkGatewayProbePort {

    private static final String CACHE_KEY = "spark-gateway";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final Cache<String, SparkProbeResult> probeCache;
    private final Cache<String, List<String>> modelsCache;

    public SparkGatewayProbeAdapter(WebClient.Builder builder, MetricsHttpProperties props) {
        WebClient.Builder configured = builder.baseUrl(props.sparkGateway().baseUrl());
        // Stamp the Bearer header as a default if an API key is configured —
        // the post-2026-05-20 spark-inference-gateway returns 401 without it,
        // which the verdict logic surfaces as `degraded` even when the gateway
        // is healthy. Per ADR-15 §12 amendment.
        String apiKey = props.sparkGateway().apiKey();
        if (apiKey != null) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = configured.build();
        Duration ttl = Duration.ofSeconds(props.sparkGateway().probeCacheTtlSeconds());
        this.probeCache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1).build();
        this.modelsCache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1).build();
    }

    @Override
    public Mono<SparkProbeResult> probe() {
        SparkProbeResult cached = probeCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return Mono.just(cached);
        }
        // GET (not HEAD): the post-2026-05-20 spark-inference-gateway is a
        // uvicorn-based ASGI app that returns 405 for HEAD on `/v1/models`
        // because FastAPI / Starlette does not auto-route HEAD onto GET
        // handlers. The probe is reachability + verdict only — we drop the
        // body via `toBodilessEntity()` so the bandwidth cost is the same as
        // HEAD would have been if it worked. See ADR-15 §12 amendment
        // 2026-05-20.
        return webClient.get()
                .uri("/v1/models")
                .retrieve()
                .toBodilessEntity()
                .timeout(PROBE_TIMEOUT)
                .map(e -> {
                    SparkProbeResult result = new SparkProbeResult(true, e.getStatusCode().is2xxSuccessful());
                    probeCache.put(CACHE_KEY, result);
                    return result;
                })
                .onErrorResume(WebClientResponseException.class, ex -> {
                    SparkProbeResult result = new SparkProbeResult(true, false);
                    probeCache.put(CACHE_KEY, result);
                    return Mono.just(result);
                })
                .onErrorResume(ex -> {
                    SparkProbeResult result = SparkProbeResult.down();
                    probeCache.put(CACHE_KEY, result);
                    return Mono.just(result);
                });
    }

    @Override
    public Mono<List<String>> listModels() {
        List<String> cached = modelsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return Mono.just(cached);
        }
        return webClient.get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(PROBE_TIMEOUT)
                .map(SparkGatewayProbeAdapter::parseModels)
                .doOnNext(list -> modelsCache.put(CACHE_KEY, list))
                .onErrorResume(ex -> {
                    modelsCache.put(CACHE_KEY, List.of());
                    return Mono.just(List.of());
                });
    }

    static List<String> parseModels(JsonNode root) {
        List<String> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode model : data) {
                String id = model.path("id").asText("");
                if (!id.isBlank()) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
