package com.playground.ragingestion.api.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Per-BC GroupedOpenApi bean per ADR-02 v2 + ADR-01 v2. M3 has no
 * application controllers; the group is empty in practice but the bean
 * keeps the openapi-ui's group selector consistent across BCs.
 */
@Configuration(proxyBeanMethods = false)
public class RagApiGroupConfig {

    @Bean
    public GroupedOpenApi ragGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("rag-ingestion")
                .pathsToMatch("/rag-ingestion/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("RAG-Ingestion BC API")
                        .description(
                                "Kafka-driven ingestion BC — no public HTTP surface, actuator only. "
                                        + "Consumes docs.document.* topics; emits rag.document.ingested.")
                        .version("v1")))
                .build();
    }
}
