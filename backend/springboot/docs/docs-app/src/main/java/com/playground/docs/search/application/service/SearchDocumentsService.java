package com.playground.docs.search.application.service;

import com.playground.docs.search.application.port.ChunkSearchPort;
import com.playground.docs.search.application.port.QueryEmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Use case behind the {@code search_documents} tool (agentic-search spec D1).
 * Embeds the query, runs the caller-scoped pgvector search, and maps each
 * ranked chunk into an LLM-visible {@link Result} with a global 1..k position
 * and a head-truncated excerpt.
 *
 * <p>The excerpt is a fixed {@value #EXCERPT_CHARS}-char head truncation —
 * this replaces chat's old per-chunk token budget intent (the tool surface
 * speaks chars, not tokens, to stay model-agnostic).
 */
@Service
public class SearchDocumentsService {

    static final int EXCERPT_CHARS = 600;
    static final int MIN_K = 1;
    static final int MAX_K = 20;
    static final int DEFAULT_K = 6;

    private final QueryEmbeddingPort queryEmbeddingPort;
    private final ChunkSearchPort chunkSearchPort;

    public SearchDocumentsService(
            QueryEmbeddingPort queryEmbeddingPort, ChunkSearchPort chunkSearchPort) {
        this.queryEmbeddingPort = queryEmbeddingPort;
        this.chunkSearchPort = chunkSearchPort;
    }

    /** One ranked search hit handed to the LLM (and on to FE via the citation accumulator). */
    public record Result(
            int position,
            UUID documentId,
            int chunkIndex,
            String title,
            String excerpt,
            String visibility) {}

    /**
     * Terminal tool result body: hits + returned count + a human-readable summary.
     *
     * <p><strong>{@code totalFound} is the number of results RETURNED</strong>
     * ({@code results.size()}, always ≤ {@code topK}) — <em>not</em> a
     * corpus-wide count of every chunk that matched. pgvector runs a
     * {@code LIMIT k} ANN search and has no cheap way to count total matches,
     * so the wire contract (agentic-search spec D1) defines this field as the
     * returned hit count. The field name stays {@code totalFound} because chat
     * and the FE consume it by that name. The {@code summary} "&lt;query&gt; —
     * N건" reports the same N (returned count), consistent with this.
     */
    public record SearchOutcome(List<Result> results, int totalFound, String summary) {}

    public SearchOutcome search(UUID callerId, String query, Integer topK, UUID documentId) {
        int k = Math.max(MIN_K, Math.min(MAX_K, topK == null ? DEFAULT_K : topK));
        float[] embedding = queryEmbeddingPort.embedQuery(query);
        List<ChunkSearchPort.Row> rows = chunkSearchPort.search(callerId, embedding, k, documentId);

        List<Result> results = new ArrayList<>(rows.size());
        int position = 1;
        for (ChunkSearchPort.Row row : rows) {
            results.add(new Result(
                    position++,
                    row.documentId(),
                    row.chunkIndex(),
                    row.title(),
                    excerpt(row.text()),
                    row.visibility()));
        }
        String summary = query + " — " + results.size() + "건";
        return new SearchOutcome(results, results.size(), summary);
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS);
    }
}
