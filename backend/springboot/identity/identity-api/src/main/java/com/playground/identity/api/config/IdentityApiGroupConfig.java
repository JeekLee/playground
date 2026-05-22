package com.playground.identity.api.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Per-BC GroupedOpenApi bean per ADR-02 v2 + ADR-01 v2. */
@Configuration(proxyBeanMethods = false)
public class IdentityApiGroupConfig {

    @Bean
    public GroupedOpenApi identityGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("identity")
                .pathsToMatch("/me", "/users/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("Identity BC API")
                        .description("Authenticated user lookup + bootstrap endpoint (per ADR-10).")
                        .version("v1")))
                .build();
    }
}
