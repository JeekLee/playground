package com.playground.chat.domain.service;

import com.playground.chat.domain.model.RetrievedChunk;
import java.util.List;

/**
 * Renumbers the {@code [N]} citation markers in an assistant turn's text into a
 * dense {@code [1..K]} sequence and produces the cited-chunk subset in that
 * order. Pure, stateless domain logic — extracted from {@code ChatTurnService}
 * (ADR-14 / agentic-search spec D2) so the orchestrator no longer carries the
 * marker-expansion algorithm inline. Sibling of {@link CitationExtractor}.
 */
public final class CitationRenumberer {

    private CitationRenumberer() {
    }

    /**
     * Walk the accumulated text in order, find each {@code [N]} marker — or a
     * grouped {@code [N, M, ...]} bracket the LLM sometimes emits — whose
     * numbers correspond to retrieved chunks, assign new dense sequence numbers
     * in first-encounter order, and rewrite the text so the rendered cards read
     * [1][2][3] regardless of which original retrieval positions the LLM picked.
     *
     * <p>A grouped bracket is EXPANDED to individual renumbered brackets
     * ({@code [1, 2, 4, 5]} → {@code [1][2][3][4]}), so the persisted text holds
     * only single {@code [N]} brackets that the existing FE regex renders as
     * pills — and a position referenced only inside a group is no longer
     * dropped from the cited subset. Numbers that don't match any retrieved
     * chunk (hallucinations) are dropped from the expansion; a bracket whose
     * numbers are ALL orphans is left untouched so the trail of bad LLM output
     * is still visible.
     *
     * <p>Public static so a unit test can exercise the expansion logic directly
     * (the records below are likewise public).
     */
    public static CitationRenumber renumberCitations(String text, List<RetrievedChunk> retrieved) {
        if (text == null || text.isEmpty()) {
            return new CitationRenumber("", List.of());
        }
        java.util.Map<Integer, RetrievedChunk> byPosition = new java.util.HashMap<>();
        for (RetrievedChunk c : retrieved) {
            byPosition.put(c.position(), c);
        }
        java.util.LinkedHashMap<Integer, Integer> remap = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]").matcher(text);
        // Pass 1 — dense first-encounter numbering across ALL brackets (single
        // or grouped), in textual order of the numbers within each group. An
        // unparseable/oversized number (e.g. [99999999999999]) is skipped —
        // treated as invalid/orphan — mirroring CitationExtractor.extractMarkers.
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                Integer orig = tryParse(part);
                if (orig != null && byPosition.containsKey(orig) && !remap.containsKey(orig)) {
                    remap.put(orig, remap.size() + 1);
                }
            }
        }
        // Pass 2 — rewrite. Expand each (possibly grouped) bracket into the
        // concatenation of its valid renumbered single brackets; if every
        // number in the bracket is an orphan (or unparseable), leave the
        // original untouched.
        String rewritten = m.reset().replaceAll(match -> {
            StringBuilder expanded = new StringBuilder();
            for (String part : match.group(1).split(",")) {
                Integer orig = tryParse(part);
                Integer mapped = orig == null ? null : remap.get(orig);
                if (mapped != null) {
                    expanded.append('[').append(mapped).append(']');
                }
            }
            return expanded.length() == 0
                    ? java.util.regex.Matcher.quoteReplacement(match.group(0))
                    : expanded.toString();
        });
        List<CitedChunk> cited = new java.util.ArrayList<>(remap.size());
        for (java.util.Map.Entry<Integer, Integer> e : remap.entrySet()) {
            cited.add(new CitedChunk(e.getValue(), byPosition.get(e.getKey())));
        }
        return new CitationRenumber(rewritten, cited);
    }

    /**
     * Parse a single citation number from a bracket part, returning {@code null}
     * when it doesn't fit in an int (e.g. an oversized {@code 99999999999999}).
     * Matches {@code CitationExtractor.extractMarkers} — an unparseable number is
     * skipped (treated as invalid/orphan) rather than crashing the turn with a
     * {@link NumberFormatException}.
     */
    private static Integer tryParse(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException unused) {
            return null;
        }
    }

    /** Re-numbered text + the cited chunk list in dense [1..N] order. */
    public record CitationRenumber(String text, List<CitedChunk> cited) {
    }

    /** A retrieved chunk paired with its new dense citation number. */
    public record CitedChunk(int newN, RetrievedChunk chunk) {
    }
}
