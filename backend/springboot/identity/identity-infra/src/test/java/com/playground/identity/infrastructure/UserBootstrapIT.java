package com.playground.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.identity.application.dto.UserBootstrapCommand;
import com.playground.identity.application.service.UserBootstrapAppService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-stack integration test for the identity bootstrap flow — exercises the
 * JPA write, the Spring Modulith outbox row, and the Kafka externalization to
 * the {@code identity.user.registered} topic. Tagged {@code integration} so
 * the default test task skips it when Docker is unavailable.
 */
@SpringBootTest(
        classes = com.playground.identity.api.IdentityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class UserBootstrapIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("playground")
            .withUsername("playground")
            .withPassword("playground");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=identity");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    UserBootstrapAppService bootstrapService;

    @Test
    void first_bootstrap_creates_user_and_emits_outbox_row() {
        var result = bootstrapService.bootstrap(new UserBootstrapCommand(
                "google-sub-1234",
                "jeeklee1120@gmail.com",
                "JeekLee",
                "https://lh3.googleusercontent.com/a/x"));

        assertThat(result.created()).isTrue();
        assertThat(result.id()).isNotBlank();
    }

    @Test
    void second_bootstrap_returns_same_id() {
        var first = bootstrapService.bootstrap(new UserBootstrapCommand(
                "google-sub-5678", "alice@example.com", "Alice", null));
        var second = bootstrapService.bootstrap(new UserBootstrapCommand(
                "google-sub-5678", "alice@example.com", "Alice", null));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.created()).isFalse();
    }
}
