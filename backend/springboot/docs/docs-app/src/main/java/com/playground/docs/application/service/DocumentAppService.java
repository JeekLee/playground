package com.playground.docs.application.service;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.dto.DocumentBodyDto;
import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.dto.MyDocumentListItemDto;
import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.application.port.BlobStoragePort;
import com.playground.docs.application.port.IdentityLookupPort;
import com.playground.docs.application.repository.DocumentLikeRepository;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.event.DocumentDeleted;
import com.playground.docs.domain.event.DocumentExtractionRequested;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    /** M2 S3: optional like repository so getById can populate {@code likedByMe}. */
    private final DocumentLikeRepository likeRepository;
    private final Clock clock;
    /**
     * M6.1 ADR-12 §A12.4 — optional MinIO port. Wired by docs-infra's
     * {@code MinioBlobStorageAdapter}; nullable so unit tests that
     * construct the service via the legacy constructors keep working.
     * When null and a delete arrives for a document with a non-null
     * {@code sourceObjectKey} the row is dropped but the MinIO blob is
     * orphaned — the nightly cleanup pass (future work) reconciles.
     */
    private final BlobStoragePort blobStorage;

    /**
     * Primary constructor (S3) — Spring picks this one in production wiring
     * via the explicit {@link Autowired}. The shorter S2-compat overload
     * below routes through this constructor with {@code likeRepository = null}.
     *
     * <p>The {@code likeRepository} parameter is optional: a null value
     * disables the {@code likedByMe} join and the detail DTO falls back to
     * S2 behavior ({@code likedByMe == null}). This keeps the older five-arg
     * unit tests in {@code DocumentAppServiceTest} working without a fresh
     * wiring change.
     */
    @Autowired
    public DocumentAppService(
            DocumentRepository repository,
            ApplicationEventPublisher events,
            IdentityLookupPort identityLookup,
            DocumentLikeRepository likeRepository,
            Clock clock,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BlobStoragePort blobStorage) {
        this.repository = repository;
        this.events = events;
        this.identityLookup = identityLookup;
        this.likeRepository = likeRepository;
        this.clock = clock;
        this.blobStorage = blobStorage;
    }

    /** Backwards-compat constructor without blobStorage. */
    public DocumentAppService(
            DocumentRepository repository,
            ApplicationEventPublisher events,
            IdentityLookupPort identityLookup,
            DocumentLikeRepository likeRepository,
            Clock clock) {
        this(repository, events, identityLookup, likeRepository, clock, null);
    }

    /** S2-compat constructor for tests pre-dating the like repo. */
    public DocumentAppService(
            DocumentRepository repository,
            ApplicationEventPublisher events,
            IdentityLookupPort identityLookup,
            Clock clock) {
        this(repository, events, identityLookup, null, clock, null);
    }

    // --- CRUD ---

    @Transactional
    public DocumentDetailDto create(CreateDocumentCommand command) {
        return createWithId(UUID.randomUUID(), command);
    }

    /**
     * M6.1 — explicit-id create. The multipart controller assigns the
     * document UUID up-front so the MinIO object key
     * ({@code {documentId}/source.{ext}}) and the DB row share the same id
     * (atomic naming, easier orphan cleanup).
     */
    @Transactional
    public DocumentDetailDto createWithId(UUID documentUuid, CreateDocumentCommand command) {
        Instant now = Instant.now(clock);
        DocumentTitle title = parseTitle(command.title());
        DocumentPath path = parsePath(command.path());
        AuthorId authorId = AuthorId.of(command.authorId());
        MimeType mimeType = command.mimeType() == null ? MimeType.MARKDOWN : command.mimeType();
        DocumentId documentId = DocumentId.of(documentUuid);

        if (command.isAsyncExtraction()) {
            // M6.1 ADR-12 §A12.5 — async path. INSERT with empty body +
            // pending_extraction; publish extraction-requested so the worker
            // listener picks it up after the transaction commits.
            Document doc = Document.createPendingExtraction(
                    documentId,
                    authorId,
                    title,
                    path,
                    mimeType,
                    command.sourceObjectKey(),
                    command.sourceSizeBytes() == null ? 0L : command.sourceSizeBytes(),
                    command.sourceMime() == null ? mimeType.wireValue() : command.sourceMime(),
                    now);
            Document saved = repository.save(doc);
            publishExtractionRequested(saved, now);
            return toDetailDto(saved, callerOwnsRow(saved, authorId.value()));
        }

        // Synchronous M2 path — body provided up front (JSON POST or
        // plain markdown multipart without MinIO retention).
        DocumentBody body = parseBody(command.body());
        Document doc = Document.create(
                documentId,
                authorId,
                title,
                body,
                path,
                mimeType,
                now);
        Document saved = repository.save(doc);
        publishUploaded(saved, now);
        return toDetailDto(saved, callerOwnsRow(saved, authorId.value()));
    }

    @Transactional(readOnly = true)
    public List<MyDocumentListItemDto> listMine(UUID callerId) {
        return listMine(callerId, null);
    }

    /**
     * S3 overload: optionally scope to a single folder path per M2 spec §6.1
     * row {@code GET /api/docs?scope=mine&path={folder}}. Null/blank path →
     * caller's full mine list.
     */
    @Transactional(readOnly = true)
    public List<MyDocumentListItemDto> listMine(UUID callerId, String pathFilter) {
        AuthorId author = AuthorId.of(callerId);
        List<Document> rows = (pathFilter == null || pathFilter.isBlank())
                ? repository.findAllByAuthor(author)
                : repository.findAllByAuthorAndPath(author, normalizePath(pathFilter));
        return rows.stream()
                .map(d -> MyDocumentListItemDto.from(d, d.viewCount(), d.likeCount()))
                .toList();
    }

    /**
     * Normalize a path-filter parameter: validate via {@link DocumentPath}
     * (single source of truth for format rules) and return its canonical
     * form. Rejects malformed paths with the same {@code PATH_INVALID}
     * error code the create path uses.
     */
    private static String normalizePath(String raw) {
        return parsePath(raw).value();
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
        Boolean likedByMe = resolveLikedByMe(id, callerId);
        return toDetailDto(doc, callerOwnsRow(doc, callerId), likedByMe);
    }

    /** Internal body-fetch for M3 rag-ingestion (ADR-12 §2). No auth filter; visibility-agnostic. */
    @Transactional(readOnly = true)
    public DocumentBodyDto getBody(UUID documentId) {
        DocumentId id = DocumentId.of(documentId);
        Document doc = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        return DocumentBodyDto.from(doc);
    }

    /**
     * M6.1 — accessor for the source-blob metadata. Returns {@code null}
     * when the document has no source blob (pre-M6.1 row) or when the caller
     * is not authorized; the controller maps the latter via the
     * {@link #getById} visibility gate already.
     */
    @Transactional(readOnly = true)
    public com.playground.docs.application.dto.SourceBlobMeta getSourceMeta(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        Document doc = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        if (!isReadableBy(doc, callerId)) {
            throw new DocumentNotFoundException(id);
        }
        if (doc.sourceObjectKey() == null) {
            return null;
        }
        return com.playground.docs.application.dto.SourceBlobMeta.from(doc);
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
        // M6.1 — best-effort delete the MinIO blob. Adapter logs + swallows
        // failures so a transient MinIO outage doesn't roll back the row
        // delete; orphan cleanup pass reconciles drift.
        if (blobStorage != null && existing.sourceObjectKey() != null) {
            blobStorage.deleteObject(existing.sourceObjectKey());
        }
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

    private void publishExtractionRequested(Document doc, Instant now) {
        if (events == null) return;
        events.publishEvent(new DocumentExtractionRequested(
                doc.id(),
                doc.authorId(),
                doc.mimeType(),
                doc.sourceObjectKey(),
                now));
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
        return toDetailDto(doc, authorOwnsRow, null);
    }

    /**
     * S3 overload — wires the {@code likedByMe} flag (resolved by
     * {@link #resolveLikedByMe} when the caller is authenticated; null
     * otherwise per spec §6.4 — anonymous detail responses omit the flag).
     *
     * <p>Per M2 spec §6.4 {@code DocDetail.author} is a required block on
     * every single-doc response — including the author's own view. The
     * {@code authorOwnsRow} parameter is retained for callers that want to
     * skip the optional identity lookup, but the resolved author always
     * flows through to the DTO (fallback to a placeholder when the lookup
     * misses — see {@link #resolveAuthor}).
     */
    private DocumentDetailDto toDetailDto(Document doc, boolean authorOwnsRow, Boolean likedByMe) {
        AuthorDto author = resolveAuthor(doc.authorId().value());
        return DocumentDetailDto.from(doc, author, doc.viewCount(), doc.likeCount(), likedByMe);
    }

    /**
     * S3 helper: when the caller is authenticated, ask the like repository
     * whether the (doc, caller) pair exists. Returns {@code null} for
     * anonymous callers per spec §6.4 ({@code likedByMe?} is absent on
     * anonymous responses). Tolerates a null {@code likeRepository} (test
     * paths constructed via the S2-compat constructor).
     */
    private Boolean resolveLikedByMe(DocumentId documentId, UUID callerId) {
        if (callerId == null || likeRepository == null) {
            return null;
        }
        return likeRepository.existsBy(documentId, AuthorId.of(callerId));
    }

    /**
     * Resolve the author block via the cross-BC identity lookup. Falls back
     * to a placeholder {@code AuthorDto} carrying just the user id when the
     * lookup port is unavailable (test paths) or the identity row has been
     * purged — never returns null so the wire contract guarantees a
     * non-null author on every {@code DocDetail} per spec §6.4.
     */
    private AuthorDto resolveAuthor(UUID authorUserId) {
        if (identityLookup != null) {
            AuthorDto resolved = identityLookup.findById(authorUserId).orElse(null);
            if (resolved != null) {
                return resolved;
            }
        }
        return new AuthorDto(authorUserId, "Unknown", null);
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
