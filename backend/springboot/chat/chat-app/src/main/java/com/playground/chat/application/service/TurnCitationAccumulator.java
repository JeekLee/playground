package com.playground.chat.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.domain.enums.Visibility;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turn-scoped search-citation accumulator (agentic-search spec D2). Renumbers
 * each {@code search_documents} result's per-call {@code position} (1..k) into
 * a turn-global position so a multi-search turn yields a single dense
 * {@code [1..N]} citation space; rewrites the LLM-visible body's position
 * field accordingly and accumulates a {@link RetrievedChunk} per result.
 *
 * <p>At turn end {@link #retrieved()} is the input to the existing
 * renumber/persist path ({@code renumberCitations} → {@code message_citations}
 * → Done-event citations) — the source of the {@code retrieved} list changes
 * from pipeline retrieval to tool results, the citation machinery is unchanged.
 *
 * <p>Not thread-safe: tool invocations within a turn serialize on the dispatch
 * thread (see {@code ChatTurnService.handleToolInvocation}).
 */
public class TurnCitationAccumulator {

    private final List<RetrievedChunk> retrieved = new ArrayList<>();

    /**
     * Renumber the {@code search_documents} result body into a global
     * citation space and accumulate its chunks.
     *
     * @param caller     the searching user — recorded as each chunk's owner
     *                   ({@link RetrievedChunk#chunkOwner()}); the docs-api
     *                   visibility filter has already scoped the rows to this
     *                   caller, so the owner is the caller for accounting.
     * @param resultBody the tool's {@code result} body (the LLM-visible node).
     * @return a renumbered copy when {@code results} is a non-empty array;
     *         otherwise the original body unchanged (empty search absorbs
     *         nothing).
     */
    public JsonNode absorb(UserId caller, JsonNode resultBody) {
        JsonNode results = resultBody.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return resultBody;
        }
        ObjectNode copy = resultBody.deepCopy();
        ArrayNode arr = (ArrayNode) copy.get("results");
        for (JsonNode item : arr) {
            int global = retrieved.size() + 1;
            ((ObjectNode) item).put("position", global);
            retrieved.add(new RetrievedChunk(
                    global,
                    DocumentId.of(UUID.fromString(item.path("documentId").asText())),
                    item.path("chunkIndex").asInt(),
                    item.path("excerpt").asText(""),
                    item.path("title").asText(null),
                    caller,
                    Visibility.fromWire(item.path("visibility").asText("private"))));
        }
        return copy;
    }

    /** The turn's accumulated chunks in global {@code [1..N]} order. */
    public List<RetrievedChunk> retrieved() {
        return List.copyOf(retrieved);
    }
}
