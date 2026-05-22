package com.playground.docs.ingestion.domain.service;

import java.util.List;
import java.util.Locale;

/**
 * Splits a paragraph into sentences. Invoked by {@link WindowNormalizer}
 * only when a single CommonMark paragraph exceeds
 * {@link ChunkingPolicy#sizeTokens()} — the common case skips this entirely
 * and packs whole blocks.
 *
 * <p>Implementations must preserve all characters of the input: concatenating
 * the returned list yields the original string verbatim (whitespace and line
 * breaks attached to whichever sentence they border). This invariant keeps
 * the eventual chunk text exactly reconstructable.
 */
public interface SentenceSplitter {

    List<String> split(String paragraph, Locale locale);
}
