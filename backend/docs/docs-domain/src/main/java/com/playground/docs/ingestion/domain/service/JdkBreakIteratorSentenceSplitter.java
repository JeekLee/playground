package com.playground.docs.ingestion.domain.service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link SentenceSplitter} backed by the JDK's
 * {@link BreakIterator#getSentenceInstance(Locale)} — no external dependency.
 * Korean accuracy is adequate for the oversize-paragraph fallback path; an
 * ICU4J-backed splitter can replace this later behind the same interface
 * without touching {@link WindowNormalizer}.
 */
public final class JdkBreakIteratorSentenceSplitter implements SentenceSplitter {

    @Override
    public List<String> split(String paragraph, Locale locale) {
        if (paragraph == null || paragraph.isBlank()) {
            return List.of();
        }
        BreakIterator it = BreakIterator.getSentenceInstance(locale);
        it.setText(paragraph);
        List<String> out = new ArrayList<>();
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            out.add(paragraph.substring(start, end));
        }
        return List.copyOf(out);
    }
}
