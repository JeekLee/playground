package com.playground.chat.infrastructure.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the singleton {@link MinioClient} bean for the chat attachment
 * store per ADR-20 §D3 (mirrors docs-api's MinioClientConfig). Defaults target
 * the compose-internal sidecar {@code minio-playground:9000} so the chat
 * container reaches MinIO over the bridge network with no host-port plumbing.
 *
 * <p>Env precedence (compose sets these): {@code PLAYGROUND_CHAT_MINIO_ENDPOINT},
 * {@code MINIO_ROOT_USER}, {@code MINIO_ROOT_PASSWORD}. Region defaults to
 * {@code us-east-1} — MinIO ignores it but the SDK requires it for path-style
 * URL construction.
 */
@Configuration(proxyBeanMethods = false)
public class MinioClientConfig {

    @Bean(destroyMethod = "")
    public MinioClient minioClient(
            @Value("${PLAYGROUND_CHAT_MINIO_ENDPOINT:http://minio-playground:9000}") String endpoint,
            @Value("${MINIO_ROOT_USER:playground}") String accessKey,
            @Value("${MINIO_ROOT_PASSWORD:playground-secret}") String secretKey,
            @Value("${PLAYGROUND_CHAT_MINIO_REGION:us-east-1}") String region) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
    }
}
