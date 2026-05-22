package com.playground.docs.infrastructure.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the singleton {@link MinioClient} bean from {@code application.yml}
 * properties per ADR-12 §A12.4. Defaults target the compose-internal sidecar
 * {@code minio-playground:9000} so the docs-api container reaches MinIO over
 * the bridge network without any host-port plumbing.
 *
 * <p>Region defaults to {@code us-east-1} — MinIO ignores the value but the
 * SDK requires it for path-style URL construction. Operators may override via
 * the {@code playground.docs.minio.region} property.
 */
@Configuration(proxyBeanMethods = false)
public class MinioClientConfig {

    @Bean(destroyMethod = "")
    public MinioClient minioClient(
            @Value("${playground.docs.minio.endpoint:http://minio-playground:9000}") String endpoint,
            @Value("${playground.docs.minio.access-key:${MINIO_ROOT_USER:playground}}") String accessKey,
            @Value("${playground.docs.minio.secret-key:${MINIO_ROOT_PASSWORD:playground-secret}}") String secretKey,
            @Value("${playground.docs.minio.region:us-east-1}") String region) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
    }
}
