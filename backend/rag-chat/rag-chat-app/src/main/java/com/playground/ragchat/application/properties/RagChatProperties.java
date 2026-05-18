package com.playground.ragchat.application.properties;

/**
 * Plain POJO carrying the M4 prompt + retrieval tunables per ADR-14 §7 + §8.
 * The Spring Boot {@code @ConfigurationProperties} binding lives in
 * {@code rag-chat-infra} (per the ADR-02 layering rule and the rag-ingestion
 * precedent in {@code ChunkingProperties}); this POJO is created and injected
 * by an {@code @Bean} factory in {@code RagChatPropertiesConfig}.
 */
public final class RagChatProperties {

    /** ADR-14 §7 default — overridable via PLAYGROUND_RAG_CHAT_RETRIEVAL_K. */
    public static final int DEFAULT_RETRIEVAL_K = 6;

    /** ADR-13 §G.2 — runtime HNSW probe depth per retrieval SELECT. */
    public static final int DEFAULT_EF_SEARCH = 40;

    /** Per-turn user-message raw byte cap per spec §5.1 (4 KB). */
    public static final int MAX_USER_MESSAGE_BYTES = 4096;

    private final int retrievalK;
    private final int efSearch;
    private final int systemPromptBudgetTokens;
    private final int retrievalBlockTokenBudget;
    private final int maxHistoryTokens;
    private final int maxCompletionTokens;
    private final int perChunkTokenBudget;

    public RagChatProperties(
            int retrievalK,
            int efSearch,
            int systemPromptBudgetTokens,
            int retrievalBlockTokenBudget,
            int maxHistoryTokens,
            int maxCompletionTokens,
            int perChunkTokenBudget) {
        this.retrievalK = retrievalK;
        this.efSearch = efSearch;
        this.systemPromptBudgetTokens = systemPromptBudgetTokens;
        this.retrievalBlockTokenBudget = retrievalBlockTokenBudget;
        this.maxHistoryTokens = maxHistoryTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.perChunkTokenBudget = perChunkTokenBudget;
    }

    public static RagChatProperties defaults() {
        return new RagChatProperties(
                DEFAULT_RETRIEVAL_K,
                DEFAULT_EF_SEARCH,
                200,
                2400,
                24576,
                4000,
                400);
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
}
