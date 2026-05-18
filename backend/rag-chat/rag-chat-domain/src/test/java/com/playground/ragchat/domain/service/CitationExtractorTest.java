package com.playground.ragchat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationExtractorTest {

    private final CitationExtractor extractor = new CitationExtractor();

    @Test
    void extractMarkers_singleAndMulti() {
        Set<Integer> markers = extractor.extractMarkers("answer with [1] and also [2][3].");
        assertThat(markers).containsExactly(1, 2, 3);
    }

    @Test
    void extractMarkers_dedupesAndPreservesOrder() {
        Set<Integer> markers = extractor.extractMarkers("foo [2] bar [1] baz [2] qux [1]");
        // LinkedHashSet preserves first-seen order — 2 then 1.
        assertThat(markers).containsExactly(2, 1);
    }

    @Test
    void extractMarkers_ignoresZeroAndNegative() {
        Set<Integer> markers = extractor.extractMarkers("[0] should be ignored and [1] kept");
        // [0] has digit 0 → position 0 which fails >= 1 check; only 1 remains.
        assertThat(markers).containsExactly(1);
    }

    @Test
    void extractMarkers_emptyInputReturnsEmpty() {
        assertThat(extractor.extractMarkers(null)).isEmpty();
        assertThat(extractor.extractMarkers("")).isEmpty();
        assertThat(extractor.extractMarkers("no markers here")).isEmpty();
    }

    @Test
    void extractMarkers_ignoresNonNumericBrackets() {
        assertThat(extractor.extractMarkers("[a] [foo] [1bar]")).isEmpty();
    }

    @Test
    void stripMarkers_removesAndCollapsesSpaces() {
        String stripped = extractor.stripMarkers("foo [1] bar [2] baz [3]");
        assertThat(stripped).isEqualTo("foo bar baz");
    }

    @Test
    void stripMarkers_emptyReturnsEmpty() {
        assertThat(extractor.stripMarkers(null)).isEmpty();
        assertThat(extractor.stripMarkers("")).isEqualTo("");
    }
}
