package com.playground.metrics.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code playground.metrics.*} entries from {@code application.yml}
 * per ADR-15 §7 + §18. The defaults match the compose-internal hostnames
 * shipped by the M5 infra-implementer.
 */
@ConfigurationProperties(prefix = "playground.metrics")
public record MetricsHttpProperties(
        Prometheus prometheus,
        Loki loki,
        SparkGateway sparkGateway) {

    public record Prometheus(String baseUrl, long timeoutMs) {
        public Prometheus {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://prometheus-playground:9090";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 8000L;
            }
        }
    }

    public record Loki(String baseUrl, long timeoutMs) {
        public Loki {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://loki-playground:3100";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 15000L;
            }
        }
    }

    /**
     * @param apiKey Bearer token sent on every probe call. Null / blank means
     *     no {@code Authorization} header — appropriate for a bare vLLM that
     *     does not validate API keys (original ADR-04 host-process wiring); the
     *     post-2026-05-20 spark-inference-gateway enforces Bearer auth, so in
     *     that setup wire it via {@code METRICS_SPARK_GATEWAY_API_KEY} (or fall
     *     back to {@code SPRING_AI_OPENAI_API_KEY} which the chat /
     *     rag-ingestion BCs already consume). See ADR-15 §12 amendment
     *     2026-05-20.
     */
    public record SparkGateway(String baseUrl, long probeCacheTtlSeconds, String apiKey) {
        public SparkGateway {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://host.docker.internal:10080";
            }
            if (probeCacheTtlSeconds <= 0) {
                probeCacheTtlSeconds = 15L;
            }
            // Normalize blank → null so the adapter can do a single null-check.
            if (apiKey != null && apiKey.isBlank()) {
                apiKey = null;
            }
        }
    }

    public MetricsHttpProperties {
        if (prometheus == null) {
            prometheus = new Prometheus(null, 0);
        }
        if (loki == null) {
            loki = new Loki(null, 0);
        }
        if (sparkGateway == null) {
            sparkGateway = new SparkGateway(null, 0, null);
        }
    }
}
