package com.playground.chat.application.properties;

/**
 * Plain POJO carrying the chat prompt + tool-calling tunables. The Spring Boot
 * {@code @ConfigurationProperties} binding lives in {@code chat-infra} (per the
 * ADR-02 layering rule and the rag-ingestion precedent in
 * {@code ChunkingProperties}); this POJO is created and injected by an
 * {@code @Bean} factory in {@code ChatPropertiesConfig}.
 *
 * <p>agentic-search spec D2: the always-on retrieval tunables
 * ({@code retrievalK}, {@code efSearch}, {@code retrievalBlockTokenBudget},
 * {@code perChunkTokenBudget}) were removed — pipeline retrieval was replaced
 * by the {@code search_documents} tool (docs-api owns its own search tuning).
 */
public final class ChatProperties {

    /** ADR-17 §6 default — max tool-call depth per user turn. */
    public static final int DEFAULT_TOOL_MAX_DEPTH = 5;

    /** ADR-17 §4 default — max tool-result body bytes; over-cap = truncate-and-warn. */
    public static final int DEFAULT_TOOL_MAX_RESULT_BYTES = 16384;

    private final int systemPromptBudgetTokens;
    private final int maxHistoryTokens;
    private final int maxCompletionTokens;
    private final int toolMaxDepth;
    private final int toolMaxResultBytes;

    public ChatProperties(
            int systemPromptBudgetTokens,
            int maxHistoryTokens,
            int maxCompletionTokens,
            int toolMaxDepth,
            int toolMaxResultBytes) {
        this.systemPromptBudgetTokens = systemPromptBudgetTokens;
        this.maxHistoryTokens = maxHistoryTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.toolMaxDepth = toolMaxDepth;
        this.toolMaxResultBytes = toolMaxResultBytes;
    }

    public static ChatProperties defaults() {
        return new ChatProperties(
                200,
                24576,
                4000,
                DEFAULT_TOOL_MAX_DEPTH,
                DEFAULT_TOOL_MAX_RESULT_BYTES);
    }

    public int systemPromptBudgetTokens() {
        return systemPromptBudgetTokens;
    }

    public int maxHistoryTokens() {
        return maxHistoryTokens;
    }

    public int maxCompletionTokens() {
        return maxCompletionTokens;
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
