package com.playground.ragchat.api.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Per-BC GroupedOpenApi bean per ADR-02 v2 + ADR-01 v2. */
@Configuration(proxyBeanMethods = false)
public class RagChatApiGroupConfig {

    @Bean
    public GroupedOpenApi ragChatGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("rag-chat")
                .pathsToMatch("/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("Rag-Chat BC API")
                        .description(
                                "Authenticated streaming chat over the playground RAG corpus "
                                        + "(M4 — SSE streaming via Spring AI ChatClient, pgvector retrieval, "
                                        + "cross-schema reads into rag/docs/identity, Resilience4j breaker + "
                                        + "Redisson rate limit per ADR-14).")
                        .version("v1")))
                .build();
    }
}
