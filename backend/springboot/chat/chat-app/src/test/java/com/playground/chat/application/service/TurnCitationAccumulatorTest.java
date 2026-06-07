package com.playground.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.domain.enums.Visibility;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests the per-turn global renumbering of {@code search_documents} results
 * (agentic-search spec D2). Each tool call returns per-call positions 1..k;
 * the accumulator rewrites them into turn-global positions and accumulates a
 * {@link RetrievedChunk} list that feeds the existing renumber/persist path.
 */
class TurnCitationAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserId caller = UserId.of(UUID.randomUUID());

    /** Build a {@code {"results":[...], "totalFound":n}} body with positions 1..n. */
    private JsonNode searchResultBody(int n) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode results = body.putArray("results");
        for (int i = 1; i <= n; i++) {
            ObjectNode item = results.addObject();
            item.put("position", i);
            item.put("documentId", UUID.randomUUID().toString());
            item.put("chunkIndex", i - 1);
            item.put("title", "Doc " + i);
            item.put("excerpt", "excerpt " + i);
            item.put("visibility", "private");
        }
        body.put("totalFound", n);
        return body;
    }

    private List<Integer> positions(JsonNode body) {
        List<Integer> out = new java.util.ArrayList<>();
        body.path("results").forEach(n -> out.add(n.path("position").asInt()));
        return out;
    }

    @Test
    void renumbersAcrossSearches() {
        var acc = new TurnCitationAccumulator();
        JsonNode first = searchResultBody(3); // per-call positions 1..3
        JsonNode rewritten1 = acc.absorb(caller, first);
        assertThat(positions(rewritten1)).containsExactly(1, 2, 3);

        JsonNode second = searchResultBody(2);
        JsonNode rewritten2 = acc.absorb(caller, second);
        assertThat(positions(rewritten2)).containsExactly(4, 5); // global renumber

        assertThat(acc.retrieved()).hasSize(5);
        assertThat(acc.retrieved().get(3).position()).isEqualTo(4);
    }

    @Test
    void mapsFieldsIntoRetrievedChunk() {
        var acc = new TurnCitationAccumulator();
        UUID docId = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode results = body.putArray("results");
        ObjectNode item = results.addObject();
        item.put("position", 1);
        item.put("documentId", docId.toString());
        item.put("chunkIndex", 7);
        item.put("title", "Site Brief");
        item.put("excerpt", "연면적 1200 m2");
        item.put("visibility", "public");

        acc.absorb(caller, body);

        assertThat(acc.retrieved()).hasSize(1);
        RetrievedChunk c = acc.retrieved().get(0);
        assertThat(c.position()).isEqualTo(1);
        assertThat(c.documentId().value()).isEqualTo(docId);
        assertThat(c.chunkIndex()).isEqualTo(7);
        assertThat(c.title()).isEqualTo("Site Brief");
        assertThat(c.text()).isEqualTo("연면적 1200 m2"); // excerpt → text
        assertThat(c.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(c.chunkOwner()).isEqualTo(caller); // owner from caller
    }

    @Test
    void emptyResultsAbsorbsNothing() {
        var acc = new TurnCitationAccumulator();
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("results");
        body.put("totalFound", 0);

        JsonNode out = acc.absorb(caller, body);

        assertThat(out).isSameAs(body); // body returned as-is
        assertThat(acc.retrieved()).isEmpty();
    }
}
