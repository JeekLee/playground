package com.playground.metrics.api.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Per-BC GroupedOpenApi bean per ADR-02 v2 + ADR-01 v2. */
@Configuration(proxyBeanMethods = false)
public class MetricsApiGroupConfig {

    @Bean
    public GroupedOpenApi metricsGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("metrics")
                .pathsToMatch("/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("Metrics BC API")
                        .description(
                                "M5 metrics surface — 4 reactive routes (dashboard / services "
                                        + "/ timeseries / logs) against Prometheus + Loki + "
                                        + "spark-inference-gateway. PromQL/LogQL whitelist + "
                                        + "allowlist-substitution per ADR-15.")
                        .version("v1")))
                .build();
    }
}
