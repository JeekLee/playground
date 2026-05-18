package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.event.DocumentDeleted;
import com.playground.docs.domain.event.DocumentUploaded;
import com.playground.docs.domain.event.DocumentVisibilityChanged;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Verifies which Kafka outbox events fire per M2 spec §5 rules:
 *
 * <ul>
 *   <li>{@code uploaded} fires on CREATE and on PATCH-where-body-checksum changed.
 *       Title-only / path-only PATCH does NOT fire it.</li>
 *   <li>{@code visibility-changed} fires only on /publish + /unpublish.
 *       PATCH never flips visibility, so it cannot fire from PATCH.</li>
 *   <li>{@code deleted} fires on hard delete (terminal event).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DocumentEventPublicationTest {

    private final Instant t0 = Instant.parse("2026-05-18T00:00:00Z");
    private final Clock fixedClock = Clock.fixed(t0, ZoneOffset.UTC);

    @Mock
    DocumentRepository repository;
    @Mock
    ApplicationEventPublisher events;

    private DocumentAppService service;

    @BeforeEach
    void setUp() {
        // identityLookup left null — author-resolution is unrelated to event publication
        // (M2 spec §5 says events never carry author display name).
        service = new DocumentAppService(repository, events, null, fixedClock);
    }

    @Test
    void create_publishes_uploaded_event_with_body_checksum() {
        UUID author = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateDocumentCommand(author, "Hello", "first body", null));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DocumentUploaded.class);
        DocumentUploaded event = (DocumentUploaded) captor.getValue();
        assertThat(event.userId().value()).isEqualTo(author);
        assertThat(event.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(event.bodyChecksum())
                .isEqualTo(DocumentBody.of("first body").checksum());
        assertThat(event.title().value()).isEqualTo("Hello");
        assertThat(event.path().value()).isEqualTo("/");
    }

    @Test
    void patch_body_change_publishes_uploaded_event() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body v1");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.patch(new PatchDocumentCommand(docId, author, null, "body v2"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DocumentUploaded.class);
        DocumentUploaded event = (DocumentUploaded) captor.getValue();
        assertThat(event.bodyChecksum())
                .isEqualTo(DocumentBody.of("body v2").checksum())
                .isNotEqualTo(DocumentBody.of("body v1").checksum());
    }

    @Test
    void patch_title_only_does_not_publish_uploaded() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "unchanged body");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.patch(new PatchDocumentCommand(docId, author, "New Title", null));

        // No event — body didn't change. Spec §5: title-only edits do not re-trigger
        // embedding work; the search projector picks up title via visibility-changed
        // (none here either) or on the next body edit.
        verify(events, never()).publishEvent(any());
    }

    @Test
    void patch_same_body_does_not_publish_uploaded() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "same body");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));

        // Same body string → same checksum → no event.
        service.patch(new PatchDocumentCommand(docId, author, null, "same body"));

        verify(events, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }

    @Test
    void publish_emits_visibility_changed_with_old_private_new_public() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.publish(docId, author);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DocumentVisibilityChanged.class);
        DocumentVisibilityChanged event = (DocumentVisibilityChanged) captor.getValue();
        assertThat(event.oldVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(event.newVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(event.publishedAt()).isEqualTo(t0);
    }

    @Test
    void unpublish_emits_visibility_changed_retaining_publishedAt() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body").publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unpublish(docId, author);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        DocumentVisibilityChanged event = (DocumentVisibilityChanged) captor.getValue();
        assertThat(event.oldVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(event.newVisibility()).isEqualTo(Visibility.PRIVATE);
        // Spec §5: publishedAt retained — the event carries the same value as the public state.
        assertThat(event.publishedAt()).isEqualTo(t0);
    }

    @Test
    void publish_when_already_public_is_idempotent_noop() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body").publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));

        service.publish(docId, author);

        verify(events, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }

    @Test
    void unpublish_when_already_private_is_idempotent_noop() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));

        service.unpublish(docId, author);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void delete_emits_deleted_event() {
        UUID author = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document existing = newDoc(docId, author, "body");
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(existing));

        service.delete(docId, author);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DocumentDeleted.class);
        DocumentDeleted event = (DocumentDeleted) captor.getValue();
        assertThat(event.documentId().value()).isEqualTo(docId);
        assertThat(event.userId().value()).isEqualTo(author);
    }

    private Document newDoc(UUID docId, UUID authorId, String body) {
        return Document.create(
                DocumentId.of(docId),
                AuthorId.of(authorId),
                DocumentTitle.of("Title"),
                DocumentBody.of(body),
                DocumentPath.ROOT,
                t0);
    }
}
