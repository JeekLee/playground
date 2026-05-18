package com.playground.docs.application.service;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.DocListItemDto;
import com.playground.docs.application.port.IdentityLookupPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.service.MarkdownExcerpt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for the community feed and per-author public feed per
 * M2 spec §6.1.
 *
 * <p>Endpoints backed:
 * <ul>
 *   <li>{@code GET /api/docs} (no scope, no author) → community feed (all
 *       authors' public docs, sorted {@code published_at DESC}).</li>
 *   <li>{@code GET /api/docs?author={userId}} → same shape, filtered to one
 *       author.</li>
 * </ul>
 *
 * <p>Page size pinned to {@value #PAGE_SIZE} per spec §6.1. Cursor encoding /
 * decoding flows through {@link FeedCursor}.
 *
 * <p>Author resolution batches the page's distinct {@code userId}s into a
 * single {@link IdentityLookupPort#findAllByIds} call so a 20-row page costs
 * at most one HTTP roundtrip to identity-api.
 */
@Service
public class DocumentFeedService {

    /** Page size pinned for the community + per-author feeds (M2 spec §6.1). */
    public static final int PAGE_SIZE = 20;

    private final DocumentRepository repository;
    private final IdentityLookupPort identityLookup;

    public DocumentFeedService(DocumentRepository repository, IdentityLookupPort identityLookup) {
        this.repository = repository;
        this.identityLookup = identityLookup;
    }

    /** Community-wide public feed. */
    @Transactional(readOnly = true)
    public CursorPage<DocListItemDto> communityFeed(String cursor) {
        FeedCursor.Decoded decoded = FeedCursor.decode(cursor);
        List<Document> page = repository.findPublicFeed(
                decoded == null ? null : decoded.publishedAt(),
                decoded == null ? null : decoded.id(),
                PAGE_SIZE + 1);
        return assemble(page);
    }

    /** Per-author public feed (used by the home tile). */
    @Transactional(readOnly = true)
    public CursorPage<DocListItemDto> authorFeed(UUID authorUserId, String cursor) {
        FeedCursor.Decoded decoded = FeedCursor.decode(cursor);
        List<Document> page = repository.findPublicFeedByAuthor(
                AuthorId.of(authorUserId),
                decoded == null ? null : decoded.publishedAt(),
                decoded == null ? null : decoded.id(),
                PAGE_SIZE + 1);
        return assemble(page);
    }

    /**
     * Convert the raw row list into a {@link CursorPage}, resolving authors in
     * a single batch. The repository returns {@code PAGE_SIZE + 1} rows so we
     * can detect whether there's a next page without a count query.
     */
    private CursorPage<DocListItemDto> assemble(List<Document> page) {
        boolean hasMore = page.size() > PAGE_SIZE;
        List<Document> visible = hasMore ? page.subList(0, PAGE_SIZE) : page;
        Map<UUID, AuthorDto> authors = resolveAuthors(visible);

        List<DocListItemDto> items = visible.stream()
                .map(doc -> new DocListItemDto(
                        doc.id().value(),
                        doc.title().value(),
                        MarkdownExcerpt.of(doc.body().value()),
                        doc.visibility().wireValue(),
                        doc.path().value(),
                        authors.get(doc.authorId().value()),
                        doc.publishedAt(),
                        doc.viewCount(),
                        doc.likeCount(),
                        null /* likedByMe — S3 */))
                .toList();

        String nextCursor = null;
        if (hasMore && !visible.isEmpty()) {
            Document last = visible.get(visible.size() - 1);
            nextCursor = FeedCursor.encode(last.publishedAt(), last.id().value());
        }
        return CursorPage.of(items, nextCursor);
    }

    private Map<UUID, AuthorDto> resolveAuthors(List<Document> page) {
        if (page.isEmpty() || identityLookup == null) {
            return Map.of();
        }
        Set<UUID> uniqueAuthors = page.stream()
                .map(d -> d.authorId().value())
                .collect(Collectors.toSet());
        return identityLookup.findAllByIds(uniqueAuthors);
    }
}
