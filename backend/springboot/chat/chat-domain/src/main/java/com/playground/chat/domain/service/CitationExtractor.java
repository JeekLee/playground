package com.playground.chat.domain.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Extracts the 1-indexed {@code [N]} markers that appeared in the streamed
 * assistant text per ADR-14 §10. The regex is greedy and flat — markers don't
 * nest. The returned set preserves insertion order so later filtering against
 * the retrieval list maintains the original ordinal sequence.
 *
 * <p>The orphan-marker case (LLM cited {@code [9]} when K=6 returned 6
 * chunks) is handled downstream by the filter step in
 * {@code ChatTurnService} — this class returns every marker it finds; the
 * citation-persistence call drops the ones with no matching retrieved chunk.
 */
@Service
public class CitationExtractor {

    /**
     * {@code [N]} or a grouped {@code [N, M, ...]} where each number is one or
     * more digits. The LLM sometimes groups sources in one bracket
     * ({@code [1, 2, 4, 5]}); group(1) captures the whole comma-separated inner
     * list so {@link #extractMarkers} can flatten it into individual positions.
     */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]");

    public Set<Integer> extractMarkers(String assistantText) {
        Set<Integer> found = new LinkedHashSet<>();
        if (assistantText == null || assistantText.isEmpty()) {
            return found;
        }
        Matcher m = MARKER.matcher(assistantText);
        while (m.find()) {
            // group(1) is one number ("3") or a comma list ("1, 2, 4, 5") —
            // flatten the list, preserving first-seen order in the set.
            for (String part : m.group(1).split(",")) {
                try {
                    int n = Integer.parseInt(part.trim());
                    if (n >= 1) {
                        found.add(n);
                    }
                } catch (NumberFormatException unused) {
                    // [12345678901234567890] doesn't fit in an int — skip.
                }
            }
        }
        return found;
    }

    /**
     * Strip {@code [N]} markers from a prior assistant turn before the prompt
     * assembler includes it in {@code [CONVERSATION SO FAR]} (spec §6.1 step 10
     * note: "Prior assistant turns are stripped of their [N] markers before
     * being included"). Single space preserved between the words on either
     * side of the removed marker.
     */
    public String stripMarkers(String assistantText) {
        if (assistantText == null || assistantText.isEmpty()) {
            return assistantText == null ? "" : assistantText;
        }
        return MARKER.matcher(assistantText).replaceAll("").replaceAll(" {2,}", " ").trim();
    }
}
