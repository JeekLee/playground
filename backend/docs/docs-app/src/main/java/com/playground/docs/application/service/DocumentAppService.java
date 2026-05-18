package com.playground.docs.application.service;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentBodyDto;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.dto.MyDocumentListItemDto;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.port.IdentityLookupPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.event.DocumentDeleted;
import com.playground.docs.domain.event.DocumentUploaded;
import com.playground.docs.domain.event.DocumentVisibilityChanged;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import com.playground.shared.error.ExceptionCreator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service orchestrating the docs CRUD + event publication per M2
 * spec §5 and §6.1.
 *
 * <p>S2 additions over S1:
 * <ul>
 *   <li>Domain events ({@link DocumentUploaded}, {@link DocumentVisibilityChanged},
 *       {@link DocumentDeleted}) published via Spring's
 *       {@link ApplicationEventPublisher} inside the same {@code @Transactional}
 *       boundary as the DB write — Spring Modulith's JPA outbox persists them
 *       atomically with the {@code documents} row per ADR-12 §1.</li>
 *   <li>{@code uploaded} fires on CREATE and on PATCH-where-body-checksum
 *       actually changed. Title-only / path-only PATCH does NOT fire it
 *       (M2 spec §5 + ADR-12 §9).</li>
 *   <li>{@code visibility-changed} fires on publish / unpublish only; never
 *       from PATCH (spec §6.1).</li>
 *   <li>Author resolution via the identity-lookup port — detail responses
 *       carry the resolved author block.</li>
 *   <li>{@link #getBody(UUID)} backs the internal {@code /internal/docs/{id}/body}
 *       route used by M3 rag-ingestion (ADR-12 §2).</li>
 * </ul>
 *
 * <p>The event publisher / identity lookup are both optional in the
 * constructor so the unit tests in docs-app can exercise the CRUD paths
 * without wiring an outbox or a WebClient.
 */
@Service
public class DocumentAppService {

    private final DocumentRepository repository;
    private final ApplicationEventPublisher events;
    private final IdentityLookupPort identityLookup;
    private final Clock clock;

    public DocumentAppService(
            DocumentRepository repository,
            ApplicationEventPublisher events,
            IdentityLookupPort identityLookup,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.identityLookup = identityLookup;
        this.clock = clock;
    }

    // --- CRUD ---

    @Transactional
    public DocumentDetailDto create(CreateDocumentCommand command) {
        Instant now = Instant.now(clock);
        DocumentTitle title = parseTitle(command.title());
        DocumentBody body = parseBody(command.body());
        DocumentPath path = parsePath(command.path());
        AuthorId authorId = AuthorId.of(command.authorId());

        Document doc = Document.create(
                DocumentId.of(UUID.randomUUID()),
                authorId,
                title,
                body,
                path,
                now);
        Document saved = repository.save(doc);

        // Spec §5: uploaded fires on CREATE (always — the body is new).
        publishUploaded(saved, now);

        return toDetailDto(saved, callerOwnsRow(saved, authorId.value()));
    }

    @Transactional(readOnly = true)
    public List<MyDocumentListItemDto> listMine(UUID callerId) {
        AuthorId author = AuthorId.of(callerId);
        return repository.findAllByAuthor(author).stream()
                .map(d -> MyDocumentListItemDto.from(d, d.viewCount(), d.likeCount()))
                .toList();
    }

    /**
     * Per M2 spec §6.1 {@code GET /api/docs/{id}} is auth-optional. Visibility-
     * or-ownership gate: public → 200 to anyone; private → 200 only to the
     * author; anything else → 404 indistinguishably (do not leak existence).
     *
     * @param documentId the document UUID parsed from the URL
     * @param callerId   the caller's identity, or {@code null} when the request is anonymous
     */
    @Transactional(readOnly = true)
    public DocumentDetailDto getById(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        Document doc = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!isReadableBy(doc, callerId)) {
            throw new DocumentNotFoundException(id);
        }
        return toDetailDto(doc, callerOwnsRow(doc, callerId));
    }

    /** Internal body-fetch for M3 rag-ingestion (ADR-12 §2). No auth filter; visibility-agnostic. */
    @Transactional(readOnly = true)
    public DocumentBodyDto getBody(UUID documentId) {
        DocumentId id = DocumentId.of(documentId);
        Document doc = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        return DocumentBodyDto.from(doc);
    }

    @Transactional
    public DocumentDetailDto patch(PatchDocumentCommand command) {
        DocumentId id = DocumentId.of(command.documentId());
        AuthorId caller = AuthorId.of(command.callerId());
        Document existing = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!existing.isAuthoredBy(caller)) {
            throw new DocumentNotFoundException(id);
        }
        Instant now = Instant.now(clock);

        DocumentTitle newTitle = command.title() == null ? null : parseTitle(command.title());
        DocumentBody newBody = command.body() == null ? null : parseBody(command.body());

        // Per M2 spec §6.1 PATCH only carries title+body. publish/unpublish flow
        // through their dedicated endpoints; folder moves land in M2.1.
        Document updated = existing.edit(newTitle, newBody, null, now);
        // Skip the DB write entirely if neither field actually changed — the
        // aggregate's #edit() returns a structurally-equal-but-distinct
        // Document, so we have to compare field-wise here.
        boolean titleChanged = newTitle != null && !newTitle.equals(existing.title());
        boolean bodyChanged = newBody != null
                && !newBody.checksum().equals(existing.body().checksum());
        if (!titleChanged && !bodyChanged) {
            return toDetailDto(existing, true);
        }
        Document saved = repository.save(updated);
        // Spec §5: uploaded fires on body change only. Title-only PATCH does
        // not re-trigger embedding work.
        if (bodyChanged) {
            publishUploaded(saved, now);
        }
        return toDetailDto(saved, true);
    }

    /**
     * Per M2 spec §6.1 {@code POST /api/docs/{id}/publish}. Owner-only;
     * idempotent on a doc that's already public (returns current state, no
     * mutation). First publish stamps {@code publishedAt = now}.
     */
    @Transactional
    public DocumentDetailDto publish(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        AuthorId caller = AuthorId.of(callerId);
        Document existing = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!existing.isAuthoredBy(caller)) {
            throw new DocumentNotFoundException(id);
        }
        if (existing.isPublic()) {
            return toDetailDto(existing, true);
        }
        Instant now = Instant.now(clock);
        Document published = existing.publish(now);
        Document saved = repository.save(published);
        publishVisibilityChanged(saved, Visibility.PRIVATE, Visibility.PUBLIC, now);
        return toDetailDto(saved, true);
    }

    /**
     * Per M2 spec §6.1 {@code POST /api/docs/{id}/unpublish}. Owner-only;
     * idempotent on a doc that's already private; <em>retains
     * {@code publishedAt}</em> across the transition (spec §6.1 + §4.4).
     */
    @Transactional
    public DocumentDetailDto unpublish(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        AuthorId caller = AuthorId.of(callerId);
        Document existing = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!existing.isAuthoredBy(caller)) {
            throw new DocumentNotFoundException(id);
        }
        if (!existing.isPublic()) {
            return toDetailDto(existing, true);
        }
        Instant now = Instant.now(clock);
        Document unpublished = existing.unpublish(now);
        Document saved = repository.save(unpublished);
        publishVisibilityChanged(saved, Visibility.PUBLIC, Visibility.PRIVATE, now);
        return toDetailDto(saved, true);
    }

    @Transactional
    public void delete(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        AuthorId caller = AuthorId.of(callerId);
        Document existing = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!existing.isAuthoredBy(caller)) {
            throw new DocumentNotFoundException(id);
        }
        repository.deleteById(id);
        publishDeleted(existing, Instant.now(clock));
    }

    // --- event publication helpers ---

    private void publishUploaded(Document doc, Instant now) {
        if (events == null) return;
        events.publishEvent(new DocumentUploaded(
                doc.id(),
                doc.authorId(),
                doc.visibility(),
                doc.title(),
                doc.path(),
                doc.body().checksum(),
                now));
    }

    private void publishVisibilityChanged(
            Document doc, Visibility oldVisibility, Visibility newVisibility, Instant now) {
        if (events == null) return;
        events.publishEvent(new DocumentVisibilityChanged(
                doc.id(),
                doc.authorId(),
                oldVisibility,
                newVisibility,
                doc.publishedAt(),
                now));
    }

    private void publishDeleted(Document doc, Instant now) {
        if (events == null) return;
        events.publishEvent(new DocumentDeleted(doc.id(), doc.authorId(), now));
    }

    // --- helpers ---

    /**
     * Build the detail DTO; only resolve the author block on responses that
     * may surface to a different user (i.e. {@code authorOwnsRow == false}).
     * The caller's own detail page already knows who the author is — skipping
     * the identity lookup keeps the common authoring path off the cross-BC
     * HTTP critical path.
     */
    private DocumentDetailDto toDetailDto(Document doc, boolean authorOwnsRow) {
        AuthorDto author = authorOwnsRow ? null : resolveAuthor(doc.authorId().value());
        // S2: likedByMe is null (the document_likes table lands in S3).
        return DocumentDetailDto.from(doc, author, doc.viewCount(), doc.likeCount(), null);
    }

    private AuthorDto resolveAuthor(UUID authorUserId) {
        if (identityLookup == null) {
            return null;
        }
        return identityLookup.findById(authorUserId).orElse(null);
    }

    private static boolean callerOwnsRow(Document doc, UUID callerId) {
        return callerId != null && doc.isAuthoredBy(AuthorId.of(callerId));
    }

    private static boolean isReadableBy(Document doc, UUID callerId) {
        if (doc.isPublic()) {
            return true;
        }
        if (callerId == null) {
            return false;
        }
        return doc.isAuthoredBy(AuthorId.of(callerId));
    }

    private static DocumentTitle parseTitle(String value) {
        if (value == null || value.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.TITLE_BLANK).throwIt();
        }
        try {
            return DocumentTitle.of(value);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.TITLE_BLANK).throwIt();
            return null; // unreachable
        }
    }

    private static DocumentBody parseBody(String value) {
        try {
            return DocumentBody.of(value);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.BODY_TOO_LARGE).throwIt();
            return null; // unreachable
        }
    }

    private static DocumentPath parsePath(String value) {
        try {
            return DocumentPath.of(value);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.PATH_INVALID, value).throwIt();
            return null; // unreachable
        }
    }

    @SuppressWarnings("unused")
    private static Optional<DocumentBody> ignore(String s) {
        return Optional.empty();
    }
}
