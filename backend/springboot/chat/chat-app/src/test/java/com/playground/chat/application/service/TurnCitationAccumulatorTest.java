package com.playground.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.chat.domain.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

/**
 * Tests the corpus-blind absorption and per-turn global renumbering of
 * {@code search_documents} results (SP3b spec D4). The accumulator copies the
 * generic {@code SourceRef} fields without interpretation and rewrites each
 * item's {@code position} into a turn-global slot.
 */
class TurnCitationAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void copiesGenericFieldsBlindAndInjectsGlobalPosition() {
        var acc = new TurnCitationAccumulator();
        JsonNode body = json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"A\","
                + "\"content\":\"본문A\",\"uri\":\"https://o/docs/d1\"}]}");

        JsonNode rewritten = acc.absorb(body);

        assertThat(rewritten.path("results").get(0).path("position").asInt()).isEqualTo(1);
        assertThat(acc.retrieved()).hasSize(1);
        RetrievedChunk c = acc.retrieved().get(0);
        assertThat(c.position()).isEqualTo(1);
        assertThat(c.source().sourceType()).isEqualTo("document");
        assertThat(c.source().title()).isEqualTo("A");
        assertThat(c.source().content()).isEqualTo("본문A");
        assertThat(c.source().uri()).isEqualTo("https://o/docs/d1");
        // copy isolation: original input body must not gain a position
        assertThat(body.path("results").get(0).has("position")).isFalse();
    }

    @Test
    void renumbersAcrossTwoSearchesGlobally() {
        var acc = new TurnCitationAccumulator();
        acc.absorb(json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"A\",\"content\":\"x\","
                + "\"uri\":\"u1\"},{\"sourceType\":\"document\",\"title\":\"B\",\"content\":\"y\","
                + "\"uri\":\"u2\"}]}"));
        JsonNode second = acc.absorb(json("{\"results\":[{\"sourceType\":\"document\",\"title\":\"C\","
                + "\"content\":\"z\",\"uri\":\"u3\"}]}"));

        assertThat(second.path("results").get(0).path("position").asInt()).isEqualTo(3); // global
        assertThat(acc.retrieved()).hasSize(3);
        assertThat(acc.retrieved().get(2).position()).isEqualTo(3);
        assertThat(acc.retrieved().get(2).source().title()).isEqualTo("C");
    }

    @Test
    void missingSourceTypeThrows() {
        var acc = new TurnCitationAccumulator();
        assertThatThrownBy(() -> acc.absorb(json("{\"results\":[{\"title\":\"x\",\"content\":\"y\",\"uri\":\"u\"}]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyResultsNoop() {
        var acc = new TurnCitationAccumulator();
        JsonNode body = json("{\"results\":[]}");

        assertThat(acc.absorb(body)).isSameAs(body);
        assertThat(acc.retrieved()).isEmpty();
    }
}
