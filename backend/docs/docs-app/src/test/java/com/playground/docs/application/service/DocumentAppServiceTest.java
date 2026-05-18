package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import com.playground.shared.error.BadRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentAppServiceTest {

    private final Instant t0 = Instant.parse("2026-05-18T00:00:00Z");
    private final Clock fixedClock = Clock.fixed(t0, ZoneOffset.UTC);

    @Mock
    DocumentRepository repository;

    private DocumentAppService service;

    @BeforeEach
    void setUp() {
        service = new DocumentAppService(repository, fixedClock);
    }

    @Test
    void create_returns_detail_with_private_visibility_and_root_path() {
        UUID authorId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentDetailDto detail = service.create(new CreateDocumentCommand(
                authorId, "Hello", "body", null));

        assertThat(detail.title()).isEqualTo("Hello");
        assertThat(detail.visibility()).isEqualTo("private");
        assertThat(detail.path()).isEqualTo("/");
        assertThat(detail.publishedAt()).isNull();
        assertThat(detail.createdAt()).isEqualTo(t0);
        assertThat(detail.updatedAt()).isEqualTo(t0);
    }

    @Test
    void create_rejects_blank_title() {
        UUID authorId = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new CreateDocumentCommand(authorId, "   ", "body", null)))
                .isInstanceOf(BadRequestException.class);
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void create_rejects_invalid_path() {
        UUID authorId = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new CreateDocumentCommand(authorId, "Hi", "", "no-slash")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void get_returns_doc_when_public_and_caller_anonymous() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document publicDoc = newDoc(docId, authorId).publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(publicDoc));

        DocumentDetailDto detail = service.getById(docId, null);
        assertThat(detail.visibility()).isEqualTo("public");
    }

    @Test
    void get_returns_404_when_private_and_caller_not_author() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document privateDoc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(privateDoc));

        assertThatThrownBy(() -> service.getById(docId, strangerId))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void get_returns_404_when_private_and_caller_anonymous() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document privateDoc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(privateDoc));

        assertThatThrownBy(() -> service.getById(docId, null))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void get_returns_doc_when_private_and_caller_is_author() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document privateDoc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(privateDoc));

        DocumentDetailDto detail = service.getById(docId, authorId);
        assertThat(detail.visibility()).isEqualTo("private");
    }

    @Test
    void patch_returns_404_when_caller_not_author() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.patch(new PatchDocumentCommand(docId, strangerId, "New", null)))
                .isInstanceOf(DocumentNotFoundException.class);
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    @Test
    void patch_updates_title_and_body_only() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentDetailDto detail = service.patch(new PatchDocumentCommand(docId, authorId, "New", "New body"));

        // PATCH per spec §6.1 only carries title + body; visibility stays private.
        assertThat(detail.title()).isEqualTo("New");
        assertThat(detail.body()).isEqualTo("New body");
        assertThat(detail.visibility()).isEqualTo("private");
        assertThat(detail.publishedAt()).isNull();
    }

    // --- publish / unpublish ---

    @Test
    void publish_returns_404_when_caller_not_author() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.publish(docId, strangerId))
                .isInstanceOf(DocumentNotFoundException.class);
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    @Test
    void publish_first_time_stamps_publishedAt() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentDetailDto detail = service.publish(docId, authorId);

        assertThat(detail.visibility()).isEqualTo("public");
        assertThat(detail.publishedAt()).isEqualTo(t0);
    }

    @Test
    void publish_is_noop_when_already_public() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document published = newDoc(docId, authorId).publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(published));

        DocumentDetailDto detail = service.publish(docId, authorId);

        assertThat(detail.visibility()).isEqualTo("public");
        assertThat(detail.publishedAt()).isEqualTo(t0);
        // No repository.save() on idempotent path — avoids spurious updatedAt bumps.
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    @Test
    void unpublish_returns_404_when_caller_not_author() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId).publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.unpublish(docId, strangerId))
                .isInstanceOf(DocumentNotFoundException.class);
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    @Test
    void unpublish_retains_publishedAt() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document published = newDoc(docId, authorId).publish(t0);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(published));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentDetailDto detail = service.unpublish(docId, authorId);

        assertThat(detail.visibility()).isEqualTo("private");
        // Spec §6.1 unpublish row: "publishedAt retained".
        assertThat(detail.publishedAt()).isEqualTo(t0);
    }

    @Test
    void unpublish_is_noop_when_already_private() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        DocumentDetailDto detail = service.unpublish(docId, authorId);

        assertThat(detail.visibility()).isEqualTo("private");
        assertThat(detail.publishedAt()).isNull();
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    // --- delete + list ---

    @Test
    void delete_returns_404_when_caller_not_author() {
        UUID authorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.delete(docId, strangerId)).isInstanceOf(DocumentNotFoundException.class);
        Mockito.verify(repository, Mockito.never()).deleteById(any());
    }

    @Test
    void delete_removes_doc_when_caller_is_author() {
        UUID authorId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, authorId);
        when(repository.findById(DocumentId.of(docId))).thenReturn(Optional.of(doc));

        service.delete(docId, authorId);
        Mockito.verify(repository).deleteById(DocumentId.of(docId));
    }

    @Test
    void listMine_returns_caller_documents() {
        UUID authorId = UUID.randomUUID();
        Document a = newDoc(UUID.randomUUID(), authorId);
        Document b = newDoc(UUID.randomUUID(), authorId);
        when(repository.findAllByAuthor(AuthorId.of(authorId))).thenReturn(List.of(a, b));

        var result = service.listMine(authorId);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).visibility()).isEqualTo("private");
    }

    private Document newDoc(UUID docId, UUID authorId) {
        return Document.create(
                DocumentId.of(docId),
                AuthorId.of(authorId),
                DocumentTitle.of("Title"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                t0);
    }
}
