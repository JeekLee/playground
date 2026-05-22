package com.playground.ragchat.infrastructure.config;

import com.playground.ragchat.application.properties.RagChatProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code playground.rag-chat.*} per ADR-14 §7 + §8. Lives in
 * {@code -infra} (not {@code -app}) because {@link ConfigurationProperties} is
 * a Spring Boot type and {@code -app} is forbidden from importing
 * {@code org.springframework.boot.*}. Mirrors the rag-ingestion
 * {@code ChunkingProperties} pattern.
 */
@ConfigurationProperties(prefix = "playground.rag-chat")
public class RagChatPropertiesBinding {

    private final Retrieval retrieval = new Retrieval();
    private final Prompt prompt = new Prompt();
    private final Tool tool = new Tool();

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Tool getTool() {
        return tool;
    }

    public RagChatProperties toProperties() {
        return new RagChatProperties(
                retrieval.getK(),
                retrieval.getEfSearch(),
                prompt.getSystemPromptBudgetTokens(),
                prompt.getRetrievalBlockTokenBudget(),
                prompt.getMaxHistoryTokens(),
                prompt.getMaxCompletionTokens(),
                prompt.getPerChunkTokenBudget(),
                tool.getMaxDepth(),
                tool.getMaxResultBytes());
    }

    public static class Retrieval {
        private int k = RagChatProperties.DEFAULT_RETRIEVAL_K;
        private int efSearch = RagChatProperties.DEFAULT_EF_SEARCH;

        public int getK() {
            return k;
        }

        public void setK(int k) {
            this.k = k;
        }

        public int getEfSearch() {
            return efSearch;
        }

        public void setEfSearch(int efSearch) {
            this.efSearch = efSearch;
        }
    }

    public static class Prompt {
        private int systemPromptBudgetTokens = 200;
        private int retrievalBlockTokenBudget = 2400;
        private int maxHistoryTokens = 24576;
        private int maxCompletionTokens = 4000;
        private int perChunkTokenBudget = 400;

        public int getSystemPromptBudgetTokens() {
            return systemPromptBudgetTokens;
        }

        public void setSystemPromptBudgetTokens(int v) {
            this.systemPromptBudgetTokens = v;
        }

        public int getRetrievalBlockTokenBudget() {
            return retrievalBlockTokenBudget;
        }

        public void setRetrievalBlockTokenBudget(int v) {
            this.retrievalBlockTokenBudget = v;
        }

        public int getMaxHistoryTokens() {
            return maxHistoryTokens;
        }

        public void setMaxHistoryTokens(int v) {
            this.maxHistoryTokens = v;
        }

        public int getMaxCompletionTokens() {
            return maxCompletionTokens;
        }

        public void setMaxCompletionTokens(int v) {
            this.maxCompletionTokens = v;
        }

        public int getPerChunkTokenBudget() {
            return perChunkTokenBudget;
        }

        public void setPerChunkTokenBudget(int v) {
            this.perChunkTokenBudget = v;
        }
    }

    /** ADR-17 §4 + §6 — tool-calling tunables. */
    public static class Tool {
        private int maxDepth = RagChatProperties.DEFAULT_TOOL_MAX_DEPTH;
        private int maxResultBytes = RagChatProperties.DEFAULT_TOOL_MAX_RESULT_BYTES;

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int v) {
            this.maxDepth = v;
        }

        public int getMaxResultBytes() {
            return maxResultBytes;
        }

        public void setMaxResultBytes(int v) {
            this.maxResultBytes = v;
        }
    }
}
