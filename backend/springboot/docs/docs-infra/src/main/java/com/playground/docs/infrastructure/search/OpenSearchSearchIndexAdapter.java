package com.playground.docs.infrastructure.search;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.SearchHitDto;
import com.playground.docs.application.port.SearchIndexPort;
import com.playground.docs.domain.model.Document;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HighlightField;
import org.opensearch.client.opensearch.core.search.Highlight;
import org.opensearch.client.opensearch.generic.Bodies;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenSearch projection + search adapter per ADR-12 §5–§6 + spec §4.2.
 *
 * <p>Owns the {@code docs-v1} index — bootstraps mapping + Nori analyzer at
 * boot when the index is absent, writes via {@link #index(Document, String)} /
 * {@link #delete(UUID)} from the in-service Kafka projector, and serves
 * full-text queries via {@link #searchPublic} / {@link #searchMine}.
 *
 * <p>Failure isolation per spec §10: writes throw on a downed cluster; the
 * caller (the projector) catches + WARNs + lets the Kafka offset advance.
 * Reads throw on a downed cluster; the {@link com.playground.docs.application
 * .service.DocumentSearchService} maps the exception to a 503 with
 * {@code DocsErrorCode.SEARCH_UNAVAILABLE}.
 *
 * <p>The index-bootstrap path uses the OpenSearch generic client to send the
 * raw mapping JSON — the typed {@code CreateIndexRequest.Builder} on the 2.10.x
 * line doesn't expose {@code withJson(String)} for the {@code settings}+{@code mappings}
 * compound body, so we go one level lower (literal {@code PUT /docs-v1} with
 * the spec §4.2 + §6 mapping JSON as the body).
 */
@Component
@RequiredArgsConstructor
public class OpenSearchSearchIndexAdapter implements SearchIndexPort {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchSearchIndexAdapter.class);
    private static final String INDEX = "docs-v1";
    private static final int PAGE_SIZE = 20;

    private final OpenSearchClient client;

    @PostConstruct
    void ensureIndex() {
        try {
            boolean exists = client.indices().exists(b -> b.index(INDEX)).value();
            if (exists) {
                log.info("OpenSearch index {} already exists — skipping bootstrap", INDEX);
                return;
            }
            log.info("OpenSearch index {} missing — creating with Nori analyzer + mapping", INDEX);
            OpenSearchGenericClient generic = client.generic();
            Request request = Requests.builder()
                    .endpoint("/" + INDEX)
                    .method("PUT")
                    .json(INDEX_BODY)
                    .build();
            try (Response response = generic.execute(request)) {
                int code = response.getStatus();
                if (code < 200 || code >= 300) {
                    log.warn("OpenSearch index create returned status={} — body={}",
                            code, response.getBody().map(Object::toString).orElse(""));
                } else {
                    log.info("OpenSearch index {} created (status={})", INDEX, code);
                }
            }
        } catch (Exception e) {
            // Boot does not fail — search routes will 503 until OpenSearch is up.
            log.warn("OpenSearch index bootstrap failed — search routes will 503 until cluster is reachable", e);
        }
    }

    @Override
    public void index(Document doc, String authorName) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("documentId", doc.id().value().toString());
            body.put("userId", doc.authorId().value().toString());
            body.put("authorName", authorName);
            body.put("title", doc.title().value());
            body.put("body", doc.body().value());
            body.put("visibility", doc.visibility().wireValue());
            body.put("path", doc.path().value());
            body.put("publishedAt", doc.publishedAt());
            body.put("updatedAt", doc.updatedAt());

            IndexRequest<Map<String, Object>> request = new IndexRequest.Builder<Map<String, Object>>()
                    .index(INDEX)
                    .id(doc.id().value().toString())
                    .document(body)
                    .refresh(org.opensearch.client.opensearch._types.Refresh.False)
                    .build();
            client.index(request);
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch index failed for doc=" + doc.id(), e);
        }
    }

    @Override
    public void delete(UUID documentId) {
        try {
            client.delete(b -> b.index(INDEX).id(documentId.toString()));
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch delete failed for doc=" + documentId, e);
        }
    }

    @Override
    public CursorPage<SearchHitDto> searchPublic(String query, String cursor) {
        return runSearch(query, cursor, true, null);
    }

    @Override
    public CursorPage<SearchHitDto> searchMine(UUID callerUserId, String query, String cursor) {
        return runSearch(query, cursor, false, callerUserId);
    }

    private CursorPage<SearchHitDto> runSearch(
            String query, String cursor, boolean publicScope, UUID callerUserId) {
        int from = parseFrom(cursor);

        Query textQuery = Query.of(q -> q.multiMatch(m -> m
                .query(query)
                .fields("title^3", "title.en^3", "body", "body.en", "authorName^2")
                .type(TextQueryType.BestFields)
                .operator(Operator.Or)));
        Query filter = publicScope
                ? Query.of(q -> q.term(t -> t.field("visibility").value(FieldValue.of("public"))))
                : Query.of(q -> q.term(t -> t.field("userId").value(FieldValue.of(callerUserId.toString()))));
        Query combined = Query.of(q -> q.bool(b -> b.must(textQuery).filter(filter)));

        Highlight highlight = Highlight.of(h -> h
                .preTags("<mark>")
                .postTags("</mark>")
                .fragmentSize(160)
                .numberOfFragments(1)
                .fields("body", HighlightField.of(f -> f))
                .fields("title", HighlightField.of(f -> f)));

        try {
            SearchResponse<Map> response = client.search(s -> s
                    .index(INDEX)
                    .query(combined)
                    .highlight(highlight)
                    .from(from)
                    .size(PAGE_SIZE),
                    Map.class);

            List<SearchHitDto> items = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                items.add(toDto(hit, publicScope));
            }
            long totalHits = response.hits().total() == null ? 0 : response.hits().total().value();
            String nextCursor = (from + items.size()) < totalHits
                    ? String.valueOf(from + items.size())
                    : null;
            return CursorPage.of(items, nextCursor);
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch search failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static SearchHitDto toDto(Hit<Map> hit, boolean publicScope) {
        Map<String, Object> src = hit.source();
        String documentId = stringField(src, "documentId");
        if (documentId == null) {
            documentId = hit.id();
        }
        String title = stringField(src, "title");
        String visibility = stringField(src, "visibility");
        String path = stringField(src, "path");
        String authorName = stringField(src, "authorName");
        String userId = stringField(src, "userId");
        String snippet = pickSnippet(hit, src);
        Instant publishedAt = parseInstant(stringField(src, "publishedAt"));
        Instant updatedAt = parseInstant(stringField(src, "updatedAt"));

        AuthorDto author = null;
        if (publicScope && userId != null) {
            UUID id;
            try {
                id = UUID.fromString(userId);
            } catch (IllegalArgumentException e) {
                id = null;
            }
            if (id != null) {
                author = new AuthorDto(id, authorName, null);
            }
        }
        return new SearchHitDto(
                documentId == null ? null : UUID.fromString(documentId),
                title,
                visibility,
                path,
                author,
                snippet,
                publishedAt,
                updatedAt);
    }

    @SuppressWarnings("unchecked")
    private static String pickSnippet(Hit<Map> hit, Map<String, Object> src) {
        Map<String, List<String>> highlights = hit.highlight();
        if (highlights != null) {
            List<String> bodyFrag = highlights.get("body");
            if (bodyFrag != null && !bodyFrag.isEmpty()) {
                return bodyFrag.get(0);
            }
            List<String> titleFrag = highlights.get("title");
            if (titleFrag != null && !titleFrag.isEmpty()) {
                return titleFrag.get(0);
            }
        }
        // Fallback to first 160 chars of the raw body if no highlight returned.
        String body = stringField(src, "body");
        if (body == null) return "";
        return body.length() <= 160 ? body : body.substring(0, 160) + "…";
    }

    private static String stringField(Map<String, Object> src, String key) {
        if (src == null) return null;
        Object v = src.get(key);
        return v == null ? null : v.toString();
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseFrom(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int v = Integer.parseInt(cursor);
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Index settings + mapping per ADR-12 §6 + M2 spec §4.2. Korean analyzer
     * is Nori (built-in to the OpenSearch 2.x image); English is the
     * standard analyzer. Multi-field {@code title.en} / {@code body.en}
     * cover Latin-only queries where Nori would over-decompound.
     */
    private static final String INDEX_BODY = """
            {
              "settings": {
                "analysis": {
                  "tokenizer": {
                    "nori_user_dict": {
                      "type": "nori_tokenizer",
                      "decompound_mode": "mixed",
                      "discard_punctuation": "true"
                    }
                  },
                  "analyzer": {
                    "korean": {
                      "type": "custom",
                      "tokenizer": "nori_user_dict",
                      "filter": ["lowercase", "nori_part_of_speech", "nori_readingform"]
                    },
                    "english": {
                      "type": "standard"
                    }
                  }
                }
              },
              "mappings": {
                "properties": {
                  "documentId": { "type": "keyword" },
                  "userId":     { "type": "keyword" },
                  "authorName": {
                    "type": "text",
                    "analyzer": "korean",
                    "fields": {
                      "en":  { "type": "text", "analyzer": "english" },
                      "raw": { "type": "keyword" }
                    }
                  },
                  "title": {
                    "type": "text",
                    "analyzer": "korean",
                    "fields": {
                      "en":  { "type": "text", "analyzer": "english" },
                      "raw": { "type": "keyword" }
                    }
                  },
                  "body": {
                    "type": "text",
                    "analyzer": "korean",
                    "fields": { "en": { "type": "text", "analyzer": "english" } }
                  },
                  "visibility":  { "type": "keyword" },
                  "path":        { "type": "keyword" },
                  "publishedAt": { "type": "date" },
                  "updatedAt":   { "type": "date" }
                }
              }
            }
            """;
}
