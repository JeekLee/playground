package com.playground.chat.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.shared.chat.SourceRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn-scoped search-citation accumulator (SP3b spec D4). Renumbers each
 * {@code search_documents} result's per-call position into a turn-global
 * position so a multi-search turn yields a single dense {@code [1..N]} citation
 * space; rewrites the LLM-visible body's {@code position} field accordingly and
 * accumulates a {@link RetrievedChunk} per result.
 *
 * <p>The accumulator is <strong>corpus-blind</strong>: it copies the generic
 * {@code SourceRef} fields ({@code sourceType}/{@code title}/{@code content}/
 * {@code uri}) without interpreting them. It does not read documentId/chunkIndex/
 * visibility by name — a future non-document corpus flows through unchanged.
 *
 * <p>At turn end {@link #retrieved()} is the input to the existing
 * renumber/persist path ({@code renumberCitations} → {@code message_citations}
 * → Done-event citations) — the citation machinery is unchanged.
 *
 * <p>Not thread-safe: tool invocations within a turn serialize on the dispatch
 * thread (see {@code ChatTurnService.handleToolInvocation}).
 */
public class TurnCitationAccumulator {

    private final List<RetrievedChunk> retrieved = new ArrayList<>();

    /**
     * Copy the {@code search_documents} result body's generic citations without
     * interpretation and inject a turn-global {@code position} into each item.
     *
     * @param resultBody the tool's {@code result} body (the LLM-visible node).
     * @return a renumbered copy when {@code results} is a non-empty array;
     *         otherwise the original body unchanged (empty search absorbs
     *         nothing).
     */
    public JsonNode absorb(JsonNode resultBody) {
        JsonNode results = resultBody.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return resultBody;
        }
        ObjectNode copy = resultBody.deepCopy();
        ArrayNode arr = (ArrayNode) copy.get("results");
        for (JsonNode item : arr) {
            int global = retrieved.size() + 1;
            ((ObjectNode) item).put("position", global); // LLM-visible — for [N] citation
            retrieved.add(new RetrievedChunk(global, new SourceRef(
                    item.path("sourceType").asText(),
                    item.path("title").asText(null),
                    item.path("content").asText(null),
                    item.path("uri").asText(null))));
        }
        return copy;
    }

    /** The turn's accumulated chunks in global {@code [1..N]} order. */
    public List<RetrievedChunk> retrieved() {
        return List.copyOf(retrieved);
    }
}
