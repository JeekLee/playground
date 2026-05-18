package com.playground.ragchat.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.playground.ragchat.domain.model.vo.TokenCount;
import org.springframework.stereotype.Service;

/**
 * Counts tokens against the cl100k-base BPE tokenizer per ADR-14 §8. Used for
 * budget bookkeeping (the prompt assembler's history-truncation decision and
 * the per-chunk 400-token truncation in the retrieval block) — <strong>not</strong>
 * for prompt construction. The actual prompt is plain text concatenation; we
 * never tokenize it before sending to Spring AI.
 *
 * <p>Mirrors M3's tokenizer choice (ADR-13 §1) for consistency across the
 * playground's RAG pipeline.
 */
@Service
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /** Count tokens in the given text. Empty / null → 0. */
    public TokenCount count(String text) {
        if (text == null || text.isEmpty()) {
            return TokenCount.zero();
        }
        return TokenCount.of(encoding.countTokens(text));
    }

    /**
     * Truncate to at most {@code maxTokens}; returns the original string if it
     * already fits. Head-truncation (keep the first {@code maxTokens} tokens of
     * the input). Per ADR-14 §8: the retrieval block clips each chunk to 400
     * tokens via this method before insertion.
     */
    public String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return text == null ? "" : text;
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        IntArrayList slice = new IntArrayList(maxTokens);
        for (int i = 0; i < maxTokens; i++) {
            slice.add(tokens.get(i));
        }
        return encoding.decode(slice);
    }
}
