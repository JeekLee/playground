package com.playground.ragchat.domain.model.vo;

/**
 * Per-turn token budget split pinned by ADR-14 §8 (200 + 2400 + 24576 + 4000
 * = 31176; under Qwen3-32B's 32 768 context window by 1592 tokens of safety
 * margin).
 *
 * <p>Used by {@code HistoryTruncator} (the {@code maxHistoryTokens()} slot)
 * and by the prompt assembler (every other slot).
 */
public record TokenBudget(
        int systemPromptTokens,
        int retrievalBlockTokens,
        int maxHistoryTokens,
        int maxCompletionTokens) {

    public TokenBudget {
        if (systemPromptTokens < 0
                || retrievalBlockTokens < 0
                || maxHistoryTokens < 0
                || maxCompletionTokens < 0) {
            throw new IllegalArgumentException("TokenBudget slots must be >= 0");
        }
    }

    /** ADR-14 §8 defaults: 200 + 2400 + 24576 + 4000. */
    public static TokenBudget defaults() {
        return new TokenBudget(200, 2400, 24576, 4000);
    }

    public int total() {
        return systemPromptTokens + retrievalBlockTokens + maxHistoryTokens + maxCompletionTokens;
    }
}
