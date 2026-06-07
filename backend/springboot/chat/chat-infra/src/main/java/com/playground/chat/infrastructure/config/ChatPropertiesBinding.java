package com.playground.chat.infrastructure.config;

import com.playground.chat.application.properties.ChatProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code playground.chat.*} per ADR-14 §7 + §8. Lives in
 * {@code -infra} (not {@code -app}) because {@link ConfigurationProperties} is
 * a Spring Boot type and {@code -app} is forbidden from importing
 * {@code org.springframework.boot.*}. Mirrors the rag-ingestion
 * {@code ChunkingProperties} pattern.
 */
@ConfigurationProperties(prefix = "playground.chat")
public class ChatPropertiesBinding {

    private final Prompt prompt = new Prompt();
    private final Tool tool = new Tool();

    public Prompt getPrompt() {
        return prompt;
    }

    public Tool getTool() {
        return tool;
    }

    public ChatProperties toProperties() {
        return new ChatProperties(
                prompt.getSystemPromptBudgetTokens(),
                prompt.getMaxHistoryTokens(),
                prompt.getMaxCompletionTokens(),
                tool.getMaxDepth(),
                tool.getMaxResultBytes());
    }

    public static class Prompt {
        private int systemPromptBudgetTokens = 200;
        private int maxHistoryTokens = 24576;
        private int maxCompletionTokens = 4000;

        public int getSystemPromptBudgetTokens() {
            return systemPromptBudgetTokens;
        }

        public void setSystemPromptBudgetTokens(int v) {
            this.systemPromptBudgetTokens = v;
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
    }

    /** ADR-17 §4 + §6 — tool-calling tunables. */
    public static class Tool {
        private int maxDepth = ChatProperties.DEFAULT_TOOL_MAX_DEPTH;
        private int maxResultBytes = ChatProperties.DEFAULT_TOOL_MAX_RESULT_BYTES;

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
