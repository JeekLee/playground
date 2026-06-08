package com.playground.docs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.dto.DocumentManifestEntry;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import java.util.List;
import java.util.UUID;
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
 * Full-stack integration test for the docs S1 CRUD slice — exercises the JPA
 * write/read path, the visibility-or-ownership gate, the dedicated
 * publish/unpublish endpoints, and the tenant-isolation invariants per M2 spec
 * §12. Tagged {@code integration} so the default test task skips it when
 * Docker is unavailable.
 */
@SpringBootTest(
        classes = com.playground.docs.api.DocsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class DocumentCrudIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("playground")
            .withUsername("playground")
            .withPassword("playground");

    // Kafka container is started even though S1 publishes no events — Spring
    // Boot's Kafka auto-config still resolves the bootstrap servers at startup,
    // and a bad URL would make the context fail. M2 S2 will exercise it for
    // real.
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

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
    }

    @Autowired
    DocumentAppService service;

    @Test
    void create_then_get_roundtrips_the_draft() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hello", "body", null));
        assertThat(created.id()).isNotBlank();
        assertThat(created.visibility()).isEqualTo("private");
        assertThat(created.publishedAt()).isNull();

        DocumentDetailDto fetched = service.getById(UUID.fromString(created.id()), author);
        assertThat(fetched.title()).isEqualTo("Hello");
        assertThat(fetched.body()).isEqualTo("body");
    }

    @Test
    void other_user_cannot_read_private_doc() {
        UUID author = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "private body", null));
        UUID id = UUID.fromString(created.id());

        assertThatThrownBy(() -> service.getById(id, stranger)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.getById(id, null)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void anyone_can_read_public_doc() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        service.publish(id, author);

        DocumentDetailDto anon = service.getById(id, null);
        assertThat(anon.visibility()).isEqualTo("public");
        assertThat(anon.publishedAt()).isNotNull();
    }

    @Test
    void publish_unpublish_republish_keeps_id_and_publishedAt() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        DocumentDetailDto published = service.publish(id, author);
        DocumentDetailDto unpublished = service.unpublish(id, author);
        DocumentDetailDto republished = service.publish(id, author);

        assertThat(published.id()).isEqualTo(created.id());
        assertThat(unpublished.id()).isEqualTo(created.id());
        assertThat(republished.id()).isEqualTo(created.id());
        // publishedAt is stamped on first publish and retained thereafter.
        assertThat(unpublished.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(republished.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(unpublished.visibility()).isEqualTo("private");
        assertThat(republished.visibility()).isEqualTo("public");
    }

    @Test
    void publish_twice_is_idempotent() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        DocumentDetailDto first = service.publish(id, author);
        DocumentDetailDto second = service.publish(id, author);

        assertThat(second.visibility()).isEqualTo("public");
        assertThat(second.publishedAt()).isEqualTo(first.publishedAt());
        // The idempotent no-op path skips the repository write, so updatedAt
        // also stays put — verified by checking it against the first call.
        assertThat(second.updatedAt()).isEqualTo(first.updatedAt());
    }

    @Test
    void unpublish_on_private_doc_is_idempotent_noop() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        DocumentDetailDto unpublished = service.unpublish(id, author);

        assertThat(unpublished.visibility()).isEqualTo("private");
        assertThat(unpublished.publishedAt()).isNull();
        // Same updatedAt as creation — no DB write happened on the no-op path.
        assertThat(unpublished.updatedAt()).isEqualTo(created.updatedAt());
    }

    @Test
    void other_user_cannot_patch_publish_unpublish_or_delete() {
        UUID author = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        assertThatThrownBy(() -> service.patch(new PatchDocumentCommand(id, stranger, "Bad", null)))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.publish(id, stranger)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.unpublish(id, stranger)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id, stranger)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void delete_removes_the_document() {
        UUID author = UUID.randomUUID();
        DocumentDetailDto created = service.create(new CreateDocumentCommand(author, "Hi", "body", null));
        UUID id = UUID.fromString(created.id());

        service.delete(id, author);

        assertThatThrownBy(() -> service.getById(id, author)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void manifest_returns_earliest_by_created_at_asc_capped_by_limit() {
        UUID author = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        // Created sequentially → monotonically non-decreasing created_at.
        service.create(new CreateDocumentCommand(author, "First", "body", null));
        service.create(new CreateDocumentCommand(author, "Second", "body", null));
        service.create(new CreateDocumentCommand(author, "Third", "body", null));
        service.create(new CreateDocumentCommand(other, "Other", "body", null));

        List<DocumentManifestEntry> top2 = service.manifestForUser(author, 2);

        // created_at ASC, limit 2 → the two earliest, in upload order.
        assertThat(top2).hasSize(2);
        assertThat(top2).extracting(DocumentManifestEntry::title)
                .containsExactly("First", "Second");
        assertThat(top2).allSatisfy(e -> {
            assertThat(e.id()).isNotNull();
            assertThat(e.title()).isNotBlank();
        });

        // Full list excludes the other user's doc and stays in upload order.
        List<DocumentManifestEntry> all = service.manifestForUser(author, 30);
        assertThat(all).extracting(DocumentManifestEntry::title)
                .containsExactly("First", "Second", "Third");
    }

    @Test
    void list_mine_returns_only_caller_documents() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.create(new CreateDocumentCommand(a, "A1", "body", null));
        service.create(new CreateDocumentCommand(a, "A2", "body", null));
        service.create(new CreateDocumentCommand(b, "B1", "body", null));

        var aDocs = service.listMine(a);
        var bDocs = service.listMine(b);

        assertThat(aDocs).hasSize(2);
        assertThat(bDocs).hasSize(1);
        assertThat(aDocs).noneMatch(item -> item.title().equals("B1"));
        assertThat(bDocs).noneMatch(item -> item.title().startsWith("A"));
    }
}
