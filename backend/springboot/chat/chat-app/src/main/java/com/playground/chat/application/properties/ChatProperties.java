package com.playground.chat.application.properties;

/**
 * Plain POJO carrying the M4 prompt + retrieval tunables per ADR-14 §7 + §8.
 * The Spring Boot {@code @ConfigurationProperties} binding lives in
 * {@code chat-infra} (per the ADR-02 layering rule and the rag-ingestion
 * precedent in {@code ChunkingProperties}); this POJO is created and injected
 * by an {@code @Bean} factory in {@code ChatPropertiesConfig}.
 */
public final class ChatProperties {

    /** ADR-14 §7 default — overridable via PLAYGROUND_CHAT_RETRIEVAL_K. */
    public static final int DEFAULT_RETRIEVAL_K = 6;

    /** ADR-13 §G.2 — runtime HNSW probe depth per retrieval SELECT. */
    public static final int DEFAULT_EF_SEARCH = 40;

    /** Per-turn user-message raw byte cap per spec §5.1 (4 KB). */
    public static final int MAX_USER_MESSAGE_BYTES = 4096;

    /** ADR-17 §6 default — max tool-call depth per user turn. */
    public static final int DEFAULT_TOOL_MAX_DEPTH = 5;

    /** ADR-17 §4 default — max tool-result body bytes; over-cap = truncate-and-warn. */
    public static final int DEFAULT_TOOL_MAX_RESULT_BYTES = 16384;

    private final int retrievalK;
    private final int efSearch;
    private final int systemPromptBudgetTokens;
    private final int retrievalBlockTokenBudget;
    private final int maxHistoryTokens;
    private final int maxCompletionTokens;
    private final int perChunkTokenBudget;
    private final int toolMaxDepth;
    private final int toolMaxResultBytes;

    public ChatProperties(
            int retrievalK,
            int efSearch,
            int systemPromptBudgetTokens,
            int retrievalBlockTokenBudget,
            int maxHistoryTokens,
            int maxCompletionTokens,
            int perChunkTokenBudget,
            int toolMaxDepth,
            int toolMaxResultBytes) {
        this.retrievalK = retrievalK;
        this.efSearch = efSearch;
        this.systemPromptBudgetTokens = systemPromptBudgetTokens;
        this.retrievalBlockTokenBudget = retrievalBlockTokenBudget;
        this.maxHistoryTokens = maxHistoryTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.perChunkTokenBudget = perChunkTokenBudget;
        this.toolMaxDepth = toolMaxDepth;
        this.toolMaxResultBytes = toolMaxResultBytes;
    }

    public static ChatProperties defaults() {
        return new ChatProperties(
                DEFAULT_RETRIEVAL_K,
                DEFAULT_EF_SEARCH,
                200,
                2400,
                24576,
                4000,
                400,
                DEFAULT_TOOL_MAX_DEPTH,
                DEFAULT_TOOL_MAX_RESULT_BYTES);
    }

    public int retrievalK() {
        return retrievalK;
    }

    public int efSearch() {
        return efSearch;
    }

    public int systemPromptBudgetTokens() {
        return systemPromptBudgetTokens;
    }

    public int retrievalBlockTokenBudget() {
        return retrievalBlockTokenBudget;
    }

    public int maxHistoryTokens() {
        return maxHistoryTokens;
    }

    public int maxCompletionTokens() {
        return maxCompletionTokens;
    }

    public int perChunkTokenBudget() {
        return perChunkTokenBudget;
    }

    /** ADR-17 §6 — max tool-call depth per user turn. */
    public int toolMaxDepth() {
        return toolMaxDepth;
    }

    /** ADR-17 §4 — max tool-result body bytes (truncate-and-warn above). */
    public int toolMaxResultBytes() {
        return toolMaxResultBytes;
    }
}
