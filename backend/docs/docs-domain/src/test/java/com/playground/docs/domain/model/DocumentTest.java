package com.playground.docs.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTest {

    private final Instant t0 = Instant.parse("2026-05-18T00:00:00Z");
    private final Instant t1 = Instant.parse("2026-05-18T01:00:00Z");
    private final Instant t2 = Instant.parse("2026-05-18T02:00:00Z");
    private final Instant t3 = Instant.parse("2026-05-18T03:00:00Z");

    @Test
    void create_starts_as_private_with_no_publishedAt() {
        Document doc = Document.create(
                DocumentId.of(UUID.randomUUID()),
                AuthorId.of(UUID.randomUUID()),
                DocumentTitle.of("Hello"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                t0);
        assertThat(doc.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(doc.publishedAt()).isNull();
        assertThat(doc.createdAt()).isEqualTo(t0);
        assertThat(doc.updatedAt()).isEqualTo(t0);
        assertThat(doc.path()).isEqualTo(DocumentPath.ROOT);
    }

    @Test
    void publish_first_time_stamps_publishedAt_and_bumps_updatedAt() {
        Document doc = newDoc().publish(t1);
        assertThat(doc.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(doc.publishedAt()).isEqualTo(t1);
        assertThat(doc.updatedAt()).isEqualTo(t1);
    }

    @Test
    void publish_is_idempotent_when_already_public() {
        Document published = newDoc().publish(t1);
        Document republished = published.publish(t2);

        // Idempotent: spec §6.1 publish row says "if already public, return current state".
        // The aggregate returns the same instance verbatim — no updatedAt bump,
        // no publishedAt mutation.
        assertThat(republished).isSameAs(published);
        assertThat(republished.publishedAt()).isEqualTo(t1);
        assertThat(republished.updatedAt()).isEqualTo(t1);
    }

    @Test
    void unpublish_retains_publishedAt() {
        Document published = newDoc().publish(t1);
        Document unpublished = published.unpublish(t2);

        assertThat(unpublished.visibility()).isEqualTo(Visibility.PRIVATE);
        // Per spec §6.1 unpublish row: "publishedAt retained".
        assertThat(unpublished.publishedAt()).isEqualTo(t1);
        assertThat(unpublished.updatedAt()).isEqualTo(t2);
    }

    @Test
    void unpublish_then_republish_keeps_original_publishedAt() {
        Document published = newDoc().publish(t1);
        Document unpublished = published.unpublish(t2);
        Document republished = unpublished.publish(t3);

        // The publishedAt stamp survives the full unpublish/republish cycle —
        // spec §4.4: first publish stamps; subsequent transitions retain.
        assertThat(unpublished.publishedAt()).isEqualTo(t1);
        assertThat(republished.publishedAt()).isEqualTo(t1);
        assertThat(republished.updatedAt()).isEqualTo(t3);
    }

    @Test
    void unpublish_is_idempotent_when_already_private() {
        Document doc = newDoc();
        Document unpublished = doc.unpublish(t1);

        // Idempotent: never-published private doc remains exactly as it was —
        // no publishedAt stamp leaks in, no updatedAt bump.
        assertThat(unpublished).isSameAs(doc);
        assertThat(unpublished.publishedAt()).isNull();
        assertThat(unpublished.updatedAt()).isEqualTo(t0);
    }

    @Test
    void edit_returns_new_instance_with_updated_fields() {
        Document doc = newDoc();
        Document edited = doc.edit(
                DocumentTitle.of("New title"),
                DocumentBody.of("New body"),
                null,
                t1);
        assertThat(edited.title().value()).isEqualTo("New title");
        assertThat(edited.body().value()).isEqualTo("New body");
        assertThat(edited.path()).isEqualTo(doc.path()); // unchanged (null)
        assertThat(edited.updatedAt()).isEqualTo(t1);
        assertThat(edited.createdAt()).isEqualTo(doc.createdAt());
    }

    @Test
    void isAuthoredBy_matches_when_author_id_equal() {
        AuthorId author = AuthorId.of(UUID.randomUUID());
        AuthorId stranger = AuthorId.of(UUID.randomUUID());
        Document doc = Document.create(
                DocumentId.of(UUID.randomUUID()),
                author,
                DocumentTitle.of("Hi"),
                DocumentBody.empty(),
                DocumentPath.ROOT,
                t0);
        assertThat(doc.isAuthoredBy(author)).isTrue();
        assertThat(doc.isAuthoredBy(stranger)).isFalse();
    }

    private Document newDoc() {
        return Document.create(
                DocumentId.of(UUID.randomUUID()),
                AuthorId.of(UUID.randomUUID()),
                DocumentTitle.of("Hello"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                t0);
    }
}
