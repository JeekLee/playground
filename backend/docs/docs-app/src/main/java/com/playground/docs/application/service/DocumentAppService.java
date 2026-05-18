package com.playground.docs.application.service;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.dto.MyDocumentListItemDto;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.repository.DocumentRepository;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service for the M2 S1 docs CRUD slice. Orchestrates the seven
 * endpoints documented in M2 spec §6.1 (S1 subset):
 *
 * <ul>
 *   <li>{@link #create(CreateDocumentCommand)} — author creates a draft (JSON or multipart)</li>
 *   <li>{@link #listMine(UUID)} — caller lists their own documents (?scope=mine)</li>
 *   <li>{@link #getById(UUID, UUID)} — auth-optional read with visibility gate</li>
 *   <li>{@link #patch(PatchDocumentCommand)} — author-only update of {title, body}</li>
 *   <li>{@link #publish(UUID, UUID)} — author-only PRIVATE -> PUBLIC, stamps publishedAt on first call</li>
 *   <li>{@link #unpublish(UUID, UUID)} — author-only PUBLIC -> PRIVATE, retains publishedAt</li>
 *   <li>{@link #delete(UUID, UUID)} — author-only hard delete</li>
 * </ul>
 *
 * <p>Per M2 spec §6.5 + §10 "Tenant isolation" the mismatch between caller and
 * author surfaces as {@link DocumentNotFoundException} (404), never as a 403 —
 * the API must not leak the existence of a private doc to non-authors.
 *
 * <p>S1 does NOT emit Kafka events. The outbox wiring is in place (ADR-12 §1)
 * but no domain events are constructed yet; M2 S2 introduces
 * {@code DocumentUploaded} / {@code DocumentVisibilityChanged} /
 * {@code DocumentDeleted} per M2 spec §5.
 */
@Service
public class DocumentAppService {

    // Structured logging on state transitions (per M2 spec §10 "Observability")
    // lands in M2 S2 alongside the outbox events. S1 keeps the application
    // service silent — the unified RestControllerAdvice in shared-kernel already
    // logs every thrown AbstractException at the right level per ADR-11.

    private final DocumentRepository repository;
    private final Clock clock;

    public DocumentAppService(DocumentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

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
        return DocumentDetailDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<MyDocumentListItemDto> listMine(UUID callerId) {
        AuthorId author = AuthorId.of(callerId);
        return repository.findAllByAuthor(author).stream()
                .map(MyDocumentListItemDto::from)
                .toList();
    }

    /**
     * Per M2 spec §6.1 {@code GET /api/docs/{id}} is auth-optional. Visibility-or-
     * ownership gate:
     * <ul>
     *   <li>{@code visibility == PUBLIC} → 200 to anyone.</li>
     *   <li>{@code visibility == PRIVATE} and caller is the author → 200.</li>
     *   <li>Anything else (private to non-author, missing row) → 404
     *       indistinguishably (do not leak existence).</li>
     * </ul>
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
        return DocumentDetailDto.from(doc);
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
        Document saved = repository.save(updated);
        return DocumentDetailDto.from(saved);
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
            // Idempotent no-op: spec §6.1 publish row says "if already public,
            // return current state". Skip the repository.save() to avoid a
            // spurious updatedAt bump.
            return DocumentDetailDto.from(existing);
        }
        Document published = existing.publish(Instant.now(clock));
        Document saved = repository.save(published);
        return DocumentDetailDto.from(saved);
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
            return DocumentDetailDto.from(existing);
        }
        Document unpublished = existing.unpublish(Instant.now(clock));
        Document saved = repository.save(unpublished);
        return DocumentDetailDto.from(saved);
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
        // existing is read above for the tenant-isolation check; reference it
        // here so future structured-logging additions (M2 S2) have the row
        // metadata without re-fetching.
        @SuppressWarnings("unused")
        Document deleted = existing;
    }

    // --- private parsing helpers ---

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
}
