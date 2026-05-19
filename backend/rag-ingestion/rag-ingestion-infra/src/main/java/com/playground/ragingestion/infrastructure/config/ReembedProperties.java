package com.playground.ragingestion.infrastructure.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-tunables for the {@code reembed} Spring profile's CLI run per
 * ADR-13 §7 (M3.1 amendment). Only consumed when the application boots with
 * {@code spring.profiles.active=reembed}; ignored otherwise.
 *
 * <p>Lives in {@code -infra} (mirroring {@link ChunkingProperties}) because
 * {@link ConfigurationProperties} is a Spring Boot type and {@code -app} is
 * forbidden from importing {@code org.springframework.boot.*}. The
 * {@code ReembedCommandLineRunner} bean (in {@code -app}, under
 * {@code @Profile("reembed")}) consumes this typed bean via constructor
 * injection.
 */
@ConfigurationProperties(prefix = "playground.rag-ingestion.reembed")
public class ReembedProperties {

    private String scope = "all";
    private UUID userId;
    private UUID documentId;
    private long interDocumentDelayMillis = 0;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public long getInterDocumentDelayMillis() {
        return interDocumentDelayMillis;
    }

    public void setInterDocumentDelayMillis(long interDocumentDelayMillis) {
        this.interDocumentDelayMillis = interDocumentDelayMillis;
    }
}
