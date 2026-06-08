package com.playground.docs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.application.service.DocumentLikeService;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.infrastructure.persistence.DocumentJpaRepository;
import com.playground.docs.infrastructure.scheduler.CounterResyncJob;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the nightly resync job (ADR-12 §11) repairs drift on
 * {@code docs.documents.like_count} from the {@code docs.document_likes}
 * source-of-truth table.
 *
 * <p>Strategy: drive a like via the application service to land both rows
 * in lockstep, then poke {@code like_count} out of sync via the JPA layer
 * directly. Run the resync. Assert the column matches the COUNT(*) again.
 *
 * <p>Tagged {@code integration} so the default {@code ./gradlew build}
 * (no {@code -PintegrationTests}) skips it when Docker is unavailable —
 * matches the discipline established by {@code DocumentCrudIT}.
 */
@SpringBootTest(
        classes = com.playground.docs.api.DocsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class CounterResyncJobIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("playground")
            .withUsername("playground")
            .withPassword("playground");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=docs");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("playground.docs.public-origin", () -> "https://test.example");
    }

    @Autowired
    DocumentAppService docService;
    @Autowired
    DocumentLikeService likeService;
    @Autowired
    CounterResyncJob resyncJob;
    @Autowired
    DocumentJpaRepository jpa;

    @Test
    void resync_repairs_drift_between_like_count_and_document_likes() {
        // Arrange: one doc, two likes from two distinct users. The
        // application service maintains like_count in lockstep, so the
        // initial state is parity (like_count == 2).
        UUID author = UUID.randomUUID();
        UUID liker1 = UUID.randomUUID();
        UUID liker2 = UUID.randomUUID();
        DocumentDetailDto created = docService.create(
                new CreateDocumentCommand(author, "Like me", "body", null));
        UUID docId = UUID.fromString(created.id());
        likeService.like(docId, liker1);
        likeService.like(docId, liker2);

        var beforeDrift = jpa.findById(docId).orElseThrow();
        assertThat(beforeDrift.getLikeCount()).isEqualTo(2L);

        // Simulate drift: stomp on like_count via a back-door write that
        // bypasses the application service. The source-of-truth table
        // still has 2 rows.
        beforeDrift.setLikeCount(99L);
        jpa.save(beforeDrift);
        assertThat(jpa.findById(docId).orElseThrow().getLikeCount()).isEqualTo(99L);

        // Act: run the resync job (synchronous, transactional).
        resyncJob.resyncLikeCounts();

        // Assert: like_count snaps back to the COUNT(*) value.
        var repaired = jpa.findById(docId).orElseThrow();
        assertThat(repaired.getLikeCount()).isEqualTo(2L);

        // And the next decrement still works (like_count -> 1 after unlike).
        likeService.unlike(docId, liker1);
        var afterUnlike = jpa.findById(docId).orElseThrow();
        assertThat(afterUnlike.getLikeCount()).isEqualTo(1L);
    }

    @Test
    void resync_zeroes_like_count_when_all_likes_removed() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = docService.create(
                new CreateDocumentCommand(author, "Lonely", "body", null));
        UUID docId = UUID.fromString(created.id());

        // No likes; drift the counter to a positive value, then resync.
        var row = jpa.findById(docId).orElseThrow();
        row.setLikeCount(42L);
        jpa.save(row);

        resyncJob.resyncLikeCounts();

        assertThat(jpa.findById(docId).orElseThrow().getLikeCount()).isEqualTo(0L);
    }

    @SuppressWarnings("unused")
    private DocumentId asId(UUID docId) {
        return DocumentId.of(docId);
    }
}
