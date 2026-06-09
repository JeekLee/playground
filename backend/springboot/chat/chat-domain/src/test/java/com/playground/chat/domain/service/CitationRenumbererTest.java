package com.playground.chat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.shared.chat.SourceRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Marker-expansion + dense-renumbering tests for {@link CitationRenumberer}.
 * Moved out of {@code ChatTurnServiceTest} when the algorithm was extracted from
 * the orchestrator into this pure domain helper (issue #244 item ①).
 */
class CitationRenumbererTest {

    @Test
    void renumberCitations_expandsGroupedBracketToIndividualMarkers() {
        // The LLM grouped sources as [1, 2, 4, 5]; with all 5 positions
        // retrieved the group expands to individual dense markers and every
        // referenced position becomes a citation.
        List<RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3), chunk(4), chunk(5));

        CitationRenumberer.CitationRenumber out =
                CitationRenumberer.renumberCitations("a [1] b [2] c [1, 2, 4, 5].", retrieved);

        // First-encounter dense numbering across all brackets: 1→1, 2→2,
        // 4→3, 5→4. The group expands to individual brackets, no separator.
        assertThat(out.text()).isEqualTo("a [1] b [2] c [1][2][3][4].");
        // cited carries one entry per distinct referenced position (4 here);
        // positions 1,2 from singles + 4,5 newly seen inside the group.
        assertThat(out.cited()).extracting(CitationRenumberer.CitedChunk::newN)
                .containsExactly(1, 2, 3, 4);
        assertThat(out.cited()).extracting(c -> c.chunk().position())
                .containsExactly(1, 2, 4, 5);
    }

    @Test
    void renumberCitations_dropsInvalidPositionsInsideGroup() {
        // Only 3 positions retrieved; [1, 9] cites 9 (orphan). The valid 1 is
        // kept (and renumbered), the orphan 9 is dropped from the expansion.
        List<RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3));

        CitationRenumberer.CitationRenumber out =
                CitationRenumberer.renumberCitations("only [1, 9] here.", retrieved);

        assertThat(out.text()).isEqualTo("only [1] here.");
        assertThat(out.cited()).extracting(CitationRenumberer.CitedChunk::newN)
                .containsExactly(1);
    }

    @Test
    void renumberCitations_skipsOversizedNumberInsideGroup() {
        // The LLM grouped sources with an oversized number that overflows int:
        // [1, 99999999999999, 2]. Without the parseInt guard this throws
        // NumberFormatException and crashes the turn. The guard treats it as an
        // orphan — dropped from the expansion — while the valid 1, 2 expand.
        List<RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3));

        CitationRenumberer.CitationRenumber out =
                CitationRenumberer.renumberCitations("see [1, 99999999999999, 2].", retrieved);

        assertThat(out.text()).isEqualTo("see [1][2].");
        assertThat(out.cited()).extracting(CitationRenumberer.CitedChunk::newN)
                .containsExactly(1, 2);
        assertThat(out.cited()).extracting(c -> c.chunk().position())
                .containsExactly(1, 2);
    }

    @Test
    void renumberCitations_leavesAllInvalidGroupUntouched() {
        // [8, 9] with only 3 retrieved — every position is an orphan, so the
        // bracket is left exactly as written (preserves the bad-output trail).
        List<RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3));

        CitationRenumberer.CitationRenumber out =
                CitationRenumberer.renumberCitations("bogus [8, 9] ref.", retrieved);

        assertThat(out.text()).isEqualTo("bogus [8, 9] ref.");
        assertThat(out.cited()).isEmpty();
    }

    private static RetrievedChunk chunk(int position) {
        return new RetrievedChunk(
                position,
                new SourceRef(
                        "document", "Doc " + position, "text " + position,
                        "https://o/docs/" + position));
    }
}
