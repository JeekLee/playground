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

    public record SparkGateway(String baseUrl, long probeCacheTtlSeconds) {
        public SparkGateway {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://host.docker.internal:10080";
            }
            if (probeCacheTtlSeconds <= 0) {
                probeCacheTtlSeconds = 15L;
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
            sparkGateway = new SparkGateway(null, 0);
        }
    }
}
