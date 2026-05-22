package com.playground.docs.api.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Per-BC GroupedOpenApi bean per ADR-02 v2 + ADR-01 v2. */
@Configuration(proxyBeanMethods = false)
public class DocsApiGroupConfig {

    @Bean
    public GroupedOpenApi docsGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("docs")
                .pathsToMatch("/docs/**", "/")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("Docs BC API")
                        .description(
                                "Single-author CRUD for Markdown documents (M2 S1 — community feed, "
                                        + "search, engagement, and events land in S2+).")
                        .version("v1")))
                .build();
    }
}
